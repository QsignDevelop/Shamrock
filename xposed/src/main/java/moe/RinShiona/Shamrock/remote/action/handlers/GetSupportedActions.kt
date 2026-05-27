package moe.RinShiona.Shamrock.remote.action.handlers

import moe.RinShiona.Shamrock.remote.action.ActionManager
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.entries.Status
import moe.RinShiona.Shamrock.remote.entries.resultToString

internal object GetSupportedActions: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        return resultToString(true, Status.Ok, ActionManager.actionMap.keys.toList(), echo = session.echo)
    }

    override fun path(): String = "get_supported_actions"
}