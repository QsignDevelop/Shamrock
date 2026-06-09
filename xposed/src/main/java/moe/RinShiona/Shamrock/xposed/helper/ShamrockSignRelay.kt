package moe.RinShiona.Shamrock.xposed.helper

import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.delay
import moe.RinShiona.Shamrock.xposed.ipc.impl.SignPacketCollector
import moe.RinShiona.Shamrock.xposed.ipc.qsign.IQSign
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal object ShamrockSignRelay {

    private val relayDir get() = File("/data/data/com.tencent.mobileqq/files/shamrock_ipc").also { it.mkdirs() }
    private val reqFile get() = File(relayDir, "sign_req.json")
    private val rspFile get() = File(relayDir, "sign_rsp.json")
    private val pendingFlag get() = File(relayDir, "sign_pending")
    private val heartbeatFile get() = File(relayDir, "msf_alive")

    private val msfLoopStarted = AtomicBoolean(false)
    private val reqCounter = AtomicLong(0)

    @Volatile var lastRelayError: String = ""
        private set

    fun touchMsfHeartbeat() {
        runCatching { heartbeatFile.writeText(System.currentTimeMillis().toString()) }
    }

    fun isMsfHeartbeatFresh(maxAgeMs: Long = 15_000): Boolean {
        val ts = runCatching { heartbeatFile.readText().trim().toLong() }.getOrNull() ?: return false
        return System.currentTimeMillis() - ts < maxAgeMs
    }

    fun startMsfLoop(classLoader: ClassLoader) {
        if (!msfLoopStarted.compareAndSet(false, true)) return
        SignPacketCollector.ensureHook(classLoader)
        ChannelResponseCapture.ensureHook(classLoader)
        touchMsfHeartbeat()
        Thread({
            XposedBridge.log("Shamrock: SignRelay MSF loop started")
            while (true) {
                try {
                    touchMsfHeartbeat()
                    if (pendingFlag.exists()) handlePendingRequest(classLoader)
                    Thread.sleep(40)
                } catch (e: Throwable) {
                    Thread.sleep(500)
                }
            }
        }, "Shamrock-SignRelay-MSF").apply { isDaemon = true; start() }
    }

    private fun handlePendingRequest(classLoader: ClassLoader) {
        val obj = runCatching { JSONObject(reqFile.readText()) }.getOrNull() ?: return
        val id = obj.optLong("id", -1)
        val cmd = obj.optString("cmd")
        val uin = obj.optString("uin")
        val seq = obj.optInt("seq")
        val buffer = runCatching { Base64.getDecoder().decode(obj.optString("buffer")) }.getOrNull() ?: ByteArray(0)
        val requestQua = obj.optString("qua").takeIf { it.isNotBlank() }
        MsfSignBootstrap.ensureReady(classLoader, requestQua)
        val quaR = SignCore.resolveQuaForSign(classLoader, requestQua)
        val effectiveUin = SignResultHelper.resolveUin(classLoader, uin)
        val (rawResult, callbacks) = SignPacketCollector.collect {
            SignCore.invoke(classLoader, cmd, buffer, seq, effectiveUin, requestQua)
        }
        val rsp = JSONObject().put("id", id)
        if (rawResult == null) {
            rsp.put("ok", false).put(
                "err",
                "invokeSign null quaSrc=${quaR.source} quaLen=${quaR.qua.length} " +
                    "sec=${SignResultHelper.lastSecuritySignError} fekit=${SignResultHelper.lastFeKitError}",
            )
        } else {
            val iq = SignResultHelper.toIQSign(rawResult, callbacks, classLoader)
            if (iq != null && SignResultHelper.isComplete(iq)) {
                rsp.put("ok", true)
                    .put("token", Base64.getEncoder().encodeToString(iq.token))
                    .put("sign", Base64.getEncoder().encodeToString(iq.sign))
                    .put("extra", Base64.getEncoder().encodeToString(iq.extra))
                    .put("o3did", iq.o3did)
            } else {
                rsp.put("ok", false).put("err", "partial quaSrc=${quaR.source} quaLen=${quaR.qua.length}")
            }
        }
        lastRelayError = rsp.optString("err", "")
        rspFile.writeText(rsp.toString())
        pendingFlag.delete()
    }

    suspend fun requestSign(
        cmd: String, uin: String, seq: Int, buffer: ByteArray,
        qua: String? = null, timeoutMs: Long = 6_000,
    ): IQSign? {
        val id = reqCounter.incrementAndGet()
        reqFile.writeText(JSONObject().apply {
            put("id", id); put("cmd", cmd); put("uin", uin); put("seq", seq)
            put("buffer", Base64.getEncoder().encodeToString(buffer))
            qua?.takeIf { it.isNotBlank() }?.let { put("qua", it) }
        }.toString())
        rspFile.delete()
        pendingFlag.createNewFile()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!pendingFlag.exists() && rspFile.exists()) {
                val obj = runCatching { JSONObject(rspFile.readText()) }.getOrNull() ?: break
                if (obj.optLong("id") != id || !obj.optBoolean("ok")) {
                    lastRelayError = obj.optString("err", "")
                    return null
                }
                return runCatching {
                    IQSign(
                        Base64.getDecoder().decode(obj.getString("token")),
                        Base64.getDecoder().decode(obj.getString("sign")),
                        Base64.getDecoder().decode(obj.optString("extra", "")),
                        obj.optString("o3did", ""),
                        emptyList(),
                    )
                }.getOrNull()
            }
            delay(25)
        }
        pendingFlag.delete()
        return null
    }
}
