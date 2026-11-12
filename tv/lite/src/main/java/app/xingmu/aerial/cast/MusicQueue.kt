package app.xingmu.aerial.cast

/**
 * The built-in jukebox's queue. It lives as long as the process, not the page: a phone can queue a
 * song while the TV is showing Home, and [MusicActivity] finds it when the command opens the page.
 */
class MusicSong(
    val mid: String,
    val name: String,
    val singer: String,
    val cover: String,
    val album: String,
    val interval: Int,
    var quality: String,
    var url: String,
    /** The CDN's own URL, used only if the backend relay cannot be played. */
    var directUrl: String = "",
) {
    /** "歌名 - 歌手", the form the queue shows. */
    val label: String get() = if (singer.isEmpty()) name else "$name - $singer"
}

object MusicQueue {
    private const val LIMIT = 100
    private val songs = ArrayList<MusicSong>()
    private var index = -1

    /** True while [MusicActivity] is between onStart and onStop: then a new song only joins the queue. */
    @Volatile var active = false

    /** True while audio is actually running; a pause lets the next request take over at once. */
    @Volatile var playing = false

    /** Set when a song arrives while the page is away, so onStart knows to begin playing. */
    @Volatile var autoPlay = false

    /** Called on a worker thread whenever the queue or the current song changes. */
    @Volatile var listener: (() -> Unit)? = null

    @Synchronized
    fun enqueue(song: MusicSong): Boolean {
        songs.add(song)
        while (songs.size > LIMIT) {
            songs.removeAt(0)
            if (index >= 0) index--
        }
        // A request that arrives while the page is away (or paused) becomes what plays next; while
        // a song is actually running it only joins the end, as the phone's 已点 list shows.
        if (!active || !playing || index < 0) {
            index = songs.size - 1
            autoPlay = true
        }
        notifyChanged()
        return true
    }

    @Synchronized
    fun current(): MusicSong? = songs.getOrNull(index)

    @Synchronized
    fun nextSong(): MusicSong? = songs.getOrNull(index + 1)

    @Synchronized
    fun upcoming(): List<MusicSong> {
        if (index < 0 || index + 1 >= songs.size) return emptyList()
        return ArrayList(songs.subList(index + 1, songs.size))
    }

    @Synchronized
    fun advance(): MusicSong? {
        if (index + 1 >= songs.size) return null
        index++
        autoPlay = false
        notifyChanged()
        return songs[index]
    }

    @Synchronized
    fun rewind(): MusicSong? {
        if (songs.isEmpty()) return null
        if (index > 0) index--
        autoPlay = false
        notifyChanged()
        return songs[index]
    }

    @Synchronized
    fun position(): Int = index

    @Synchronized
    fun size(): Int = songs.size

    @Synchronized
    fun clear() {
        songs.clear()
        index = -1
        autoPlay = false
        notifyChanged()
    }

    private fun notifyChanged() {
        val callback = listener ?: return
        runCatching { callback() }
    }
}
