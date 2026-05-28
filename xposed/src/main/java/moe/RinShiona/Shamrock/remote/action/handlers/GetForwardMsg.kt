package moe.RinShiona.Shamrock.remote.action.handlers

import com.tencent.qqnt.kernel.nativeinterface.MsgConstant
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import moe.RinShiona.Shamrock.helper.MessageHelper
import moe.RinShiona.Shamrock.qqinterface.servlet.MsgSvc
import moe.RinShiona.Shamrock.qqinterface.servlet.msg.MsgConvert
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.xposed.helper.NTServiceFetcher
import kotlin.coroutines.resume

internal object GetForwardMsg: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val msgHash = session.getIntOrNull("id")
            ?: session.getIntOrNull("message_id")
            ?: return noParam("id/message_id", session.echo)

        val root = MsgSvc.getMsg(msgHash).getOrElse {
            return logic("无法获取合并转发根消息: ${it.message}", session.echo)
        }

        val kernelService = NTServiceFetcher.kernelService
        val msgService = kernelService.wrapperSession.msgService
        val contact = MessageHelper.generateContact(root.chatType, root.peerUin.toString())

        val records = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { continuation ->
                msgService.getMultiMsg(contact, root.msgId, root.msgSeq) { code, why, list ->
                    if (code == 0 && list != null && list.isNotEmpty()) {
                        continuation.resume(list)
                    } else {
                        continuation.resume(null)
                    }
                }
            }
        }

        if (records.isNullOrEmpty()) {
            return logic("获取合并转发内容失败", session.echo)
        }

        val nodes = records.map { record ->
            mapOf(
                "user_id" to record.senderUin,
                "nickname" to record.sendNickName,
                "time" to record.msgTime,
                "message" to MsgConvert.convertMsgRecordToMsgSegment(record),
                "message_type" to if (record.chatType == MsgConstant.KCHATTYPEGROUP) "group" else "private"
            )
        }
        return ok(mapOf("messages" to nodes), session.echo)
    }

    override val requiredParams: Array<String> = arrayOf("id")

    override val alias: Array<String> = arrayOf("get_forward_message")

    override fun path(): String = "get_forward_msg"
}
