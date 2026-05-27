package moe.RinShiona.Shamrock.remote.entries

import kotlinx.serialization.Serializable

@Serializable
data class ErrorCatch(
    var url: String,
    var error: String
)
