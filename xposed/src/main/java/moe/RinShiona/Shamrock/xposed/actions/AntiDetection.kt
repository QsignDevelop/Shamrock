@file:Suppress("UNCHECKED_CAST", "NAME_SHADOWING")

package moe.RinShiona.Shamrock.xposed.actions

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.RinShiona.Shamrock.xposed.helper.ModuleHide
import moe.RinShiona.Shamrock.xposed.helper.ModuleHideHooks
import moe.RinShiona.Shamrock.xposed.helper.PackageInstallMonitorHooks
import moe.RinShiona.Shamrock.xposed.helper.PandoraHideHooks
import moe.RinShiona.Shamrock.xposed.AntiDetectionConfig
import mqq.app.MobileQQ
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shamrock anti-detection module.
 *
 * Rewritten for QQ 9.2.90 NT.
 *
 *   - All log messages are ASCII to avoid encoding issues.
 *   - Each phase is self-contained, fails independently.
 *   - Adds 9.2.90 specific bypasses:
 *       * ArtTiHookTask probe (QSec.detectMethod)
 *       * Xposed class enumeration via ClassLoader.loadClass
 *       * SystemProperties (xposed*, ro.debuggable, ro.secure)
 *       * /proc maps file reads (filter Xposed/LSPosed strings)
 *       * PackageManager lookups for known root/Magisk apps
 *       * FEKit.getSign fall-through to remote qsign server
 *
 * The hooks are deliberately kept in Java/Xposed land. Native hardening lives
 * in xposed/src/main/cpp/xposed.cpp.
 */
@SuppressLint("StaticFieldLeak")
internal class AntiDetection : IAction {

    companion object {
        private const val TAG = "AntiDetection"
        private val isInitialized = AtomicBoolean(false)

        private val XPOSED_KEYWORDS = listOf(
            "xposed", "Xposed", "XPOSED",
            "LSPosed", "lspx", "lsposed",
            "de.robv.android.xposed",
            "com.swift.internal",
            "org.lsposed", "moe.shizuku", "shizuku",
            "EdXposed", "edxposed", "TaiChi", "taichi"
        )

        private val MAGISK_KEYWORDS = listOf(
            "magisk", "Magisk", "MAGISK",
            "/sbin/.magisk", "/data/adb/magisk",
            "su.d", "magiskhide", "magiskpolicy",
            "MagiskHide"
        )

        private val DANGEROUS_APPS = listOf(
            "de.robv.android.xposed.installer",
            "org.lsposed.manager", "org.lsposed.lspatch",
            "org.meowcat.edxposed.manager",
            "com.solohsu.android.edxp.manager",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su",
            "com.noshufou.android.su.elite",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.topjohnwu.magisk",
            "com.ryandev.hidesu",
            "com.tutuapp.tutuhelper",
            "top.hookvip.pro",
            "me.simpleHook",
            "moe.fuqiuluo.shamrock",
        ) + ModuleHide.packageNames

        private val DANGEROUS_PATHS = listOf(
            "/data/user_de/0/de.robv.android.xposed.installer",
            "/data/adb",
            "/magisk",
            "/.magisk",
            "/system/app/SuperSU",
            "/system/app/VSuperSU",
            "/data/local/xposed",
            "/system/xposed",
            "/data/data/org.lsposed.manager",
            "/data/data/${ModuleHide.PACKAGE}",
            "/data/user/0/${ModuleHide.PACKAGE}",
        )

        // QQ 9.2.90 specific anti-detection probe classes
        private val QQ_ANTI_DETECTION_CLASSES = listOf(
            "com.tencent.mobileqq.qsec.qsecurity.QSec",
            "com.tencent.mobileqq.dt.app.Dtc"
        )
    }

    private var feKitInstance: Any? = null
    private var qsecInstance: Any? = null

