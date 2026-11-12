package app.xingmu.aerial.cast

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
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
)

/**
 * The jukebox's player, kept outside any Activity: Home, the record-player page and the playback
 * service all talk to this one object. That is what lets Back leave the page, or the screensaver
 * start, without stopping the music.
 */
object MusicPlayback {
    private const val TAG = "AerialMusic"
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

    fun playCurrent() {
        val start = if (prepared || player != null) position else 0L
        main.post { doPlayCurrent(start) }
    }

    fun toggle() {
        main.post {
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
        status = "正在获取播放地址…"
        notifyState()
        val token = loadToken
        if (song.url.isNotEmpty()) {
            startPlayer(song, song.url, startMs)
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
            startPlayer(song, play.stream, startMs)
        }
    }

    private fun startPlayer(song: MusicSong, url: String, startMs: Long) {
        releasePlayer()
        val generation = playerGeneration
        status = "正在缓冲…"
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
            media.setDataSource(url)
            media.setOnPreparedListener {
                main.post {
                    if (generation != playerGeneration) return@post
                    prepared = true
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
                    position = duration
                    notifyState()
                    if (MusicQueue.advance() != null) {
                        doPlayCurrent(0L)
                    } else {
                        status = "播放结束"
                        notifyState()
                    }
                }
            }
            media.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "ijk error $what/$extra url=$url")
                main.post {
                    if (generation != playerGeneration) return@post
                    prepared = false
                    playing = false
                    val direct = song.directUrl
                    if (!triedDirect && direct.isNotEmpty() && direct != url) {
                        triedDirect = true
                        startPlayer(song, direct, startMs)
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
        val media = player
        player = null
        prepared = false
        playing = false
        duration = 0L
        if (position < 0) position = 0L
        runCatching {
            media?.stop()
            media?.release()
        }
        abandonFocus()
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
}
