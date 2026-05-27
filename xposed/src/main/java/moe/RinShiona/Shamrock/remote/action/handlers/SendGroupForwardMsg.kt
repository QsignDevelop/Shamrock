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
            val msgs = hashList.map {
                MsgSvc.getMsg(it).getOrNull()
            }
            val resId = LongMsgHelper.uploadGroupMsg(groupId.toString(), msgs.filterNotNull())
            return ok(mapOf("res_id" to resId), session.echo)
        }


        return "xxx"
    }

    operator fun invoke(msgs: List<MsgRecord>, echo: JsonElement = EmptyJsonString): String {
        if (msgs.isEmpty()) {
            return logic("消息为空", echo)
        } else if (msgs.size > 100) {
            return logic("消息数量过多", echo)
        }



        TODO()
    }

    override fun path(): String = "send_group_forward_msg"
}