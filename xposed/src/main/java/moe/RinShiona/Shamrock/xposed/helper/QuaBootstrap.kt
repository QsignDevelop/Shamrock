package moe.RinShiona.Shamrock.xposed.helper

import android.content.Context
import de.robv.android.xposed.XposedBridge
import mqq.app.MobileQQ

/**
 * 从已安装 QQ 包信息构造 QUA，不依赖 MobileQQ.getContext()（qqinterface 桩会抛错）。
 */
internal object QuaBootstrap {

    private const val QQ_PKG = "com.tencent.mobileqq"

    fun applicationContext(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as Context
    }.getOrNull()

    fun buildFromInstalledPackage(ctx: Context? = applicationContext()): String? = runCatching {
        val c = ctx ?: return@runCatching null
        val targetPkg = if (c.packageName == QQ_PKG) c.packageName else QQ_PKG
        val info = c.packageManager.getPackageInfo(targetPkg, 0)
        val verName = info.versionName ?: return@runCatching null
        val verCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
        "V1_AND_SQ_${verName}_${verCode}_YYB_D"
    }.getOrNull()

    /** 签名前强制写入 QSecConfig.business_qua 并落盘 relay。 */
    fun forceApply(classLoader: ClassLoader, requestQua: String? = null): SignCore.QuaResolve {
        val resolved = SignCore.resolveQuaForSign(classLoader, requestQua)
        val qua = when {
            SignCore.isSignAttemptQua(resolved.qua) -> resolved.qua
            else -> buildFromInstalledPackage()
                ?: runCatching {
                    val ctx = MobileQQ.getContext()
                    buildFromInstalledPackage(ctx)
                }.getOrNull()
                ?: ""
        }
        if (!SignCore.isSignAttemptQua(qua)) {
            return SignCore.QuaResolve("", "empty")
        }
        QSecContextBridge.forceApplyQua(classLoader, qua)
        val source = if (resolved.qua == qua) resolved.source else "forced_pkg_builtin"
        XposedBridge.log("Shamrock: forceApplyQua src=$source len=${qua.length} head=${qua.take(40)}")
        return SignCore.QuaResolve(qua, source)
    }
}
