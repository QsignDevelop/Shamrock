package moe.RinShiona.Shamrock.remote.api

import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.coroutines.delay
import moe.RinShiona.Shamrock.remote.entries.Status
import moe.RinShiona.Shamrock.tools.getOrPost
import moe.RinShiona.Shamrock.tools.respond
import moe.RinShiona.Shamrock.helper.LogCenter
import kotlin.system.exitProcess

fun Routing.obtainFrameworkInfo() {
    getOrPost("/get_start_time") {
        respond(
            isOk = true,
            code = Status.Ok,
            moe.RinShiona.Shamrock.remote.HTTPServer.startTime
        )
    }

    get("/shut") {
        moe.RinShiona.Shamrock.remote.HTTPServer.stop()
        LogCenter.log("正在关闭Shamrock。", toast = true)
        delay(3000)
        exitProcess(0)
    }
}
