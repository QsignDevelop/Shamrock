package moe.RinShiona.Shamrock.helper

import com.tencent.qqnt.kernel.nativeinterface.MsgConstant
import moe.RinShiona.Shamrock.remote.service.data.push.MsgSubType
import moe.RinShiona.Shamrock.remote.service.data.push.MsgType
import kotlin.math.abs

/**
 * QQ NT [MsgConstant] chatType 与 OneBot / HTTP API 的桥接。
 * 所有会话类型均可生成 msgHash、落库、推送；OneBot message_type 仍映射为 group/private。
 */
internal object ChatTypeHelper {

    private val DETAIL_TO_CHAT: Map<String, Int> = mapOf(
        "private" to MsgConstant.KCHATTYPEC2C,
        "friend" to MsgConstant.KCHATTYPEC2C,
        "c2c" to MsgConstant.KCHATTYPEC2C,
        "group" to MsgConstant.KCHATTYPEGROUP,
        "troop" to MsgConstant.KCHATTYPEGROUP,
        "guild" to MsgConstant.KCHATTYPEGUILD,
        "less" to MsgConstant.KCHATTYPETEMPC2CFROMUNKNOWN,
        "temp" to MsgConstant.KCHATTYPETEMPC2CFROMUNKNOWN,
        "temp_unknown" to MsgConstant.KCHATTYPETEMPC2CFROMUNKNOWN,
        "temp_group" to MsgConstant.KCHATTYPETEMPC2CFROMGROUP,
        "disc" to MsgConstant.KCHATTYPEDISC,
        "discussion" to MsgConstant.KCHATTYPEDISC,
        "buddy_notify" to MsgConstant.KCHATTYPEBUDDYNOTIFY,
        "group_notify" to MsgConstant.KCHATTYPEGROUPNOTIFY,
        "group_helper" to MsgConstant.KCHATTYPEGROUPHELPER,
        "group_guild" to MsgConstant.KCHATTYPEGROUPGUILD,
        "group_bless" to MsgConstant.KCHATTYPEGROUPBLESS,
        "service" to MsgConstant.KCHATTYPESERVICEASSISTANT,
        "service_assistant" to MsgConstant.KCHATTYPESERVICEASSISTANT,
        "service_sub" to MsgConstant.KCHATTYPESERVICEASSISTANTSUB,
        "subscribe" to MsgConstant.KCHATTYPESUBSCRIBEFOLDER,
        "qq_notify" to MsgConstant.KCHATTYPEQQNOTIFY,
        "dataline" to MsgConstant.KCHATTYPEDATALINE,
        "dataline_mqq" to MsgConstant.KCHATTYPEDATALINEMQQ,
        "fav" to MsgConstant.KCHATTYPEFAV,
        "weiyun" to MsgConstant.KCHATTYPEWEIYUN,
        "circle" to MsgConstant.KCHATTYPECIRCLE,
        "square_public" to MsgConstant.KCHATTYPESQUAREPUBLIC,
        "game" to MsgConstant.KCHATTYPEGAMEMESSAGE,
        "game_folder" to MsgConstant.KCHATTYPEGAMEMESSAGEFOLDER,
        "nearby" to MsgConstant.KCHATTYPENEARBY,
        "nearby_assistant" to MsgConstant.KCHATTYPENEARBYASSISTANT,
        "nearby_interact" to MsgConstant.KCHATTYPENEARBYINTERACT,
        "nearby_folder" to MsgConstant.KCHATTYPENEARBYFOLDER,
        "nearby_hello" to MsgConstant.KCHATTYPENEARBYHELLOFOLDER,
        "temp_addressbook" to MsgConstant.KCHATTYPETEMPADDRESSBOOK,
        "temp_crm" to MsgConstant.KCHATTYPETEMPBUSSINESSCRM,
        "temp_verify" to MsgConstant.KCHATTYPETEMPFRIENDVERIFY,
        "temp_public" to MsgConstant.KCHATTYPETEMPPUBLICACCOUNT,
        "temp_wpa" to MsgConstant.KCHATTYPETEMPWPA,
        "match_friend" to MsgConstant.KCHATTYPEMATCHFRIEND,
        "match_folder" to MsgConstant.KCHATTYPEMATCHFRIENDFOLDER,
        "relate_account" to MsgConstant.KCHATTYPERELATEACCOUNT,
        "guild_meta" to MsgConstant.KCHATTYPEGUILDMETA,
        "unknown" to MsgConstant.KCHATTYPEUNKNOWN,
    )

