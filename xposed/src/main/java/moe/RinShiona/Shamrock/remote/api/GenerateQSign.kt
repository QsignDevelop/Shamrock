@file:OptIn(DelicateCoroutinesApi::class)

package moe.RinShiona.Shamrock.remote.api

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.core.BytePacketBuilder
import kotlinx.io.core.readBytes
import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.remote.entries.Status
import moe.RinShiona.Shamrock.tools.EMPTY_BYTE_ARRAY
import moe.RinShiona.Shamrock.tools.EmptyJsonObject
import moe.RinShiona.Shamrock.tools.fetchGetOrThrow
import moe.RinShiona.Shamrock.tools.fetchOrNull
import moe.RinShiona.Shamrock.tools.fetchOrThrow
import moe.RinShiona.Shamrock.tools.fetchPostOrThrow
import moe.RinShiona.Shamrock.tools.getOrPost
import moe.RinShiona.Shamrock.tools.hex2ByteArray
import moe.RinShiona.Shamrock.tools.json
import moe.RinShiona.Shamrock.tools.respond
import moe.RinShiona.Shamrock.tools.toHexString
import moe.RinShiona.Shamrock.utils.PlatformUtils
import moe.RinShiona.Shamrock.xposed.ipc.ShamrockIpc
import moe.RinShiona.Shamrock.xposed.ipc.bytedata.IByteData
import moe.RinShiona.Shamrock.xposed.ipc.impl.ShamrockNative
import moe.RinShiona.Shamrock.xposed.ipc.qsign.IQSigner
import mqq.app.MobileQQ
import java.nio.ByteBuffer
import java.util.Locale

private var signer: IQSigner? = null
private var byteData: IByteData? = null

private fun getMsfServiceInfo(): ActivityManager.RunningServiceInfo? {
    val context = MobileQQ.getContext()
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    for (serviceInfo in activityManager.getRunningServices(Int.MAX_VALUE)) {
        if (serviceInfo.service.className == "com.tencent.mobileqq.msf.service.MsfService") {
            return serviceInfo
        }
    }
    return null
}

private fun isMsfServiceAlive(): Boolean = getMsfServiceInfo() != null

fun Routing.qsignHome() {
    get("/") {
        QSignStats.recordHome()
        val qqVersion = PlatformUtils.getClientVersion(MobileQQ.getContext())
        call.respond(
            QSignHome(
                code = 0,
                ServerVersion = "Shamrock Neko QSign v$qqVersion",
                Title = "🐾 Shamrock Neko QSign Server",
                msg = listOf(
                    "喵~ 这里是真机 QQ 原生 Sign 服务，不是 Unidbg 哦！",
                    "RegisterNatives 动态分析内置在 libshamrocknt.so 里喵~",
                    "接口格式与 unidbg QSign 兼容，记得开 Neko 模式 🐾"
                ),
                Info = QSignStats.homeInfo(),
                VersionList = listOf(qqVersion)
            )
        )
    }
}

