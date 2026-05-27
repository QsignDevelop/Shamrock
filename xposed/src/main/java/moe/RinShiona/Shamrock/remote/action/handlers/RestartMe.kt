package moe.RinShiona.Shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.tools.EmptyJsonString

internal object RestartMe: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        return invoke(2000, session.echo)
    }

    operator fun invoke(duration: Int, echo: JsonElement = EmptyJsonString): String {
        return ok("不支持", echo)
    }

    override fun path(): String = "set_restart"
}