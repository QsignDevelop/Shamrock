package moe.RinShiona.Shamrock.remote.api

import moe.RinShiona.Shamrock.helper.ChatTypeHelper
import moe.RinShiona.Shamrock.helper.MessageHelper
import com.tencent.qqnt.kernel.nativeinterface.MsgConstant
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import moe.RinShiona.Shamrock.remote.action.handlers.*
import moe.RinShiona.Shamrock.tools.fetchGetOrNull
import moe.RinShiona.Shamrock.tools.fetchGetOrThrow
import moe.RinShiona.Shamrock.tools.fetchOrThrow
import moe.RinShiona.Shamrock.tools.fetchPostJsonArray
import moe.RinShiona.Shamrock.tools.fetchPostJsonString
import moe.RinShiona.Shamrock.tools.fetchPostOrNull
import moe.RinShiona.Shamrock.tools.fetchPostOrThrow
import moe.RinShiona.Shamrock.tools.getOrPost
import moe.RinShiona.Shamrock.tools.isJsonData
import moe.RinShiona.Shamrock.tools.isJsonString

fun Routing.messageAction() {
    getOrPost("/delete_msg") {
        val msgHash = fetchOrThrow("message_id").toInt()
        call.respondText(DeleteMessage(msgHash))
    }

    getOrPost("/get_msg") {
        val msgHash = fetchOrThrow("message_id").toInt()
        call.respondText(GetMsg(msgHash))
    }

    route("/(send_msg|send_message)".toRegex()) {
        get {
            val msgType = fetchGetOrThrow("message_type")
            val message = fetchGetOrThrow("message")
            val autoEscape = fetchGetOrNull("auto_escape")?.toBooleanStrict() ?: false
            val chatType = MessageHelper.obtainMessageTypeByDetailType(msgType)
            val peerIdKey = ChatTypeHelper.peerIdParamName(chatType)
            val peerId = fetchGetOrNull("peer_id") ?: fetchGetOrThrow(peerIdKey)
            call.respondText(SendMessage(chatType, peerId, message, autoEscape))
        }
        post {
            val msgType = fetchPostOrThrow("message_type")
            val chatType = MessageHelper.obtainMessageTypeByDetailType(msgType)
            val peerIdKey = ChatTypeHelper.peerIdParamName(chatType)
            val peerId = fetchPostOrNull("peer_id") ?: fetchPostOrThrow(peerIdKey)
            call.respondText(if (isJsonData() && !isJsonString("message")) {
                SendMessage(chatType, peerId, fetchPostJsonArray("message"))
            } else {
                val autoEscape = fetchPostOrNull("auto_escape")?.toBooleanStrict() ?: false
                SendMessage(chatType, peerId, fetchPostOrThrow("message"), autoEscape)
            })
        }
    }

    route("/send_group_(msg|message)".toRegex()) {
        get {
            val groupId = fetchGetOrThrow("group_id")
            val message = fetchGetOrThrow("message")
            val autoEscape = fetchGetOrNull("auto_escape")?.toBooleanStrict() ?: false
            call.respondText(SendMessage(MsgConstant.KCHATTYPEGROUP, groupId, message, autoEscape))
        }
        post {
            val groupId = fetchPostOrThrow("group_id")

            val autoEscape = fetchPostOrNull("auto_escape")?.toBooleanStrict() ?: false

            val result = if (isJsonData()) {
                if (isJsonString("message")) {
                    SendMessage(MsgConstant.KCHATTYPEGROUP, groupId, fetchPostJsonString("message"), autoEscape)
                } else {
                    SendMessage(MsgConstant.KCHATTYPEGROUP, groupId, fetchPostJsonArray("message"))
                }
            } else {
                SendMessage(MsgConstant.KCHATTYPEGROUP, groupId, fetchPostOrThrow("message"), autoEscape)
            }

            call.respondText(result)
        }
    }

    route("/send_private_(msg|message)".toRegex()) {
        get {
            val userId = fetchGetOrThrow("user_id")
            val message = fetchGetOrThrow("message")
            val autoEscape = fetchGetOrNull("auto_escape")?.toBooleanStrict() ?: false
            call.respondText(SendMessage(MsgConstant.KCHATTYPEC2C, userId, message, autoEscape))
        }
        post {
            val userId = fetchPostOrThrow("user_id")
            val autoEscape = fetchPostOrNull("auto_escape")?.toBooleanStrict() ?: false

            val result = if (isJsonData()) {
                if (isJsonString("message")) {
                    SendMessage(MsgConstant.KCHATTYPEC2C, userId, fetchPostJsonString("message"), autoEscape)
                } else {
                    SendMessage(MsgConstant.KCHATTYPEC2C, userId, fetchPostJsonArray("message"))
                }
            } else {
                SendMessage(MsgConstant.KCHATTYPEC2C, userId, fetchPostOrThrow("message"), autoEscape)
            }

            call.respondText(result)
        }
    }
}