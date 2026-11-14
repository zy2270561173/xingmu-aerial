package app.xingmu.aerial.cast

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/** A read-only view of the player for Home and the jukebox page; it never owns anything. */
class MusicSnapshot(
    val song: MusicSong?,
    val upcoming: List<MusicSong>,
    val playing: Boolean,
    val prepared: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val quality: String,
    val status: String,
    val index: Int,
    /** A line that outlives a state change ("MV 不可用，已切回音频"), shown for a few seconds. */
    val notice: String = "",
    /** The MV toggle as persisted; the page's button draws its label from this. */
    val mvEnabled: Boolean = true,
    /** True only while a MV's video really owns the surface; the disc steps aside for it. */
    val mvActive: Boolean = false,
    val mvSource: String = CastConfig.MV_SOURCE_QM,
    /** The playing MV's cover/title, for the page and the screensaver's card. */
    val mvCover: String = "",
    val mvTitle: String = "",
    /** B站 search state: what was found, what is picked, and the countdown before it plays. */
    val biliResults: List<BiliMv> = emptyList(),
    val biliSelected: Int = 0,
    val biliCountdown: Int = 0,
    val biliToken: Int = 0,
    val biliQuery: String = "",
)

/**
 * The jukebox's player, kept outside any Activity: Home, the record-player page and the playback
 * service all talk to this one object. That is what lets Back leave the page, or the screensaver
 * start, without stopping the music.
 *
 * It has two shapes. Audio plays the backend's audio relay and the page draws its record. MV mode
 * plays the music video into a SurfaceHolder the page owns (QM: the song's own MV, B站: the first
 * hit for 歌名 + 歌手 after a three second countdown). A MV the box cannot decode falls back to
 * audio for that song and says so, instead of leaving a black surface.
 */
object MusicPlayback {
    private const val TAG = "AerialMusic"
    private const val BILI_COUNTDOWN_SECONDS = 3
    /** The UI says "MV 不可用，已切回音频" for this long; six seconds is two lines of status. */
    private const val NOTICE_MS = 6_000L
    /**
     * QM MV renditions to try before giving up: the MV's own default (1080p High@5.0), then 720p,
     * then 360p. A 4.4 MediaCodec often rejects the level-5.0 stream, and a picture at 360p beats
     * a black surface (the backend serves the lower rendition from the MV's own ladder).
     */
    private val MV_QUALITY_LADDER = intArrayOf(0, 30, 10)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(MusicSnapshot) -> Unit>()

    private var appContext: Context? = null
    @Volatile private var player: IjkMediaPlayer? = null
    @Volatile private var playing = false
    @Volatile private var prepared = false
    @Volatile private var position = 0L
    @Volatile private var duration = 0L
    @Volatile private var status = ""
    private var loadToken = 0
    private var playerGeneration = 0
    private var triedDirect = false
    private var audioFocus: AudioManager.OnAudioFocusChangeListener? = null

    // ---- MV state ----
    /** The page's video target; null while the page is closed or between two surfaces. */
    @Volatile private var surface: Surface? = null
    @Volatile private var mvActive = false
    @Volatile private var mvCover = ""
    @Volatile private var mvTitle = ""
    @Volatile private var notice = ""
    private var noticeUntil = 0L
    @Volatile private var biliResults: List<BiliMv> = emptyList()
    @Volatile private var biliSelected = 0
    @Volatile private var biliCountdown = 0
    @Volatile private var biliToken = 0
    @Volatile private var biliQuery = ""
    private var countdownTask: Runnable? = null
    /** Which entry of [MV_QUALITY_LADDER] the QM MV is currently trying. */
    private var mvQualityIndex = 0
    /** Songs whose MV failed to decode once; the rest of the session plays them as audio. */
    private val mvFailed = HashSet<String>()

    /** CastAgent sets this once; every queue/playback change is reported to the backend. */
    @Volatile var statePublisher: ((String) -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() {
            val media = player ?: return
            position = runCatching { media.currentPosition }.getOrDefault(position)
            main.postDelayed(this, 250L)
        }
    }

