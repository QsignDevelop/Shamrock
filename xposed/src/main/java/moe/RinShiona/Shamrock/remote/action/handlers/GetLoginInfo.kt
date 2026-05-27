package moe.RinShiona.Shamrock.remote.action.handlers

import com.tencent.mobileqq.app.QQAppInterface
import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.remote.entries.StdAccount
import moe.RinShiona.Shamrock.tools.EmptyJsonString
import moe.RinShiona.Shamrock.xposed.helper.AppRuntimeFetcher
import mqq.app.MobileQQ

internal object GetLoginInfo: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        return invoke(session.echo)
    }

    operator fun invoke(echo: JsonElement = EmptyJsonString): String {
        val accounts = MobileQQ.getMobileQQ().allAccounts
        val runtime = AppRuntimeFetcher.appRuntime
        val curUin = runtime.currentAccountUin
        val account = accounts.firstOrNull { it.uin == curUin }
        return if (account == null || !account.isLogined) {
            error("当前不处于已登录状态", echo = echo)
        } else {
            ok(StdAccount(
                curUin.toLong(),if (runtime is QQAppInterface) runtime.currentNickname else "unknown"
            ), echo = echo)
        }
    }

    override fun path(): String = "get_login_info"
}