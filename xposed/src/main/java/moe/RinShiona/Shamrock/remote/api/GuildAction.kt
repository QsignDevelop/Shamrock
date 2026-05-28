package moe.RinShiona.Shamrock.remote.api

import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import moe.RinShiona.Shamrock.remote.action.handlers.GetGuildServiceProfile
import moe.RinShiona.Shamrock.remote.entries.EmptyObject
import moe.RinShiona.Shamrock.tools.getOrPost
import moe.RinShiona.Shamrock.tools.respond
import moe.RinShiona.Shamrock.xposed.helper.NTServiceFetcher

fun Routing.guildAction() {
    getOrPost("/get_guild_service_profile") {
        call.respondText(GetGuildServiceProfile())
    }

    getOrPost("/refresh_guild_list") {
        val kernelService = NTServiceFetcher.kernelService
        val guildService = kernelService.wrapperSession.guildService
        guildService.refreshGuildList(true)
        respond(true, 0, msg = "ok", data = EmptyObject)
    }

    getOrPost("/get_guild_list") {
        val kernelService = NTServiceFetcher.kernelService
        val guildService = kernelService.wrapperSession.guildService
        guildService.refreshGuildList(false)
        respond(true, 0, msg = "refresh triggered; use NT guild listener for push events", data = EmptyObject)
    }
}
