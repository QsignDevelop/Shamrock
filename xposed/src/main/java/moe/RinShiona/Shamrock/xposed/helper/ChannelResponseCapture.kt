package moe.RinShiona.Shamrock.xposed.helper

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File

/**
 * 真机 MSF 收到 O3 ECDH 回包时落盘到 shamrock_ipc，供 `tools/pull_adb_assets.ps1` 拉到 txlib adb_capture。
 */
internal object ChannelResponseCapture {

    private val dir get() = File("/data/data/com.tencent.mobileqq/files/shamrock_ipc").also { it.mkdirs() }

    @Volatile private var installed = false

    fun ensureHook(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        runCatching {
            val proxy = classLoader.loadClass("com.tencent.mobileqq.channel.ChannelProxy")
            fun saveEstablishReq(cmd: String?, data: ByteArray?) {
                if (cmd == null || data == null) return
                if (!cmd.contains("SsoSecureA2Establish", ignoreCase = true) || data.size < 64) return
                File(dir, "sso_secure_a2_establish_req.bin").writeBytes(data)
                XposedBridge.log("[ChannelResponseCapture] saved ${data.size}B -> sso_secure_a2_establish_req.bin")
            }
            listOf("sendMessageInner", "sendMessage").forEach { methodName ->
                XposedHelpers.findAndHookMethod(
                    proxy,
                    methodName,
                    String::class.java,
                    ByteArray::class.java,
                    Long::class.javaPrimitiveType,
                    object : de.robv.android.xposed.XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            saveEstablishReq(
                                param.args.getOrNull(0) as? String,
                                param.args.getOrNull(1) as? ByteArray,
                            )
                        }
                    },
                )
            }
            val mgr = classLoader.loadClass("com.tencent.mobileqq.channel.ChannelManager")
            mgr.declaredMethods.filter { it.name == "onNativeReceive" }.forEach { method ->
                XposedHelpers.findAndHookMethod(mgr, method.name, *method.parameterTypes, object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cmd = param.args.getOrNull(0) as? String ?: return
                        val data = param.args.getOrNull(1) as? ByteArray ?: return
                        if (data.size < 32) return
                        when {
                            cmd.contains("SsoSecureA2Establish", ignoreCase = true) -> {
                                File(dir, "sso_secure_a2_establish_resp.bin").writeBytes(data)
                                XposedBridge.log("[ChannelResponseCapture] saved ${data.size}B -> sso_secure_a2_establish_resp.bin cmd=$cmd")
                            }
                            cmd.contains("SsoSecureA2Access", ignoreCase = true) -> {
                                File(dir, "sso_secure_a2_access_resp.bin").writeBytes(data)
                                XposedBridge.log("[ChannelResponseCapture] saved ${data.size}B -> sso_secure_a2_access_resp.bin cmd=$cmd")
                            }
                            cmd.contains("SsoSecureAccess", ignoreCase = true) &&
                                !cmd.contains("A2", ignoreCase = true) -> {
                                File(dir, "sso_secure_access_resp.bin").writeBytes(data)
                            }
                        }
                    }
                })
            }
            XposedBridge.log("[ChannelResponseCapture] ChannelManager.onNativeReceive hooks installed")
        }.onFailure {
            XposedBridge.log("[ChannelResponseCapture] install failed: ${it.message}")
        }
    }
}
