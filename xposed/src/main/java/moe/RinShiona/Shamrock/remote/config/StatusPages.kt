package moe.RinShiona.Shamrock.remote.config

import moe.RinShiona.Shamrock.helper.ErrorTokenException
import moe.RinShiona.Shamrock.helper.LogicException
import moe.RinShiona.Shamrock.helper.ParamsException
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.remote.entries.CommonResult
import moe.RinShiona.Shamrock.remote.entries.ErrorCatch
import moe.RinShiona.Shamrock.remote.entries.Status

val ECHO_KEY = AttributeKey<JsonElement>("echo")

fun Application.statusPages() {
    install(StatusPages) {
        exception<ParamsException> { call, cause ->
            val echo = if (call.attributes.contains(ECHO_KEY)) {
                call.attributes[ECHO_KEY]
            } else null
            call.respond(CommonResult(
                status = "failed",
                retcode = Status.BadParam.code,
                data = ErrorCatch(call.request.uri, cause.message ?: ""),
                echo = echo
            ))
        }
        exception<LogicException> { call, cause ->
            val echo = if (call.attributes.contains(ECHO_KEY)) {
                call.attributes[ECHO_KEY]
            } else null
            call.respond(CommonResult(
                status = "failed",
                retcode = Status.LogicError.code,
                data = ErrorCatch(call.request.uri, cause.message ?: ""),
                echo = echo
            ))
        }
        exception<ErrorTokenException> { call, cause ->
            val echo = if (call.attributes.contains(ECHO_KEY)) {
                call.attributes[ECHO_KEY]
            } else null
            call.respond(CommonResult(
                status = "failed",
                retcode = Status.ErrorToken.code,
                data = ErrorCatch(call.request.uri, cause.message ?: ""),
                echo = echo
            ))
        }
        exception<Throwable> { call, cause ->
            val echo = if (call.attributes.contains(ECHO_KEY)) {
                call.attributes[ECHO_KEY]
            } else null
            call.respond(CommonResult(
                status = "failed",
                retcode = Status.InternalHandlerError.code,
                data = ErrorCatch(call.request.uri, cause.message ?: ""),
                echo = echo
            ))
        }
    }
}