fun Routing.qsign() {
    getOrPost("/reset_qsign") {
        val context = MobileQQ.getContext()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (serviceInfo in activityManager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceInfo.service.className == "com.tencent.mobileqq.msf.service.MsfService") {
                Process.killProcess(serviceInfo.pid)
            }
        }
        GlobalScope.launch(Dispatchers.Main) {
            val componentName = ComponentName(
                context.packageName,
                "com.tencent.mobileqq.msf.service.MsfService"
            )
            val intent = Intent().apply { component = componentName }
            intent.putExtra("to_SenderProcessName", "com.tencent.mobileqq")
            context.startService(intent)
        }
        call.respond(OldApiResult(0, "MSF restarted", data = EmptyJsonObject))
    }

    get("/shamrock/native_status") {
        call.respond(APIResult(0, "success", ShamrockNative.status()))
    }

    getOrPost("/get_running_service") {
        val activityManager = MobileQQ.getContext()
            .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val output = mutableListOf<JsonElement>()
        for (serviceInfo in activityManager.getRunningServices(Int.MAX_VALUE)) {
            output.add(mapOf(
                "service" to serviceInfo.service.className,
                "pid" to serviceInfo.pid,
                "uid" to serviceInfo.uid
            ).json)
        }
        call.respondText(output.json.toString())
    }

    get("/get_cmd_whitelist") {
        if (!ensureSigner()) return@get
        call.respond(OldApiResult(0, "success", signer!!.cmdWhiteList))
    }

    getOrPost("/get_xw_debug_id") {
        if (!ensureSigner()) return@getOrPost
        val uin = fetchOrThrow("uin")
        val data = fetchOrThrow("data")
        val parts = data.split("_")
        if (parts.size < 2) {
            call.respond(OldApiResult(-2, "invalid data", null))
            return@getOrPost
        }
        val xwDebugId = signer!!.xwDebugId(uin, parts[0], parts[1])
        call.respond(OldApiResult(0, "success", xwDebugId?.toHexString() ?: ""))
    }

    route("/sign") {
        get {
            readSignCompatParams()
            val uin = fetchGetOrThrow("uin")
            val cmd = fetchGetOrThrow("cmd")
            val seq = fetchGetOrThrow("seq").toInt()
            val buffer = fetchGetOrThrow("buffer").hex2ByteArray()
            requestSign(cmd, uin, seq, buffer)
        }
        post {
            val req = call.receive<SignRequest>()
            readSignCompatParamsFrom(req)
            requestSign(req.cmd, req.uin, req.seq.toInt(), req.buffer.hex2ByteArray())
        }
    }

    get("/submit") {
        val cmd = fetchGetOrThrow("cmd")
        val callbackId = fetchGetOrThrow("callback_id").toLong()
        val buffer = fetchGetOrThrow("buffer").hex2ByteArray()
        requestSubmit(cmd, callbackId, buffer)
    }

    get("/sign/prewarm") {
        if (!ensureSigner()) {
            call.respond(APIResult(1, "MSF not ready", null))
            return@get
        }
        call.respond(APIResult(0, "success", PrewarmResult(prewarmed = true, poolSize = 1)))
    }

    post("/sign/prewarm") {
        if (!ensureSigner()) {
            call.respond(APIResult(1, "MSF not ready", null))
            return@post
        }
        call.respond(APIResult(0, "success", PrewarmResult(prewarmed = true, poolSize = 1)))
    }

    get("/custom_energy") {
        val uin = fetchGetOrThrow("uin")
        val data = fetchGetOrThrow("data")
        val salt = fetchGetOrThrow("salt").hex2ByteArray()
        requestEnergy(uin, data, salt)
    }

    route("/energy") {
        get {
            val uin = fetchGetOrThrow("uin")
            val data = fetchGetOrThrow("data")
            if (!(data.startsWith("810_") || data.startsWith("812_"))) {
                call.respond(APIResult<String>(-2, "data param invalid", null))
                return@get
            }
            val salt = fetchSalt(data, uin)
            if (salt.isEmpty()) {
                call.respond(APIResult<String>(-3, "cannot infer mode, provide mode manually", null))
                return@get
            }
            requestEnergy(uin, data, salt)
        }
        post {
            val uin = fetchPostOrThrow("uin")
            val data = fetchPostOrThrow("data")
            if (!(data.startsWith("810_") || data.startsWith("812_"))) {
                call.respond(APIResult<String>(-2, "data param invalid", null))
                return@post
            }
            val salt = fetchSalt(data, uin)
            if (salt.isEmpty()) {
                call.respond(APIResult<String>(-3, "cannot infer mode, provide mode manually", null))
                return@post
            }
            requestEnergy(uin, data, salt)
        }
    }

    get("/get_byte") {
        if (!isMsfServiceAlive()) {
            call.respond(APIResult<String>(-2, "MSF not started", null))
            return@get
        }
        if (!initByteData()) {
            call.respond(APIResult<String>(-2, "ByteData IPC unavailable", null))
            return@get
        }

        val uin = fetchGetOrThrow("uin")
        val data = fetchGetOrThrow("data")
        if (!(data.startsWith("810_") || data.startsWith("812_"))) {
            call.respond(APIResult<String>(-2, "data param invalid", null))
            return@get
        }
        val salt = fetchSalt(data, uin)
        if (salt.isEmpty()) {
            call.respond(APIResult<String>(-3, "cannot infer mode", null))
            return@get
        }

        val sign = byteData!!.sign(uin, data, salt).sign
        if (sign == null) {
            QSignStats.recordEnergy(false)
            call.respond(APIResult<String>(-2, "failed", null))
        } else {
            QSignStats.recordEnergy(true)
            call.respond(APIResult(0, "success", sign.toHexString().uppercase(Locale.ROOT)))
        }
    }

    get("/friend_sign") {
        if (!ensureSigner()) return@get
        val addUin = fetchOrThrow("add_uin")
        val source = fetchOrThrow("source")
        val uin = fetchOrThrow("uin").toLong()
        val sign = signer!!.energy("add_friend", BytePacketBuilder().also {
            it.writeLong(uin)
            it.writeLong(addUin.toLong())
            it.writeInt(source.toInt())
        }.build().readBytes())
        respondEnergyLegacy(sign)
    }

    get("/group_sign") {
        if (!ensureSigner()) return@get
        val addUin = fetchOrThrow("group_uin")
        val source = fetchOrThrow("source")
        val subsource = fetchOrThrow("sub_source")
        val uin = fetchOrThrow("uin").toLong()
        val sign = signer!!.energy("add_group", BytePacketBuilder().also {
            it.writeLong(uin)
            it.writeLong(addUin.toLong())
            it.writeInt(source.toInt())
            it.writeInt(subsource.toInt())
        }.build().readBytes())
        respondEnergyLegacy(sign)
    }
}

