package moe.RinShiona.Shamrock.xposed.helper

/**
 * Fingerprints of this LSPosed module that QQ / QSec / Dtc may scan for.
 * Centralised so Java + native hide lists stay aligned.
 */
internal object ModuleHide {
    const val PACKAGE = "moe.RinShiona.Shamrock"

    val packageNames = listOf(
        PACKAGE,
        "moe.RinShiona.Shamrock.xposed",
        // Legacy / common hook companion apps on CN ROMs
        "moe.fuqiuluo.shamrock",
        "top.hookvip.pro",
        "me.simpleHook",
        "lozn.hookui",
        "com.flass.layoutinspect",
        "icu.nullptr.nativetest",
        "io.github.huskydg.memorydetector",
        "com.vring.hiddenapp",
        "de.robv.android.xposed.installer",
        "org.lsposed.manager",
        "org.lsposed.lspatch",
    )

    /** Substrings matched against filesystem paths, maps lines, APK paths, etc. */
    val pathKeywords = listOf(
        PACKAGE,
        "RinShiona/Shamrock",
        "RinShiona.Shamrock",
        "fuqiuluo/shamrock",
        "shamrocknt",
        "libshamrock",
        "libshamrocknt",
        "xqbot.provider",
        "/data/data/$PACKAGE",
        "/data/user/0/$PACKAGE",
        "/data/adb/lspd",
        "/data/adb/modules",
        "/data/misc/lspd",
        "/data/misc/riru",
        "modules.list",
        "libriru",
        "liblspd",
        "libzygisk",
        "hookvip",
        "simpleHook",
        // libfekit smaps scanner (9.2.90)
        "anon:dalvik-DEX",
        "dalvik-DEX",
        "gdb-server",
        "LspModuleClassLoader",
        "InMemoryDexClassLoader",
        "de.robv.android.xposed",
        "org.lsposed",
    )

    /** Class / log / stack-trace keywords — only hide from QSec callers, not from ourselves. */
    val traceKeywords = listOf(
        PACKAGE,
        "RinShiona.Shamrock",
        "Shamrock.xposed",
        "libshamrocknt",
        "XposedBridge",
        "de.robv.android.xposed",
        "org.lsposed",
        "LspModuleClassLoader",
    )

    fun matchesPath(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        return pathKeywords.any { path.contains(it, ignoreCase = true) }
    }

    fun matchesPackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        return packageNames.any { pkg == it || pkg.startsWith("$it.") }
    }

    /** Drop lines that expose the module (for /proc/self/maps style buffers). */
    fun filterSensitiveLines(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder()
        text.lineSequence().forEach { line ->
            if (!lineContainsSensitive(line)) {
                out.append(line).append('\n')
            }
        }
        return out.toString()
    }

    fun lineContainsSensitive(line: String): Boolean {
        return pathKeywords.any { line.contains(it, ignoreCase = true) } ||
            traceKeywords.any { line.contains(it, ignoreCase = true) }
    }

    fun sanitizeValue(value: String?): String? {
        if (value.isNullOrEmpty()) return value
        if (!lineContainsSensitive(value)) return value
        return ""
    }

    /** True when the current thread looks like QQ security code doing a scan. */
    fun isSecurityScannerCaller(): Boolean {
        return Thread.currentThread().stackTrace.any { frame ->
            val cn = frame.className
            (cn.contains("tencent", ignoreCase = true) &&
                (cn.contains("qsec", ignoreCase = true) ||
                    cn.contains("qsecurity", ignoreCase = true) ||
                    cn.contains(".dt.", ignoreCase = true) ||
                    cn.contains("mobileqq.fe", ignoreCase = true) ||
                    cn.contains("qmethod", ignoreCase = true) ||
                    cn.contains("pandoraex", ignoreCase = true) ||
                    cn.contains("privacy", ignoreCase = true) ||
                    cn.contains("PackageInstallMonitor", ignoreCase = true) ||
                    cn.contains("DexMonitor", ignoreCase = true))) ||
                cn.contains("ArtTiHook", ignoreCase = true) ||
                cn.contains("GuardCheck", ignoreCase = true) ||
                cn.contains("GuardManager", ignoreCase = true) ||
                cn.contains("CodeCheck", ignoreCase = true)
        }
    }
}
