package moe.RinShiona.Shamrock.xposed.helper

import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.io.File

internal object SignTrace {
    private val dir get() = File("/data/data/com.tencent.mobileqq/files/shamrock_ipc").also { it.mkdirs() }
    private val traceFile get() = File(dir, "sign_trace.json")

    @Volatile private var activeId: String = ""
    private val steps = ThreadLocal.withInitial { mutableListOf<String>() }

    fun isEnabled(): Boolean = QSignConfig.signTraceEnabled

    fun begin(id: String) {
        activeId = id
        steps.get().clear()
        step("begin", id)
    }

    fun attach(id: String) {
        activeId = id
    }

    fun pause() {}

    fun step(tag: String, detail: String = "") {
        if (!isEnabled()) return
        val line = "$tag${if (detail.isNotBlank()) ": $detail" else ""}"
        steps.get().add(line)
        XposedBridge.log("[SignTrace] $line")
    }

    fun end(ok: Boolean, err: String = "") {
        if (!isEnabled()) return
        val obj = JSONObject()
            .put("id", activeId)
            .put("ok", ok)
            .put("err", err)
            .put("steps", steps.get().joinToString(" | "))
            .put("ts", System.currentTimeMillis())
        runCatching { traceFile.writeText(obj.toString()) }
        steps.get().clear()
    }

    fun summary(): String = steps.get().joinToString(" | ")

    fun readLastForApi(): JSONObject =
        runCatching { JSONObject(traceFile.readText()) }.getOrDefault(JSONObject())
}
