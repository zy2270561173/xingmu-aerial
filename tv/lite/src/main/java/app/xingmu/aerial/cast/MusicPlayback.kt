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
    /** The MV's decoded picture size, so the page can crop it to fill the screen. */
    val mvVideoWidth: Int = 0,
    val mvVideoHeight: Int = 0,
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
     * QM MV renditions to try before giving up. A modern box software-decodes this MV badly: the
     * 1080p High@5.0 rendition (about 77 MB) drops frames, so it starts at the 720p relay stream.
     * A 4.4 box's MediaCodec often rejects the 1080p rendition outright, so it starts there and
     * steps down; either way 360p is the last rung and the song then plays as audio.
     */
    private val MV_LADDER_4X = intArrayOf(0, 30, 10)
    private val MV_LADDER_MODERN = intArrayOf(30, 10)
    /** A MV that has not advanced a millisecond for this long is stuck, not just buffering. */
    private const val MV_STALL_MS = 12_000L
    /** How long a MV's advance is measured against real time to notice a starved stream. */
    private const val MV_SAMPLE_MS = 10_000L
    /** The jukebox page's TextureView wants the picture. */
    const val SURFACE_PAGE = "page"
    /** The screensaver's TextureView wants it: the MV mode saver plays the video full screen. */
    const val SURFACE_SAVER = "saver"
    /**
     * bilibili's CDN answers 403 to a bare request: the pair below is what the backend relay sends,
     * and without it the direct link cannot be played at all (measured 2026-11-16).
     */
    private val BILI_HEADERS = mapOf(
        "Referer" to "https://www.bilibili.com",
        "User-Agent" to "Xingmu-Aerial-Music/1.0",
    )

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
    /** The video target: the jukebox page's TextureView or the screensaver's; null when nobody wants it. */
    @Volatile private var surface: Surface? = null
    /** Which of the two asked for [surface]; empty when it is not attached. */
    @Volatile private var surfaceOwner = ""
    /**
     * The Surface the running decoder was built for. ijk can be pointed at another one, but on
     * these boxes that is where a green picture comes from (a hardware decoder keeps writing into
     * the buffers it was configured with), so a different surface means a new decoder.
     */
    @Volatile private var decoderSurface: Surface? = null
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
    /** Which entry of [mvLadder] the QM MV is currently trying. */
    private var mvQualityIndex = 0
    @Volatile private var mvVideoWidth = 0
    @Volatile private var mvVideoHeight = 0
    /**
     * The ways into the rendition on screen, in the order they are tried: the CDN link first, the
     * backend relay next, and the CDN once more as a second chance when the relay is what starves.
     */
    private var mvSources: List<Pair<String, String>> = emptyList()
    private var mvSourceIndex = 0
    /** Extra request headers the source needs (bilibili's CDN refuses its links without them). */
    private var mvHeaders: Map<String, String>? = null
    /** Set once the hardware decoder fails; the rest of the session decodes MV in software. */
    private var mvSoftwareDecode = false
    /** Progress bookkeeping for the stuck-MV watchdog (see [watchMvStall]). */
    private var progressAt = 0L
    private var progressPosition = -1L
    private var windowAt = 0L
    private var windowPosition = -1L
    private var lastStatsAt = 0L
    /** Songs whose MV failed to decode once; the rest of the session plays them as audio. */
    private val mvFailed = HashSet<String>()

    /** CastAgent sets this once; every queue/playback change is reported to the backend. */
    @Volatile var statePublisher: ((String) -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() {
            val media = player ?: return
            position = runCatching { media.currentPosition }.getOrDefault(position)
            watchMvStall(media)
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
            mvVideoWidth = mvVideoWidth,
            mvVideoHeight = mvVideoHeight,
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
     * Where the MV should draw: the page's TextureView ([SURFACE_PAGE]) or the screensaver's
     * ([SURFACE_SAVER]). Detaching it (Back, or the screensaver covering the page) must not pause
     * playback, so the surface is only remembered.
     *
     * A *new* surface behind a running MV rebuilds the decoder and picks the picture up at the
     * position it was at. Handing the running decoder a second surface works on some boxes and
     * shows green on this one (Home → 点歌台 again, measured 2026-11-16); a decoder built for the
     * surface it draws into is the reliable way, and the seek keeps the song continuous.
     */
    fun setSurface(value: Surface?, owner: String = SURFACE_PAGE) {
        main.post {
            if (value == null) {
                // Only its owner may take the picture away: a page closing must not drop the
                // screensaver's surface, nor the screensaver the page's.
                if (surfaceOwner.isNotEmpty() && surfaceOwner != owner) return@post
                if (surface != null) Log.i(TAG, "mv surface detached by " + owner)
                surface = null
                surfaceOwner = ""
                return@post
            }
            val previous = surface
            surface = value
            surfaceOwner = owner
            val media = player
            // Audio, or nothing decoding: the surface is only remembered for the next MV.
            if (!mvActive || media == null) return@post
            if (previous === value && decoderSurface === value) return@post
            if (mvSources.isEmpty()) return@post
            val song = MusicQueue.current() ?: return@post
            val at = runCatching { media.currentPosition }.getOrDefault(position).coerceAtLeast(0L)
            Log.i(TAG, "mv surface attached by " + owner + ": rebuilding the decoder at " + at + "ms")
            position = at
            playMvSource(song, at, paused = !playing)
        }
    }

    fun mvEnabled(): Boolean = CastConfig.mvEnabled
    fun mvSource(): String = CastConfig.mvSource

    /** Which surface is attached ([SURFACE_PAGE], [SURFACE_SAVER]) or empty when there is none. */
    fun attachedSurfaceOwner(): String = surfaceOwner

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
            val at = index.coerceIn(0, biliResults.size - 1)
            Log.i(TAG, "bili pick #" + (at + 1) + ": " + biliResults[at].title)
            playBili(song, at, loadToken)
        }
    }

    /**
     * A key, a tap or Back on the B站 picker before its countdown ran out: the viewer is choosing,
     * so the countdown stops and OK decides. The song goes on meanwhile: switching the source had
     * stopped it, and a silent jukebox while the list is open is not what "cancel" should mean.
     */
    fun cancelBiliCountdown() {
        main.post {
            if (biliCountdown <= 0) return@post
            val was = biliCountdown
            cancelCountdown()
            status = "请选择要播放的 MV"
            Log.i(TAG, "bili countdown cancelled at " + was + "s by user interaction")
            notifyState()
            val song = MusicQueue.current()
            if (song != null && player == null && !prepared) playAudio(song, 0L, loadToken)
        }
    }

    /** A short line above the status, without interrupting playback. */
    fun note(text: String) {
        main.post { setNotice(text) }
    }

    /**
     * The line under the song right now: a notice while it lasts, else the player's own status.
     * The page polls this so a notice really disappears when its six seconds are up, instead of
     * waiting for the next state change.
     */
    fun statusLine(): String {
        val current = if (System.currentTimeMillis() < noticeUntil) notice else ""
        return current.ifEmpty { status }
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
                // 音源服务没会员/凭证过期时 502：有 MV 就用 MV 那条流（自带音轨）顶上，
                // 免得用户点什么都是"无法播放"。
                if (song.vid.isNotEmpty() && !mvFailed.contains(mvKey(song))) {
                    Log.i(TAG, "audio source failed (" + (error ?: "") + "), falling back to the MV stream")
                    status = "音源不可用，改用 MV 播放"
                    notifyState()
                    playQmMv(song, startMs, token)
                    return@resolve
                }
                status = "无法播放：" + (error ?: "未知错误") + "（音乐服务需要有效会员）"
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
        val quality = mvLadder()[0]
        status = "正在获取 MV…"
        notifyState()
        Log.i(TAG, "mv start quality=" + qualityLabel(quality) + " vid=" + song.vid)
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
        mvUrls(song, quality) { relay, direct ->
            if (token != loadToken) return@mvUrls
            startMv(song, relay, direct, startMs)
        }
    }

    /** 4.4 starts at the MV's own 1080p and steps down; anything newer starts at 720p. */
    private fun mvLadder(): IntArray =
        if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.KITKAT) {
            MV_LADDER_MODERN
        } else {
            MV_LADDER_4X
        }

    /**
     * The two ways to the same rendition: the backend relay (what the page is designed around)
     * and the CDN link the backend hands out with it.
     */
    private fun mvUrls(song: MusicSong, quality: Int, callback: (String, String) -> Unit) {
        MusicApi.mvUrl(song.vid, quality) { play, error ->
            if (play == null) Log.i(TAG, "mv url: " + (error ?: "empty"))
            callback(play?.stream ?: MusicApi.qmMvStream(song.vid, quality), play?.direct.orEmpty())
        }
    }

    /**
     * Plays one rendition, trying the CDN link first and the relay only when the CDN cannot carry
     * it. The backend sits abroad (111.170.155.145, measured 2026-11-16) and QQ's CDN gives that
     * relay about 40 KB/s while the same TV pulls the CDN link at 5 MB/s, so the direct link is
     * the normal way and the relay is the fallback.
     */
    private fun startMv(
        song: MusicSong,
        relay: String,
        direct: String,
        startMs: Long,
        headers: Map<String, String>? = null,
    ) {
        mvSources = mvCandidates(relay, direct)
        mvSourceIndex = 0
        mvHeaders = headers
        playMvSource(song, startMs)
    }

    /** Direct first, relay second, and the direct link once more when the relay is what starves. */
    private fun mvCandidates(relay: String, direct: String): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>(3)
        if (direct.isNotEmpty()) list.add("direct" to direct)
        if (relay.isNotEmpty()) list.add("relay" to relay)
        if (direct.isNotEmpty() && relay.isNotEmpty()) list.add("direct" to direct)
        return list
    }

    private fun playMvSource(song: MusicSong, startMs: Long, paused: Boolean = false) {
        val source = mvSources.getOrNull(mvSourceIndex) ?: return
        val where = if (CastConfig.mvSource == CastConfig.MV_SOURCE_BILI) {
            "bili"
        } else {
            "q=" + qualityLabel(mvLadder().getOrElse(mvQualityIndex) { 0 })
        }
        val way = if (source.first == "direct") "the CDN link (direct)" else "the relay"
        Log.i(TAG, "mv playing " + way + " " + where + " vid=" + song.vid)
        startPlayer(
            song, source.second, startMs, video = true, directUrl = "", headers = mvHeaders,
            paused = paused,
        )
    }

    /**
     * The hardware decoder did not do the job (an error, or a picture that stopped): play the same
     * link again in software and keep software for the rest of the session. Returns false when the
     * player is already in software, so the caller moves on to the next link or rendition.
     */
    private fun fallBackToSoftware(song: MusicSong): Boolean {
        if (mvSoftwareDecode) return false
        mvSoftwareDecode = true
        Log.w(TAG, "mv hardware decode failed: retrying the same link in software")
        setNotice("MV 改用软解")
        playMvSource(song, position.coerceAtLeast(0L))
        return true
    }

    /**
     * The current way into the MV did not carry the picture (an error, or a stream that starved):
     * move to the next one. Returns false when this rendition has no way left, and the caller then
     * steps down a rendition or falls back to the song's audio.
     */
    private fun advanceMvSource(song: MusicSong, reason: String): Boolean {
        if (mvSourceIndex + 1 >= mvSources.size) return false
        val from = mvSources[mvSourceIndex].first
        mvSourceIndex++
        val to = mvSources[mvSourceIndex].first
        Log.i(TAG, "mv " + from + " " + reason + ": playing the " + to + " link instead")
        setNotice("MV 换用" + if (to == "direct") "直连线路" else "中继线路")
        playMvSource(song, position.coerceAtLeast(0L))
        return true
    }

    /**
     * The stream did not decode (or stopped moving): try the next rendition in the MV's own ladder.
     * Returns false when every rendition failed, and the caller then falls back to the song's audio.
     */
    private fun retryQmQuality(song: MusicSong): Boolean {
        val ladder = mvLadder()
        if (mvQualityIndex + 1 >= ladder.size) return false
        mvQualityIndex++
        val quality = ladder[mvQualityIndex]
        status = "MV 正在尝试 " + qualityLabel(quality) + "…"
        Log.i(TAG, "mv retry quality=" + quality + " vid=" + song.vid)
        notifyState()
        mvUrls(song, quality) { relay, direct ->
            if (MusicQueue.current()?.mid != song.mid) return@mvUrls
            startMv(song, relay, direct, 0L)
        }
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
            Log.i(TAG, "bili search: " + results.size + " results for \"" + biliQuery +
                "\", countdown " + biliCountdown + "s, first: " + results[0].title)
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
                    Log.i(TAG, "bili countdown " + biliCountdown + "s: " +
                        (biliResults.getOrNull(biliSelected)?.title.orEmpty()))
                    notifyState()
                    main.postDelayed(this, 1000L)
                } else {
                    status = ""
                    Log.i(TAG, "bili countdown done: auto-playing #" + (biliSelected + 1) + " " +
                        (biliResults.getOrNull(biliSelected)?.title.orEmpty()))
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
        // Same order as the QM MV: the CDN link first (with the headers bilibili wants), the
        // backend relay only when the direct link cannot be played.
        MusicApi.biliMvUrl(result.bvid) { play, error ->
            if (MusicQueue.current()?.mid != song.mid) return@biliMvUrl
            if (play == null) Log.i(TAG, "bili url: " + (error ?: "empty"))
            startMv(
                song,
                play?.stream ?: MusicApi.biliMvStream(result.bvid),
                play?.direct.orEmpty(),
                0L,
                BILI_HEADERS,
            )
        }
    }

    private fun startPlayer(
        song: MusicSong,
        url: String,
        startMs: Long,
        video: Boolean,
        directUrl: String,
        headers: Map<String, String>? = null,
        paused: Boolean = false,
    ) {
        // A MV being rebuilt for a new surface keeps its state: the page must not fall back to the
        // record player, and the saver must not leave MV mode, for the second the decoder takes to
        // come back. Its position is carried over by the caller (setSurface).
        val keepMv = mvActive && video
        val keepPlaying = playing && keepMv
        releasePlayer()
        mvActive = keepMv
        playing = keepPlaying
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
            // ffmpeg keeps the user agent in its own option and drops a "User-Agent" line from
            // the headers dictionary: without this the bilibili CDN answers 403 (measured).
            val agent = headers?.entries?.firstOrNull { it.key.equals("User-Agent", true) }?.value
            if (agent != null) media.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", agent)
            if (video) {
                // Hardware decode by default: a 1080p MV is 25–60 fps of H.264 that this class of
                // box only manages at 15 fps in software (measured 2026-11-16 on the Redmi box),
                // while MediaCodec runs it off the CPU entirely. If the hardware path fails — an
                // error, or a picture whose clock stops — [mvSoftwareDecode] sticks and every
                // later MV is decoded in software instead.
                val hardware = !mvSoftwareDecode
                media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", if (hardware) 1 else 0)
                if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.KITKAT) {
                    media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1)
                    media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1)
                }
                media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-fps", 30)
                // 缓冲：15 MB 大约是 720p 流半分钟的余量，够顶住一次 Wi-Fi 抖动，又不至于在慢速
                // 网络上攒下几十秒延迟；infbuf 必须是 0，否则 ijk 会忽略 max-buffer-size 一直缓存。
                media.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", 0)
                media.setOption(
                    IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                    "max-buffer-size",
                    15L * 1024L * 1024L,
                )
                // 首帧快一点：默认 5 MB 的探测量在这条流上要读出好几秒，1 MB 足够识别 moov。
                media.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1L shl 20)
                media.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 500_000)
                val target = surface
                decoderSurface = target
                target?.let { runCatching { media.setSurface(it) } }
                Log.i(
                    TAG,
                    "mv decoder dec=" + (if (hardware) "hw" else "sw") +
                        " surface=" + (if (target != null) surfaceOwner.ifEmpty { SURFACE_PAGE } else "none"),
                )
                media.setOnVideoSizeChangedListener { _, width, height, _, _ ->
                    if (width > 0 && height > 0 && (width != mvVideoWidth || height != mvVideoHeight)) {
                        mvVideoWidth = width
                        mvVideoHeight = height
                        Log.i(TAG, "mv video size " + width + "x" + height)
                        notifyState()
                    }
                }
            }
            if (headers != null) media.setDataSource(url, headers) else media.setDataSource(url)
            media.setOnPreparedListener {
                main.post {
                    if (generation != playerGeneration) return@post
                    prepared = true
                    mvActive = video
                    duration = runCatching { it.duration }.getOrDefault(0L).coerceAtLeast(0L)
                    if (startMs > 0) runCatching { it.seekTo(startMs) }
                    position = startMs
                    if (paused) {
                        // A rebuild of a MV that was paused: the picture comes back, the sound does not.
                        runCatching { it.pause() }
                        playing = false
                    } else {
                        it.start()
                        playing = true
                        requestFocus()
                    }
                    status = ""
                    progressPosition = -1L
                    progressAt = 0L
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
                        // A failing hardware decoder is retried in software before the link or the
                        // rendition is given up.
                        if (fallBackToSoftware(song)) return@post
                        // A link that failed costs one hop to the other one (CDN ↔ relay) and
                        // keeps the picture before any rendition is given up.
                        if (advanceMvSource(song, "failed")) return@post
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

    /**
     * Two ways a MV goes wrong, both watched here: the clock stops for [MV_STALL_MS] (a dead
     * decoder, a relay that stopped sending), or it advances at well under real time over a
     * [MV_SAMPLE_MS] window (the stream cannot be carried at this bitrate). Either way the same
     * rendition is tried from the CDN link first, then the next lower rendition, and finally the
     * song plays as audio — the same ending as a decode error. While a MV plays this also logs
     * what the decoder really did (frames per second, cached seconds) for the record.
     */
    private fun watchMvStall(media: IjkMediaPlayer) {
        // No surface means no picture: the page is closed (Home, the screensaver) and ijk is
        // decoding into nothing. Whatever the clock does there is not a stutter on screen, and
        // stepping down now would quietly downgrade the MV the user comes back to.
        if (!mvActive || !prepared || surface == null) {
            progressAt = 0L
            windowAt = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (position > progressPosition) {
            progressPosition = position
            progressAt = now
        } else if (progressAt == 0L) {
            progressAt = now
        } else if (now - progressAt > MV_STALL_MS) {
            progressAt = 0L
            progressPosition = -1L
            windowAt = 0L
            onMvStall("stalled")
            return
        }
        // Starving: the picture ran at less than half real time across the window. A slow relay
        // shows up here long before the clock stops completely.
        if (windowAt == 0L) {
            windowAt = now
            windowPosition = position
        } else if (now - windowAt >= MV_SAMPLE_MS) {
            val advanced = position - windowPosition
            val elapsed = now - windowAt
            windowAt = now
            windowPosition = position
            if (advanced * 2 < elapsed) {
                onMvStall("starving: " + advanced + "ms of picture in " + elapsed + "ms")
                return
            }
        }
        if (now - lastStatsAt >= 5_000L) {
            lastStatsAt = now
            val where = if (CastConfig.mvSource == CastConfig.MV_SOURCE_BILI) {
                "bili"
            } else {
                "q=" + qualityLabel(mvLadder()[mvQualityIndex])
            }
            runCatching {
                Log.i(
                    TAG,
                    "mv stats src=" + (mvSources.getOrNull(mvSourceIndex)?.first ?: "-") +
                        " dec=" + (if (mvSoftwareDecode) "sw" else "hw") +
                        " " + where +
                        " pos=" + position +
                        " decoded_fps=" + media.videoDecodeFramesPerSecond +
                        " output_fps=" + media.videoOutputFramesPerSecond +
                        " decoder=" + media.videoDecoder +
                        " cached_ms=" + media.videoCachedDuration,
                )
            }
        }
    }

    private fun onMvStall(reason: String) {
        val song = MusicQueue.current() ?: return
        Log.w(TAG, "mv " + reason + " at " + position + "ms")
        if (fallBackToSoftware(song)) return
        if (advanceMvSource(song, reason)) return
        if (CastConfig.mvSource == CastConfig.MV_SOURCE_QM && retryQmQuality(song)) return
        mvActive = false
        mvCover = ""
        mvTitle = ""
        mvFailed.add(mvKey(song))
        setNotice("MV 播放卡住，已切回音频")
        notifyState()
        playAudio(song, 0L, loadToken)
    }

    private fun releasePlayer() {
        loadToken++
        playerGeneration++
        main.removeCallbacks(ticker)
        main.removeCallbacks(stateTicker)
        cancelCountdown()
        val media = player
        player = null
        decoderSurface = null
        prepared = false
        playing = false
        mvActive = false
        duration = 0L
        progressAt = 0L
        progressPosition = -1L
        windowAt = 0L
        windowPosition = -1L
        lastStatsAt = 0L
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
        mvVideoWidth = 0
        mvVideoHeight = 0
        mvSources = emptyList()
        mvSourceIndex = 0
        mvHeaders = null
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
