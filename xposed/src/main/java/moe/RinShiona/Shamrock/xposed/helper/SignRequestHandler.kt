package moe.RinShiona.Shamrock.xposed.helper

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import moe.RinShiona.Shamrock.remote.api.APIResult
import moe.RinShiona.Shamrock.remote.api.QSignStats
import moe.RinShiona.Shamrock.remote.api.SignResponse
import moe.RinShiona.Shamrock.remote.api.SsoPacket
import moe.RinShiona.Shamrock.remote.entries.Status
import moe.RinShiona.Shamrock.tools.respond
import moe.RinShiona.Shamrock.tools.toHexString
import moe.RinShiona.Shamrock.xposed.ipc.ShamrockIpc
import moe.RinShiona.Shamrock.xposed.ipc.qsign.IQSign
import moe.RinShiona.Shamrock.xposed.ipc.qsign.IQSigner
import mqq.app.MobileQQ
import java.util.Locale

internal object SignRequestHandler {

    var signer: IQSigner? = null

    private fun isMsfServiceAlive(): Boolean =
        ShamrockSignRelay.isMsfHeartbeatFresh() || run {
            val am = MobileQQ.getContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getRunningServices(Int.MAX_VALUE)?.any {
                it.service.className == "com.tencent.mobileqq.msf.service.MsfService"
            } == true
        }

    private suspend fun initSigner(): Boolean {
        ShamrockIpc.get(ShamrockIpc.IPC_QSIGN)?.takeIf { it.isBinderAlive }?.let {
            signer = IQSigner.Stub.asInterface(it)
            it.linkToDeath({ signer = null }, 0)
            return true
        }
        if (ShamrockSignRelay.isMsfHeartbeatFresh()) return false
        val binder = IpcFetcher.fetch(ShamrockIpc.IPC_QSIGN, timeoutMs = 2_500) ?: return false
        signer = IQSigner.Stub.asInterface(binder)
        binder.linkToDeath({ signer = null }, 0)
        return true
    }

    private fun wakeMsfService() {
        runCatching {
            MobileQQ.getContext().startService(Intent().apply {
                component = ComponentName("com.tencent.mobileqq", "com.tencent.mobileqq.msf.service.MsfService")
                putExtra("to_SenderProcessName", "com.tencent.mobileqq")
            })
        }
    }

    suspend fun PipelineContext<Unit, ApplicationCall>.requestSign(
        cmd: String, uin: String, seq: Int, buffer: ByteArray, requestQua: String? = null,
    ) {
        XPrefConfigLoader.reloadQSignOnly()
        val loader = MobileQQ.getContext().classLoader
        awaitQuaReady(loader)

        suspend fun fail(msg: String, code: Int = -1) {
            QSignStats.recordSign(false)
            call.respond(APIResult<SignResponse>(code, msg, null))
        }

        if (!isMsfServiceAlive()) {
            wakeMsfService()
            delay(1500)
        }
        if (!isMsfServiceAlive()) {
            fail("MSF not started — 请完全退出并重启 QQ，确认 LSPosed 已对 QQ+MSF 生效", -2)
            return
        }

        QSecContextBridge.publishRelayQua(loader)
        QSecContextBridge.publishSnapshot(loader)

        // 1) 主进程直接签（QSec 就绪且非模板 qua 时）
        if (SignCore.isQSecReadyForSign(loader, SignCore.resolveQuaForSign(loader, requestQua).qua, uin)) {
            MainSignBridge.sign(cmd, seq, uin, buffer, requestQua)?.let {
                emitSign(it, "success (main)")
                return
            }
        }

        // 2) MSF 文件 relay（签名通常在 MSF 生成）
        val relay = ShamrockSignRelay.requestSign(cmd, uin, seq, buffer, requestQua, timeoutMs = 12_000)
        if (relay != null && SignResultHelper.isComplete(relay)) {
            emitSign(relay, "success (MSF relay)")
            return
        }

        if (signer == null || signer?.asBinder()?.isBinderAlive == false) initSigner()
        if (signer?.asBinder()?.isBinderAlive == true) {
            val sign = withTimeoutOrNull(4000) { signer!!.sign(cmd, seq, uin, buffer) }
            if (sign != null && SignResultHelper.isComplete(sign)) {
                emitSign(sign, "success (IPC)")
                return
            }
        }

        val hint = buildString {
            append(ShamrockSignRelay.lastRelayError.ifBlank { "relay empty" })
            val qua = SignCore.resolveQuaForSign(loader, requestQua)
            append(" | quaSrc=").append(qua.source).append(" quaLen=").append(qua.qua.length)
            append(" | msfHb=").append(ShamrockSignRelay.isMsfHeartbeatFresh())
            append(" | 请确认 QQ 已登录且 LSPosed 已对 QQ+MSF 生效")
        }
        fail("sign failed: $hint")
    }

    private suspend fun awaitQuaReady(classLoader: ClassLoader, maxWaitMs: Long = 8_000) {
        QuaBootstrap.forceApply(classLoader, null)
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            QSecContextBridge.publishRelayQua(classLoader)
            val qua = QuaBootstrap.forceApply(classLoader, null).qua
            if (SignCore.isSignAttemptQua(qua)) return
            delay(400)
        }
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.emitSign(sign: IQSign, msg: String) {
        QSignStats.recordSign(true)
        call.respond(
            APIResult(
                0, msg,
                SignResponse(
                    token = sign.token.toHexString().uppercase(Locale.ROOT),
                    extra = sign.extra.toHexString().uppercase(Locale.ROOT),
                    sign = sign.sign.toHexString().uppercase(Locale.ROOT),
                    o3did = sign.o3did.uppercase(Locale.ROOT),
                    requestCallback = sign.callbacks.map {
                        SsoPacket(it.cmd, it.body.uppercase(Locale.ROOT), it.callbackId)
                    },
                ),
            ),
        )
    }
}
