package moe.RinShiona.Shamrock.helper

import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import moe.RinShiona.Shamrock.qqinterface.servlet.GroupSvc
import moe.RinShiona.Shamrock.remote.service.api.BasePushServlet
import moe.RinShiona.Shamrock.remote.service.data.push.MemberRole
import moe.RinShiona.Shamrock.remote.service.data.push.MsgType

internal object MsgPushRouter {

    fun pushIncoming(
        servlet: BasePushServlet,
        record: MsgRecord,
        elements: List<MsgElement>,
        raw: String,
        msgHash: Int,
    ) {
        when (ChatTypeHelper.toOneBotMsgType(record.chatType)) {
            MsgType.Group -> servlet.pushGroupMsg(record, elements, raw, msgHash)
            MsgType.Private -> servlet.pushPrivateMsg(record, elements, raw, msgHash)
        }
    }

    fun pushSelfSent(
        servlet: BasePushServlet,
        record: MsgRecord,
        elements: List<MsgElement>,
        raw: String,
        msgHash: Int,
    ) {
        when (ChatTypeHelper.toOneBotMsgType(record.chatType)) {
            MsgType.Group -> servlet.pushSelfGroupSentMsg(record, elements, raw, msgHash)
            MsgType.Private -> servlet.pushSelfPrivateSentMsg(record, elements, raw, msgHash)
        }
    }

    fun memberRole(record: MsgRecord): MemberRole {
        if (!ChatTypeHelper.isGroupLike(record.chatType)) return MemberRole.Member
        val groupId = record.peerUin.toString()
        return when (record.senderUin) {
            GroupSvc.getOwner(groupId) -> MemberRole.Owner
            in GroupSvc.getAdminList(groupId) -> MemberRole.Admin
            else -> MemberRole.Member
        }
    }
}
