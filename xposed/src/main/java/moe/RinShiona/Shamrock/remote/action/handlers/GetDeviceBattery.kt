package moe.RinShiona.Shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.tools.EmptyJsonString
import moe.RinShiona.Shamrock.utils.PlatformUtils

internal object GetDeviceBattery: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        return invoke(session.echo)
    }

    override fun path(): String = "get_device_battery"

    operator fun invoke(echo: JsonElement = EmptyJsonString): String {
        return ok(PlatformUtils.getDeviceBattery(), echo = echo)
    }
}