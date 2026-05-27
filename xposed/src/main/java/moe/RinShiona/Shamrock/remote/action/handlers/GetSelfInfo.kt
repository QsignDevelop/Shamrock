package moe.RinShiona.Shamrock.remote.action.handlers

import com.tencent.mobileqq.app.QQAppInterface
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.remote.entries.Status
import moe.RinShiona.Shamrock.remote.entries.resultToString
import moe.RinShiona.Shamrock.remote.service.data.UserDetail
import moe.RinShiona.Shamrock.xposed.helper.AppRuntimeFetcher
import mqq.app.MobileQQ

internal object GetSelfInfo: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        //val accounts = MobileQQ.getMobileQQ().allAccounts
        val runtime = AppRuntimeFetcher.appRuntime as QQAppInterface
        val curUin = runtime.currentAccountUin
        //val account = accounts.firstOrNull { it.uin == curUin }

        return resultToString(true, Status.Ok, UserDetail(
            curUin.toLong(), runtime.currentNickname, runtime.currentNickname
        ), echo = session.echo)
    }

    override fun path(): String = "get_self_info"
}