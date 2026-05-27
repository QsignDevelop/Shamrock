package moe.RinShiona.Shamrock.remote.api

import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import moe.RinShiona.Shamrock.tools.fetchOrNull
import moe.RinShiona.Shamrock.tools.getOrPost
import moe.RinShiona.Shamrock.helper.LogCenter

fun Routing.showLog() {
    getOrPost("/log") {
        val start = fetchOrNull("start")?.toIntOrNull() ?: 0
        val recent =fetchOrNull("recent")?.toBooleanStrictOrNull() ?: false
        val log = LogCenter.getLogLines(start, recent)
        call.respondText(log.joinToString("\n"))
    }
}