package moe.RinShiona.Shamrock.xposed.ipc.impl

import moe.RinShiona.Shamrock.xposed.helper.SignCore
import moe.RinShiona.Shamrock.xposed.helper.SignResultHelper
import moe.RinShiona.Shamrock.xposed.ipc.qsign.IQSign
import moe.RinShiona.Shamrock.xposed.ipc.qsign.IQSigner
import mqq.app.MobileQQ

internal class QSignerImpl : IQSigner.Stub() {

    private val qqLoader: ClassLoader by lazy {
        MobileQQ.getContext().classLoader.also { SignPacketCollector.ensureHook(it) }
    }

    override fun sign(cmd: String, seq: Int, uin: String, buffer: ByteArray): IQSign {
        val (rawResult, callbacks) = SignPacketCollector.collect {
            SignCore.invoke(qqLoader, cmd, buffer, seq, uin) ?: return@collect null
        }
        if (rawResult == null) return IQSign()
        return SignResultHelper.toIQSign(rawResult, callbacks, qqLoader) ?: IQSign()
    }

    override fun energy(module: String, salt: ByteArray): ByteArray? {
        ShamrockNative.energy(module, salt)?.let { return it }
        return runCatching {
            val cls = qqLoader.loadClass("com.tencent.mobileqq.qsec.qsecdandelionsdk.Dandelion")
            val inst = cls.getMethod("getInstance").invoke(null)
            cls.getMethod("fly", String::class.java, ByteArray::class.java).invoke(inst, module, salt) as? ByteArray
        }.getOrNull()
    }

    override fun energyData(data: String, salt: ByteArray): ByteArray? {
        ShamrockNative.energy(data, salt)?.let { return it }
        return runCatching {
            val cls = qqLoader.loadClass("com.tencent.mobileqq.qsec.qsecdandelionsdk.Dandelion")
            val inst = cls.getMethod("getInstance").invoke(null)
            cls.getMethod("fly", String::class.java, ByteArray::class.java).invoke(inst, data, salt) as? ByteArray
        }.getOrNull()
    }

    override fun submit(cmd: String, callbackId: Long, buffer: ByteArray): Boolean {
        return runCatching {
            val mgrClass = qqLoader.loadClass("com.tencent.mobileqq.channel.ChannelManager")
            val mgr = mgrClass.getMethod("getInstance").invoke(null) ?: return false
            for (method in mgrClass.declaredMethods.filter { it.name == "onNativeReceive" }) {
                method.isAccessible = true
                when (method.parameterTypes.size) {
                    4 -> if (method.parameterTypes[3] == Long::class.javaPrimitiveType) {
                        method.invoke(mgr, cmd, buffer, true, callbackId); return true
                    }
                    5 -> { method.invoke(mgr, cmd, buffer, true, 0, callbackId); return true }
                }
            }
            false
        }.getOrDefault(false)
    }

    override fun xwDebugId(uin: String, start: String, end: String): ByteArray? {
        return runCatching {
            val qsecClass = qqLoader.loadClass("com.tencent.mobileqq.qsec.qsecurity.QSec")
            val instance = qsecClass.getMethod("getInstance").invoke(null)
            val m = qsecClass.getDeclaredMethod("getXwDebugID", String::class.java).apply { isAccessible = true }
            m.invoke(instance, "$uin\u0000${start}_$end") as? ByteArray
        }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    override fun getCmdWhiteList(): List<String> {
        return runCatching {
            val feKit = qqLoader.loadClass("com.tencent.mobileqq.fe.FEKit")
            feKit.getMethod("getCmdWhiteList").invoke(feKit.getMethod("getInstance").invoke(null)) as? List<String>
        }.getOrNull() ?: emptyList()
    }
}