    override fun invoke(ctx: Context) {
        if (!AntiDetectionConfig.enabled) {
            log("AntiDetection disabled by config")
            return
        }
        if (!isInitialized.compareAndSet(false, true)) {
            log("AntiDetection already initialized")
            return
        }

        log("==========================================")
        log("Shamrock Anti-Detection (9.2.90 NT ready)")
        log("==========================================")

        // Each phase is independent. We deliberately try all of them even
        // if some fail ? anti-detection is layered.
        runPhase("CoreDetection", ::hookCoreDetection)
        if (!EarlyAntiDetection.fullyInstalled) {
            runPhase("QQ9290PrivacyLayer", ::hookQQ9290PrivacyLayer)
            runPhase("ModuleHide", {
                val loader = ctx.classLoader ?: MobileQQ.getContext()?.classLoader
                if (loader != null) ModuleHideHooks.installEarly(loader)
                ModuleHideHooks.installMapsFilter()
            })
        } else {
            log("skip QQ9290PrivacyLayer/ModuleHide — EarlyAntiDetection deferred layer already installed")
        }
        if (AntiDetectionConfig.hideFiles)       runPhase("FileDetection",    ::hookFileDetection)
        if (AntiDetectionConfig.hideProps)       runPhase("SystemProperties", ::hookSystemProperties)
        if (AntiDetectionConfig.hideProc || AntiDetectionConfig.hideNative)
                                                  runPhase("ProcDetection",    ::hookProcDetection)
        if (AntiDetectionConfig.hideApk || AntiDetectionConfig.hideSignature)
                                                  runPhase("PackageDetection", ::hookPackageDetection)
        if (AntiDetectionConfig.hideMagisk)      runPhase("MagiskDetection",  ::hookMagiskDetection)
        if (AntiDetectionConfig.hideSignature)   runPhase("SignatureVerify",  ::hookSignatureVerification)
        if (AntiDetectionConfig.hookSign)        runPhase("FEKitSignHook",    { hookFEKitSign(ctx) })
        if (AntiDetectionConfig.hideNetwork)     runPhase("NetworkDetection", ::hookNetworkDetection)
        if (AntiDetectionConfig.hideLSPosed)     runPhase("LSPosedHide",      ::hookLSPosedSpecific)

        // Backup QSec.detectMethod + Build.TAGS only — do not skip NtTask (breaks startup).
        runPhase("ArtTiHookBypass", { hookArtTiHookBypass(ctx) })

        log("AntiDetection initialization complete")
    }

    private inline fun runPhase(name: String, block: () -> Unit) {
        try {
            block()
            log("[Phase $name] OK")
        } catch (e: Throwable) {
            log("[Phase $name] FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun log(msg: String) {
        if (AntiDetectionConfig.debugLog) {
            XposedBridge.log("[$TAG] $msg")
        }
    }

    /** PackageInstallMonitorKt + DexMonitor + StackTrace — backup if EarlyAntiDetection missed. */
    private fun hookQQ9290PrivacyLayer() {
        val loader = try {
            MobileQQ.getContext()?.classLoader
        } catch (_: Throwable) {
            null
        } ?: return
        PackageInstallMonitorHooks.install(loader)
        PandoraHideHooks.install(loader)
        StackTraceHideHooks.install()
    }

    // ============ Phase 1: Core (Class.forName / loadClass filtering) ============
    private fun hookCoreDetection() {
        // Class.forName(String, boolean, ClassLoader)
        XposedHelpers.findAndHookMethod(
            Class::class.java, "forName",
            String::class.java, Boolean::class.javaPrimitiveType, ClassLoader::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!AntiDetectionConfig.hideXposed) return
                    val name = param.args[0] as? String ?: return
                    if (XPOSED_KEYWORDS.any { name.contains(it, ignoreCase = true) }) {
                        param.throwable = ClassNotFoundException(name)
                        return
                    }
                    // Hide our module classes from QSec scanners only (not from Shamrock itself).
                    if (ModuleHide.isSecurityScannerCaller() &&
                        ModuleHide.traceKeywords.any { name.contains(it, ignoreCase = true) }
                    ) {
                        param.throwable = ClassNotFoundException(name)
                    }
                }
            }
        )

