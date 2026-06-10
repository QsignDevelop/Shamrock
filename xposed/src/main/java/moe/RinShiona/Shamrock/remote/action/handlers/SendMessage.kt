package moe.RinShiona.Shamrock.remote.action.handlers

import com.tencent.qqnt.kernel.nativeinterface.MsgConstant
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.helper.ChatTypeHelper
import moe.RinShiona.Shamrock.helper.MessageHelper
import moe.RinShiona.Shamrock.helper.ParamsException
import moe.RinShiona.Shamrock.qqinterface.servlet.MsgSvc
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.helper.ContactHelper
import moe.RinShiona.Shamrock.remote.service.data.MessageResult
import moe.RinShiona.Shamrock.tools.json
import moe.RinShiona.Shamrock.helper.Level
import moe.RinShiona.Shamrock.helper.LogCenter
import moe.RinShiona.Shamrock.tools.EmptyJsonString

internal object SendMessage: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val detailType = session.getStringOrNull("detail_type")
            ?: session.getStringOrNull("message_type")
        try {
            val chatType = session.getIntOrNull("chat_type")
                ?: session.getStringOrNull("chat_type")?.let {
                    it.toIntOrNull() ?: ChatTypeHelper.detailTypeToChatType(it)
                }
                ?: detailType?.let { MessageHelper.obtainMessageTypeByDetailType(it) }
                ?: run {
                    when {
                        session.has("group_id") -> MsgConstant.KCHATTYPEGROUP
                        session.has("user_id") -> MsgConstant.KCHATTYPEC2C
                        session.has("peer_id") -> MsgConstant.KCHATTYPEC2C
                        else -> return noParam("chat_type/detail_type/group_id/user_id/peer_id", session.echo)
                    }
                }
            val peerId = session.getStringOrNull("peer_id")
                ?: if (ChatTypeHelper.isGroupLike(chatType)) {
                    session.getStringOrNull("group_id")
                } else {
                    session.getStringOrNull("user_id")
                }
                ?: return noParam(
                    "${ChatTypeHelper.peerIdParamName(chatType)} 或 peer_id",
                    session.echo,
                )
            return if (session.isString("message")) {
                val autoEscape = session.getBooleanOrDefault("auto_escape", false)
                val message = session.getString("message")
                invoke(chatType, peerId, message, autoEscape, session.echo)
            } else {
                val message = session.getArray("message")
                invoke(chatType, peerId, message, session.echo)
            }
        } catch (e: ParamsException) {
            return noParam(e.message!!, session.echo)
        } catch (e: Throwable) {
            return logic(e.message ?: e.toString(), session.echo)
        }
    }

    // 发送文本格式/CQ码类型消息
    suspend operator fun invoke(
        chatType: Int,
        peerId: String,
        message: String,
        autoEscape: Boolean,
        echo: JsonElement = EmptyJsonString
    ): String {
        //if (!ContactHelper.checkContactAvailable(chatType, peerId)) {
        //    return logic("contact is not found", echo = echo)
        //}
        val result = if (autoEscape) {
            MsgSvc.sendToAio(chatType, peerId, arrayListOf(message).json)
        } else {
            val msg = MessageHelper.decodeCQCode(message)
            if (msg.isEmpty()) {
                LogCenter.log("CQ码不合法", Level.WARN)
                return logic("CQCode is illegal", echo)
            } else {
                MsgSvc.sendToAio(chatType, peerId, msg)
            }
        }
        return ok(MessageResult(
            msgId = result.second,
            time = result.first * 0.001
        ), echo)
    }

    // 消息段格式消息
    suspend operator fun invoke(
        chatType: Int, peerId: String, message: JsonArray, echo: JsonElement = EmptyJsonString
    ): String {
        //if (!ContactHelper.checkContactAvailable(chatType, peerId)) {
        //    return logic("contact is not found", echo = echo)
        //}
        val result = MsgSvc.sendToAio(chatType, peerId, message)
        return ok(MessageResult(
            msgId = result.second,
            time = result.first * 0.001
        ), echo)
    }

    override val requiredParams: Array<String> = arrayOf("message")

    override fun path(): String = "send_message"

    override val alias: Array<String> = arrayOf("send_msg")
}