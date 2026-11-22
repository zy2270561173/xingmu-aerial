package app.xingmu.aerial

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * ijk 的原生库兜底加载。
 *
 * 正常安装时系统会把 `lib/<abi>/` 下的 .so 解到 `nativeLibraryDir`，`System.loadLibrary` 直接可用。
 * 但有些电视把 Aerial 预装在 `/system/app`（这台 Q21 就是），这种"系统应用"的
 * `nativeLibraryDir` 只剩 `/vendor/lib`、`/system/lib`，`loadLibrary` 抛
 * `UnsatisfiedLinkError: Couldn't load ijkwdzffmpeg ... findLibrary returned null`，
 * 一点播就闪退。
 *
 * 这里从 APK 里把本机 ABI 的 .so 解到应用私有目录，再用绝对路径逐个 `System.load`。
 * 依赖顺序不一定可靠（libijkplayer 依赖 ffmpeg/sdl），所以多轮尝试，能加载的先加载。
 */
object NativeLibs {
    private const val TAG = "AerialNative"

    @Volatile
    private var ready = false

    fun ensure(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val app = context.applicationInfo
            val libDir = app.nativeLibraryDir
            if (libDir != null && File(libDir, "libijkwdzffmpeg.so").isFile) {
                ready = true
                return
            }
            val abi = abi()
            val apk = File(app.sourceDir)
            val target = File(context.filesDir, "native-$abi")
            try {
                extract(apk, abi, target)
                loadAll(target)
                ready = true
                Log.i(TAG, "loaded ijk libraries from $target")
            } catch (t: Throwable) {
                Log.w(TAG, "could not load ijk libraries from the APK", t)
            }
        }
    }

    private fun abi(): String {
        val supported = if (Build.VERSION.SDK_INT >= 21) Build.SUPPORTED_ABIS else arrayOf(Build.CPU_ABI)
        return supported.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
            ?: supported.firstOrNull()
            ?: "armeabi-v7a"
    }

    private fun extract(apk: File, abi: String, target: File) {
        target.mkdirs()
        ZipFile(apk).use { zip ->
            val prefix = "lib/$abi/"
            for (entry in zip.entries()) {
                if (!entry.name.startsWith(prefix) || !entry.name.endsWith(".so")) continue
                val out = File(target, entry.name.substringAfterLast('/'))
                if (out.isFile && out.length() == entry.size) continue
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                out.setReadable(true, false)
                out.setExecutable(true, false)
            }
        }
    }

    private fun loadAll(dir: File) {
        val pending = dir.listFiles { file -> file.name.endsWith(".so") }?.toMutableList() ?: mutableListOf()
        // sdl 最底层，先加载；其余靠多轮重试解决。
        pending.sortBy { if (it.name.contains("sdl")) 0 else if (it.name.contains("player")) 1 else 2 }
        var progress = true
        while (pending.isNotEmpty() && progress) {
            progress = false
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val lib = iterator.next()
                try {
                    System.load(lib.absolutePath)
                    iterator.remove()
                    progress = true
                } catch (t: Throwable) {
                    Log.w(TAG, "load ${lib.name} failed: ${t.javaClass.simpleName}")
                }
            }
        }
        if (pending.isNotEmpty()) {
            Log.w(TAG, "unloaded: " + pending.joinToString { it.name })
        }
    }
}
