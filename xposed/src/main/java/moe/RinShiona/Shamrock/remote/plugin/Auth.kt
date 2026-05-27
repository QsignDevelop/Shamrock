package moe.RinShiona.Shamrock.remote.plugin

import io.ktor.server.application.ApplicationCall
import moe.RinShiona.Shamrock.helper.ErrorTokenException
import io.ktor.server.application.createApplicationPlugin
import moe.RinShiona.Shamrock.remote.service.config.ShamrockConfig
import moe.RinShiona.Shamrock.tools.fetchOrNull

private suspend fun ApplicationCall.checkToken() {
    val token = ShamrockConfig.getToken()
    if (token.isBlank()) {
        return
    }
    var accessToken = request.headers["Authorization"]
        ?: fetchOrNull("ticket")
        ?: fetchOrNull("access_token")
        ?: throw ErrorTokenException
    if (accessToken.startsWith("Bearer ")) {
        accessToken = accessToken.substring(7)
    }
    if (token != accessToken) {
        throw ErrorTokenException
    }
}

internal val Auth = createApplicationPlugin("Auth") {
    // 获取get请求的token参数并校验
    this.onCall { call ->
        call.checkToken()
    }
    /*
    this.onCallReceive { call, _ ->
        var accessToken = call.fetchOrNull("access_token")
            ?: call.fetchOrNull("ticket")
            ?: call.request.headers["Authorization"]
            ?: throw ErrorTokenException
        if (accessToken.startsWith("Bearer ")) {
            accessToken = accessToken.substring(7)
        }
        if (token != accessToken) {
            throw ErrorTokenException
        }
    }*/
}