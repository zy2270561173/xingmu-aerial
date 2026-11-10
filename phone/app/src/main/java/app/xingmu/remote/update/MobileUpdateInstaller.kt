package app.xingmu.remote.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object MobileUpdateInstaller {
    fun open(activity: Activity, path: String): Boolean = runCatching {
        val apk = File(path)
        require(apk.isFile)
        val uri: Uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.files",
            apk,
        )
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        true
    }.getOrDefault(false)
}
