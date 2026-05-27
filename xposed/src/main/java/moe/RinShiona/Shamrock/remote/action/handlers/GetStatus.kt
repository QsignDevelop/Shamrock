package moe.RinShiona.Shamrock.remote.action.handlers

import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.entries.Status
import moe.RinShiona.Shamrock.remote.entries.resultToString
import moe.RinShiona.Shamrock.remote.service.data.BotStatus
import moe.RinShiona.Shamrock.remote.service.data.Self
import moe.RinShiona.Shamrock.xposed.helper.AppRuntimeFetcher
import mqq.app.MobileQQ

internal object GetStatus: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val runtime = AppRuntimeFetcher.appRuntime
        val curUin = runtime.currentAccountUin
        return resultToString(true, Status.Ok, listOf(
            BotStatus(
                Self("qq", curUin.toLong()), runtime.isLogin, status = "正常", good = runtime.isLogin
            )
        ), echo = session.echo)
    }

    override fun path(): String = "get_status"
}