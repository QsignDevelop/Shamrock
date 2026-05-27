package moe.RinShiona.Shamrock.xposed.ipc.impl

import moe.RinShiona.Shamrock.xposed.ipc.qsign.IQSign
import moe.RinShiona.Shamrock.xposed.ipc.qsign.IQSigner
import mqq.app.MobileQQ
import java.lang.reflect.Method
import java.util.Base64
import moe.RinShiona.Shamrock.xposed.ipc.qsign.IQSignCallback

/**
 * MSF-process IQSigner implementation. Bridges to QQ's own
 * `com.tencent.mobileqq.fe.FEKit`, `Dandelion` and `QSec` classes via
 * reflection, with native fast-path via libshamrocknt.so.
 */
internal class QSignerImpl : IQSigner.Stub() {

    private val qqLoader: ClassLoader by lazy {
        MobileQQ.getContext().classLoader.also {
            SignPacketCollector.ensureHook(it)
        }
    }

    private val feKitClass: Class<*> by lazy {
        qqLoader.loadClass("com.tencent.mobileqq.fe.FEKit")
    }

    private val feKitInstance: Any by lazy {
        feKitClass.getMethod("getInstance").invoke(null)!!
    }

    private val signResultClass: Class<*> by lazy {
        qqLoader.loadClass("com.tencent.mobileqq.sign.QQSecuritySign\$SignResult")
    }

    private val getSignMethod: Method by lazy {
        runCatching {
            feKitClass.getMethod(
                "getSign",
                String::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                String::class.java
            )
        }.getOrElse {
            feKitClass.getMethod(
                "getSign",
                String::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType
            )
        }
    }

    override fun sign(cmd: String, seq: Int, uin: String, buffer: ByteArray): IQSign {
        val seqBytes = byteArrayOf(
            (seq shr 24 and 0xFF).toByte(),
            (seq shr 16 and 0xFF).toByte(),
            (seq shr 8 and 0xFF).toByte(),
            (seq and 0xFF).toByte()
        )
        val qua = readQSecConfigField("business_qua") ?: ""

        val (rawResult, callbacks) = SignPacketCollector.collect {
            val nativeResult = ShamrockNative.getSign(qua, cmd, buffer, seqBytes, uin)
            if (nativeResult != null) {
                return@collect nativeResult
            }

            try {
                if (getSignMethod.parameterTypes.size == 4) {
                    getSignMethod.invoke(feKitInstance, cmd, buffer, seq, uin)
                } else {
                    getSignMethod.invoke(feKitInstance, cmd, buffer, seq)
                }
            } catch (_: Throwable) {
                null
            }
        }

        if (rawResult == null) return IQSign()
        return extractIQSign(rawResult, callbacks)
    }

    private fun extractIQSign(rawResult: Any, callbacks: List<moe.RinShiona.Shamrock.xposed.ipc.qsign.IQSignCallback>): IQSign {
        return try {
            val cls = if (rawResult.javaClass == signResultClass) signResultClass
            else rawResult.javaClass
            val token = cls.getField("token").get(rawResult) as? ByteArray
            val sign = cls.getField("sign").get(rawResult) as? ByteArray
            val extra = cls.getField("extra").get(rawResult) as? ByteArray
            val tokenArr = token ?: ByteArray(0)
            val o3did = readQSecConfigField("business_o3did").orEmpty().ifEmpty {
                Base64.getEncoder().encodeToString(tokenArr)
            }
            IQSign(
                token = tokenArr,
                sign = sign ?: ByteArray(0),
                extra = extra ?: ByteArray(0),
                o3did = o3did,
                callbacks = callbacks
            )
        } catch (_: Throwable) {
            IQSign()
        }
    }

    private fun readQSecConfigField(name: String): String? {
        return try {
            val cls = qqLoader.loadClass("com.tencent.mobileqq.qsec.qsecurity.QSecConfig")
            cls.getField(name).get(null) as? String
        } catch (_: Throwable) {
            null
        }
    }

    override fun energy(module: String, salt: ByteArray): ByteArray? {
        val native = ShamrockNative.energy(module, salt)
        if (native != null) return native

        return try {
            val dandelionClass = qqLoader.loadClass(
                "com.tencent.mobileqq.qsec.qsecdandelionsdk.Dandelion"
            )
            val instance = dandelionClass.getMethod("getInstance").invoke(null)
            val fly = dandelionClass.getMethod(
                "fly", String::class.java, ByteArray::class.java
            )
            fly.invoke(instance, module, salt) as? ByteArray
        } catch (_: Throwable) {
            null
        }
    }

    override fun energyData(data: String, salt: ByteArray): ByteArray? {
        val native = ShamrockNative.energy(data, salt)
        if (native != null) return native

        return try {
            val dandelionClass = qqLoader.loadClass(
                "com.tencent.mobileqq.qsec.qsecdandelionsdk.Dandelion"
            )
            val instance = dandelionClass.getMethod("getInstance").invoke(null)
            val fly = dandelionClass.getMethod(
                "fly", String::class.java, ByteArray::class.java
            )
            fly.invoke(instance, data, salt) as? ByteArray
        } catch (_: Throwable) {
            null
        }
    }

    override fun submit(cmd: String, callbackId: Long, buffer: ByteArray): Boolean {
        return try {
            val mgrClass = qqLoader.loadClass("com.tencent.mobileqq.channel.ChannelManager")
            val mgr = mgrClass.getMethod("getInstance").invoke(null) ?: return false
            val methods = mgrClass.declaredMethods.filter { it.name == "onNativeReceive" }
            for (method in methods) {
                try {
                    method.isAccessible = true
                    when (method.parameterTypes.size) {
                        4 -> {
                            if (method.parameterTypes[3] == Long::class.javaPrimitiveType) {
                                method.invoke(mgr, cmd, buffer, true, callbackId)
                                return true
                            }
                        }
                        5 -> {
                            method.invoke(mgr, cmd, buffer, true, 0, callbackId)
                            return true
                        }
                    }
                } catch (_: Throwable) {
                    continue
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    override fun xwDebugId(uin: String, start: String, end: String): ByteArray? {
        return try {
            val qsecClass = qqLoader.loadClass("com.tencent.mobileqq.qsec.qsecurity.QSec")
            val instance = qsecClass.getMethod("getInstance").invoke(null)
            val getXwDebugID = qsecClass.getDeclaredMethod("getXwDebugID", String::class.java)
            getXwDebugID.isAccessible = true
            val encoded = "$uin\u0000${start}_$end"
            getXwDebugID.invoke(instance, encoded) as? ByteArray
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun getCmdWhiteList(): List<String> {
        return try {
            (feKitClass.getMethod("getCmdWhiteList").invoke(feKitInstance) as? List<String>)
                ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
