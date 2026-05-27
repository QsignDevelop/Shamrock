package moe.RinShiona.Shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.qqinterface.servlet.GroupSvc
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.tools.EmptyJsonString

internal object LeaveTroop: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val groupId = session.getString("group_id")
        return invoke(groupId, session.echo)
    }

    operator fun invoke(groupId: String, echo: JsonElement = EmptyJsonString): String {
        if (GroupSvc.isOwner(groupId)) {
            return error("you are the owner of this group", echo)
        }
        GroupSvc.resignTroop(groupId.toLong())
        return ok("成功", echo)
    }

    override val requiredParams: Array<String> = arrayOf("group_id")

    override val alias: Array<String> = arrayOf("set_group_leave")

    override fun path(): String = "leave_group"
}