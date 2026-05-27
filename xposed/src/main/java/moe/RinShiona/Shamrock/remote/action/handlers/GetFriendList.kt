package moe.RinShiona.Shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.remote.service.data.FriendEntry
import moe.RinShiona.Shamrock.remote.service.data.PlatformType
import moe.RinShiona.Shamrock.qqinterface.servlet.FriendSvc
import moe.RinShiona.Shamrock.tools.EmptyJsonString

internal object GetFriendList: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val refresh = session.getBooleanOrDefault("refresh", false)
        return invoke(refresh, session.echo)
    }

    suspend operator fun invoke(refresh: Boolean, echo: JsonElement = EmptyJsonString): String {
        val friendList = FriendSvc.getFriendList(refresh).onFailure {
            return error(it.message ?: "unknown error", echo)
        }.getOrThrow()
        return ok(friendList.map { friend ->
            FriendEntry(
                id = friend.uin.toLong(),
                name = friend.name,
                displayName = friend.remark,
                remark = friend.remark,
                age = friend.age,
                gender = friend.gender,
                groupId = friend.groupid,
                platformType = PlatformType.valueOf(friend.iTermType),
                termType = friend.iTermType
            )
        }, echo)
    }


    override fun path(): String = "get_friend_list"
}