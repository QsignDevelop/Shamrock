package moe.RinShiona.Shamrock.xposed.helper

import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Anti-hook-detection scrubbers for QQ NT 9.2.90.
 *
 * QQ probes for LSPosed/Xposed via many lateral channels even if QSec.detectMethod
 * returns false:
 *   - Stack trace walk: Throwable.getStackTrace() looking for "XposedBridge",
 *     "LspHooker_", "de.robv.android.xposed.*", "org.lsposed.lspd.*".
 *   - Reflection enumeration: Class.getDeclaredMethods() finding "LspHooker$..." methods
 *     attached to a hooked class.
 *   - Resource enumeration: ClassLoader.getResources("META-INF/xposed/...").
 *   - SystemProperties.get("ro.debuggable" / "ro.secure" / "init.svc.adbd").
 *   - Build.TAGS == "test-keys" (rooted-build heuristic).
 *   - Debug.isDebuggerConnected().
 *   - Runtime.exec("getprop ...") / ProcessBuilder.start() — dangerous shell probes.
 *
 * Each scrubber is gated on [ModuleHide.isSecurityScannerCaller] so we never lie
 * to Shamrock itself or to benign QQ paths.
 */
internal object HookEvasion {

    private val installed = AtomicBoolean(false)

    private val HOOK_FRAME_KEYWORDS = listOf(
        "de.robv.android.xposed",
        "XposedBridge",
        "XposedHelpers",
        "LspHooker",
        "LSPHooker",
        "org.lsposed",
        "org.lsposd",
        "moe.RinShiona.Shamrock",
        "moe.fuqiuluo.shamrock",
        "Shamrock.xposed",
        "libshamrocknt",
        "\$XC_MethodHook",
    )

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return

        scrubStackTraces()
        scrubReflectionEnumeration()
        scrubResourceEnumeration(classLoader)
        scrubBuildTags()
        scrubSystemProperties(classLoader)
        scrubDebugProbes()
        scrubShellProbes()

