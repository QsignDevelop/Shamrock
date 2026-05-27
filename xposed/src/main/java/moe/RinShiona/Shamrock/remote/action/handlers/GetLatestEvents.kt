package moe.RinShiona.Shamrock.remote.action.handlers

import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.entries.EmptyObject
import moe.RinShiona.Shamrock.remote.entries.Status
import moe.RinShiona.Shamrock.remote.entries.resultToString

// 弱智玩意，不予实现
// 请开启HTTP回调 把事件回调回去
// 而不是在我这里轮询
internal object GetLatestEvents: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        return resultToString(
            true, Status.Ok, listOf<EmptyObject>(), echo = session.echo
        )
    }

    override fun path(): String = "get_latest_events"
}