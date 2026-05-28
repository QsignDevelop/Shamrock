package moe.RinShiona.Shamrock.remote.action.handlers

import android.util.Base64
import com.tencent.mobileqq.qroute.QRoute
import com.tencent.mobileqq.qrscan.api.IQRCodeApi
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler

internal object ScanQRCode: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val api = QRoute.api(IQRCodeApi::class.java)
        val picBytes = Base64.decode(session.getString("pic"), Base64.DEFAULT)
        if (picBytes.isEmpty()) {
            return logic("pic 解码失败", session.echo)
        }

        kotlin.runCatching {
            api.init(1, "", "")
        }

        val scanCode = api.scanImage(picBytes, 0, picBytes.size)
        val type = StringBuilder()
        val content = StringBuilder()
        val resultCode = api.getOneResult(type, content)

        return ok(
            mapOf(
                "scan_code" to scanCode,
                "result_code" to resultCode,
                "type" to type.toString(),
                "content" to content.toString(),
                "version" to api.version
            ),
            session.echo
        )
    }

    override val requiredParams: Array<String> = arrayOf("pic")

    override val alias: Array<String> = arrayOf("sanc_qrcode")

    override fun path(): String = "scan_qrcode"
}
