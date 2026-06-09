package moe.RinShiona.Shamrock.xposed.helper

import moe.RinShiona.Shamrock.xposed.AntiDetectionConfig
import moe.RinShiona.Shamrock.xposed.ipc.impl.ShamrockNative
import mqq.app.MobileQQ

internal object SignCore {

    data class QuaResolve(val qua: String, val source: String)

    /** 真机 QQ 的 QUA 就是 V1_AND_SQ_x.x.x_xxxx_YYB_D 格式，不能按 README 模板拒签。 */
    fun isSignAttemptQua(qua: String): Boolean =
        qua.length >= 10 && qua.startsWith("V1_AND_SQ_")

    fun isUsableQua(qua: String): Boolean = isSignAttemptQua(qua)

    /** 仅用于拒绝明显不是当前 QQ 版本的 HTTP 占位 qua（版本号对不上时）。 */
    fun isStaleHttpQua(qua: String, classLoader: ClassLoader): Boolean {
        if (!isSignAttemptQua(qua)) return true
        val installed = QuaBootstrap.buildFromInstalledPackage() ?: return false
        if (qua == installed) return false
        val reqVer = Regex("V1_AND_SQ_([0-9.]+)_").find(qua)?.groupValues?.getOrNull(1)
        val pkgVer = Regex("V1_AND_SQ_([0-9.]+)_").find(installed)?.groupValues?.getOrNull(1)
        return reqVer != null && pkgVer != null && reqVer != pkgVer
    }

    /** QSec / getSign 可安全调用：有效 QUA + 已登录 uin + QSec 实例。 */
    fun isQSecReadyForSign(classLoader: ClassLoader, qua: String, requestUin: String = ""): Boolean {
        if (!isUsableQua(qua)) return false
        val uin = SignResultHelper.resolveUin(classLoader, requestUin)
        if (uin.isBlank() || uin == "0") return false
        return kotlin.runCatching {
            classLoader.loadClass("com.tencent.mobileqq.qsec.qsecurity.QSec")
                .getMethod("getInstance").invoke(null) != null
        }.getOrDefault(false)
    }

    fun resolveFallbackQua(classLoader: ClassLoader): String {
        QSecContextBridge.readRelayQua()?.takeIf { isSignAttemptQua(it) }?.let { return it }
        QSecContextBridge.resolveQuaFromAppPublic(classLoader)?.takeIf { isSignAttemptQua(it) }?.let { return it }
        QuaBootstrap.buildFromInstalledPackage()?.let { return it }
        return ""
    }

    fun resolveQuaForSign(classLoader: ClassLoader, requestQua: String? = null): QuaResolve {
        requestQua?.takeIf { isSignAttemptQua(it) && !isStaleHttpQua(it, classLoader) }
            ?.let { return QuaResolve(it, "http_request") }
        QSecContextBridge.readRelayQua()?.takeIf { isSignAttemptQua(it) }
            ?.let { return QuaResolve(it, "relay_qua") }
        QSecContextBridge.readField(classLoader, "business_qua")?.takeIf { isSignAttemptQua(it) }
            ?.let { return QuaResolve(it, "snapshot") }
        QSecContextBridge.resolveQuaFromAppPublic(classLoader)?.takeIf { isSignAttemptQua(it) }
            ?.let { return QuaResolve(it, "getQua") }
        SignResultHelper.readQSecConfigField(classLoader, "business_qua")?.takeIf { isSignAttemptQua(it) }
            ?.let { return QuaResolve(it, "qsec_config") }
        resolveFallbackQua(classLoader).takeIf { isSignAttemptQua(it) }?.let {
            return QuaResolve(it, "fallback_builtin")
        }
        return QuaResolve("", "empty")
    }

    fun resolveQuaString(classLoader: ClassLoader, requestQua: String? = null): String =
        resolveQuaForSign(classLoader, requestQua).qua

    fun invoke(
        classLoader: ClassLoader,
        cmd: String,
        buffer: ByteArray,
        seq: Int,
        uin: String,
        requestQua: String? = null,
    ): Any? {
        val effectiveUin = SignResultHelper.resolveUin(classLoader, uin)
        val quaR = QuaBootstrap.forceApply(classLoader, requestQua)
        val qua = quaR.qua
        val seqBytes = SignResultHelper.intSeqToBytes(seq)

        SignTrace.step("sign.prepare", "cmd=$cmd uin=$effectiveUin quaSrc=${quaR.source} quaLen=${qua.length}")

        if (!isQSecReadyForSign(classLoader, qua, effectiveUin)) {
            SignTrace.step("sign.skip", "qsec not ready qua=${qua.take(24)}")
            return null
        }

        warmFeKit(classLoader, qua)

        if (!AntiDetectionConfig.connectivitySafeMode) {
            if (!ShamrockNative.signNativeReady) {
                ShamrockNative.bootstrap(QuaBootstrap.applicationContext() ?: MobileQQ.getContext())
            }
            if (ShamrockNative.signNativeReady && qua.isNotBlank()) {
                SignTrace.step("native.getSign.try", "quaLen=${qua.length}")
                val nativeResult = ShamrockNative.getSign(qua, cmd, buffer, seqBytes, effectiveUin)
                if (nativeResult != null && SignResultHelper.isComplete(nativeResult, classLoader)) return nativeResult
                SignTrace.step("native.getSign.miss", SignResultHelper.lastSecuritySignError)
            }
        }

        SignTrace.step("securitySign.try", "quaLen=${qua.length}")
        val secResult = SignResultHelper.invokeSecuritySign(classLoader, cmd, buffer, seqBytes, effectiveUin)
        if (secResult != null && SignResultHelper.isComplete(secResult, classLoader)) return secResult
        SignTrace.step("securitySign.fail", SignResultHelper.lastSecuritySignError.ifBlank { "null" })

        SignTrace.step("fekit.getSign.try", "")
        val feResult = SignResultHelper.invokeFeKit(classLoader, cmd, buffer, seq, effectiveUin)
        if (feResult != null && SignResultHelper.isComplete(feResult, classLoader)) return feResult
        SignTrace.step("fekit.getSign.fail", SignResultHelper.lastFeKitError.ifBlank { "empty" })
        return null
    }

    private fun warmFeKit(classLoader: ClassLoader, qua: String) {
        if (!isSignAttemptQua(qua)) return
        if (qua.isNotBlank()) QSecContextBridge.initFeKit(classLoader, qua)
        runCatching {
            val sec = classLoader.loadClass("com.tencent.mobileqq.sign.QQSecuritySign")
            val inst = sec.getMethod("getInstance").invoke(null) ?: return@runCatching
            runCatching { sec.getMethod("init", String::class.java).invoke(inst, qua) }
            runCatching { sec.getMethod("requestTokenMain", Boolean::class.javaPrimitiveType).invoke(inst, true) }
            sec.getMethod("requestToken").invoke(inst)
        }
        repeat(3) { attempt ->
            runCatching {
                val feKit = classLoader.loadClass("com.tencent.mobileqq.fe.FEKit")
                feKit.getMethod("requestToken").invoke(feKit.getMethod("getInstance").invoke(null))
            }
            if (attempt < 2) Thread.sleep(400)
        }
    }
}
