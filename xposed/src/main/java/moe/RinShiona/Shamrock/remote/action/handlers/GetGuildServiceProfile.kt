package moe.RinShiona.Shamrock.remote.action.handlers

import com.tencent.mobileqq.qqguildsdk.api.IGPSService
import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.tools.EmptyJsonString
import moe.RinShiona.Shamrock.xposed.helper.AppRuntimeFetcher

internal object GetGuildServiceProfile: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String = invoke(session.echo)

    operator fun invoke(echo: JsonElement = EmptyJsonString): String {
        val service = AppRuntimeFetcher.appRuntime
            .getRuntimeService(IGPSService::class.java, "all")
        if (!service.isGProSDKInitCompleted) {
            return error("GPro 服务未初始化，请先打开 QQ 频道页", echo = echo)
        }

        return ok(
            mapOf(
                "tiny_id" to service.selfTinyId.toString(),
                "initialized" to true
            ),
            echo = echo
        )
    }

    override fun path(): String = "get_guild_service_profile"
}
