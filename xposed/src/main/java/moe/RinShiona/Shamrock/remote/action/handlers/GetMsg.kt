package moe.RinShiona.Shamrock.remote.action.handlers

import com.tencent.qqnt.kernel.nativeinterface.MsgConstant
import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.helper.MessageHelper
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.remote.service.data.MessageDetail
import moe.RinShiona.Shamrock.remote.service.data.MessageSender
import moe.RinShiona.Shamrock.qqinterface.servlet.MsgSvc
import moe.RinShiona.Shamrock.qqinterface.servlet.msg.MsgConvert
import moe.RinShiona.Shamrock.tools.EmptyJsonString

internal object GetMsg: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val hashCode = session.getIntOrNull("message_id")
            ?: session.getInt("msg_id")
        return invoke(hashCode, session.echo)
    }

    suspend operator fun invoke(msgHash: Int, echo: JsonElement = EmptyJsonString): String {
        val msg = MsgSvc.getMsg(msgHash).onFailure {
            return logic("Obtain msg failed, please check your msg_id.", echo)
        }.getOrThrow()
        val seq = msg.clientSeq.toInt()
        return ok(MessageDetail(
            time = msg.msgTime.toInt(),
            msgType = MessageHelper.obtainDetailTypeByMsgType(msg.chatType),
            msgId = msgHash,
            realId = seq,
            sender = MessageSender(
                msg.senderUin, msg.sendNickName, "unknown", 0, msg.senderUid
            ),
            message = MsgConvert.convertMsgRecordToMsgSegment(msg),
            peerId = msg.peerUin,
            groupId = if (msg.chatType == MsgConstant.KCHATTYPEGROUP) msg.peerUin else 0,
            targetId = if (msg.chatType != MsgConstant.KCHATTYPEGROUP) msg.peerUin else 0
        ), echo)
    }

    override val requiredParams: Array<String> = arrayOf("message_id")

    override val alias: Array<String> = arrayOf("get_message")

    override fun path(): String = "get_msg"
}