private suspend fun PipelineContext<Unit, ApplicationCall>.ensureSigner(): Boolean {
    if (!isMsfServiceAlive()) {
        call.respond(OldApiResult(-2, "MSF not started", null))
        return false
    }
    if (signer == null || signer?.asBinder()?.isBinderAlive == false) {
        if (!initSigner()) {
            respond(false, Status.InternalHandlerError)
            return false
        }
    }
    return true
}

private suspend fun PipelineContext<Unit, ApplicationCall>.requestEnergy(
    uin: String,
    data: String,
    salt: ByteArray
) {
    if (!isMsfServiceAlive()) {
        QSignStats.recordEnergy(false)
        call.respond(APIResult<String>(-2, "MSF not started", null))
        return
    }
    if (signer == null || signer?.asBinder()?.isBinderAlive == false) {
        if (!initSigner()) {
            QSignStats.recordEnergy(false)
            respond(false, Status.InternalHandlerError)
            return
        }
    }

    val sign = withTimeoutOrNull(5000) {
        signer!!.energyData(data, salt)
    }
    if (sign == null) {
        QSignStats.recordEnergy(false)
        call.respond(APIResult<String>(-1, "failed", null))
    } else {
        QSignStats.recordEnergy(true)
        call.respond(APIResult(0, "success", sign.toHexString().uppercase(Locale.ROOT)))
    }
}

private suspend fun PipelineContext<Unit, ApplicationCall>.respondEnergyLegacy(sign: ByteArray?) {
    if (sign == null) {
        QSignStats.recordEnergy(false)
        call.respond(OldApiResult(-1, "failed", null))
    } else {
        QSignStats.recordEnergy(true)
        call.respond(OldApiResult(0, "success", sign.toHexString()))
    }
}

private suspend inline fun PipelineContext<Unit, ApplicationCall>.fetchSalt(
    data: String,
    uin: String
): ByteArray {
    var mode = fetchOrNull("mode")
    if (mode == null) {
        mode = when (data) {
            "810_d", "810_a", "810_f", "810_9" -> "v2"
            "810_2", "810_25", "810_7", "810_24" -> "v1"
            "812_b", "812_a" -> "v3"
            "812_5" -> "v4"
            else -> null
        }
    }
    if (mode == null) return EMPTY_BYTE_ARRAY

    val version = fetchOrThrow("version")
    if (!version.startsWith("6.0.0")) {
        throw RuntimeException("version must start with 6.0.0")
    }

    return when (mode) {
        "v1" -> {
            val guid = fetchOrThrow("guid").hex2ByteArray()
            val sub = data.substring(4).toInt(16)
            ByteBuffer.allocate(8 + 2 + guid.size + 2 + 10 + 4).also {
                it.putLong(uin.toLong())
                it.putShort(guid.size.toShort())
                it.put(guid)
                it.putShort(version.length.toShort())
                it.put(version.toByteArray())
                it.putInt(sub)
            }.array()
        }
        "v2" -> {
            val guid = fetchOrThrow("guid").hex2ByteArray()
            val sub = data.substring(4).toInt(16)
            ByteBuffer.allocate(4 + 2 + guid.size + 2 + 10 + 4 + 4).also {
                it.putInt(0)
                it.putShort(guid.size.toShort())
                it.put(guid)
                it.putShort(version.length.toShort())
                it.put(version.toByteArray())
                it.putInt(sub)
                it.putInt(0)
            }.array()
        }
        "v3" -> {
            val phone = fetchOrThrow("phone").toByteArray()
            ByteBuffer.allocate(phone.size + 2 + 2 + version.length + 2).also {
                it.put(phone)
                it.putShort(0)
                it.putShort(version.length.toShort())
                it.put(version.toByteArray())
                it.putShort(0)
            }.array()
        }
        "v4" -> EMPTY_BYTE_ARRAY
        else -> EMPTY_BYTE_ARRAY
    }
}

