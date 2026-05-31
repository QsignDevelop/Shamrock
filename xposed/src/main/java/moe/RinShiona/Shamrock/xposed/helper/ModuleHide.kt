package moe.RinShiona.Shamrock.xposed.helper

/**
 * Fingerprints of this LSPosed module that QQ / QSec / Dtc may scan for.
 * Centralised so Java + native hide lists stay aligned.
 *
 * Public package id is [PACKAGE] (CherryPop). Legacy Shamrock id remains in
 * hide lists because QQ 9.2.90 blacklists the old name.
 */
internal object ModuleHide {
    const val PACKAGE = "moe.RinShiona.CherryPop"
    const val LEGACY_PACKAGE = "moe.RinShiona.Shamrock"
    const val DISPLAY_NAME = "CherryPop"
    const val NATIVE_LIB = "cherrypopnt"
    const val LEGACY_NATIVE_LIB = "shamrocknt"

    /** IPC / dynamic broadcast actions (must match App side). */
    const val ACTION_XQBOT_DYNAMIC = "$PACKAGE.xqbot.dynamic"
    const val ACTION_MSF_DYNAMIC = "$PACKAGE.msf.dynamic"

    /** Root managers, hook tools, auto-click / screen automation apps QQ flags as illegal. */
    val illegalAppPackages = listOf(
        PACKAGE,
        LEGACY_PACKAGE,
        "$PACKAGE.xposed",
        "$LEGACY_PACKAGE.xposed",
        // Hook / module companions
        "moe.fuqiuluo.shamrock",
        "de.robv.android.xposed.installer",
        "org.lsposed.manager",
        "org.lsposed.lspatch",
        "org.meowcat.edxposed.manager",
        "com.solohsu.android.edxp.manager",
        "top.hookvip.pro",
        "me.simpleHook",
        "lozn.hookui",
        "com.flass.layoutinspect",
        "icu.nullptr.nativetest",
        "io.github.huskydg.memorydetector",
        "com.vring.hiddenapp",
        // MT 管理器 / 逆向常用文件管理器
        "bin.mt.plus",
        "bin.mt.plus.canary",
        "bin.mt.termex",
        "com.mt.filemanager",
        // Root / su managers
        "com.topjohnwu.magisk",
        "io.github.huskydg.magisk",
        "com.kingroot.kinguser",
        "com.kingo.root",
        "com.devadvance.rootcloak",
        "com.formyhm.hideroot",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.noshufou.android.su",
        "com.noshufou.android.su.elite",
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "com.ryandev.hidesu",
        "me.weishu.kernelsu",
        "me.bmax.apatch",
        "com.saurik.substrate",
        // Screen auto-click / automation (模拟点击)
        "com.cyjh.mobileanjian",
        "com.cyjh.mobileanjianenhanced",
        "com.cyjh.gundam",
        "org.autojs.autojs",
        "org.autojs.autojspro",
        "com.stardust.autojs",
        "com.stardust.scriptdroid",
        "com.zidongdianji",
        "com.xwtec.autoclick",
        "com.autoclicker.clicker",
        "com.kongzue.autoclick",
        "com.xinchuan.autoclick",
        "com.dianjiqi.app",
        "com.jumobile.multiapp",
        "com.lbe.parallel.intl",
        "com.excelliance.multiaccounts",
        // Game assistants / macros often flagged
        "com.tutuapp.tutuhelper",
        "com.cheatengine.cegui",
    )

    val packageNames: List<String> get() = illegalAppPackages