    private val CHAT_TO_DETAIL: Map<Int, String> = buildMap {
        DETAIL_TO_CHAT.forEach { (name, type) ->
            if (!containsKey(type)) put(type, name)
        }
        put(MsgConstant.KCHATTYPEC2C, "private")
        put(MsgConstant.KCHATTYPEGROUP, "group")
    }

    private val GROUP_LIKE: Set<Int> = setOf(
        MsgConstant.KCHATTYPEGROUP,
        MsgConstant.KCHATTYPEGROUPGUILD,
        MsgConstant.KCHATTYPEGROUPHELPER,
        MsgConstant.KCHATTYPEGROUPNOTIFY,
        MsgConstant.KCHATTYPEGROUPBLESS,
        MsgConstant.KCHATTYPEDISC,
    )

    fun detailTypeToChatType(detailType: String): Int {
        val key = detailType.trim().lowercase()
        DETAIL_TO_CHAT[key]?.let { return it }
        key.toIntOrNull()?.let { return it }
        if (key.startsWith("chat_")) {
            key.removePrefix("chat_").toIntOrNull()?.let { return it }
        }
        error("不支持的消息来源类型: $detailType")
    }

    fun chatTypeToDetailType(chatType: Int): String =
        CHAT_TO_DETAIL[chatType] ?: "chat_$chatType"

    fun chatTypeDisplayName(chatType: Int): String = when (chatType) {
        MsgConstant.KCHATTYPEC2C -> "私聊"
        MsgConstant.KCHATTYPEGROUP -> "群聊"
        MsgConstant.KCHATTYPEGUILD -> "频道"
        MsgConstant.KCHATTYPETEMPC2CFROMUNKNOWN -> "临时会话"
        MsgConstant.KCHATTYPETEMPC2CFROMGROUP -> "群临时会话"
        MsgConstant.KCHATTYPESERVICEASSISTANT -> "服务号"
        MsgConstant.KCHATTYPEGROUPNOTIFY -> "群通知"
        MsgConstant.KCHATTYPEBUDDYNOTIFY -> "好友通知"
        MsgConstant.KCHATTYPEQQNOTIFY -> "QQ通知"
        else -> chatTypeToDetailType(chatType)
    }

    fun generateMsgIdHash(chatType: Int, msgId: Long): Int {
        val key = "ct${chatType}_$msgId"
        return abs(key.hashCode())
    }

    fun isGroupLike(chatType: Int): Boolean = chatType in GROUP_LIKE

    fun toOneBotMsgType(chatType: Int): MsgType =
        if (isGroupLike(chatType)) MsgType.Group else MsgType.Private

    fun toOneBotMsgSubType(chatType: Int): MsgSubType = when (chatType) {
        MsgConstant.KCHATTYPEGROUP,
        MsgConstant.KCHATTYPEDISC,
        MsgConstant.KCHATTYPEGROUPBLESS -> MsgSubType.NORMAL
        MsgConstant.KCHATTYPETEMPC2CFROMGROUP,
        MsgConstant.KCHATTYPETEMPC2CFROMUNKNOWN -> MsgSubType.GroupLess
        MsgConstant.KCHATTYPEGROUPNOTIFY,
        MsgConstant.KCHATTYPEBUDDYNOTIFY,
        MsgConstant.KCHATTYPEQQNOTIFY -> MsgSubType.NOTICE
        MsgConstant.KCHATTYPEGUILD,
        MsgConstant.KCHATTYPEGROUPGUILD,
        MsgConstant.KCHATTYPEGUILDMETA,
        MsgConstant.KCHATTYPESERVICEASSISTANT,
        MsgConstant.KCHATTYPESERVICEASSISTANTSUB,
        MsgConstant.KCHATTYPESUBSCRIBEFOLDER -> MsgSubType.Other
        else -> MsgSubType.Friend
    }

    fun peerIdParamName(chatType: Int): String =
        if (isGroupLike(chatType)) "group_id" else "user_id"

    fun resolveGroupId(chatType: Int, peerUin: Long): Long =
        if (isGroupLike(chatType)) peerUin else 0L

    fun resolveTargetId(chatType: Int, peerUin: Long): Long =
        if (isGroupLike(chatType)) 0L else peerUin
}
