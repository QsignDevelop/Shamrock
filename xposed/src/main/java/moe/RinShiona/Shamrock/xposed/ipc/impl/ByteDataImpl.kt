package moe.RinShiona.Shamrock.xposed.ipc.impl

import moe.RinShiona.Shamrock.xposed.ipc.bytedata.IByteData
import moe.RinShiona.Shamrock.xposed.ipc.bytedata.IByteDataSign
import mqq.app.MobileQQ

/**
 * MSF-process IByteData implementation. Bridges to QQ's own
 * `com.tencent.mobileqq.qsec.qsecprotocol.ByteData` via reflection.
 */
internal class ByteDataImpl : IByteData.Stub() {

    private val qqLoader: ClassLoader by lazy {
        MobileQQ.getContext().classLoader
    }

    private val byteDataClass: Class<*> by lazy {
        qqLoader.loadClass("com.tencent.mobileqq.qsec.qsecprotocol.ByteData")
    }

    private val byteDataInstance: Any by lazy {
        byteDataClass.getMethod("getInstance").invoke(null)!!
    }

    override fun sign(uin: String, data: String, salt: ByteArray): IByteDataSign {
        return try {
            val sign = byteDataClass.getMethod(
                "getSign",
                String::class.java, String::class.java, ByteArray::class.java
            ).invoke(byteDataInstance, uin, data, salt) as? ByteArray
            IByteDataSign(sign = sign)
        } catch (e: Throwable) {
            IByteDataSign(sign = null)
        }
    }
}
