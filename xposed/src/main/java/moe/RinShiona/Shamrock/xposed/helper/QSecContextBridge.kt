package moe.RinShiona.Shamrock.xposed.helper

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import mqq.app.MobileQQ
import org.json.JSONObject
import java.io.File

internal object QSecContextBridge {

    @Volatile private var lastSetupQua: String = ""

    private val relayDir get() = File("/data/data/com.tencent.mobileqq/files/shamrock_ipc").also { it.mkdirs() }
    private val snapshotFile get() = File(relayDir, "qsec_snapshot.json")
    private val relayQuaFile get() = File(relayDir, "app_qua.txt")

    private val SNAPSHOT_FIELDS = listOf(
        "business_uin", "business_qua", "business_o3did", "business_guid", "business_q36", "business_seed",
    )

    fun installHooks(classLoader: ClassLoader) {
        runCatching {
            val cls = classLoader.loadClass("com.tencent.mobileqq.qsec.qsecurity.QSecConfig")
            XposedBridge.hookAllMethods(cls, "setupBusinessInfo", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val qua = param.args.getOrNull(6) as? String ?: return
                    if (qua.length < 10) return
                    lastSetupQua = qua
                    relayQuaFile.writeText(qua)
                    XposedBridge.log("Shamrock: setupBusinessInfo qua=${qua.take(48)} len=${qua.length}")
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    publishSnapshot(classLoader)
                }
            })
        }.onFailure { XposedBridge.log("Shamrock: QSec hook failed: ${it.message}") }
    }

    fun publishSnapshot(classLoader: ClassLoader) {
        publishRelayQua(classLoader)
        val obj = buildSnapshotJson(classLoader)
        if (obj.length() == 0) return
        snapshotFile.writeText(obj.toString())
        XposedBridge.log("Shamrock: QSec snapshot (qua=${obj.optString("business_qua").take(48)})")
    }

    fun publishRelayQua(classLoader: ClassLoader) {
        val qua = listOfNotNull(
            lastSetupQua.takeIf { it.length >= 10 },
            readFieldRaw(classLoader, "business_qua"),
            resolveQuaFromApp(classLoader),
            buildFallbackQua(),
        ).firstOrNull { SignCore.isSignAttemptQua(it) } ?: return
        relayQuaFile.writeText(qua)
    }

    fun readRelayQua(): String? =
        runCatching { relayQuaFile.takeIf { it.exists() }?.readText()?.trim() }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun buildSnapshotJson(classLoader: ClassLoader): JSONObject {
        val obj = JSONObject()
        for (field in SNAPSHOT_FIELDS) readFieldRaw(classLoader, field)?.let { obj.put(field, it) }
        val snapQua = obj.optString("business_qua")
        if (!SignCore.isSignAttemptQua(snapQua)) {
            readRelayQua()?.let { obj.put("business_qua", it) }
        }
        if (!SignCore.isSignAttemptQua(obj.optString("business_qua"))) {
            resolveQuaFromApp(classLoader)?.let { obj.put("business_qua", it) }
        }
        if (!obj.has("business_uin") || obj.optString("business_uin").isBlank()) {
            SignResultHelper.resolveUin(classLoader, "").takeIf { it.isNotBlank() && it != "0" }
                ?.let { obj.put("business_uin", it) }
        }
        return obj
    }

    fun forceApplyQua(classLoader: ClassLoader, qua: String) {
        if (!SignCore.isSignAttemptQua(qua)) return
        lastSetupQua = qua
        applyField(classLoader, "business_qua", qua)
        relayQuaFile.writeText(qua)
    }

    fun applySnapshot(classLoader: ClassLoader, requestQua: String? = null) {
        requestQua?.takeIf { SignCore.isSignAttemptQua(it) }?.let { forceApplyQua(classLoader, it) }
        runCatching { JSONObject(snapshotFile.readText()) }.getOrNull()?.let { obj ->
            for (field in SNAPSHOT_FIELDS) {
                val value = obj.optString(field).takeIf { it.isNotBlank() } ?: continue
                if (field == "business_qua" && !SignCore.isSignAttemptQua(value)) continue
                applyField(classLoader, field, value)
            }
        }
        resolveQua(classLoader, requestQua).takeIf { SignCore.isSignAttemptQua(it) }
            ?.let { forceApplyQua(classLoader, it) }
    }

    fun resolveQua(classLoader: ClassLoader, requestQua: String? = null): String {
        requestQua?.takeIf { SignCore.isSignAttemptQua(it) }?.let { return it }
        lastSetupQua.takeIf { SignCore.isSignAttemptQua(it) }?.let { return it }
        readRelayQua()?.takeIf { SignCore.isSignAttemptQua(it) }?.let { return it }
        readFieldRaw(classLoader, "business_qua")?.takeIf { SignCore.isSignAttemptQua(it) }?.let { return it }
        runCatching {
            snapshotFile.takeIf { it.exists() }?.let {
                JSONObject(it.readText()).optString("business_qua").takeIf { q -> SignCore.isSignAttemptQua(q) }
            }
        }.getOrNull()?.let { return it }
        resolveQuaFromApp(classLoader)?.takeIf { SignCore.isSignAttemptQua(it) }?.let { return it }
        return SignCore.resolveFallbackQua(classLoader)
    }

    fun readField(classLoader: ClassLoader, name: String): String? {
        readFieldRaw(classLoader, name)?.let { return it }
        return runCatching {
            snapshotFile.takeIf { it.exists() }?.let {
                JSONObject(it.readText()).optString(name).takeIf { v -> v.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun readFieldRaw(classLoader: ClassLoader, name: String): String? =
        SignResultHelper.readQSecConfigField(classLoader, name)?.takeIf { it.isNotBlank() }

    private fun applyField(classLoader: ClassLoader, name: String, value: String): Boolean =
        runCatching {
            classLoader.loadClass("com.tencent.mobileqq.qsec.qsecurity.QSecConfig")
                .getField(name).set(null, value)
            true
        }.getOrDefault(false)

    fun resolveQuaFromAppPublic(classLoader: ClassLoader): String? = resolveQuaFromApp(classLoader)

    private fun resolveQuaFromApp(classLoader: ClassLoader): String? {
        readFieldRaw(classLoader, "business_qua")?.takeIf { it.isNotBlank() }?.let { return it }
        runCatching { MobileQQ.getContext().getQua().takeIf { it.isNotBlank() }?.let { return it } }
        runCatching {
            val mq = MobileQQ.getMobileQQ()
            (mq.javaClass.getMethod("getQua").invoke(mq) as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        for (clsName in listOf(
            "com.tencent.common.config.AppSetting",
            "com.tencent.mobileqq.app.BusinessInfoConfig",
        )) {
            runCatching {
                val cls = classLoader.loadClass(clsName)
                (cls.getMethod("getQua").invoke(null) as? String)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    private fun buildFallbackQua(): String? = QuaBootstrap.buildFromInstalledPackage()

    fun initFeKit(classLoader: ClassLoader, qua: String) {
        if (!SignCore.isSignAttemptQua(qua)) return
        val ctx = MobileQQ.getContext()
        runCatching {
            val feKit = classLoader.loadClass("com.tencent.mobileqq.fe.FEKit")
            val inst = feKit.getMethod("getInstance").invoke(null) ?: return@runCatching
            feKit.getMethod(
                "init", Context::class.java, String::class.java, String::class.java,
                String::class.java, String::class.java, String::class.java,
            ).invoke(
                inst, ctx,
                SignResultHelper.resolveUin(classLoader, ""),
                readField(classLoader, "business_guid").orEmpty(),
                readField(classLoader, "business_o3did").orEmpty(),
                readField(classLoader, "business_q36").orEmpty(),
                qua,
            )
        }.onFailure { XposedBridge.log("[QSecContextBridge] FEKit.init failed: ${it.message}") }
    }

    fun startMainPublisher(classLoader: ClassLoader) {
        installHooks(classLoader)
        startPeriodicPublisher(classLoader)
    }

    fun startMsfPublisher(classLoader: ClassLoader) {
        installHooks(classLoader)
        startPeriodicPublisher(classLoader)
    }

    private fun startPeriodicPublisher(classLoader: ClassLoader) {
        Thread({
            while (true) {
                runCatching { publishSnapshot(classLoader) }
                Thread.sleep(2000)
            }
        }, "Shamrock-QSecPublisher").apply { isDaemon = true; start() }
    }
}
