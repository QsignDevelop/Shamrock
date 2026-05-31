package moe.RinShiona.Shamrock.ui.tools

import android.content.Context
import android.os.Build
import moe.RinShiona.Shamrock.ui.app.ShamrockConfig
import java.io.File

object DeviceStatus {

    fun detectRoot(): Boolean {
        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/adb/magisk",
            "/data/adb/magisk.db",
        )
        if (suPaths.any { File(it).exists() }) return true
        val path = System.getenv("PATH") ?: return false
        return path.split(":").any { File("$it/su").exists() }
    }

    fun detectSu(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        p.waitFor() == 0
    } catch (_: Exception) {
        false
    }

    fun getQqVersionLabel(ctx: Context): String = try {
        val pm = ctx.packageManager
        val pkg = "com.tencent.mobileqq"
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(pkg, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toString()
        }
        "QQ ${info.versionName} · build $code"
    } catch (_: Exception) {
        "QQ 未安装"
    }

    fun getAntiDetectLabel(ctx: Context): String = when {
        !ShamrockConfig.isAntiDetectionEnabled(ctx) -> "已关闭"
        ShamrockConfig.isAntiConnectivitySafe(ctx) -> "联网优先 · 已启用"
        else -> "完整模式 · 已启用"
    }
}
