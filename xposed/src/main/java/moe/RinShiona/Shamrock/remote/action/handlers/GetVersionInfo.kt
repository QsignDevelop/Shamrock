package moe.RinShiona.Shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.remote.service.data.VersionInfo
import moe.RinShiona.Shamrock.tools.EmptyJsonString
import moe.RinShiona.Shamrock.tools.ShamrockVersion

internal object GetVersionInfo : IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        return invoke(session.echo)
    }

    operator fun invoke(echo: JsonElement = EmptyJsonString): String {
        return ok(
            VersionInfo(
                appFullName = "Shamrock v$ShamrockVersion",
                appName = "Shamrock",
                appVersion = ShamrockVersion,
                impl = "shamrock",
                version = ShamrockVersion,
                onebotVersion = "12",
            ),
            echo = echo
        )
    }

    override val alias: Array<String> = arrayOf("get_version")

    override fun path(): String = "get_version_info"

}