    /** A slow state push while playing keeps the phone's position fresh between real changes. */
    private val stateTicker = object : Runnable {
        override fun run() {
            if (!prepared) return
            if (playing) publishState()
            main.postDelayed(this, 5_000L)
        }
    }

    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun addListener(listener: (MusicSnapshot) -> Unit) {
        listeners.add(listener)
        main.post { runCatching { listener(snapshot()) } }
    }

    fun removeListener(listener: (MusicSnapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun snapshot(): MusicSnapshot {
        val current = MusicQueue.current()
        return MusicSnapshot(
            song = current,
            upcoming = MusicQueue.upcoming(),
            playing = playing,
            prepared = prepared,
            positionMs = position,
            durationMs = duration,
            quality = current?.quality.orEmpty(),
            status = status,
            index = MusicQueue.currentIndex(),
            notice = if (System.currentTimeMillis() < noticeUntil) notice else "",
            mvEnabled = CastConfig.mvEnabled,
            mvActive = mvActive,
            mvSource = CastConfig.mvSource,
            mvCover = mvCover,
            mvTitle = mvTitle,
            biliResults = biliResults,
            biliSelected = biliSelected,
            biliCountdown = biliCountdown,
            biliToken = biliToken,
            biliQuery = biliQuery,
        )
    }

    fun positionMs(): Long = position
    fun durationMs(): Long = duration
    fun isPlaying(): Boolean = playing

    /** Joins the queue and starts it when nothing is playing; the service keeps it alive after. */
    fun enqueue(context: Context, song: MusicSong) {
        attach(context)
        ensureService()
        val makeCurrent = !playing
        main.post {
            MusicQueue.enqueue(song, makeCurrent)
            if (makeCurrent) doPlayCurrent(0L) else notifyState()
        }
    }

    /**
     * The page's TextureView surface. The MV player draws into it; detaching it (Back, or the
     * screensaver covering the page) must not pause playback, so the surface is only remembered.
     */
    fun setSurface(value: Surface?) {
        surface = value
        main.post {
            val media = player ?: return@post
            if (mvActive) runCatching { media.setSurface(value) }
        }
    }

    fun mvEnabled(): Boolean = CastConfig.mvEnabled
    fun mvSource(): String = CastConfig.mvSource

    /** MV on restarts the song with video from the top; MV off returns to audio at the same place. */
    fun setMvEnabled(enabled: Boolean) {
        main.post {
            if (CastConfig.mvEnabled == enabled) return@post
            CastConfig.setMvEnabled(enabled)
            val song = MusicQueue.current()
            if (song == null) {
                notifyState()
                return@post
            }
            if (enabled && CastConfig.mvSource == CastConfig.MV_SOURCE_QM && song.vid.isEmpty()) {
                note("这首歌没有 MV")
            }
            doPlayCurrent(if (enabled) 0L else position)
        }
    }

    /** The source only matters while MV is on; in audio mode it just changes the next song's MV. */
    fun setMvSource(source: String) {
        main.post {
            CastConfig.setMvSource(source)
            val song = MusicQueue.current()
            if (!CastConfig.mvEnabled || song == null) {
                notifyState()
                return@post
            }
            if (source == CastConfig.MV_SOURCE_QM && song.vid.isEmpty()) note("这首歌没有 MV")
            doPlayCurrent(0L)
        }
    }

    /** A result picked in the B站 sheet: play it now, during the countdown or during playback. */
    fun selectBili(index: Int) {
        main.post {
            val song = MusicQueue.current() ?: return@post
            if (biliResults.isEmpty()) return@post
            playBili(song, index.coerceIn(0, biliResults.size - 1), loadToken)
        }
    }

    /** A short line above the status, without interrupting playback. */
    fun note(text: String) {
        main.post { setNotice(text) }
    }

    fun playCurrent() {
        val start = if (prepared || player != null) position else 0L
        main.post { doPlayCurrent(start) }
    }

    fun toggle() {
        main.post {
            if (biliCountdown > 0 && biliResults.isNotEmpty()) {
                val song = MusicQueue.current() ?: return@post
                playBili(song, biliSelected, loadToken)
                return@post
            }
            val media = player
            if (media == null || !prepared) {
                doPlayCurrent(if (prepared) position else 0L)
            } else if (playing) {
                position = runCatching { media.currentPosition }.getOrDefault(position)
                runCatching { media.pause() }
                playing = false
                notifyState()
            } else {
                runCatching { media.start() }
                playing = true
                requestFocus()
                notifyState()
            }
        }
    }

    fun next() {
        main.post {
            if (MusicQueue.advance() == null) {
                status = "已经是最后一首"
                notifyState()
            } else {
                doPlayCurrent(0L)
            }
        }
    }

    fun previous() {
        main.post {
            if (position > 3000L && player != null) {
                runCatching { player?.seekTo(0L) }
                position = 0L
                return@post
            }
            if (MusicQueue.rewind() == null) {
                status = "已经是第一首"
                notifyState()
            } else {
                doPlayCurrent(0L)
            }
        }
    }

    fun changeQuality(quality: String) {
        main.post {
            val song = MusicQueue.current() ?: return@post
            if (song.quality == quality) return@post
            val at = position
            song.quality = quality
            song.url = ""
            doPlayCurrent(at)
        }
    }

    fun seekTo(ms: Long) {
        main.post {
            position = ms.coerceAtLeast(0L)
            runCatching { player?.seekTo(position) }
        }
    }

    /** The phone deleted a row; removing the playing one moves on to the next song. */
    fun removeSong(id: String) {
        main.post {
            val wasCurrent = MusicQueue.remove(id)
            if (wasCurrent) {
                if (MusicQueue.current() != null) {
                    doPlayCurrent(0L)
                } else {
                    releasePlayer()
                    playing = false
                    prepared = false
                    status = ""
                    notifyState()
                }
            } else {
                notifyState()
            }
        }
    }

    /** The phone dragged the upcoming rows into a new order. */
    fun reorder(ids: List<String>) {
        main.post {
            MusicQueue.reorderUpcoming(ids)
            notifyState()
        }
    }

    fun applyQueueCommand(payload: JSONObject) {
        val remove = payload.optString("remove")
        if (remove.isNotEmpty()) {
            removeSong(remove)
            return
        }
        val order = payload.optJSONArray("order")
        if (order != null) {
            val ids = ArrayList<String>()
            for (i in 0 until order.length()) {
                val id = order.optString(i)
                if (id.isNotEmpty()) ids.add(id)
            }
            reorder(ids)
        }
    }

    fun publishState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { publishState() }
            return
        }
        val publisher = statePublisher ?: return
        runCatching { publisher(stateJson(snapshot()).toString()) }
    }

