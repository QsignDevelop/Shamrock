package moe.RinShiona.Shamrock.xposed.helper

import de.robv.android.xposed.XposedBridge
import moe.RinShiona.Shamrock.xposed.ipc.qsign.IQSign
import moe.RinShiona.Shamrock.xposed.ipc.qsign.IQSignCallback
import mqq.app.MobileQQ
import java.util.Base64

internal object SignResultHelper {

    private const val SIGN_RESULT = "com.tencent.mobileqq.sign.QQSecuritySign\$SignResult"

    @Volatile var lastFeKitError: String = ""
        private set

    @Volatile var lastSecuritySignError: String = ""
        private set

    data class Fields(val token: ByteArray, val sign: ByteArray, val extra: ByteArray)

    fun intSeqToBytes(seq: Int): ByteArray = byteArrayOf(
        (seq shr 24 and 0xFF).toByte(),
        (seq shr 16 and 0xFF).toByte(),
        (seq shr 8 and 0xFF).toByte(),
        (seq and 0xFF).toByte(),
    )

    fun resolveUin(classLoader: ClassLoader, requestUin: String): String {
        QSecContextBridge.readField(classLoader, "business_uin")?.let { return it }
        if (requestUin.isNotBlank() && requestUin != "0") return requestUin
        runCatching {
            val mq = MobileQQ.getMobileQQ()
            val runtime = mq.peekAppRuntime() ?: mq.waitAppRuntime(null)
            val uin = runtime.currentAccountUin
            if (uin.isNotBlank() && uin != "0") return uin
        }
        return requestUin
    }

    fun invokeFeKit(classLoader: ClassLoader, cmd: String, buffer: ByteArray, seq: Int, uin: String): Any? {
        lastFeKitError = ""
        if (!SignCore.isQSecReadyForSign(classLoader, QSecContextBridge.resolveQua(classLoader, null), uin)) {
            lastFeKitError = "QSec not ready"
            return null
        }
        return runCatching {
            val feKitClass = classLoader.loadClass("com.tencent.mobileqq.fe.FEKit")
            val inst = feKitClass.getMethod("getInstance").invoke(null)
                ?: run { lastFeKitError = "FEKit.getInstance null"; return@runCatching null }
            val method = runCatching {
                feKitClass.getMethod("getSign", String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType, String::class.java)
            }.getOrElse {
                feKitClass.getMethod("getSign", String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType)
            }
            if (method.parameterTypes.size == 4) method.invoke(inst, cmd, buffer, seq, uin)
            else method.invoke(inst, cmd, buffer, seq)
        }.getOrElse {
            lastFeKitError = it.javaClass.simpleName + ": " + (it.message ?: "")
            null
        }
    }

    fun invokeSecuritySign(classLoader: ClassLoader, cmd: String, buffer: ByteArray, seqBytes: ByteArray, uin: String): Any? {
        lastSecuritySignError = ""
        val qua = QSecContextBridge.resolveQua(classLoader, null)
        if (!SignCore.isQSecReadyForSign(classLoader, qua, uin)) {
            lastSecuritySignError = "QSec not ready (qua=${qua.take(24)} uin=$uin)"
            return null
        }
        return runCatching {
            val secClass = classLoader.loadClass("com.tencent.mobileqq.sign.QQSecuritySign")
            val qsecClass = classLoader.loadClass("com.tencent.mobileqq.qsec.qsecurity.QSec")
            val inst = secClass.getMethod("getInstance").invoke(null)
                ?: run { lastSecuritySignError = "QQSecuritySign.getInstance null"; return@runCatching null }
            val qsec = qsecClass.getMethod("getInstance").invoke(null)
                ?: run { lastSecuritySignError = "QSec.getInstance null"; return@runCatching null }
            if (qua.isNotBlank()) {
                runCatching {
                    val cfg = classLoader.loadClass("com.tencent.mobileqq.qsec.qsecurity.QSecConfig")
                    cfg.getField("business_qua").set(null, qua)
                }
            }
            // 9.2.90: getSign(QSec, cmd, buffer, seqBytes, uin)
            runCatching {
                secClass.getMethod(
                    "getSign",
                    qsecClass,
                    String::class.java,
                    ByteArray::class.java,
                    ByteArray::class.java,
                    String::class.java,
                ).invoke(inst, qsec, cmd, buffer, seqBytes, uin)
            }.getOrElse {
                // 旧版: getSign(QSec, qua, cmd, buffer, seqBytes, uin)
                secClass.getMethod(
                    "getSign",
                    qsecClass,
                    String::class.java,
                    String::class.java,
                    ByteArray::class.java,
                    ByteArray::class.java,
                    String::class.java,
                ).invoke(inst, qsec, qua, cmd, buffer, seqBytes, uin)
            }
        }.getOrElse {
            lastSecuritySignError = it.javaClass.simpleName + ": " + (it.message ?: "")
            null
        }
    }

    fun extractFields(rawResult: Any): Fields? = runCatching {
        val cls = rawResult.javaClass
        Fields(
            cls.getField("token").get(rawResult) as? ByteArray ?: ByteArray(0),
            cls.getField("sign").get(rawResult) as? ByteArray ?: ByteArray(0),
            cls.getField("extra").get(rawResult) as? ByteArray ?: ByteArray(0),
        )
    }.getOrNull()

    fun isComplete(rawResult: Any, classLoader: ClassLoader): Boolean {
        val f = extractFields(rawResult) ?: return false
        return f.token.isNotEmpty() && f.sign.isNotEmpty()
    }

    fun isComplete(iqSign: IQSign): Boolean =
        iqSign.token.isNotEmpty() && iqSign.sign.isNotEmpty()

    fun toIQSign(rawResult: Any, callbacks: List<IQSignCallback>, classLoader: ClassLoader): IQSign? {
        val fields = extractFields(rawResult) ?: return null
        val o3did = readQSecConfigField(classLoader, "business_o3did").orEmpty().ifEmpty {
            if (fields.token.isNotEmpty()) Base64.getEncoder().encodeToString(fields.token) else ""
        }
        return IQSign(fields.token, fields.sign, fields.extra, o3did, callbacks)
    }

    fun readQSecConfigField(classLoader: ClassLoader, name: String): String? =
        runCatching {
            classLoader.loadClass("com.tencent.mobileqq.qsec.qsecurity.QSecConfig")
                .getField(name).get(null) as? String
        }.getOrNull()
}
