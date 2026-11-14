package app.xingmu.aerial.cast

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/** One line of a lyric, already on the player's millisecond clock. */
class LyricLine(val timeMs: Long, val text: String)

/** A quality the TV may ask for. */
class MusicQuality(val key: String, val label: String, val available: Boolean)

/** What /api/music/url returned: the backend relay first, the CDN's own URL as a fallback. */
class PlayUrl(val stream: String, val direct: String, val quality: String)

/** A QM MV's own metadata: the card and the saver use the cover, the phone page the name. */
class MvInfo(val vid: String, val name: String, val cover: String, val duration: Int)

/** One B站 search hit: bvid is what plays, the rest is what the picker shows. */
class BiliMv(
    val bvid: String,
    val title: String,
    val author: String,
    val cover: String,
    val duration: Int,
    val play: Int,
)

/**
 * The jukebox's backend client. Everything goes through the TV's configured backend (OkHttp 3,
 * TLS 1.2 widened for 4.4) so the CDN's short-lived links never have to be composed here.
 */
object MusicApi {
    private const val TAG = "AerialMusic"
    private const val DEFAULT_QUALITY = "MP3_320"

    /**
     * Qualities an old ijkplayer/ffmpeg decodes lightly. The upstream list also offers 24-bit
     * masters and multichannel formats, which a 4.4 box should not be asked to decode.
     */
    private val TV_KEYS = setOf(
        "MP3_320", "ACC_192", "MP3_128", "OGG_320", "OGG_192", "OGG_96", "ACC_96", "ACC_48",
    )
    private val TV_ORDER = listOf(
        "MP3_320", "ACC_192", "MP3_128", "OGG_320", "OGG_192", "OGG_96", "ACC_96", "ACC_48",
    )

    private val main = Handler(Looper.getMainLooper())
    private var client: OkHttpClient? = null

    private fun client(): OkHttpClient {
        val existing = client
        if (existing != null) return existing
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
        if (CastConfig.backendUrl.startsWith("https://")) {
            runCatching { Tls12Setup.install(builder) }
                .onFailure { Log.w(TAG, "TLS 1.2 setup failed: " + it.javaClass.simpleName) }
        }
        return builder.build().also { client = it }
    }

