package moe.RinShiona.Shamrock.xposed.helper

import de.robv.android.xposed.XposedBridge
import moe.RinShiona.Shamrock.xposed.ipc.impl.ShamrockNative
import mqq.app.MobileQQ

internal object MsfSignBootstrap {

    fun ensureReady(classLoader: ClassLoader, requestQua: String? = null) {
        QSecContextBridge.applySnapshot(classLoader, requestQua)
        val qua = QuaBootstrap.forceApply(classLoader, requestQua).qua
        if (qua.isNotBlank()) QSecContextBridge.initFeKit(classLoader, qua)
        runCatching {
            val feKit = classLoader.loadClass("com.tencent.mobileqq.fe.FEKit")
            feKit.getMethod("requestToken").invoke(feKit.getMethod("getInstance").invoke(null))
        }
        if (!moe.RinShiona.Shamrock.xposed.AntiDetectionConfig.connectivitySafeMode &&
            !ShamrockNative.initialized
        ) {
            ShamrockNative.bootstrap(QuaBootstrap.applicationContext() ?: MobileQQ.getContext())
        }
        runCatching { Class.forName("com.tencent.mobileqq.sign.QQSecuritySign", true, classLoader) }
        runCatching { Class.forName("com.tencent.mobileqq.fe.FEKit", true, classLoader) }
        ShamrockNative.onLibFeKitLoaded()
        XposedBridge.log("[MsfSignBootstrap] qua=${qua.take(40)} native=${ShamrockNative.initialized}")
    }
}