    fun stop() {
        main.post {
            releasePlayer()
            playing = false
            prepared = false
            position = 0L
            duration = 0L
            status = ""
            clearMvState()
            notifyState()
        }
    }

    /** The service is going away: stop the decoder but keep the queue for the next time. */
    fun release() {
        main.post {
            releasePlayer()
            playing = false
            prepared = false
            position = 0L
            duration = 0L
            clearMvState()
        }
    }

    private fun ensureService() {
        val context = appContext ?: return
        MusicService.start(context)
    }

    private fun doPlayCurrent(startMs: Long) {
        val song = MusicQueue.current()
        if (song == null) {
            releasePlayer()
            status = "还没有点歌"
            notifyState()
            return
        }
        ensureService()
        releasePlayer()
        triedDirect = false
        clearMvState()
        val token = loadToken
        val wantMv = CastConfig.mvEnabled && !mvFailed.contains(mvKey(song))
        if (wantMv && CastConfig.mvSource == CastConfig.MV_SOURCE_BILI) {
            searchBili(song, token)
            return
        }
        if (wantMv && song.vid.isNotEmpty()) {
            playQmMv(song, startMs, token)
            return
        }
        playAudio(song, startMs, token)
    }

    private fun playAudio(song: MusicSong, startMs: Long, token: Int) {
        status = "正在获取播放地址…"
        notifyState()
        if (song.url.isNotEmpty()) {
            startPlayer(song, song.url, startMs, video = false, directUrl = song.directUrl)
            return
        }
        MusicApi.resolve(song.mid, song.quality) { play, error ->
            if (token != loadToken) return@resolve
            if (play == null) {
                status = "无法播放：" + (error ?: "未知错误")
                notifyState()
                return@resolve
            }
            song.url = play.stream
            song.directUrl = play.direct
            song.quality = play.quality
            startPlayer(song, play.stream, startMs, video = false, directUrl = play.direct)
        }
    }

