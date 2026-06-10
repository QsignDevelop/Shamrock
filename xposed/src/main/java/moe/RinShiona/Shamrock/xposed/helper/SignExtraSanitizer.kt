package moe.RinShiona.Shamrock.xposed.helper

/**
 * 已禁用：不再改写 sign/extra 字节。
 * 过环境检测依赖 [EarlyAntiDetection] / [QQ9290DetectionHooks] / native maps-probe hook。
 */
internal object SignExtraSanitizer {

    fun sanitizeBytes(input: ByteArray?): ByteArray? = input

    fun sanitizeSignResult(result: Any?): Any? = result

    fun stats(): String = "SignExtraSanitizer{disabled=hook_path}"
}
