package app.xingmu.remote.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

private val bingClient = OkHttpClient()
private const val CACHE_MS = 6L * 60L * 60L * 1000L

/**
 * Bing's daily photo behind the app, the way Aerial ships a wallpaper rather than a black void.
 *
 * The picture is cached for six hours in filesDir so a cold start does not wait for the network,
 * and a dark gradient is laid over it: white type has to stay readable on a bright sky.
 */
@Composable
fun BingBackdrop(modifier: Modifier = Modifier, scrimTop: Float = 0.35f, scrimBottom: Float = 0.82f) {
    val context = LocalContext.current
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        image = withContext(Dispatchers.IO) { loadBing(context) }
    }
    Box(modifier) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = scrimTop), Color.Black.copy(alpha = scrimBottom)),
                ),
            ),
        )
    }
}

private fun loadBing(context: Context): ImageBitmap? {
    val file = File(context.filesDir, "bing_daily.jpg")
    if (file.isFile && System.currentTimeMillis() - file.lastModified() < CACHE_MS) {
        decode(file.readBytes())?.let { return it }
    }
    return runCatching {
        val meta = bingClient.newCall(
            Request.Builder()
                .url("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN")
                .build(),
        ).execute().use { response -> response.body?.string().orEmpty() }
        val path = JSONObject(meta).getJSONArray("images").getJSONObject(0).getString("url")
        val bytes = bingClient.newCall(Request.Builder().url("https://www.bing.com" + path).build())
            .execute().use { response -> response.body?.bytes() }
            ?: return null
        if (bytes.size > 32) {
            runCatching { file.writeBytes(bytes) }
            decode(bytes)
        } else {
            null
        }
    }.getOrNull() ?: runCatching { decode(file.readBytes()) }.getOrNull()
}

private fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()
