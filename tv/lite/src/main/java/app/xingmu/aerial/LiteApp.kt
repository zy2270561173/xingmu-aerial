package app.xingmu.aerial

import android.app.Application

/** 应用入口：把 ijk 原生库的加载提前到进程启动，避免第一次点播放才崩。 */
class LiteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NativeLibs.ensure(this)
    }
}
