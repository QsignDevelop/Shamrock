package moe.RinShiona.Shamrock.xposed.ipc.impl

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.RinShiona.Shamrock.xposed.ipc.qsign.IQSignCallback
import moe.RinShiona.Shamrock.tools.toHexString

/**
 * Collects SSO callback packets emitted during a sign operation.
 *
 * Mirrors unidbg QSecJni's ChannelProxy.sendMessage hook — when QQ's native
 * sign routine needs follow-up SSO requests, they appear here as
 * requestCallback entries in the /sign response.
 */
internal object SignPacketCollector {

    private val active = ThreadLocal.withInitial { false }
    private val packets = ThreadLocal.withInitial { mutableListOf<IQSignCallback>() }
    private var hookInstalled = false

    fun ensureHook(classLoader: ClassLoader) {
        if (hookInstalled) return
        hookInstalled = true

        runCatching {
            val proxyClass = classLoader.loadClass("com.tencent.mobileqq.channel.ChannelProxy")
            listOf("sendMessage", "sendMessageInner").forEach { methodName ->
                XposedBridge.hookAllMethods(proxyClass, methodName, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!active.get()) return
                        val cmd = param.args.getOrNull(0) as? String ?: return
                        val data = param.args.getOrNull(1) as? ByteArray ?: return
                        val callbackId = when (val idArg = param.args.getOrNull(2)) {
                            is Long -> idArg
                            is Int -> idArg.toLong()
                            else -> -1L
                        }
                        if (callbackId == -1L) return
                        packets.get().add(
                            IQSignCallback(
                                cmd = cmd,
                                body = data.toHexString(),
                                callbackId = callbackId
                            )
                        )
                    }
                })
            }
            XposedBridge.log("[SignPacketCollector] ChannelProxy hooks installed")
        }.onFailure {
            XposedBridge.log("[SignPacketCollector] hook failed: ${it.message}")
        }
    }

    inline fun <T> collect(block: () -> T): Pair<T, List<IQSignCallback>> {
        active.set(true)
        packets.get().clear()
        return try {
            block() to packets.get().toList()
        } finally {
            active.set(false)
            packets.get().clear()
        }
    }
}
