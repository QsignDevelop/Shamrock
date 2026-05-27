package moe.RinShiona.Shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.qqinterface.servlet.TicketSvc
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.remote.service.data.Credentials
import moe.RinShiona.Shamrock.tools.EmptyJsonString

internal object GetCookies: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val domain = session.getStringOrNull("domain")
            ?: return invoke(session.echo)
        return invoke(domain, session.echo)
    }

    operator fun invoke(echo: JsonElement = EmptyJsonString): String {
        return ok(Credentials(cookie = TicketSvc.getCookie()), echo)
    }

    suspend operator fun invoke(domain: String, echo: JsonElement = EmptyJsonString): String {
        return ok(Credentials(cookie = TicketSvc.getCookie(domain)), echo)
    }

    override fun path(): String = "get_cookies"
}