    private fun http(path: String, callback: (JSONObject?, String?) -> Unit) {
        val base = CastConfig.backendUrl
        if (base.isEmpty()) {
            main.post { callback(null, "未配置后端地址") }
            return
        }
        val request = Request.Builder().url(base + path).get().build()
        client().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                Log.w(TAG, "GET $path failed: " + error.javaClass.simpleName)
                main.post { callback(null, "网络错误：" + error.javaClass.simpleName) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { result ->
                    val body = result.body()?.string().orEmpty()
                    if (!result.isSuccessful) {
                        main.post { callback(null, reason(body, result.code())) }
                        return
                    }
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    if (json == null) {
                        main.post { callback(null, "后端返回了非 JSON 数据") }
                        return
                    }
                    main.post { callback(json, null) }
                }
            }
        })
    }

    /** The backend's short reason, never a stack trace or an HTML error page. */
    private fun reason(body: String, code: Int): String {
        val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull().orEmpty()
        val text = detail.ifEmpty { "HTTP $code" }
        return if (text.length > 80) text.substring(0, 80) else text
    }

    fun search(query: String, limit: Int, callback: (List<MusicSong>, String?) -> Unit) {
        val path = "/api/music/search?q=" + Uri.encode(query) + "&limit=" + limit
        http(path) { json, error ->
            if (json == null) {
                callback(emptyList(), error)
                return@http
            }
            val songs = ArrayList<MusicSong>()
            val list = json.optJSONObject("data")?.optJSONArray("list")
            if (list != null) {
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val mid = item.optString("mid")
                    if (mid.isEmpty()) continue
                    songs.add(
                        MusicSong(
                            mid = mid,
                            name = item.optString("name").ifEmpty { item.optString("title") },
                            singer = item.optString("singer").ifEmpty { item.optString("singerText") },
                            cover = item.optString("cover"),
                            album = item.optString("album"),
                            interval = item.optInt("interval", 0),
                            quality = DEFAULT_QUALITY,
                            url = "",
                            vid = item.optString("vid").ifEmpty {
                                item.optJSONObject("mv")?.optString("vid").orEmpty()
                            },
                        )
                    )
                }
            }
            callback(songs, null)
        }
    }

    fun qualities(mid: String, current: String, callback: (List<MusicQuality>, String?) -> Unit) {
        http("/api/music/qualities/" + Uri.encode(mid)) { json, error ->
            if (json == null) {
                callback(fallbackQualities(current), error)
                return@http
            }
            val parsed = ArrayList<MusicQuality>()
            val list = json.optJSONObject("data")?.optJSONArray("list")
            if (list != null) {
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val key = item.optString("key")
                    if (key.isEmpty()) continue
                    parsed.add(
                        MusicQuality(
                            key = key,
                            label = item.optString("label").ifEmpty { key },
                            available = item.optBoolean("available", false),
                        )
                    )
                }
            }
            val usable = parsed.filter { it.available && (it.key in TV_KEYS || it.key == current) }
                .sortedBy { TV_ORDER.indexOf(it.key).let { at -> if (at < 0) TV_ORDER.size else at } }
            if (usable.isEmpty()) callback(fallbackQualities(current), error) else callback(usable, null)
        }
    }

    private fun fallbackQualities(current: String): List<MusicQuality> {
        val list = arrayListOf(
            MusicQuality("MP3_320", "HQ 高品质 MP3 320k", true),
            MusicQuality("ACC_192", "HQ 高品质 AAC 192k", true),
            MusicQuality("MP3_128", "标准音质 MP3 128k", true),
        )
        if (list.none { it.key == current }) list.add(0, MusicQuality(current, current, true))
        return list
    }

    fun resolve(mid: String, quality: String, callback: (PlayUrl?, String?) -> Unit) {
        val path = "/api/music/url/" + Uri.encode(mid) + "?quality=" + Uri.encode(quality)
        http(path) { json, error ->
            if (json == null) {
                callback(null, error)
                return@http
            }
            val data = json.optJSONObject("data")
            val direct = data?.optString("url").orEmpty()
            if (direct.isEmpty()) {
                callback(null, "后端没有返回播放地址")
                return@http
            }
            val used = data?.optString("quality").orEmpty().ifEmpty { quality }
            // Build the relay URL here, not from data.stream_url: that one may name the loopback
            // address the server bound to, which is not the address the TV reached it on.
            val stream = CastConfig.backendUrl + "/api/music/stream/" + Uri.encode(mid) +
                "?quality=" + Uri.encode(used)
            callback(PlayUrl(stream, direct, used), null)
        }
    }

    /** The song's own (QM) MV: the relay stream plus the CDN link, as the audio resolver does. */
    fun mvUrl(vid: String, callback: (PlayUrl?, String?) -> Unit) {
        http("/api/music/mv/" + Uri.encode(vid) + "/url") { json, error ->
            if (json == null) {
                callback(null, error)
                return@http
            }
            val data = json.optJSONObject("data")
            callback(PlayUrl(qmMvStream(vid), data?.optString("url").orEmpty(), "MV"), null)
        }
    }

    /** The relay URL is composed here for the same reason as the audio one: no loopback host. */
    fun qmMvStream(vid: String, quality: Int = 0): String {
        val base = CastConfig.backendUrl + "/api/music/mv/" + Uri.encode(vid) + "/stream"
        // quality 0 = the MV's own default (highest); 4.4 falls back to 30/10 via the backend.
        return if (quality > 0) base + "?quality=" + quality else base
    }

    fun biliMvStream(bvid: String): String =
        CastConfig.backendUrl + "/api/music/mv/bili/" + Uri.encode(bvid) + "/stream"

    /** The QM MV's cover/name; the saver paints it as the background while the MV plays. */
    fun mvInfo(vid: String, callback: (MvInfo?, String?) -> Unit) {
        http("/api/music/mv/" + Uri.encode(vid)) { json, error ->
            if (json == null) {
                callback(null, error)
                return@http
            }
            val data = json.optJSONObject("data") ?: return@http callback(null, "MV 信息为空")
            val info = MvInfo(
                vid = data.optString("vid").ifEmpty { vid },
                name = data.optString("name"),
                cover = data.optString("cover"),
                duration = data.optInt("duration", 0),
            )
            callback(info, null)
        }
    }

    /** B站 MV search by 歌名 + 歌手; each hit carries the bvid its stream is played by. */
    fun biliSearch(keyword: String, callback: (List<BiliMv>, String?) -> Unit) {
        val path = "/api/music/mv/bili/search?q=" + Uri.encode(keyword) + "&limit=8"
        http(path) { json, error ->
            if (json == null) {
                callback(emptyList(), error)
                return@http
            }
            val results = ArrayList<BiliMv>()
            val list = json.optJSONObject("data")?.optJSONArray("list")
            if (list != null) {
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val bvid = item.optString("bvid")
                    if (bvid.isEmpty()) continue
                    results.add(
                        BiliMv(
                            bvid = bvid,
                            title = item.optString("title"),
                            author = item.optString("author"),
                            cover = item.optString("cover"),
                            duration = item.optInt("duration", 0),
                            play = item.optInt("play", 0),
                        )
                    )
                }
            }
            callback(results, null)
        }
    }

    fun lyric(mid: String, callback: (List<LyricLine>, String?) -> Unit) {
        http("/api/music/lyric/" + Uri.encode(mid)) { json, error ->
            if (json == null) {
                callback(emptyList(), error)
                return@http
            }
            val lines = ArrayList<LyricLine>()
            val list = json.optJSONObject("data")?.optJSONArray("lines")
            if (list != null) {
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val text = item.optString("text").trim()
                    if (text.isEmpty()) continue
                    lines.add(LyricLine((item.optDouble("time", 0.0) * 1000.0).toLong(), text))
                }
            }
            callback(lines, null)
        }
    }

    fun cover(url: String, callback: (Bitmap?, String?) -> Unit) {
        if (url.isEmpty()) {
            callback(null, "没有封面")
            return
        }
        // Through the backend: y.gtimg.cn refuses the TLS handshake 4.4's OkHttp can offer.
        val base = CastConfig.backendUrl
        val target = if (base.isEmpty()) url else base + "/api/music/cover?url=" + Uri.encode(url)
        val request = Request.Builder().url(target).get().build()
        client().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                main.post { callback(null, error.javaClass.simpleName) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { result ->
                    val bitmap = if (result.isSuccessful) {
                        runCatching { BitmapFactory.decodeStream(result.body()?.byteStream()) }.getOrNull()
                    } else null
                    main.post { callback(bitmap, if (bitmap == null) "封面加载失败" else null) }
                }
            }
        })
    }
}