    /** QM: the song's own MV. The info call is for the cover/title the saver paints. */
    private fun playQmMv(song: MusicSong, startMs: Long, token: Int) {
        mvQualityIndex = 0
        status = "正在获取 MV…"
        notifyState()
        MusicApi.mvInfo(song.vid) { info, error ->
            // Starting the player bumps loadToken, so this one is matched by song instead.
            if (MusicQueue.current()?.mid != song.mid) return@mvInfo
            if (info != null) {
                mvCover = info.cover
                mvTitle = info.name.ifEmpty { song.name }
                notifyState()
            } else {
                Log.i(TAG, "mv info: " + (error ?: "empty"))
            }
        }
        MusicApi.mvUrl(song.vid) { play, error ->
            if (token != loadToken) return@mvUrl
            // The relay path is always here; /url only adds the CDN link used if it fails.
            val stream = play?.stream ?: MusicApi.qmMvStream(song.vid)
            if (play == null) Log.i(TAG, "mv url: " + (error ?: "empty"))
            startPlayer(song, stream, startMs, video = true, directUrl = "")
        }
    }

    /**
     * The 1080p stream did not decode: try the next rendition in the MV's own ladder. Returns false
     * when every rendition failed, and the caller then falls back to the song's audio.
     */
    private fun retryQmQuality(song: MusicSong): Boolean {
        if (mvQualityIndex + 1 >= MV_QUALITY_LADDER.size) return false
        mvQualityIndex++
        val quality = MV_QUALITY_LADDER[mvQualityIndex]
        status = "MV 解码失败，正在尝试 " + qualityLabel(quality) + "…"
        Log.i(TAG, "mv retry quality=" + quality + " vid=" + song.vid)
        notifyState()
        startPlayer(song, MusicApi.qmMvStream(song.vid, quality), 0L, video = true, directUrl = "")
        return true
    }

    private fun qualityLabel(quality: Int): String = when (quality) {
        30 -> "720p"
        10 -> "360p"
        else -> "默认清晰度"
    }

    /** B站: search 歌名 + 歌手, then let the first hit play after a three second countdown. */
    private fun searchBili(song: MusicSong, token: Int) {
        biliQuery = (song.name + " " + song.singer).trim()
        biliResults = emptyList()
        biliSelected = 0
        biliCountdown = 0
        biliToken++
        status = "正在搜索 B站 MV…"
        notifyState()
        MusicApi.biliSearch(biliQuery) { results, error ->
            if (token != loadToken) return@biliSearch
            if (results.isEmpty()) {
                status = if (error != null) "B站搜索失败：" + error else "B站没有找到相关 MV"
                mvFailed.add(mvKey(song))
                notifyState()
                playAudio(song, 0L, token)
                return@biliSearch
            }
            biliResults = results
            biliSelected = 0
            biliCountdown = BILI_COUNTDOWN_SECONDS
            biliToken++
            status = biliCountdown.toString() + " 秒后播放：" + results[0].title
            notifyState()
            startCountdown(song, token)
        }
    }