    /** Bootloader / verified-boot props — spoof locked when QSec reads them. */
    private val bootloaderSafeProps = mapOf(
        "ro.boot.flash.locked" to "1",
        "ro.boot.verifiedbootstate" to "green",
        "ro.boot.vbmeta.device_state" to "locked",
        "ro.boot.warranty_bit" to "0",
        "ro.warranty_bit" to "0",
        "ro.boot.secureboot" to "1",
        "ro.secureboot.lockstate" to "locked",
        "ro.boot.secboot" to "enabled",
        "ro.bootloader.locked" to "1",
        "ro.bootloader_unlocked" to "0",
        "sys.oem_unlock_allowed" to "0",
        "oem_unlock_allowed" to "0",
        "ro.oem_unlock_supported" to "0",
        "ro.boot.avb_version" to "1.1",
        "ro.boot.keymaster" to "1",
        "ro.boot.veritymode" to "enforcing",
    )

    /** Substrings matched against filesystem paths, maps lines, APK paths, etc. */
    val pathKeywords = listOf(
        PACKAGE,
        LEGACY_PACKAGE,
        "RinShiona/CherryPop",
        "RinShiona.Shamrock",
        "RinShiona.CherryPop",
        "CherryPop",
        "cherrypopnt",
        "libcherrypopnt",
        "fuqiuluo/shamrock",
        "shamrocknt",
        "Shamrock",
        "shamrock",
        "libshamrock",
        "libshamrocknt",
        "xqbot.provider",
        "/data/data/$PACKAGE",
        "/data/user/0/$PACKAGE",
        "/data/data/$LEGACY_PACKAGE",
        "/data/user/0/$LEGACY_PACKAGE",
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
        "bin.mt.plus",
        "bin/mt/plus",
        "MT管理器",
        "mobileanjian",
        "autojs",
        "autoclick",
        "zidongdianji",
        "anon:dalvik-DEX",
        "dalvik-DEX",
        "gdb-server",
        "LspModuleClassLoader",
        "InMemoryDexClassLoader",
        "de.robv.android.xposed",
        "org.lsposed",
        "/system/xbin/su",
        "/system/bin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/system/app/SuperSU",
        "/system/app/VSuperSU",
        "/data/adb/magisk",
        "/sbin/.magisk",
        "/.magisk",
        "magiskpolicy",
        "magiskhide",
        "zygisk",
        "kernelsu",
        "apatch",
    )

    val traceKeywords = listOf(
        PACKAGE,
        LEGACY_PACKAGE,
        "RinShiona.Shamrock",
        "RinShiona.CherryPop",
        "Shamrock.xposed",
        "CherryPop",
        "libshamrocknt",
        "libcherrypopnt",
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

    fun sanitizeBootloaderProp(key: String?, raw: String?): String? {
        if (key.isNullOrEmpty()) return sanitizeValue(raw)
        bootloaderSafeProps[key]?.let { return it }
        val k = key.lowercase()
        if (k.contains("flash.locked") || k.contains("verifiedboot") ||
            k.contains("vbmeta") || k.contains("warranty") ||
            k.contains("bootloader") || k.contains("secboot") ||
            k.contains("oem_unlock")
        ) {
            return when {
                k.contains("state") && raw?.contains("orange", ignoreCase = true) == true -> "green"
                k.contains("locked") || k.contains("lockstate") -> "1"
                k.contains("unlock") -> "0"
                k.contains("warranty") -> "0"
                else -> "1"
            }
        }
        return sanitizeValue(raw)
    }

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

    private val callerCheckDepth = ThreadLocal.withInitial { 0 }

    fun isSecurityScannerCaller(): Boolean {
        // HookEvasion scrubs Thread.getStackTrace; re-entering here would recurse forever.
        if (callerCheckDepth.get() > 0) return false
        callerCheckDepth.set(callerCheckDepth.get() + 1)
        return try {
            Thread.currentThread().stackTrace.any { frame ->
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
                    cn.contains("CodeCheck", ignoreCase = true) ||
                    cn.contains("mobileqq.sign", ignoreCase = true) ||
                    cn.contains("fekit", ignoreCase = true) ||
                    cn.contains("KernelService", ignoreCase = true)
            }
        } finally {
            callerCheckDepth.set((callerCheckDepth.get() - 1).coerceAtLeast(0))
        }
    }
}
