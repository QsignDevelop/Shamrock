package moe.RinShiona.Shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.qqinterface.servlet.GroupSvc
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.tools.EmptyJsonString

internal object SetGroupWholeBan: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val groupId = session.getLong("group_id")
        val enable = session.getBoolean("enable")
        return invoke(groupId, enable, session.echo)
    }

    operator fun invoke(groupId: Long, enable: Boolean, echo: JsonElement = EmptyJsonString): String {
        GroupSvc.setGroupWholeBan(groupId, enable)
        return ok("成功", echo)
    }

    override val requiredParams: Array<String> = arrayOf()

    override fun path(): String = "set_group_whole_ban"
}