    private fun startCountdown(song: MusicSong, token: Int) {
        cancelCountdown()
        val task = object : Runnable {
            override fun run() {
                if (token != loadToken || biliCountdown <= 0) return
                biliCountdown--
                if (biliCountdown > 0) {
                    status = biliCountdown.toString() + " 秒后播放：" +
                        (biliResults.getOrNull(biliSelected)?.title.orEmpty())
                    notifyState()
                    main.postDelayed(this, 1000L)
                } else {
                    status = ""
                    notifyState()
                    playBili(song, biliSelected, token)
                }
            }
        }
        countdownTask = task
        main.postDelayed(task, 1000L)
    }

    private fun cancelCountdown() {
        val task = countdownTask ?: return
        countdownTask = null
        main.removeCallbacks(task)
        biliCountdown = 0
    }

    private fun playBili(song: MusicSong, index: Int, token: Int) {
        val result = biliResults.getOrNull(index) ?: return
        if (token != loadToken) return
        cancelCountdown()
        biliSelected = index
        mvTitle = result.title
        mvCover = result.cover
        status = "正在缓冲 MV…"
        notifyState()
        startPlayer(
            song,
            MusicApi.biliMvStream(result.bvid),
            0L,
            video = true,
            directUrl = "",
        )
    }

    private fun startPlayer(
        song: MusicSong,
        url: String,
        startMs: Long,
        video: Boolean,
        directUrl: String,
    ) {
        releasePlayer()
        val generation = playerGeneration
        status = if (video) "正在缓冲 MV…" else "正在缓冲…"
        notifyState()
        val media = IjkMediaPlayer()
        player = media
        runCatching {
            media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1)
            media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1)
            media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "reconnect", 1)
            media.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1)
            media.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 8000000)
            media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "analyzeduration", 1000000)
            if (video) {
                // Hardware decoding is what makes 4.4's 1080p MV watchable at all; the audio path
                // stays on ijk's software decode, which is the setting the jukebox shipped with.
                media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1)
                media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1)
                media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1)
                media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-fps", 30)
                surface?.let { target -> runCatching { media.setSurface(target) } }
            }
            media.setDataSource(url)
            media.setOnPreparedListener {
                main.post {
                    if (generation != playerGeneration) return@post
                    prepared = true
                    mvActive = video
                    duration = runCatching { it.duration }.getOrDefault(0L).coerceAtLeast(0L)
                    if (startMs > 0) runCatching { it.seekTo(startMs) }
                    position = startMs
                    it.start()
                    playing = true
                    requestFocus()
                    status = ""
                    notifyState()
                    main.removeCallbacks(ticker)
                    main.post(ticker)
                    main.removeCallbacks(stateTicker)
                    main.postDelayed(stateTicker, 5_000L)
                }
            }
            media.setOnCompletionListener {
                main.post {
                    if (generation != playerGeneration) return@post
                    prepared = false
                    playing = false
                    mvActive = false
                    position = duration
                    notifyState()
                    if (MusicQueue.advance() != null) {
                        doPlayCurrent(0L)
                    } else {
                        // 队列空了：清掉当前曲，界面回到「暂无点歌」，而不是停在最后一首
                        MusicQueue.clear()
                        duration = 0L
                        position = 0L
                        releasePlayer()
                        status = "播放结束"
                        notifyState()
                    }
                }
            }
            media.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "ijk error $what/$extra video=$video url=$url")
                main.post {
                    if (generation != playerGeneration) return@post
                    prepared = false
                    playing = false
                    if (video) {
                        // A 4.4 codec often rejects the 1080p High@5.0 rendition: try the MV's
                        // lower renditions before giving up on video and playing the song as audio.
                        if (CastConfig.mvSource == CastConfig.MV_SOURCE_QM && retryQmQuality(song)) {
                            return@post
                        }
                        // The box cannot decode this MV (4.4 codecs are picky): say so and play the
                        // song as audio instead of leaving a black surface behind.
                        mvActive = false
                        mvCover = ""
                        mvTitle = ""
                        mvFailed.add(mvKey(song))
                        setNotice("MV 不可用，已切回音频")
                        notifyState()
                        playAudio(song, 0L, loadToken)
                    } else if (!triedDirect && directUrl.isNotEmpty() && directUrl != url) {
                        triedDirect = true
                        startPlayer(song, directUrl, startMs, video = false, directUrl = "")
                    } else {
                        status = "播放失败（" + what + "/" + extra + "）"
                        notifyState()
                    }
                }
                true
            }
            media.prepareAsync()
        }.onFailure {
            Log.w(TAG, "prepare failed: " + it.javaClass.simpleName)
            status = "播放器错误：" + it.javaClass.simpleName
            releasePlayer()
            notifyState()
        }
    }

    private fun releasePlayer() {
        loadToken++
        playerGeneration++
        main.removeCallbacks(ticker)
        main.removeCallbacks(stateTicker)
        cancelCountdown()
        val media = player
        player = null
        prepared = false
        playing = false
        mvActive = false
        duration = 0L
        if (position < 0) position = 0L
        runCatching {
            media?.stop()
            media?.release()
        }
        abandonFocus()
    }

    private fun clearMvState() {
        mvActive = false
        mvCover = ""
        mvTitle = ""
        biliResults = emptyList()
        biliSelected = 0
        biliCountdown = 0
    }

    /** A MV failure is per source: a song whose QM MV cannot decode may still play on B站. */
    private fun mvKey(song: MusicSong): String = CastConfig.mvSource + ":" + song.mid

    private fun setNotice(text: String) {
        notice = text
        noticeUntil = System.currentTimeMillis() + NOTICE_MS
        if (Looper.myLooper() == Looper.getMainLooper()) notifyState() else main.post { notifyState() }
    }

    private fun requestFocus() {
        if (audioFocus != null) return
        val context = appContext ?: return
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                main.post {
                    val media = player ?: return@post
                    if (playing) {
                        position = runCatching { media.currentPosition }.getOrDefault(position)
                        runCatching { media.pause() }
                        playing = false
                        notifyState()
                    }
                }
            }
        }
        audioFocus = listener
        @Suppress("DEPRECATION")
        manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
    }

    private fun abandonFocus() {
        val listener = audioFocus ?: return
        audioFocus = null
        val context = appContext ?: return
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        @Suppress("DEPRECATION")
        manager.abandonAudioFocus(listener)
    }

    private fun notifyState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { notifyState() }
            return
        }
        val snapshot = snapshot()
        for (listener in listeners) runCatching { listener(snapshot) }
        val publisher = statePublisher ?: return
        runCatching { publisher(stateJson(snapshot).toString()) }
    }

    private fun stateJson(snapshot: MusicSnapshot): JSONObject {
        val queue = JSONArray()
        for (song in MusicQueue.all()) queue.put(songJson(song))
        val data = JSONObject()
            .put("playing", snapshot.playing)
            .put("prepared", snapshot.prepared)
            .put("position_ms", snapshot.positionMs)
            .put("duration_ms", snapshot.durationMs)
            .put("index", snapshot.index)
            .put("quality", snapshot.quality)
            .put("mv_active", snapshot.mvActive)
            .put("mv_source", snapshot.mvSource)
            .put("queue", queue)
        data.put("current", snapshot.song?.let { songJson(it) } ?: JSONObject.NULL)
        return JSONObject()
            .put("type", "music_state")
            .put("device_id", CastConfig.deviceId)
            .put("ts", System.currentTimeMillis() / 1000L)
            .put("data", data)
    }

    private fun songJson(song: MusicSong): JSONObject = JSONObject()
        .put("id", song.id)
        .put("mid", song.mid)
        .put("name", song.name)
        .put("singer", song.singer)
        .put("album", song.album)
        .put("cover", song.cover)
        .put("interval", song.interval)
        .put("quality", song.quality)
        .put("vid", song.vid)
}
