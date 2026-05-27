package moe.RinShiona.Shamrock.remote.api

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.request.httpVersion
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import moe.RinShiona.Shamrock.remote.HTTPServer
import moe.RinShiona.Shamrock.remote.action.ActionManager
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.config.ECHO_KEY
import moe.RinShiona.Shamrock.remote.entries.EmptyObject
import moe.RinShiona.Shamrock.remote.entries.IndexData
import moe.RinShiona.Shamrock.remote.entries.Status
import moe.RinShiona.Shamrock.tools.fetchOrNull
import moe.RinShiona.Shamrock.tools.fetchOrThrow
import moe.RinShiona.Shamrock.tools.fetchPostJsonElement
import moe.RinShiona.Shamrock.tools.fetchPostJsonObject
import moe.RinShiona.Shamrock.tools.isJsonArray
import moe.RinShiona.Shamrock.tools.isJsonObject
import moe.RinShiona.Shamrock.tools.isJsonString
import moe.RinShiona.Shamrock.tools.json
import moe.RinShiona.Shamrock.tools.respond
import moe.RinShiona.Shamrock.utils.PlatformUtils
import mqq.app.MobileQQ

@Serializable
data class OldApiResult<T>(
    val code: Int,
    val msg: String = "",
    @Contextual
    val data: T? = null
)

fun Routing.echoActionPost() {
    post("/") {
        val action = fetchOrThrow("action")
        val echo = if (isJsonObject("echo") || isJsonArray("echo")) {
            fetchPostJsonElement("echo")
        } else {
            (fetchOrNull("echo") ?: "").json
        }
        call.attributes.put(ECHO_KEY, echo)

        val params = fetchPostJsonObject("params")

        val handler = ActionManager[action]
        if (handler == null) {
            respond(false, Status.UnsupportedAction, EmptyObject, "不支持的Action", echo = echo)
        } else {
            call.respondText(handler.handle(ActionSession(params, echo)), ContentType.Application.Json)
        }
    }
}

fun Routing.echoVersion() {
    route("/") {
        get {
            respond(
                isOk = true,
                code = Status.Ok,
                data = IndexData(PlatformUtils.getClientVersion(MobileQQ.getContext()), HTTPServer.startTime, call.request.httpVersion)
            )
        }
        post {
            val action = fetchOrThrow("action")
            val echo = if (isJsonObject("echo") || isJsonArray("echo")) {
                fetchPostJsonElement("echo")
            } else {
                (fetchOrNull("echo") ?: "").json
            }
            call.attributes.put(ECHO_KEY, echo)

            val params = fetchPostJsonObject("params")

            val handler = ActionManager[action]
            if (handler == null) {
                respond(false, Status.UnsupportedAction, EmptyObject, "不支持的Action", echo = echo)
            } else {
                call.respondText(handler.handle(ActionSession(params, echo)), ContentType.Application.Json)
            }
        }
    }
}