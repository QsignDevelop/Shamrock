package moe.RinShiona.Shamrock.remote.api

import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.util.concurrent.atomic.AtomicLong

/** unidbg-compatible API envelope: `{ code, msg, data }` */
@Serializable
data class APIResult<T>(
    val code: Int,
    val msg: String = "",
    @Contextual
    val data: T? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SsoPacket(
    val cmd: String,
    val body: String,
    @JsonNames("callback_id")
    val callbackId: Long
)

@Serializable
data class SignResponse(
    val token: String,
    val extra: String,
    val sign: String,
    val o3did: String,
    val requestCallback: List<SsoPacket>
)

@Serializable
data class SignRequest(
    val cmd: String,
    val buffer: String,
    val uin: String,
    val seq: String,
    val qua: String? = null,
    val qimei36: String? = null,
    val android_id: String? = null,
    val guid: String? = null,
    val ver: String? = null
)

@Serializable
data class PrewarmResult(
    val prewarmed: Boolean,
    val poolSize: Int
)

@Serializable
data class HomeInfo(
    @SerialName("Success")
    val success: Long,
    @SerialName("Error")
    val error: Long,
    @SerialName("Sign")
    val sign: Long,
    val energy: Long,
    @SerialName("All")
    val all: Long
)

@Serializable
data class QSignHome(
    val code: Int,
    val ServerVersion: String,
    val Title: String,
    val msg: List<String>,
    val Info: HomeInfo,
    val VersionList: List<String>
)

object QSignStats {
    private val signOk = AtomicLong()
    private val signErr = AtomicLong()
    private val energyOk = AtomicLong()
    private val energyErr = AtomicLong()
    private val homeOk = AtomicLong()

    fun recordSign(ok: Boolean) {
        if (ok) signOk.incrementAndGet() else signErr.incrementAndGet()
    }

    fun recordEnergy(ok: Boolean) {
        if (ok) energyOk.incrementAndGet() else energyErr.incrementAndGet()
    }

    fun recordHome() {
        homeOk.incrementAndGet()
    }

    fun homeInfo(): HomeInfo {
        val sOk = signOk.get()
        val sErr = signErr.get()
        val eOk = energyOk.get()
        val eErr = energyErr.get()
        val success = sOk + eOk + homeOk.get()
        val error = sErr + eErr
        return HomeInfo(
            success = success,
            error = error,
            sign = sOk + sErr,
            energy = eOk + eErr,
            all = success + error
        )
    }
}
