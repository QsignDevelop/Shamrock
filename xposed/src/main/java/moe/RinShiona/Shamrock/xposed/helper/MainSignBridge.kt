package moe.RinShiona.Shamrock.xposed.helper

import moe.RinShiona.Shamrock.xposed.ipc.impl.SignPacketCollector
import moe.RinShiona.Shamrock.xposed.ipc.qsign.IQSign
import mqq.app.MobileQQ

/** 主进程直接签名（QSec 状态通常比 MSF 更完整）。 */
internal object MainSignBridge {

    fun sign(
        cmd: String,
        seq: Int,
        uin: String,
        buffer: ByteArray,
        requestQua: String? = null,
    ): IQSign? {
        val loader = MobileQQ.getContext().classLoader ?: return null
        QSecContextBridge.publishRelayQua(loader)
        QSecContextBridge.applySnapshot(loader, requestQua)
        val quaR = QuaBootstrap.forceApply(loader, requestQua)
        SignTrace.step("main.sign", "quaSrc=${quaR.source} quaLen=${quaR.qua.length}")
        if (!SignCore.isQSecReadyForSign(loader, quaR.qua, uin)) {
            SignTrace.step("main.sign.skip", "qsec not ready")
            return null
        }
        if (!SignCore.isSignAttemptQua(quaR.qua)) return null
        val effectiveUin = SignResultHelper.resolveUin(loader, uin)
        val (raw, callbacks) = SignPacketCollector.collect {
            SignCore.invoke(loader, cmd, buffer, seq, effectiveUin, requestQua)
        }
        if (raw == null) return null
        return SignResultHelper.toIQSign(raw, callbacks, loader)?.takeIf { SignResultHelper.isComplete(it) }
    }
}