private suspend fun initSigner(): Boolean {
    if (!isMsfServiceAlive()) return false
    val binder = ShamrockIpc.get(ShamrockIpc.IPC_QSIGN) ?: return false
    signer = IQSigner.Stub.asInterface(binder)
    binder.linkToDeath({ signer = null }, 0)
    return true
}

private suspend fun initByteData(): Boolean {
    if (byteData != null && byteData?.asBinder()?.isBinderAlive == true) return true
    val binder = ShamrockIpc.get(ShamrockIpc.IPC_BYTEDATA) ?: return false
    byteData = IByteData.Stub.asInterface(binder)
    binder.linkToDeath({ byteData = null }, 0)
    return true
}

private suspend fun PipelineContext<Unit, ApplicationCall>.requestSign(
    cmd: String,
    uin: String,
    seq: Int,
    buffer: ByteArray,
) {
    if (!isMsfServiceAlive()) {
        QSignStats.recordSign(false)
        call.respond(APIResult<SignResponse>(-2, "MSF not started", null))
        return
    }
    if (signer == null || signer?.asBinder()?.isBinderAlive == false) {
        if (!initSigner()) {
            QSignStats.recordSign(false)
            respond(false, Status.InternalHandlerError)
            return
        }
    }

    val sign = withTimeoutOrNull(5000) {
        signer!!.sign(cmd, seq, uin, buffer)
    }
    if (sign == null) {
        QSignStats.recordSign(false)
        respond(false, Status.IAmTired)
        return
    }

    QSignStats.recordSign(true)
    val callbacks = sign.callbacks.map {
        SsoPacket(it.cmd, it.body.uppercase(Locale.ROOT), it.callbackId)
    }
    call.respond(
        APIResult(
            0, "success",
            SignResponse(
                token = sign.token.toHexString().uppercase(Locale.ROOT),
                extra = sign.extra.toHexString().uppercase(Locale.ROOT),
                sign = sign.sign.toHexString().uppercase(Locale.ROOT),
                o3did = sign.o3did.uppercase(Locale.ROOT),
                requestCallback = callbacks
            )
        )
    )
}

/** unidbg 兼容参数：接受但不在 Shamrock 真机路径中使用 */
private suspend fun PipelineContext<Unit, ApplicationCall>.readSignCompatParams() {
    fetchOrNull("ver")
    fetchOrNull("qua")
    fetchOrNull("qimei36")
    fetchOrNull("android_id")
    fetchOrNull("guid")
}

private fun readSignCompatParamsFrom(req: SignRequest) {
    req.ver
    req.qua
    req.qimei36
    req.android_id
    req.guid
}

private suspend fun PipelineContext<Unit, ApplicationCall>.requestSubmit(
    cmd: String,
    callbackId: Long,
    buffer: ByteArray
) {
    if (!isMsfServiceAlive()) {
        call.respond(APIResult<String>(-2, "MSF not started", null))
        return
    }
    if (signer == null || signer?.asBinder()?.isBinderAlive == false) {
        if (!initSigner()) {
            respond(false, Status.InternalHandlerError)
            return
        }
    }

    val ok = withTimeoutOrNull(5000) {
        signer!!.submit(cmd, callbackId, buffer)
    } ?: false

    if (ok) {
        call.respond(APIResult(0, "submit success", ""))
    } else {
        call.respond(APIResult<String>(-1, "submit failed (optional — only needed when requestCallback is non-empty)", null))
    }
}