        // ClassLoader.loadClass(String, boolean)
        try {
            XposedHelpers.findAndHookMethod(
                ClassLoader::class.java, "loadClass",
                String::class.java, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!AntiDetectionConfig.hideClassLoader) return
                        val name = param.args[0] as? String ?: return
                        if (XPOSED_KEYWORDS.any { name.contains(it, ignoreCase = true) }) {
                            param.throwable = ClassNotFoundException(name)
                            return
                        }
                        if (ModuleHide.isSecurityScannerCaller() &&
                            ModuleHide.traceKeywords.any { name.contains(it, ignoreCase = true) }
                        ) {
                            param.throwable = ClassNotFoundException(name)
                        }
                    }
                }
            )
        } catch (_: NoSuchMethodError) {
            // Older Android versions: loadClass(String) only
        }
    }

    // ============ Phase 2: File detection ============
    private fun hookFileDetection() {
        // File.exists()
        XposedBridge.hookAllMethods(File::class.java, "exists", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val file = param.thisObject as? File ?: return
                val path = try { file.absolutePath } catch (_: Throwable) { return } ?: return
                if (shouldHide(path)) param.result = false
            }
        })

        // File.canRead()
        XposedBridge.hookAllMethods(File::class.java, "canRead", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val file = param.thisObject as? File ?: return
                val path = try { file.absolutePath } catch (_: Throwable) { return } ?: return
                if (shouldHide(path)) param.result = false
            }
        })

        // File.isFile()
        XposedBridge.hookAllMethods(File::class.java, "isFile", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val file = param.thisObject as? File ?: return
                val path = try { file.absolutePath } catch (_: Throwable) { return } ?: return
                if (shouldHide(path)) param.result = false
            }
        })

        // File.isDirectory()
        XposedBridge.hookAllMethods(File::class.java, "isDirectory", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val file = param.thisObject as? File ?: return
                val path = try { file.absolutePath } catch (_: Throwable) { return } ?: return
                if (shouldHide(path)) param.result = false
            }
        })
    }

    private fun shouldHide(path: String): Boolean {
        if (ModuleHide.matchesPath(path)) return true
        if (DANGEROUS_PATHS.any { path.startsWith(it) }) return true
        if (XPOSED_KEYWORDS.any { path.contains(it, ignoreCase = true) }) return true
        if (AntiDetectionConfig.hideMagisk && MAGISK_KEYWORDS.any { path.contains(it, ignoreCase = true) }) return true
        return false
    }

    // ============ Phase 3: System properties ============
    private fun hookSystemProperties() {
        val spClass = try {
            Class.forName("android.os.SystemProperties")
        } catch (_: Throwable) {
            return
        }

        // SystemProperties.get(String)
        XposedBridge.hookAllMethods(spClass, "get", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val key = param.args.getOrNull(0) as? String ?: return
                handleSystemPropertyGet(key, param)
            }
        })
    }

    private fun handleSystemPropertyGet(key: String, param: XC_MethodHook.MethodHookParam) {
        // Xposed-specific properties
        if (AntiDetectionConfig.hideXposed) {
            if (key.startsWith("xposed") || key == "in_xposed_mode" ||
                key.contains("Xposed", ignoreCase = true)) {
                param.result = ""
                return
            }
        }
        if (AntiDetectionConfig.hideSecureProps) {
            when (key) {
                "ro.debuggable" -> { param.result = "0"; return }
                "ro.secure" -> { param.result = "1"; return }
                "ro.build.tags" -> {
                    val orig = (param.result as? String) ?: ""
                    if (orig.contains("test-keys")) param.result = "release-keys"
                }
                "ro.build.type" -> {
                    val orig = (param.result as? String) ?: ""
                    if (orig == "userdebug") param.result = "user"
                }
            }
        }
    }

    // ============ Phase 4: /proc detection ============
    private fun hookProcDetection() {
        // Block Runtime.exec for dangerous commands
        XposedBridge.hookAllMethods(Runtime::class.java, "exec", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cmd = when (val a = param.args[0]) {
                    is String -> a
                    is Array<*> -> a.joinToString(" ")
                    else -> return
                }
                if (isDangerousCmd(cmd)) {
                    param.throwable = SecurityException("permission denied")
                }
            }
        })
    }

    private fun isDangerousCmd(cmd: String): Boolean {
        val c = cmd.lowercase()
        return c.contains("which su") || c == "su" || c.startsWith("su ") ||
               c.contains("getprop") || c.contains("magisk") ||
               c.contains("xposed") || c.contains("busybox")
    }

    // ============ Phase 5: PackageManager ============
    private fun hookPackageDetection() {
        val pmIface = try {
            Class.forName("android.content.pm.PackageManager")
        } catch (_: Throwable) {
            return
        }

        XposedBridge.hookAllMethods(pmIface, "getPackageInfo", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = param.args.getOrNull(0) as? String ?: return
                if (DANGEROUS_APPS.any { name == it || name.contains(it) } ||
                    ModuleHide.matchesPackage(name)
                ) {
                    param.throwable = android.content.pm.PackageManager.NameNotFoundException(name)
                }
            }
        })

        XposedBridge.hookAllMethods(pmIface, "getApplicationInfo", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = param.args.getOrNull(0) as? String ?: return
                if (DANGEROUS_APPS.any { name == it || name.contains(it) } ||
                    ModuleHide.matchesPackage(name)
                ) {
                    param.throwable = android.content.pm.PackageManager.NameNotFoundException(name)
                }
            }
        })

        // getInstalledPackages ? filter out dangerous apps
        XposedBridge.hookAllMethods(pmIface, "getInstalledPackages", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val list = param.result as? List<*> ?: return
                val filtered = list.filter { item ->
                    val pkgField = try {
                        item?.javaClass?.getField("packageName")?.get(item) as? String
                    } catch (_: Throwable) { null }
                    pkgField == null || (DANGEROUS_APPS.none { dangerous ->
                        pkgField.contains(dangerous)
                    } && !ModuleHide.matchesPackage(pkgField))
                }
                if (filtered.size != list.size) {
                    param.result = filtered
                }
            }
        })

        // getInstalledApplications — same idea
        XposedBridge.hookAllMethods(pmIface, "getInstalledApplications", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val list = param.result as? List<*> ?: return
                val filtered = list.filter { item ->
                    val pkgField = try {
                        item?.javaClass?.getField("packageName")?.get(item) as? String
                    } catch (_: Throwable) { null }
                    pkgField == null || (DANGEROUS_APPS.none { dangerous ->
                        pkgField.contains(dangerous)
                    } && !ModuleHide.matchesPackage(pkgField))
                }
                if (filtered.size != list.size) {
                    param.result = filtered
                }
            }
        })

        XposedBridge.hookAllMethods(pmIface, "queryIntentActivities", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val list = param.result as? List<*> ?: return
                val filtered = list.filter { item ->
                    val pkg = try {
                        val ai = item?.javaClass?.getField("activityInfo")?.get(item) ?: return@filter true
                        ai.javaClass.getField("packageName").get(ai) as? String
                    } catch (_: Throwable) { null }
                    pkg == null || (!ModuleHide.matchesPackage(pkg) &&
                        DANGEROUS_APPS.none { pkg == it || pkg.contains(it) })
                }
                if (filtered.size != list.size) param.result = filtered
            }
        })
    }

    // ============ Phase 7: Magisk specific ============
    private fun hookMagiskDetection() {
        // Block System.loadLibrary("magisk*")
        XposedBridge.hookAllMethods(System::class.java, "loadLibrary", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = param.args.getOrNull(0) as? String ?: return
                if (MAGISK_KEYWORDS.any { name.contains(it, ignoreCase = true) }) {
                    param.throwable = UnsatisfiedLinkError(name)
                }
            }
        })
    }

    // ============ Phase 9: Signature verification (currently observer-only) ============
    private fun hookSignatureVerification() {
        // No-op: we don't actually alter signatures ? that would break QQ's own
        // self-check. The phase exists to gate other behavior via the config flag.
    }

    // ============ Phase 10: FEKit sign hook (the big one) ============
    private fun hookFEKitSign(ctx: Context) {
        val qqLoader = try {
            MobileQQ.getContext()?.classLoader
        } catch (_: Throwable) {
            log("FEKit hook: cannot get QQ classloader")
            return
        } ?: return

        val feKitClass = try {
            qqLoader.loadClass("com.tencent.mobileqq.fe.FEKit")
        } catch (e: Throwable) {
            log("FEKit hook: FEKit class not found: ${e.message}")
            return
        }

        // Capture instance via getInstance()
        XposedBridge.hookAllMethods(feKitClass, "getInstance", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val inst = param.result ?: return
                if (feKitInstance != inst) {
                    feKitInstance = inst
                    log("FEKit instance captured")
                    hookFEKitInstanceSign(inst)
                }
            }
        })

        // Also catch via init(...)
        feKitClass.declaredMethods.filter { it.name == "init" }.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val inst = param.thisObject ?: return
                    if (feKitInstance != inst) {
                        feKitInstance = inst
                        log("FEKit instance captured via init()")
                        hookFEKitInstanceSign(inst)
                    }
                }
            })
        }
    }

    private fun hookFEKitInstanceSign(instance: Any) {
        try {
            // Hook ALL getSign overloads. 9.2.90 has both 3-arg (cmd, buf, seq)
            // and 4-arg (cmd, buf, seq, uin) versions. Older has just 3-arg.
            instance.javaClass.declaredMethods.filter { it.name == "getSign" }.forEach { m ->
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result
                        // If native call returned null or empty SignResult, fall through to remote.
                        if (AntiDetectionConfig.useRemoteQSign && resultLooksEmpty(result)) {
                            val args = param.args
                            val cmd = args.getOrNull(0) as? String ?: return
                            val buf = args.getOrNull(1) as? ByteArray ?: return
                            val seq = args.getOrNull(2) as? Int ?: 0
                            val uin = args.getOrNull(3) as? String ?: ""
                            val remote = fetchSignFromRemote(cmd, buf, seq, uin)
                            if (remote != null) {
                                param.result = remote
                                log("FEKit.getSign result replaced by remote qsign")
                            }
                        }
                    }
                })
            }
        } catch (e: Throwable) {
            log("hookFEKitInstanceSign error: ${e.message}")
        }
    }

    private fun resultLooksEmpty(result: Any?): Boolean {
        if (result == null) return true
        val cls = result.javaClass
        return try {
            val token = cls.getField("token").get(result) as? ByteArray
            val sign  = cls.getField("sign").get(result) as? ByteArray
            (token == null || token.isEmpty()) && (sign == null || sign.isEmpty())
        } catch (_: Throwable) {
            false
        }
    }

    private fun fetchSignFromRemote(cmd: String, buffer: ByteArray, seq: Int, uin: String): Any? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(
                "${AntiDetectionConfig.qsignServerUrl}/sign?" +
                "ver=1&cmd=${URLEncoder.encode(cmd, "UTF-8")}" +
                "&seq=$seq&uin=${URLEncoder.encode(uin, "UTF-8")}"
            )
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connectTimeout = 5000
                readTimeout = 5000
            }
            val body = "buffer=" + URLEncoder.encode(buffer.joinToString("") { "%02x".format(it) }, "UTF-8")
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            return parseRemoteSignResponse(response)
        } catch (e: Throwable) {
            log("remote qsign call failed: ${e.message}")
            return null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Parse `{"token":"hex","sign":"hex","extra":"hex"}` into a fresh
     * `com.tencent.mobileqq.sign.QQSecuritySign$SignResult` instance.
     *
     * We must use reflection here because `qqinterface` is currently disabled
     * as a compile dependency of `xposed`; the real class is supplied by QQ
     * itself at runtime via the host classloader.
     */
    private fun parseRemoteSignResponse(json: String): Any? {
        return try {
            val qqLoader = MobileQQ.getContext()?.classLoader ?: return null
            val srClass = qqLoader.loadClass("com.tencent.mobileqq.sign.QQSecuritySign\$SignResult")
            val instance = srClass.getDeclaredConstructor().newInstance()
            extractHex(json, "token")?.let { srClass.getField("token").set(instance, it) }
            extractHex(json, "sign")?.let  { srClass.getField("sign").set(instance, it) }
            extractHex(json, "extra")?.let { srClass.getField("extra").set(instance, it) }
            instance
        } catch (e: Throwable) {
            log("parse remote sign response failed: ${e.message}")
            null
        }
    }

    private fun extractHex(json: String, key: String): ByteArray? {
        val m = Regex(""""$key"\s*:\s*"([0-9a-fA-F]+)"""").find(json) ?: return null
        val hex = m.groupValues[1]
        if (hex.length % 2 != 0) return null
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    // ============ Phase 12: Network (proxy / Charles detection) ============
    private fun hookNetworkDetection() {
        XposedBridge.hookAllMethods(System::class.java, "getProperty", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val key = param.args.getOrNull(0) as? String ?: return
                if (key.contains("proxy", ignoreCase = true)) {
                    param.result = null
                }
            }
        })
    }

    // ============ Phase 13: LSPosed-specific ============
    private fun hookLSPosedSpecific() {
        // Defang LSPosedManager.getInstance() if it ever ends up loaded into QQ
        try {
            val cls = Class.forName("org.lsposed.lspd.LSPosedManager")
            XposedBridge.hookAllMethods(cls, "getInstance", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
            })
        } catch (_: Throwable) {}
    }

    // ============ NEW for 9.2.90: ArtTiHookTask + QSec.detectMethod bypass ============
    private fun hookArtTiHookBypass(ctx: Context) {
        val qqLoader = try { MobileQQ.getContext()?.classLoader } catch (_: Throwable) { return } ?: return

        // 1. QSec.detectMethod(String, String): Boolean
        //    Returns true when QSec finds a hook on the named method. We force false.
        try {
            val qsecClass = qqLoader.loadClass("com.tencent.mobileqq.qsec.qsecurity.QSec")
            val detectMethod: Method? = qsecClass.declaredMethods.firstOrNull {
                it.name == "detectMethod" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java
            }
            if (detectMethod != null) {
                XposedBridge.hookMethod(detectMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                })
                log("QSec.detectMethod bypass installed")
            }
        } catch (e: Throwable) {
            log("QSec.detectMethod bypass FAILED: ${e.message}")
        }

        // Build.TAGS / Build.FINGERPRINT — avoid "test-keys" tripping checks
        try {
            // Build is read at process start, so this only helps if QQ reads it lazily.
            val current = Build.TAGS ?: ""
            if (current.contains("test")) {
                XposedHelpers.setStaticObjectField(Build::class.java, "TAGS", "release-keys")
                log("Build.TAGS forced to release-keys")
            }
        } catch (_: Throwable) {}
    }
}
