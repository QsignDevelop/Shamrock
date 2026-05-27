package moe.RinShiona.Shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.qqinterface.servlet.GroupSvc
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.tools.EmptyJsonString

internal object SetGroupAdmin: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val groupId = session.getLong("group_id")
        val userId = session.getLong("user_id")
        val enable = session.getBoolean("enable")
        return invoke(groupId, userId, enable, session.echo)
    }

    operator fun invoke(groupId: Long, userId: Long, enable: Boolean, echo: JsonElement = EmptyJsonString): String {
        if (!GroupSvc.isOwner(groupId.toString())) {
            return logic("you are not owner", echo)
        }
        GroupSvc.setGroupAdmin(groupId, userId, enable)
        return ok("成功", echo)
    }

    override fun path(): String = "set_group_admin"
}