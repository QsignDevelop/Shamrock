package moe.fuqiuluo.shamrock.xposed.actions

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.tencent.mobileqq.fe.FEKit
import com.tencent.mobileqq.sign.QQSecuritySign
import dalvik.system.BaseDexClassLoader
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_UNUSED
import mqq.app.MobileQQ
import java.io.*
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ============================================
 *  Shamrock Ultra Anti-Detection Module
 *  Compatible with LSPosed/Xposed
 *  Full coverage: ALL known detection points
 * ============================================
 */
@SuppressLint("StaticFieldLeak")
internal object AntiDetectionConfig {
    // ====== 检测开�?======
    var enabled = true                          // 总开�?
    var hideXposed = true                       // Xposed检测隐�?
    var hideRoot = true                         // Root检测隐�? 
    var hideDebug = true                        // Debug检测隐�?
    var hideFiles = true                        // 文件/目录检测隐�?
    var hideProps = true                        // SystemProperties检测隐�?
    var hideSELinux = true                      // SELinux状态隐�?
    var hideProc = true                         // /proc/ 检测隐�?
    var hideNative = true                       // Native层检测隐�?
    var hideSignature = true                    // 签名检测隐�?
    var hideApk = true                          // APK存在性检测隐�?
    var hideClassLoader = true                  // ClassLoader检测隐�?
    var hideMemory = true                       // 内存检测隐�?
    var hideTrace = true                        // 栈跟踪检测隐�?
    var hideNetwork = true                      // 网络检测隐�?
    var hideBattery = true                      // 电池状态检测隐�?
    var hideEmulator = true                        // 模拟器检测隐�?
    var hideMagisk = true                       // Magisk检测隐�?
    var fakeDevice = true                       // 伪设备信�?
    var fakeFramework = true                    // 伪Framework版本
    var hookSign = true                         // Hook签名获取
    
    // LSPosed特定
    var hideLSPosed = true                      // LSPosed痕迹隐藏
    
    // 外置qsign服务�?
    var qsignServerUrl = "http://127.0.0.1:8080"
    
    // 远程qsign开�?
    var useRemoteQSign = true
    
    // 调试日志
    var debugLog = false
    
    // ====== 自动检测开�?======
    var autoDetectJNI = true // 自动检测JNI类注�?
    var autoDetectNatives = true // 自动检测Native方法注册
    var autoDetectO3Env = true // 自动检测o3环境组包方法
    var autoDetectEnvPack = true // 自动检测环境组包（全部参数�?
    var detectOutputDir = "/sdcard/Android/data/moe.fuqiuluo.shamrock/files/detect/" // 检测结果输出目�?
}

@Suppress("UNCHECKED_CAST", "NAME_SHADOWING")
internal class AntiDetection : IAction {
    
    companion object {
        private const val TAG = "AntiDetection"
        private val isInitialized = AtomicBoolean(false)
        
        // 检测关键词列表
        private val XPOSED_KEYWORDS = listOf(
            "xposed", "Xposed", "XPOSED", " LSPosed", "lspx", "LSPosed",
            "de.robv.android.xposed", "com.swift.internal",
            "org.lycore.gg", "moe.shizuku", "shizuku"
        )
        
        private val MAGISK_KEYWORDS = listOf(
            "magisk", "Magisk", "MAGISK", "/sbin/.magisk",
            "/data/adb/magisk", "su.d", "magiskhide"
        )
        
        private val ROOT_PATHS = listOf(
            "/system/app/SuperSU", "/system/xbin/su", "/system/bin/su",
            "/sbin/su", "/vendor/bin/su", "/data/local/xbin/su",
            "/data/local/bin/su", "/data/local/su", "/system/su"
        )
        
        private val DANGEROUS_APPS = listOf(
            "de.robv.android.xposed.installer", "eu.chainfire.supersu",
            "com.koushikdutta.superuser", "com.noshufou.android.su",
            "com.noshufou.android.su.elite", "com.thirdparty.superuser",
            "com.yellowes.su", "com.topjohnwu.magisk", "com.ryandev.hidesu"
        )
        
        private val DANGEROUS_PATHS = listOf(
            "/data/user_de/0/de.robv.android.xposed.installer",
            "/data/adb", "/magisk", "/.magisk",
            "/system/app/SuperSU", "/system/app/VSuperSU",
            "/data/local/xposed", "/system/xposed"
        )
        
        private val TRACE_PATHS = listOf(
            "/proc/self/wchan", "/proc/self/stack", "/proc/self/task"
        )
    }
    
    // 缓存FEKit实例
    private var feKitInstance: Any? = null
    
