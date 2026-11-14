package app.xingmu.aerial.cast

/**
 * One song in the jukebox. [id] is the backend's queue id (or a local one when the request came
 * from an older phone): the phone's drag/delete calls name these ids, so TV and phone agree on the
 * same row. It lives as long as the playback service, not the page.
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
    var id: String = "",
    /** The song's QM MV id; empty means the song has no MV and the jukebox stays on audio. */
    var vid: String = "",
) {
    /** "歌名 - 歌手", the form the queue shows. */
    val label: String get() = if (singer.isEmpty()) name else "$name - $singer"
}

/**
 * The jukebox's ordered list. It is owned by [MusicPlayback] and by the playback service, so a
 * request from a phone still becomes the queue while the TV is showing Home or the screensaver.
 */
object MusicQueue {
    private const val LIMIT = 100
    private val songs = ArrayList<MusicSong>()
    private var index = -1

    @Synchronized
    fun enqueue(song: MusicSong, makeCurrent: Boolean = false): MusicSong {
        if (song.id.isEmpty()) song.id = "local-" + System.currentTimeMillis() + "-" + songs.size
        songs.add(song)
        while (songs.size > LIMIT) {
            songs.removeAt(0)
            if (index >= 0) index--
        }
        if (makeCurrent || index < 0) index = songs.size - 1
        return song
    }

    @Synchronized
    fun current(): MusicSong? = songs.getOrNull(index)

    @Synchronized
    fun currentIndex(): Int = index

    @Synchronized
    fun all(): List<MusicSong> = ArrayList(songs)

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
        return songs[index]
    }

    @Synchronized
    fun rewind(): MusicSong? {
        if (songs.isEmpty()) return null
        if (index > 0) index--
        return songs[index]
    }

    @Synchronized
    fun position(): Int = index

    @Synchronized
    fun size(): Int = songs.size

    /** Removes [id]; true when it was the playing song (the caller then starts the next one). */
    @Synchronized
    fun remove(id: String): Boolean {
        val at = songs.indexOfFirst { it.id == id }
        if (at < 0) return false
        val wasCurrent = at == index
        songs.removeAt(at)
        if (songs.isEmpty()) {
            index = -1
        } else if (at < index) {
            index--
        } else if (wasCurrent) {
            index = index.coerceAtMost(songs.size - 1)
        }
        return wasCurrent
    }

    /** Puts the upcoming songs in the order the phone dragged them into. */
    @Synchronized
    fun reorderUpcoming(ids: List<String>) {
        if (index < 0 || index + 1 >= songs.size) return
        val upcoming = ArrayList(songs.subList(index + 1, songs.size))
        val byId = upcoming.associateBy { it.id }
        val ordered = ArrayList<MusicSong>()
        for (id in ids) byId[id]?.let { ordered.add(it) }
        for (song in upcoming) if (ids.none { it == song.id }) ordered.add(song)
        val prefix = ArrayList(songs.subList(0, index + 1))
        songs.clear()
        songs.addAll(prefix)
        songs.addAll(ordered)
    }

    @Synchronized
    fun clear() {
        songs.clear()
        index = -1
    }
}
