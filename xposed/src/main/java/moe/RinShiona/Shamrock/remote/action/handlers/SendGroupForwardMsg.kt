package moe.RinShiona.Shamrock.remote.action.handlers

import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.tools.asInt
import moe.RinShiona.Shamrock.qqinterface.servlet.MsgSvc
import moe.RinShiona.Shamrock.qqinterface.servlet.msg.LongMsgHelper
import moe.RinShiona.Shamrock.tools.EmptyJsonString

internal object SendGroupForwardMsg: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val groupId = session.getLong("group_id")
        val hashList = session.getArrayOrNull("seqs")?.map { it.asInt }
        if (hashList != null) {
            val msgs = hashList.mapNotNull { MsgSvc.getMsg(it).getOrNull() }
            if (msgs.isEmpty()) {
                return logic("seqs 对应的消息均无法获取", session.echo)
            }
            val resId = LongMsgHelper.uploadGroupMsg(groupId.toString(), msgs)
            return ok(mapOf("res_id" to resId), session.echo)
        }

        val messageIds = session.getArrayOrNull("message_id")?.map { it.asInt }
            ?: session.getArrayOrNull("messages")?.let { null }
        if (messageIds != null) {
            val msgs = messageIds.mapNotNull { MsgSvc.getMsg(it).getOrNull() }
            if (msgs.isEmpty()) {
                return logic("message_id 对应的消息均无法获取", session.echo)
            }
            val resId = LongMsgHelper.uploadGroupMsg(groupId.toString(), msgs)
            return ok(mapOf("res_id" to resId), session.echo)
        }

        return logic(
            "合并转发请提供 seqs 或 message_id 数组（Shamrock 内部消息 hash 列表）",
            session.echo
        )
    }

    operator fun invoke(msgs: List<MsgRecord>, echo: JsonElement = EmptyJsonString): String {
        if (msgs.isEmpty()) {
            return logic("消息为空", echo)
        }
        if (msgs.size > 100) {
            return logic("消息数量过多（最多 100 条）", echo)
        }
        return logic("请通过 HTTP action send_group_forward_msg 并传入 seqs/message_id", echo)
    }

    override fun path(): String = "send_group_forward_msg"
}