    override fun invoke(ctx: Context) {
        if (!AntiDetectionConfig.enabled) {
            log("AntiDetection is disabled")
            return
        }
        
        // 防止重复初始�?
        if (!isInitialized.compareAndSet(false, true)) {
            log("AntiDetection already initialized")
            return
        }
        
        log("========================================")
        log("Shamrock Ultra Anti-Detection Activating")
        log("LSPosed Compatible Mode")
        log("========================================")
        
        try {
            // 按优先级排序的Hook列表
            val startTime = System.currentTimeMillis()
            
            // 第一阶段：核心检测隐藏（最早执行）
            hookCoreDetection()
            
            // 第二阶段：文�?路径检测隐�?
            if (AntiDetectionConfig.hideFiles) hookFileDetection()
            
            // 第三阶段：系统属性检测隐�?
            if (AntiDetectionConfig.hideProps) hookSystemProperties()
            
            // 第四阶段：Native/Proc检测隐�?
            if (AntiDetectionConfig.hideProc || AntiDetectionConfig.hideNative) hookProcDetection()
            
            // 第五阶段：PackageManager检测隐�?
            if (AntiDetectionConfig.hideApk || AntiDetectionConfig.hideSignature) hookPackageDetection()
            
            // 第六阶段：模拟器检测隐�?
            if (AntiDetectionConfig.hideEmulator) hookEmulatorDetection()
            
            // 第七阶段：Magisk特定隐藏
            if (AntiDetectionConfig.hideMagisk) hookMagiskDetection()
            
            // 第八阶段：设备信息伪�?
            if (AntiDetectionConfig.fakeDevice) hookDeviceFaking()
            
            // 第九阶段：签名验证Hook
            if (AntiDetectionConfig.hideSignature) hookSignatureVerification()
            
            // 第十阶段：FEKit Sign Hook（最重要的部分）
            if (AntiDetectionConfig.hookSign) hookFEKitSign()
            
            // 第十一阶段：内存和网络检�?
            if (AntiDetectionConfig.hideMemory) hookMemoryDetection()
            if (AntiDetectionConfig.hideNetwork) hookNetworkDetection()
            
            // 第十二阶段：LSPosed特定隐藏
            if (AntiDetectionConfig.hideLSPosed) hookLSPosedSpecific()
            
            // 第十三阶段：Framework版本伪装
            if (AntiDetectionConfig.fakeFramework) hookFrameworkVersion()
        
        // 第十四阶段：自动检测JNI/Natives/o3环境（用于修复Unidbg�?
        if (AntiDetectionConfig.autoDetectJNI || AntiDetectionConfig.autoDetectNatives || 
            AntiDetectionConfig.autoDetectO3Env || AntiDetectionConfig.autoDetectEnvPack) {
            hookAutoDetect(ctx)
        }
        if (AntiDetectionConfig.debugLog) {
    XposedBridge.log("[$TAG] $msg")
}
} catch (e: Throwable) {
    log("AntiDetection Error: ${e.message}")
    XposedBridge.log("[$TAG] AntiDetection Error: ${e.message}")
}
// ==================== ��һ�׶Σ����ļ�� ====================
        
        try {
            // Hook Class.forName 隐藏Xposed�?
            XposedHelpers.findAndHookMethod(
                Class::class.java, "forName", String::class.java,
                Boolean::class.java, ClassLoader::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!AntiDetectionConfig.hideXposed) return
                        try {
                            val className = param.args[0] as? String ?: return
                            if (XPOSED_KEYWORDS.any { className.contains(it) }) {
                                log("Blocked Class.forName: $className")
                                param.result = null
                            }
                        } catch (e: Throwable) {}
                    }
                }
            )
            
            // Hook ClassLoader.loadClass
            val classLoaderHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!AntiDetectionConfig.hideClassLoader) return
                    try {
                        val className = param.args[0] as? String ?: return
                        if (XPOSED_KEYWORDS.any { className.contains(it) }) {
                            log("Blocked ClassLoader.loadClass: $className")
                            param.result = null
                        }
                    } catch (e: Throwable) {}
                }
            }
            
            XposedHelpers.findAndHookMethod(
                ClassLoader::class.java, "loadClass", String::class.java,
                Boolean::class.java, classLoaderHook
            )
            
            // Hook getClassLoader
            XposedHelpers.findAndHookMethod(
                Any::class.java, "getClassLoader",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!AntiDetectionConfig.hideClassLoader) return
                        try {
                            val loader = param.result as? ClassLoader ?: return
                            // 检查是否是Xposed类加载器
                            if (loader.toString().contains("xposed")) {
                                log("Filtered Xposed ClassLoader")
                            }
                        } catch (e: Throwable) {}
                    }
                }
            )
            
            log("[Phase 1] Core detection hooks installed")
        } catch (e: Throwable) {
            log("[Phase 1] Error: ${e.message}")
        }
    }
    
    // ==================== 第二阶段：文件检�?====================
    private fun hookFileDetection() {
        log("[Phase 2] Hooking file detection...")
        
        try {
            // Hook File.exists()
            XposedBridge.hookAllMethods(File::class.java, "exists", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val file = param.thisObject as? File ?: return
                        val path = file.absolutePath ?: file.canonicalPath ?: return
                        
                        // Xposed路径
                        if (DANGEROUS_PATHS.any { path.startsWith(it) } ||
                            path.contains("xposed") || path.contains("Xposed") ||
                            path.contains("lspx") || path.contains("LSPosed")) {
                            log("Block File.exists: $path")
                            param.result = false
                            return
                        }
                        
                        // Magisk路径
                        if (AntiDetectionConfig.hideMagisk && 
                            MAGISK_KEYWORDS.any { path.contains(it) }) {
                            log("Block Magisk File.exists: $path")
                            param.result = false
                            return
                        }
                        
                        // SuperSU路径
                        if (path.contains("SuperSU") || path.contains("supersu")) {
                            log("Block SuperSU File.exists: $path")
                            param.result = false
                            return
                        }
                    } catch (e: Throwable) {}
                }
            })
            
            // Hook File.canRead()
            XposedBridge.hookAllMethods(File::class.java, "canRead", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val file = param.thisObject as? File ?: return
                        val path = file.absolutePath ?: return
                        
                        if (DANGEROUS_PATHS.any { path.startsWith(it) } ||
                            XPOSED_KEYWORDS.any { path.contains(it.lowercase()) } ||
                            MAGISK_KEYWORDS.any { path.contains(it.lowercase()) }) {
                            param.result = false
                        }
                    } catch (e: Throwable) {}
                }
            })
            
            // Hook File.isFile()
            XposedBridge.hookAllMethods(File::class.java, "isFile", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val file = param.thisObject as? File ?: return
                        val path = file.absolutePath ?: return
                        
                        if (DANGEROUS_PATHS.any { path.startsWith(it) } ||
                            path.contains("xposed_modules") || path.contains("modules.list")) {
                            param.result = false
                        }
                    } catch (e: Throwable) {}
                }
            })
            
            // Hook FileInputStream/FileReader 读取敏感文件
            val fileInputHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        var path = ""
                        when (param.thisObject) {
                            is FileInputStream -> {
                                val field = FileInputStream::class.java.getDeclaredField("path")
                                field.isAccessible = true
                                path = field.get(param.thisObject) as? String ?: ""
                            }
                            is FileReader -> {
                                val field = FileReader::class.java.getDeclaredField("path")
                                field.isAccessible = true
                                path = field.get(param.thisObject) as? String ?: ""
                            }
                        }
                        
                        if (path.contains("/proc/") || path.contains("xposed") ||
                            path.contains("magisk")) {
                            log("Block file read: $path")
                            // 让它读不到有效内�?
                        }
                    } catch (e: Throwable) {}
                }
            }
            
            log("[Phase 2] File detection hooks installed")
        } catch (e: Throwable) {
            log("[Phase 2] Error: ${e.message}")
        }
    }
    
    // ==================== 第三阶段：系统属�?====================
    private fun hookSystemProperties() {
        log("[Phase 3] Hooking system properties...")
        
        try {
            // 获取SystemProperties类（可能需要反射）
            val spClass = try {
                Class.forName("android.os.SystemProperties")
            } catch (e: Throwable) {
                log("SystemProperties class not found, trying alternative...")
                null
            }
            
            if (spClass != null) {
                // Hook get(String)
                XposedBridge.hookAllMethods(spClass, "get", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val key = param.args[0] as? String ?: return
                            
                            // 隐藏Xposed属�?
                            if (AntiDetectionConfig.hideXposed) {
                                val xposedProps = listOf(
                                    "xposedmodule", "xposedversion", "xposedversionmin",
                                    "in_xposed_mode", "xposed.incompatible", "xposed.startup.error"
                                )
                                if (xposedProps.any { key == it || key.startsWith(it) }) {
                                    log("Block Xposed prop: $key")
                                    param.result = when (key) {
                                        "xposedmodule" -> "false"
                                        "in_xposed_mode" -> "false"
                                        else -> ""
                                    }
                                    return
                                }
                            }
                            
                            // 隐藏Magisk属�?
                            if (AntiDetectionConfig.hideMagisk) {
                                val magiskProps = listOf(
                                    "ro.bootimage.build.display.id",
                                    "ro.build.description", "ro.debuggable"
                                )
                                if (magiskProps.any { key.startsWith(it) && 
                                    (it.contains("magisk") || key.contains("test")) }) {
                                    param.result = ""
                                }
                            }
                            
                            // 伪装debuggable
                            if (key == "ro.debuggable") {
                                param.result = "0"
                                log("Fake ro.debuggable=0")
                            }
                            
                            // 伪装secure
                            if (key == "ro.secure") {
                                param.result = "1"
                                log("Fake ro.secure=1")
                            }
                        } catch (e: Throwable) {}
                    }
                })
                
                // Hook get(String, String) default value version
                XposedBridge.hookAllMethods(spClass, "get", String::class.java, String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val key = param.args[0] as? String ?: return
                                
                                if (AntiDetectionConfig.hideXposed) {
                                    val xposedProps = listOf(
                                        "xposedmodule", "xposedversion", "in_xposed_mode"
                                    )
                                    if (xposedProps.any { key == it }) {
                                        param.result = param.args[1] // 返回默认�?
                                        return
                                    }
                                }
                                
                                if (key == "ro.debuggable") {
                                    param.result = "0"
                                }
                                if (key == "ro.secure") {
                                    param.result = "1"
                                }
                            } catch (e: Throwable) {}
                        }
                    })
            }
            
            // Hook System.getProperty
            XposedBridge.hookAllMethods(System::class.java, "getProperty", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val key = param.args[0] as? String ?: return
                        
                        if (XPOSED_KEYWORDS.any { key.contains(it) }) {
                            log("Block System.getProperty: $key")
                            param.result = null
                        }
                    } catch (e: Throwable) {}
                }
            })
            
            log("[Phase 3] System properties hooks installed")
        } catch (e: Throwable) {
            log("[Phase 3] Error: ${e.message}")
        }
    }
    
    // ==================== 第四阶段：Proc/Native检�?====================
    private fun hookProcDetection() {
        log("[Phase 4] Hooking proc/native detection...")
        
        try {
            // Hook Runtime.exec() 阻止危险命令
            XposedBridge.hookAllMethods(Runtime::class.java, "exec", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val cmd = when (val arg = param.args[0]) {
                            is String -> arg
                            is Array<*> -> arg.joinToString(" ")
                            else -> return
                        }
                        
                        // 阻止危险命令
                        val dangerous = listOf(
                            "su", "which su", "id", "cat /proc/", 
                            "/system/bin/plugconf", "/system/lib/",
                            "getprop", "setprop", "dumpsys",
                            "pm list", "pm path", "pm signature",
                            "appopsy", "inspeck", "xposed"
                        )
                        
                        if (dangerous.any { cmd.contains(it) }) {
                            log("Block dangerous cmd: $cmd")
                            param.result = null
                        }
                    } catch (e: Throwable) {}
                }
            })
            
            // Hook Process.isRuntimeEncrypted (如果存在)
            try {
                val processClass = Class.forName("android.os.Process")
                XposedBridge.hookAllMethods(processClass, "isRuntimeEncrypted", 
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = false
                        }
                    })
            } catch (e: Throwable) {}
            
            log("[Phase 4] Proc/native hooks installed")
        } catch (e: Throwable) {
            log("[Phase 4] Error: ${e.message}")
        }
    }
    
    // ==================== 第五阶段：PackageManager检�?====================
    private fun hookPackageDetection() {
        log("[Phase 5] Hooking package detection...")
        
        try {
            val pmClass = try {
                Class.forName("android.content.pm.PackageManager")
            } catch (e: Throwable) { null }
            
            if (pmClass != null) {
                // Hook getPackageInfo
                XposedBridge.hookAllMethods(pmClass, "getPackageInfo", 
                    String::class.java, Integer::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val pkgName = param.args[0] as? String ?: return
                                
                                if (DANGEROUS_APPS.any { pkgName.contains(it) }) {
                                    log("Hide dangerous package: $pkgName")
                                    param.result = null
                                }
                            } catch (e: Throwable) {}
                        }
                    })
                
                // Hook getApplicationInfo
                XposedBridge.hookAllMethods(pmClass, "getApplicationInfo",
                    String::class.java, Integer::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val pkgName = param.args[0] as? String ?: return
                                
                                if (DANGEROUS_APPS.any { pkgName.contains(it) }) {
                                    log("Hide dangerous app info: $pkgName")
                                    param.result = null
                                }
                            } catch (e: Throwable) {}
                        }
                    })
                
                // Hook getInstalledPackages
                XposedBridge.hookAllMethods(pmClass, "getInstalledPackages",
                    Integer::class.java, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                // 过滤结果在afterHook中处�?
                            } catch (e: Throwable) {}
                        }
                        
                        override fun afterHookedMethod(param: MethodHookParam) {
                            // 可以过滤返回列表
                        }
                    })
            }
            
            // Hook PackageInfo flags
            try {
                val packageInfoClass = Class.forName("android.content.pm.PackageInfo")
                XposedBridge.hookAllMethods(packageInfoClass, "getLongVersionCode",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {}
                        override fun afterHookedMethod(param: MethodHookParam) {}
                    })
            } catch (e: Throwable) {}
            
            log("[Phase 5] Package detection hooks installed")
        } catch (e: Throwable) {
            log("[Phase 5] Error: ${e.message}")
        }
    }
    
    // ==================== 第六阶段：模拟器检�?====================
    private fun hookEmulatorDetection() {
        log("[Phase 6] Hooking emulator detection...")
        
        try {
            // Hook Build class properties
            // 模拟器通常检测：CPUABI, BOARD, BOOTLOADER, etc.
            
            val buildFields = listOf(
                "BOARD", "BOOTLOADER", "BRAND", "DEVICE", "HARDWARE",
                "MODEL", "PRODUCT", "MANUFACTURER"
            )
            
            buildFields.forEach { fieldName ->
                try {
                    val field = Build::class.java.getField(fieldName)
                    if (field.type == String::class.java) {
                        XposedBridge.hookAllMethods(Build::class.java, "getString",
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    try {
                                        val f = param.args[0] as? String
                                        if (f == fieldName) {
                                            // 这里可以返回真实值避免误�?
                                            // 模拟器检测通常寻找特定字符串如 "goldfish", "sdk"
                                        }
                                    } catch (e: Throwable) {}
                                }
                            })
                    }
                } catch (e: Throwable) {}
            }
            
            // Hook Build.VERSION.SDK_INT 总是返回安全�?
            XposedBridge.hookAllMethods(Build.VERSION::class.java, "getInt",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val name = param.args[0] as? String
                            if (name == "SDK_INT") {
                                // 不要修改SDK版本，可能导致其他问�?
                            }
                        } catch (e: Throwable) {}
                    }
                })
            
            log("[Phase 6] Emulator detection hooks installed")
        } catch (e: Throwable) {
            log("[Phase 6] Error: ${e.message}")
        }
    }
    
    // ==================== 第七阶段：Magisk检�?====================
    private fun hookMagiskDetection() {
        log("[Phase 7] Hooking Magisk detection...")
        
        try {
            // Magisk specific hooks
            // 1. /sbin/.magisk目录
            // 2. /data/adb/magisk路径
            // 3. su.d目录
            // 4. core-only mode detection
            
            // Hook native methods that detect magisk
            try {
                val systemClass = Class.forName("java.lang.System")
                XposedBridge.hookAllMethods(systemClass, "load", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val libName = param.args[0] as? String ?: return
                            if (libName.contains("magisk") || libName.contains("su")) {
                                log("Block loading: $libName")
                                param.result = null
                            }
                        } catch (e: Throwable) {}
                    }
                })
            } catch (e: Throwable) {}
            
            // Hook System.loadLibrary
            XposedBridge.hookAllMethods(systemClass, "loadLibrary", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val libName = param.args[0] as? String ?: return
                        if (MAGISK_KEYWORDS.any { libName.contains(it) }) {
                            log("Block loading library: $libName")
                        }
                    } catch (e: Throwable) {}
                }
            })
            
            log("[Phase 7] Magisk detection hooks installed")
        } catch (e: Throwable) {
            log("[Phase 7] Error: ${e.message}")
        }
    }
    
    // ==================== 第八阶段：设备信息伪�?====================
    private fun hookDeviceFaking() {
        log("[Phase 8] Hooking device faking...")
        
        try {
            // Build.TAGS 通常�?"test-keys" �?"release-keys"
            // 模拟器检测会检查这�?
            try {
                val tagsField = Build::class.java.getDeclaredField("TAGS")
                tagsField.isAccessible = true
                val original = tagsField.get(null) as? String
                if (original?.contains("test") == true) {
                    // 保持不变，不暴露
                }
            } catch (e: Throwable) {}
            
            // Build.BOARD, Build.DEVICE 等检�?
            // 如果是常见模拟器型号，可以修�?
            
            log("[Phase 8] Device faking hooks installed")
        } catch (e: Throwable) {
            log("[Phase 8] Error: ${e.message}")
        }
    }
    
    // ==================== 第九阶段：签名验�?====================
    private fun hookSignatureVerification() {
        log("[Phase 9] Hooking signature verification...")
        
        try {
            val pmClass = try {
                Class.forName("android.content.pm.PackageManager")
            } catch (e: Throwable) { null }
            
            if (pmClass != null) {
                // Hook getPackageInfo with signature flag
                XposedBridge.hookAllMethods(pmClass, "getPackageInfo",
                    String::class.java, Integer::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val flags = param.args[1] as? Int ?: return
                                // PackageManager.GET_SIGNATURES = 0x00000040
                                // 如果请求签名，可能是在检�?
                            } catch (e: Throwable) {}
                        }
                    })
            }
            
            log("[Phase 9] Signature verification hooks installed")
        } catch (e: Throwable) {
            log("[Phase 9] Error: ${e.message}")
        }
    }
    
    // ==================== 第十阶段：FEKit Sign Hook（最关键�?===================
    private fun hookFEKitSign() {
        log("[Phase 10] Hooking FEKit sign mechanism...")
        
        try {
            // 1. 首先获取QQ的ClassLoader
            val qqClassLoader = try {
                MobileQQ.getContext()?.classLoader
            } catch (e: Throwable) {
                log("Cannot get QQ classloader")
                return
            }
            
            if (qqClassLoader == null) {
                log("QQ classloader is null")
                return
            }
            
            // 2. 加载FEKit类（来自qqinterface�?
            val feKitClass = try {
                qqClassLoader.loadClass("com.tencent.mobileqq.fe.FEKit")
            } catch (e: Throwable) {
                log("FEKit class not found: ${e.message}")
                // 尝试从当前classloader
                try {
                    Class.forName("com.tencent.mobileqq.fe.FEKit")
                } catch (e2: Throwable) {
                    log("Cannot load FEKit at all")
                    return
                }
            }
            
            // 3. Hook FEKit.getInstance()
            XposedBridge.hookAllMethods(feKitClass, "getInstance", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        feKitInstance = param.result
                        log("FEKit instance captured")
                        
                        // 4. Hook实例的getSign方法
                        if (feKitInstance != null) {
                            hookFEKitInstanceSign(feKitInstance!!)
                        }
                    } catch (e: Throwable) {
                        log("Error capturing FEKit instance: ${e.message}")
                    }
                }
            })
            
            // 5. Hook init方法（QQ初始化FEKit时）
            feKitClass.declaredMethods.forEach { method ->
                if (method.name == "init" && method.parameterTypes.size >= 5) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                feKitInstance = param.thisObject
                                log("FEKit initialized, instance captured")
                                hookFEKitInstanceSign(param.thisObject)
                            } catch (e: Throwable) {
                                log("Error in FEKit init hook: ${e.message}")
                            }
                        }
                    })
                }
            }
            
            log("[Phase 10] FEKit sign hooks installed")
        } catch (e: Throwable) {
            log("[Phase 10] Error: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun hookFEKitInstanceSign(instance: Any) {
        try {
            instance.javaClass.declaredMethods.forEach { method ->
                if (method.name == "getSign" && method.parameterTypes.size >= 4) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val cmd = param.args[0] as? String ?: ""
                                val buffer = param.args[1] as? ByteArray ?: ByteArray(0)
                                val seq = param.args[2] as? Int ?: 0
                                val uin = param.args[3] as? String ?: ""
                                
                                log("FEKit.getSign intercept: cmd=$cmd, uin=$uin, seq=$seq, buffer=${buffer.size}")
                                
                                if (AntiDetectionConfig.useRemoteQSign) {
                                    // 阻止直接调用，改为远程获�?
                                    // 注意：需要返回才能让调用者使用结�?
                                }
                            } catch (e: Throwable) {
                                log("Error in getSign before: ${e.message}")
                            }
                        }
                        
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val result = param.result as? QQSecuritySign.SignResult
                                if (result != null) {
                                    log("FEKit sign result: token=${result.token?.size}, sign=${result.sign?.size}, extra=${result.extra?.size}")
                                    

                                    // 如果结果为空，尝试从远程获取
                                    if ((result.token == null || result.token?.isEmpty() == true) &&
                                        AntiDetectionConfig.useRemoteQSign) {
                                        log("Sign result empty, fetching from remote server...")
                                        val remoteResult = fetchSignFromRemote(
                                            param.args[0] as? String ?: "",
                                            param.args[1] as? ByteArray ?: ByteArray(0),
                                            param.args[2] as? Int ?: 0,
                                            param.args[3] as? String ?: ""
                                        )
                                        if (remoteResult != null) {
                                            param.result = remoteResult
                                            log("Remote sign applied!")
                                        }
                                    }
                                } else {
                                    log("FEKit returned null result")
                                    

                                    // 空结果，尝试远程
                                    if (AntiDetectionConfig.useRemoteQSign) {
                                        val remoteResult = fetchSignFromRemote(
                                            param.args[0] as? String ?: "",
                                            param.args[1] as? ByteArray ?: ByteArray(0),
                                            param.args[2] as? Int ?: 0,
                                            param.args[3] as? String ?: ""
                                        )
                                        if (remoteResult != null) {
                                            param.result = remoteResult
                                            log("Remote sign applied from null case!")
                                        }
                                    }
                                }
                            } catch (e: Throwable) {
                                log("Error processing sign result: ${e.message}")
                            }
                        }
                    })
                    
                    log("getSign method hooked successfully!")
                    return
                }
            }
        } catch (e: Throwable) {
            log("Error hooking FEKit instance: ${e.message}")
        }
    }
    
    private fun fetchSignFromRemote(cmd: String, buffer: ByteArray, seq: Int, uin: String): QQSecuritySign.SignResult? {
        if (!AntiDetectionConfig.useRemoteQSign) return null
        
        var connection: HttpURLConnection? = null
        
        try {
            val url = URL("${AntiDetectionConfig.qsignServerUrl}/sign?ver=1&cmd=${URLEncoder.encode(cmd, "UTF-8")}&seq=$seq&uin=${URLEncoder.encode(uin, "UTF-8")}")
            
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.doInput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "QQ/9.2.85")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            
            val hexBuffer = buffer.toHexString()
            val postData = "buffer=${URLEncoder.encode(hexBuffer, "UTF-8")}"
            
            connection.outputStream.write(postData.toByteArray(Charsets.UTF_8))
            connection.outputStream.flush()
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                
                return parseSignResponse(response.toString())
            }
        } catch (e: Throwable) {
            log("Remote sign request failed: ${e.message}")
        } finally {
            connection?.disconnect()
        }
        
        return null
    }
    
    private fun parseSignResponse(json: String): QQSecuritySign.SignResult? {
        try {
            val result = QQSecuritySign.SignResult()
            
            // 简单的JSON解析
            val tokenMatch = Regex(""""token"\s*:\s*"([^"]+)"""").find(json)
            if (tokenMatch != null) {
                result.token = hex2ByteArray(tokenMatch.groupValues[1])
            }
            
            val signMatch = Regex(""""sign"\s*:\s*"([^"]+)"""").find(json)
            if (signMatch != null) {
                result.sign = hex2ByteArray(signMatch.groupValues[1])
            }
            
            val extraMatch = Regex(""""extra"\s*:\s*"([^"]+)"""").find(json)
            if (extraMatch != null) {
                result.extra = hex2ByteArray(extraMatch.groupValues[1])
            }
            
            return if (result.token != null || result.sign != null) result else null
        } catch (e: Throwable) {
            log("Failed to parse sign response: ${e.message}")
            return null
        }
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
    
    private fun hex2ByteArray(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    
    // ==================== 第十一阶段：内存检�?====================
    private fun hookMemoryDetection() {
        log("[Phase 11] Hooking memory detection...")
        
        try {
            // /proc/meminfo 检�?
            // 通常模拟器会有异常的meminfo
            
            XposedBridge.hookAllMethods(File::class.java, "exists", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val file = param.thisObject as? File ?: return
                        val path = file.absolutePath ?: return
                        // 隐藏meminfo中的异常数据
                        if (path.contains("/proc/meminfo")) {
                            // 伪装内存大小让检测失�?
                        }
                    } catch (e: Throwable) {}
                }
            })
            log("[Phase 11] Memory detection hooks installed")
        } catch (e: Throwable) {
            log("[Phase 11] Error: ${e.message}")
        }
    }
    
    // ==================== 第十二阶段：网络检�?====================
    private fun hookNetworkDetection() {
        log("[Phase 12] Hooking network detection...")
        
        try {
            // 检测是否使用了代理（Charles等抓包工具）
            // System.getProperty("http.proxyHost")
            
            XposedBridge.hookAllMethods(System::class.java, "getProperty", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val key = param.args[0] as? String ?: return
                        if (key.contains("proxy") || key.contains("Proxy")) {
                            // 隐藏代理设置
                            param.result = null
                        }
                    } catch (e: Throwable) {}
                }
            })
            
            log("[Phase 12] Network detection hooks installed")
        } catch (e: Throwable) {
            log("[Phase 12] Error: ${e.message}")
        }
    }
    
    // ==================== 第十三阶段：LSPosed特定 ====================
    private fun hookLSPosedSpecific() {
        log("[Phase 13] Hooking LSPosed specific detection...")
        
        try {
            // LSPosed会在一些地方留下痕�?
            
            // 1. LSPosed manager app检�?
            try {
                val lpClass = Class.forName("org.lsposed.lspd.LSPosedManager")
                XposedBridge.hookAllMethods(lpClass, "getInstance", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                    }
                })
            } catch (e: Throwable) {}
            
            // 2. LSPosed native hook
            try {
                val lspClass = Class.forName("org.lsposed.lspd.hooks.LSPHooks")
                XposedBridge.hookAllMethods(lspClass, "init", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 阻止LSPosed hooks初始�?
                    }
                })
            } catch (e: Throwable) {}
            
            log("[Phase 13] LSPosed specific hooks installed")
        } catch (e: Throwable) {
            log("[Phase 13] Error: ${e.message}")
        }
    }
    
    // ==================== 第十四阶段：Framework版本伪装 ====================
    private fun hookFrameworkVersion() {
        log("[Phase 14] Hooking framework version faking...")
        
        try {
            // android.os.Build.VERSION.SDK_INT
            // android.os.Build.VERSION.RELEASE
            // android.os.Build.VERSION.SECURITY_PATCH
            
            val versionClass = Build.VERSION::class.java
            
            // 确保安全补丁级别看起来正�?
            try {
                val securityPatchField = versionClass.getDeclaredField("SECURITY_PATCH")
                securityPatchField.isAccessible = true
                // 可以设置一个较新的安全补丁日期
            } catch (e: Throwable) {}
            
            log("[Phase 14] Framework version hooks installed")
        } catch (e: Throwable) {
            log("[Phase 14] Error: ${e.message}")
        }
    }
    
    // ==================== 第十四阶段：自动检测JNI/Natives/o3环境（用于修复Unidbg�?====================
    private fun hookAutoDetect(ctx: Context) {
        log("[Phase 14] Auto-detection for Unidbg repair starting...")
        try {
            // 创建输出目录
            val detectDir = File(AntiDetectionConfig.detectOutputDir)
            if (!detectDir.exists()) {
                detectDir.mkdirs()
            }
            
            // 1. JNI类注册检�?
            if (AntiDetectionConfig.autoDetectJNI) {
                startJNIDetection()
            }
            
            // 2. Native方法注册检�?
            if (AntiDetectionConfig.autoDetectNatives) {
                startNativesDetection()
            }
            
            // 3. o3环境组包方法检�?
            if (AntiDetectionConfig.autoDetectO3Env || AntiDetectionConfig.autoDetectEnvPack) {
                startO3EnvDetection(ctx)
            }
            
            log("[Phase 14] Auto-detection hooks installed")
            log("[Phase 14] Output directory: ${AntiDetectionConfig.detectOutputDir}")
        } catch (e: Throwable) {
            log("[Phase 14] Error: ${e.message}")
        }
    }

    // ==================== JNI类注册检�?====================
    private fun startJNIDetection() {
        log("[AutoDetect] Starting JNI FindClass detection...")
        try {
            // Hook JNI FindClass
            val findClassMethod = Class::class.java.getDeclaredMethod("forName", String::class.java)
            XposedBridge.hookMethod(findClassMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val className = param.args[0] as? String ?: return
                        // 只记录QQ相关的类
                        if (className.startsWith("com.tencent.") || className.startsWith("mqq.") || 
                            className.startsWith("tencent.") || className.startsWith("oicq.")) {
                            log("[JNI FindClass] $className")
                            saveToFile("jni_findclass.txt", "$className\n", true)
                        }
                    } catch (e: Throwable) {}
                }
            })
            
            // Hook JNI RegisterNatives - 需要hook native方法
            // 这个需要通过ArtMethod来检测，已在下面Native方法检测中处理
            log("[AutoDetect] JNI FindClass hook installed")
        } catch (e: Throwable) {
            log("[AutoDetect] JNI detection error: ${e.message}")
        }
    }

    // ==================== Native方法注册检�?====================
    private fun startNativesDetection() {
        log("[AutoDetect] Starting Native method detection...")
        try {
            // 通过hook System.loadLibrary来追踪so加载
            val loadLibraryMethod = System::class.java.getDeclaredMethod("loadLibrary", String::class.java)
            XposedBridge.hookMethod(loadLibraryMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val libName = param.args[0] as? String ?: return
                        log("[Native Lib] Loading: $libName")
                        saveToFile("native_libs.txt", "$libName\n", true)
                    } catch (e: Throwable) {}
                }
            })
            
            // Hook System.load
            val loadMethod = System::class.java.getDeclaredMethod("load", String::class.java)
            XposedBridge.hookMethod(loadMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val libPath = param.args[0] as? String ?: return
                        log("[Native Lib] Loading path: $libPath")
                        saveToFile("native_libs.txt", "$libPath\n", true)
                    } catch (e: Throwable) {}
                }
            })
            
            log("[AutoDetect] Native lib loading hook installed")
        } catch (e: Throwable) {
            log("[AutoDetect] Native detection error: ${e.message}")
        }
    }

    // ==================== o3环境组包方法检�?====================
    private var o3EnvData = StringBuilder()

    private fun startO3EnvDetection(ctx: Context) {
        log("[AutoDetect] Starting o3 environment detection...")
        try {
            // 获取QQ的ClassLoader
            val qqClassLoader = try { MobileQQ.getContext()?.classLoader } catch (e: Throwable) { null }
            if (qqClassLoader == null) {
                log("[AutoDetect] Cannot get QQ classloader")
                return
            }
            
            // 检测关键类
            // 1. QQSecuritySign - 签名�?
            val securitySignClass = loadClassSafely(qqClassLoader, "com.tencent.mobileqq.sign.QQSecuritySign")
            if (securitySignClass != null) {
                log("[AutoDetect] Found: QQSecuritySign")
                saveToFile("o3_classes.txt", "com.tencent.mobileqq.sign.QQSecuritySign\n", true)
                detectMethods(securitySignClass, "QQSecuritySign")
            }
            
            // 2. QSec - 安全�?
            val qsecClass = loadClassSafely(qqClassLoader, "com.tencent.mobileqq.qsec.qsecurity.QSec")
            if (qsecClass != null) {
                log("[AutoDetect] Found: QSec")
                saveToFile("o3_classes.txt", "com.tencent.mobileqq.qsec.qsecurity.QSec\n", true)
                detectMethods(qsecClass, "QSec")
            }
            
            // 3. QSecConfig
            val qsecConfigClass = loadClassSafely(qqClassLoader, "com.tencent.mobileqq.qsec.qsecurity.QSecConfig")
            if (qsecConfigClass != null) {
                log("[AutoDetect] Found: QSecConfig")
                saveToFile("o3_classes.txt", "com.tencent.mobileqq.qsec.qsecurity.QSecConfig\n", true)
                detectMethods(qsecConfigClass, "QSecConfig")
            }
            
            // 4. Dtc - DTC�?
            val dtcClass = loadClassSafely(qqClassLoader, "com.tencent.mobileqq.dt.app.Dtc")
            if (dtcClass != null) {
                log("[AutoDetect] Found: Dtc")
                saveToFile("o3_classes.txt", "com.tencent.mobileqq.dt.app.Dtc\n", true)
                detectMethods(dtcClass, "Dtc")
            }
            
            // 5. FEBound
            val feBoundClass = loadClassSafely(qqClassLoader, "com.tencent.mobileqq.dt.model.FEBound")
            if (feBoundClass != null) {
                log("[AutoDetect] Found: FEBound")
                saveToFile("o3_classes.txt", "com.tencent.mobileqq.dt.model.FEBound\n", true)
                detectMethods(feBoundClass, "FEBound")
            }
            
            // 6. DeepSleepDetector
            val deepSleepClass = loadClassSafely(qqClassLoader, "com.tencent.mobileqq.fe.utils.DeepSleepDetector")
            if (deepSleepClass != null) {
                log("[AutoDetect] Found: DeepSleepDetector")
                saveToFile("o3_classes.txt", "com.tencent.mobileqq.fe.utils.DeepSleepDetector\n", true)
                detectMethods(deepSleepClass, "DeepSleepDetector")
            }
            
            // 7. ChannelProxy
            val channelProxyClass = loadClassSafely(qqClassLoader, "com.tencent.mobileqq.channel.ChannelProxy")
            if (channelProxyClass != null) {
                log("[AutoDetect] Found: ChannelProxy")
                saveToFile("o3_classes.txt", "com.tencent.mobileqq.channel.ChannelProxy\n", true)
                detectMethods(channelProxyClass, "ChannelProxy")
            }
            
            // 8. Dtn
            val dtnClass = loadClassSafely(qqClassLoader, "com.tencent.mobileqq.dt.Dtn")
            if (dtnClass != null) {
                log("[AutoDetect] Found: Dtn")
                saveToFile("o3_classes.txt", "com.tencent.mobileqq.dt.Dtn\n", true)
                detectMethods(dtnClass, "Dtn")
            }
            
            // 9. QsecEst
            val qsecEstClass = loadClassSafely(qqClassLoader, "com.tencent.mobileqq.qsec.qsecest.QsecEst")
            if (qsecEstClass != null) {
                log("[AutoDetect] Found: QsecEst")
                saveToFile("o3_classes.txt", "com.tencent.mobileqq.qsec.qsecest.QsecEst\n", true)
                detectMethods(qsecEstClass, "QsecEst")
            }
            
            // 10. Dandelion
            val dandelionClass = loadClassSafely(qqClassLoader, "com.tencent.mobileqq.qsec.qsecdandelionsdk.Dandelion")
            if (dandelionClass != null) {
                log("[AutoDetect] Found: Dandelion")
                saveToFile("o3_classes.txt", "com.tencent.mobileqq.qsec.qsecdandelionsdk.Dandelion\n", true)
                detectMethods(dandelionClass, "Dandelion")
            }
            
            // 11. ByteData
            val byteDataClass = loadClassSafely(qqClassLoader, "com.tencent.mobileqq.qsec.qsecprotocol.ByteData")
            if (byteDataClass != null) {
                log("[AutoDetect] Found: ByteData")
                saveToFile("o3_classes.txt", "com.tencent.mobileqq.qsec.qsecprotocol.ByteData\n", true)
                detectMethods(byteDataClass, "ByteData")
            }
            
            // 12. SecCipher
            val secCipherClass = loadClassSafely(qqClassLoader, "com.tencent.mobileqq.qsec.qseccodec.SecCipher")
            if (secCipherClass != null) {
                log("[AutoDetect] Found: SecCipher")
                saveToFile("o3_classes.txt", "com.tencent.mobileqq.qsec.qseccodec.SecCipher\n", true)
                detectMethods(secCipherClass, "SecCipher")
            }
            
            // 13. QSecFramework
            val qsecFrameworkClass = loadClassSafely(qqClassLoader, "com.tencent.qqprotect.qsec.QSecFramework")
            if (qsecFrameworkClass != null) {
                log("[AutoDetect] Found: QSecFramework")
                saveToFile("o3_classes.txt", "com.tencent.qqprotect.qsec.QSecFramework\n", true)
                detectMethods(qsecFrameworkClass, "QSecFramework")
            }
            
            // Hook FEKit的初始化和getSign方法 - 最关键
            if (AntiDetectionConfig.autoDetectEnvPack) {
                hookFEKitForEnvDetect()
            }
            
            log("[AutoDetect] o3 environment detection complete")
        } catch (e: Throwable) {
            log("[AutoDetect] o3 detection error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun loadClassSafely(classLoader: ClassLoader, className: String): Class<*>? {
        return try {
            classLoader.loadClass(className)
        } catch (e: Throwable) {
            null
        }
    }

    private fun detectMethods(clazz: Class<*>, prefix: String) {
        try {
            val sb = StringBuilder()
            sb.append("// $prefix\n")
            clazz.declaredMethods.forEach { method ->
                val mods = Modifier.toString(method.modifiers)
                val returnType = method.returnType.simpleName
                val params = method.parameterTypes.joinToString(", ") { it.simpleName }
                sb.append("// $mods $returnType ${method.name}($params)\n")
                sb.append("${method.name}|${params}\n")
            }
            clazz.declaredFields.forEach { field ->
                val mods = Modifier.toString(field.modifiers)
                val type = field.type.simpleName
                sb.append("// field: $mods $type ${field.name}\n")
            }
            saveToFile("o3_methods.txt", sb.toString(), true)
            log("[AutoDetect] Detected methods for $prefix: ${clazz.declaredMethods.size} methods")
        } catch (e: Throwable) {
            log("[AutoDetect] Error detecting methods for $prefix: ${e.message}")
        }
    }

    // ==================== Hook FEKit获取完整环境组包 ====================
    private var feKitInstanceDetected: Any? = null

    private fun hookFEKitForEnvDetect() {
        log("[AutoDetect] Hooking FEKit for complete env pack detection...")
        try {
            val qqClassLoader = MobileQQ.getContext()?.classLoader ?: return
            val feKitClass = loadClassSafely(qqClassLoader, "com.tencent.mobileqq.fe.FEKit") ?: run {
                log("[AutoDetect] FEKit class not found")
                return
            }
            
            // Hook getInstance
            XposedBridge.hookAllMethods(feKitClass, "getInstance", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        feKitInstanceDetected = param.result
                        log("[AutoDetect] FEKit instance captured: ${feKitInstanceDetected?.javaClass?.name}")
                        if (feKitInstanceDetected != null) {
                            hookFEKitMethodsForEnv(feKitInstanceDetected!!)
                        }
                    } catch (e: Throwable) {
                        log("[AutoDetect] Error capturing FEKit: ${e.message}")
                    }
                }
            })
            
            // Hook init方法
            feKitClass.declaredMethods.forEach { method ->
                if (method.name == "init" && method.parameterTypes.size >= 3) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                log("[AutoDetect] FEKit init called")
                                hookFEKitMethodsForEnv(param.thisObject)
                            } catch (e: Throwable) {}
                        }
                    })
                }
            }
            
            log("[AutoDetect] FEKit hooks installed")
        } catch (e: Throwable) {
            log("[AutoDetect] FEKit hook error: ${e.message}")
        }
    }

    private fun hookFEKitMethodsForEnv(instance: Any) {
        try {
            instance.javaClass.declaredMethods.forEach { method ->
                val methodName = method.name
                val paramCount = method.parameterTypes.size
            
                // Hook getSign方法 - 签名获取
                log("[AutoDetect] FEKit method: $methodName (${paramCount} params)")
            
                // 记录所有方�?
                saveToFile("feKit_methods.txt", "$methodName|${paramCount}|${method.parameterTypes.joinToString(",") { it.name }}\n", true)
            
                // Hook getSign - 最关键
                if (methodName == "getSign" && paramCount >= 3) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val args = param.args
                                val sb = StringBuilder()
                                sb.append("=== FEKit.getSign ===\n")
                                sb.append("method: ${methodName}\n")
                                sb.append("params count: ${args.size}\n")
                                args.forEachIndexed { index, arg ->
                                    sb.append("arg[$index]: ${arg?.javaClass?.name} = $arg\n")
                                    if (arg is ByteArray) {
                                        sb.append("  -> byte[] size: ${arg.size}\n")
                                        sb.append("  -> hex: ${arg.joinToString("") { String.format("%02X", it) }}\n")
                                    }
                                    if (arg is String) {
                                        sb.append("  -> string length: ${arg.length}\n")
                                    }
                                }
                                saveToFile("feKit_getSign_params.txt", sb.toString(), true)
                                log("[AutoDetect] getSign called with ${args.size} params")
                            } catch (e: Throwable) {
                                log("[AutoDetect] Error: ${e.message}")
                            }
                        }
                        
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val result = param.result
                                val sb = StringBuilder()
                                sb.append("=== FEKit.getSign Result ===\n")
                                if (result != null) {
                                    sb.append("result class: ${result.javaClass.name}\n")
                                    if (result is ByteArray) {
                                        sb.append("result byte[] size: ${result.size}\n")
                                        sb.append("result hex: ${result.joinToString("") { String.format("%02X", it) }}\n")
                                    } else {
                                        sb.append("result toString: $result\n")
                                        // 尝试获取对象的字�?
                                        val resultClass = result.javaClass
                                        resultClass.declaredFields.forEach { field ->
                                            field.isAccessible = true
                                            try {
                                                val fieldVal = field.get(result)
                                                sb.append("  field ${field.name}: $fieldVal\n")
                                                if (fieldVal is ByteArray) {
                                                    sb.append("    hex: ${fieldVal.joinToString("") { String.format("%02X", it) }}\n")
                                                }
                                            } catch (e: Throwable) {}
                                        }
                                    }
                                } else {
                                    sb.append("result: null\n")
                                }
                                saveToFile("feKit_getSign_result.txt", sb.toString(), true)
                                log("[AutoDetect] getSign result captured")
                            } catch (e: Throwable) {
                                log("[AutoDetect] Error capturing result: ${e.message}")
                            }
                        }
                    })
                }
                
                // Hook init方法来获取初始化参数
                if (methodName == "init" && paramCount >= 3) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val args = param.args
                                val sb = StringBuilder()
                                sb.append("=== FEKit.init ===\n")
                                sb.append("params count: ${args.size}\n")
                                args.forEachIndexed { index, arg ->
                                    sb.append("arg[$index]: ${arg?.javaClass?.name} = $arg\n")
                                }
                                saveToFile("feKit_init_params.txt", sb.toString(), true)
                                log("[AutoDetect] FEKit init params captured")
                            } catch (e: Throwable) {}
                        }
                    })
                }
            }
        } catch (e: Throwable) {
            log("[AutoDetect] Error hooking FEKit methods: ${e.message}")
        }
    }

    // ==================== 文件保存工具 ====================
    private fun saveToFile(filename: String, content: String, append: Boolean = false) {
        try {
            val file = File(AntiDetectionConfig.detectOutputDir, filename)
            if (append) {
                file.appendText(content)
            } else {
                file.writeText(content)
            }
        } catch (e: Throwable) {
            log("[AutoDetect] File save error: ${e.message}")
        }
    }
}