        log("hook-evasion layer installed")
    }

    // ---------------- 1. Stack traces ----------------

    private fun scrubStackTraces() {
        val filter = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!ModuleHide.isSecurityScannerCaller()) return
                val frames = param.result as? Array<*> ?: return
                @Suppress("UNCHECKED_CAST")
                param.result = filterStackFrames(frames as Array<StackTraceElement?>)
            }
        }

        runCatching {
            XposedBridge.hookAllMethods(Throwable::class.java, "getStackTrace", filter)
        }
        // NOTE: deliberately NOT hooking Thread.getStackTrace — KillGuardHooks
        // relies on it to detect Shamrock-induced kills via stack walk. Hooking
        // it here would recursively filter our own frames out and break that
        // detection. QQ's hook-frame probes mostly go through Throwable anyway
        // (Log.getStackTraceString(new Throwable()) pattern in detection_scan).
        runCatching {
            XposedBridge.hookAllMethods(Thread::class.java, "getAllStackTraces", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!ModuleHide.isSecurityScannerCaller()) return
                    val map = param.result as? Map<*, *> ?: return
                    val out = HashMap<Any?, Any?>(map.size)
                    for ((k, v) in map) {
                        val frames = v as? Array<*> ?: continue
                        @Suppress("UNCHECKED_CAST")
                        out[k] = filterStackFrames(frames as Array<StackTraceElement?>)
                    }
                    param.result = out
                }
            })
        }
    }

    private fun filterStackFrames(frames: Array<StackTraceElement?>): Array<StackTraceElement> {
        return frames.asSequence()
            .filterNotNull()
            .filter { f -> HOOK_FRAME_KEYWORDS.none { kw -> f.className.contains(kw, ignoreCase = true) } }
            .toList()
            .toTypedArray()
    }

    // ---------------- 2. Reflection enumeration ----------------

    private fun scrubReflectionEnumeration() {
        val methodFilter = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!ModuleHide.isSecurityScannerCaller()) return
                val arr = param.result as? Array<*> ?: return
                @Suppress("UNCHECKED_CAST")
                param.result = (arr as Array<Method?>)
                    .filterNotNull()
                    .filter { m ->
                        val n = m.name
                        !n.contains("LspHooker", ignoreCase = true) &&
                            !n.contains("XposedBridge", ignoreCase = true) &&
                            !n.startsWith("\$\$Lambda") &&
                            !n.contains("xposed", ignoreCase = true)
                    }
                    .toTypedArray()
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(Class::class.java, "getDeclaredMethods", methodFilter)
        }
        runCatching {
            XposedBridge.hookAllMethods(Class::class.java, "getMethods", methodFilter)
        }
    }

    // ---------------- 3. Resource enumeration ----------------

    private fun scrubResourceEnumeration(classLoader: ClassLoader) {
        // ClassLoader.getResources(name): Enumeration<URL>
        runCatching {
            XposedBridge.hookAllMethods(ClassLoader::class.java, "getResources", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val name = param.args.getOrNull(0) as? String ?: return
                    if (!isSensitiveResource(name)) return
                    if (!ModuleHide.isSecurityScannerCaller()) return
                    param.result = java.util.Collections.emptyEnumeration<java.net.URL>()
                }
            })
        }
        runCatching {
            XposedBridge.hookAllMethods(ClassLoader::class.java, "getResource", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val name = param.args.getOrNull(0) as? String ?: return
                    if (!isSensitiveResource(name)) return
                    if (!ModuleHide.isSecurityScannerCaller()) return
                    param.result = null
                }
            })
        }
    }

    private fun isSensitiveResource(name: String): Boolean {
        return name.contains("xposed", ignoreCase = true) ||
            name.contains("lsposed", ignoreCase = true) ||
            name.contains("META-INF/xposed", ignoreCase = true) ||
            name.contains("META-INF/lsposed", ignoreCase = true) ||
            name.contains("/lspd/", ignoreCase = true)
    }

    // ---------------- 4. Build.TAGS ----------------

    private fun scrubBuildTags() {
        // Static field overwrite — only matters for QQ paths that re-read Build.TAGS late.
        // We always overwrite to "release-keys" if it's "test-keys".
        runCatching {
            val current = Build.TAGS
            if (current != null && current.contains("test", ignoreCase = true)) {
                XposedHelpers.setStaticObjectField(Build::class.java, "TAGS", "release-keys")
                log("Build.TAGS scrubbed test-keys -> release-keys")
            }
        }
    }

    // ---------------- 5. SystemProperties.get ----------------

    private val PROP_SAFE_DEFAULTS = mapOf(
        "ro.debuggable" to "0",
        "ro.secure" to "1",
        "ro.build.tags" to "release-keys",
        "ro.build.type" to "user",
        "ro.boot.veritymode" to "enforcing",
        "ro.boot.verifiedbootstate" to "green",
        "ro.boot.flash.locked" to "1",
        "ro.boot.vbmeta.device_state" to "locked",
        "ro.boot.warranty_bit" to "0",
        "ro.warranty_bit" to "0",
        "ro.boot.selinux" to "enforcing",
        "init.svc.adbd" to "stopped",
        "init.svc.zygote" to "running",
        "service.adb.root" to "0",
        "sys.usb.config" to "none",
        "sys.usb.state" to "none",
        "persist.sys.usb.config" to "none",
        "persist.service.adb.enable" to "0",
        "persist.service.debuggable" to "0",
    )

    private fun scrubSystemProperties(classLoader: ClassLoader) {
        val cls = runCatching {
            classLoader.loadClass("android.os.SystemProperties")
        }.getOrElse {
            runCatching { Class.forName("android.os.SystemProperties") }.getOrNull()
        } ?: return

        runCatching {
            XposedBridge.hookAllMethods(cls, "get", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args.getOrNull(0) as? String ?: return
                    val safe = PROP_SAFE_DEFAULTS[key] ?: return
                    param.result = safe
                }
            })
        }
        runCatching {
            XposedBridge.hookAllMethods(cls, "getBoolean", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args.getOrNull(0) as? String ?: return
                    val safe = PROP_SAFE_DEFAULTS[key] ?: return
                    param.result = when (safe.lowercase()) {
                        "1", "true", "y", "yes", "on", "running" -> true
                        else -> false
                    }
                }
            })
        }
        runCatching {
            XposedBridge.hookAllMethods(cls, "getInt", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args.getOrNull(0) as? String ?: return
                    val safe = PROP_SAFE_DEFAULTS[key]?.toIntOrNull() ?: return
                    param.result = safe
                }
            })
        }
    }

    // ---------------- 6. Debug probes ----------------

    private fun scrubDebugProbes() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.os.Debug", ClassLoader.getSystemClassLoader(),
                "isDebuggerConnected",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = false
                }
            )
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.os.Debug", ClassLoader.getSystemClassLoader(),
                "waitingForDebugger",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = false
                }
            )
        }
    }

    // ---------------- 7. Shell probes ----------------

    private fun scrubShellProbes() {
        val cmdHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cmd = extractCmd(param.args) ?: return
                if (isDangerousCmd(cmd)) {
                    log("blocked shell: ${cmd.take(120)}")
                    param.throwable = SecurityException("permission denied")
                }
            }
        }
        runCatching { XposedBridge.hookAllMethods(Runtime::class.java, "exec", cmdHook) }
        runCatching { XposedBridge.hookAllMethods(ProcessBuilder::class.java, "start", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val pb = param.thisObject as? ProcessBuilder ?: return
                val cmd = runCatching { pb.command().joinToString(" ") }.getOrNull() ?: return
                if (isDangerousCmd(cmd)) {
                    log("blocked PB shell: ${cmd.take(120)}")
                    param.throwable = SecurityException("permission denied")
                }
            }
        }) }
    }

    private fun extractCmd(args: Array<Any?>): String? {
        for (a in args) {
            when (a) {
                is String -> return a
                is Array<*> -> {
                    val parts = a.filterIsInstance<String>()
                    if (parts.isNotEmpty()) return parts.joinToString(" ")
                }
            }
        }
        return null
    }

    private fun isDangerousCmd(cmd: String): Boolean {
        val c = cmd.lowercase()
        return c.contains("which su") ||
            c == "su" || c.startsWith("su ") || c.contains(" su ") ||
            c.contains("magisk") ||
            c.contains("lsposed") ||
            c.contains("xposed") ||
            c.contains("busybox") ||
            c.contains("getprop ro.debug") ||
            c.contains("getprop ro.secure") ||
            c.contains("getprop ro.boot") ||
            c.contains("getprop init.svc") ||
            c.contains("getprop sys.usb") ||
            c.contains("getprop persist.sys.usb") ||
            c.contains("dumpsys package") ||
            (c.startsWith("ps") && (c.contains("magisk") || c.contains("lsposed") || c.contains("zygisk")))
    }

    private fun log(msg: String) {
        XposedBridge.log("[HookEvasion] $msg")
    }
}
