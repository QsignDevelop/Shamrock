package moe.RinShiona.Shamrock.remote.api

import com.tencent.mobileqq.qqguildsdk.api.IGPSService
import com.tencent.qqnt.kernel.nativeinterface.GProRetentionGuildListRsp
import com.tencent.qqnt.kernel.nativeinterface.IGProFetchRetentionGuildListCallback
import com.tencent.qqnt.kernel.nativeinterface.IKernelGuildListener
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import moe.RinShiona.Shamrock.helper.Level
import moe.RinShiona.Shamrock.helper.LogCenter
import moe.RinShiona.Shamrock.remote.entries.EmptyObject
import moe.RinShiona.Shamrock.remote.service.listener.KernelGuildListener
import moe.RinShiona.Shamrock.tools.getOrPost
import moe.RinShiona.Shamrock.tools.respond
import moe.RinShiona.Shamrock.xposed.helper.AppRuntimeFetcher
import moe.RinShiona.Shamrock.xposed.helper.NTServiceFetcher
import mqq.app.MobileQQ

fun Routing.guildAction() {
    getOrPost("/get_guild_service_profile") {
        val service = AppRuntimeFetcher.appRuntime
            .getRuntimeService(IGPSService::class.java, "all")
        val tinyId = service.selfTinyId

    }

    getOrPost("/refresh_guild_list") {
        val kernelService = NTServiceFetcher.kernelService
        val sessionService = kernelService.wrapperSession
        val guildService = sessionService.guildService
        guildService.refreshGuildList(true)
        respond(false, -100, msg = "测试接口", data = EmptyObject)
    }

    getOrPost("/get_guild_list") {

        call.respondText("ok")
    }
}