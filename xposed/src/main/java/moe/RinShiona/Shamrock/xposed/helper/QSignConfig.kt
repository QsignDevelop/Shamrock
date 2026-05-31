package moe.RinShiona.Shamrock.xposed.helper

internal object QSignConfig {
    const val MODE_MSF = "msf"
    const val MODE_DIRECT = "direct"

    @Volatile var signMode: String = MODE_MSF
    @Volatile var signTraceEnabled: Boolean = false

    fun useDirectGetSign(): Boolean = signMode == MODE_DIRECT
    fun useMsfSign(): Boolean = !useDirectGetSign()

    fun apply(signMode: String, signTraceEnabled: Boolean) {
        this.signMode = signMode
        this.signTraceEnabled = signTraceEnabled
    }
}
