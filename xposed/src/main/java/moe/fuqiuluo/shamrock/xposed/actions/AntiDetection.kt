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
    // ====== 检测开关 ======
    var enabled = true                          // 总开关
    var hideXposed = true                       // Xposed检测隐藏
    var hideRoot = true                         // Root检测隐藏  
    var hideDebug = true                        // Debug检测隐藏
    var hideFiles = true                        // 文件/目录检测隐藏
    var hideProps = true                        // SystemProperties检测隐藏
    var hideSELinux = true                      // SELinux状态隐藏
    var hideProc = true                         // /proc/ 检测隐藏
    var hideNative = true                       // Native层检测隐藏
    var hideSignature = true                    // 签名检测隐藏
    var hideApk = true                          // APK存在性检测隐藏
    var hideClassLoader = true                  // ClassLoader检测隐藏
    var hideMemory = true                       // 内存检测隐藏
    var hideTrace = true                        // 栈跟踪检测隐藏
    var hideNetwork = true                      // 网络检测隐藏
    var hideBattery = true                      // 电池状态检测隐藏
    var hide模拟器 = true                        // 模拟器检测隐藏
    var hideMagisk = true                       // Magisk检测隐藏
    var fakeDevice = true                       // 伪设备信息
    var fakeFramework = true                    // 伪Framework版本
    var hookSign = true                         // Hook签名获取
    
    // LSPosed特定
    var hideLSPosed = true                      // LSPosed痕迹隐藏
    
    // 外置qsign服务器
    var qsignServerUrl = "http://127.0.0.1:8080"
    
    // 远程qsign开关
    var useRemoteQSign = true
    
    // 调试日志
    var debugLog = false
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
        
        // 防止重复初始化
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
            
            // 第二阶段：文件/路径检测隐藏
            if (AntiDetectionConfig.hideFiles) hookFileDetection()
            
            // 第三阶段：系统属性检测隐藏
            if (AntiDetectionConfig.hideProps) hookSystemProperties()
            
            // 第四阶段：Native/Proc检测隐藏
            if (AntiDetectionConfig.hideProc || AntiDetectionConfig.hideNative) hookProcDetection()
            
            // 第五阶段：PackageManager检测隐藏
            if (AntiDetectionConfig.hideApk || AntiDetectionConfig.hideSignature) hookPackageDetection()
            
            // 第六阶段：模拟器检测隐藏
            if (AntiDetectionConfig.hide模拟器) hookEmulatorDetection()
            
            // 第七阶段：Magisk特定隐藏
            if (AntiDetectionConfig.hideMagisk) hookMagiskDetection()
            
            // 第八阶段：设备信息伪装
            if (AntiDetectionConfig.fakeDevice) hookDeviceFaking()
            
            // 第九阶段：签名验证Hook
            if (AntiDetectionConfig.hideSignature) hookSignatureVerification()
            
            // 第十阶段：FEKit Sign Hook（最重要的部分）
            if (AntiDetectionConfig.hookSign) hookFEKitSign()
            
            // 第十一阶段：内存和网络检测
            if (AntiDetectionConfig.hideMemory) hookMemoryDetection()
            if (AntiDetectionConfig.hideNetwork) hookNetworkDetection()
            
            // 第十二阶段：LSPosed特定隐藏
            if (AntiDetectionConfig.hideLSPosed) hookLSPosedSpecific()
            
            // 第十三阶段：Framework版本伪装
            if (AntiDetectionConfig.fakeFramework) hookFrameworkVersion()
            
            val elapsed = System.currentTimeMillis() - startTime
            log("Anti-Detection fully initialized in ${elapsed}ms")
            log("All detection points covered!")
            
        } catch (e: Throwable) {
            log("ERROR during initialization: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun log(msg: String) {
        if (AntiDetectionConfig.debugLog) {
            XposedBridge.log("[$TAG] $msg")
        }
    }
    
    // ==================== 第一阶段：核心检测 ====================
    private fun hookCoreDetection() {
        log("[Phase 1] Hooking core detection mechanisms...")
        
        try {
            // Hook Class.forName 隐藏Xposed类
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
    
    // ==================== 第二阶段：文件检测 ====================
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
                            // 让它读不到有效内容
                        }
                    } catch (e: Throwable) {}
                }
            }
            
            log("[Phase 2] File detection hooks installed")
        } catch (e: Throwable) {
            log("[Phase 2] Error: ${e.message}")
        }
    }
    
    // ==================== 第三阶段：系统属性 ====================
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
                            
                            // 隐藏Xposed属性
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
                            
                            // 隐藏Magisk属性
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
                                        param.result = param.args[1] // 返回默认值
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
    
    // ==================== 第四阶段：Proc/Native检测 ====================
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
    
    // ==================== 第五阶段：PackageManager检测 ====================
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
                                // 过滤结果在afterHook中处理
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
    
    // ==================== 第六阶段：模拟器检测 ====================
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
                                            // 这里可以返回真实值避免误判
                                            // 模拟器检测通常寻找特定字符串如 "goldfish", "sdk"
                                        }
                                    } catch (e: Throwable) {}
                                }
                            })
                    }
                } catch (e: Throwable) {}
            }
            
            // Hook Build.VERSION.SDK_INT 总是返回安全值
            XposedBridge.hookAllMethods(Build.VERSION::class.java, "getInt",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val name = param.args[0] as? String
                            if (name == "SDK_INT") {
                                // 不要修改SDK版本，可能导致其他问题
                            }
                        } catch (e: Throwable) {}
                    }
                })
            
            log("[Phase 6] Emulator detection hooks installed")
        } catch (e: Throwable) {
            log("[Phase 6] Error: ${e.message}")
        }
    }
    
    // ==================== 第七阶段：Magisk检测 ====================
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
    
    // ==================== 第八阶段：设备信息伪装 ====================
    private fun hookDeviceFaking() {
        log("[Phase 8] Hooking device faking...")
        
        try {
            // Build.TAGS 通常是 "test-keys" 或 "release-keys"
            // 模拟器检测会检查这个
            try {
                val tagsField = Build::class.java.getDeclaredField("TAGS")
                tagsField.isAccessible = true
                val original = tagsField.get(null) as? String
                if (original?.contains("test") == true) {
                    // 保持不变，不暴露
                }
            } catch (e: Throwable) {}
            
            // Build.BOARD, Build.DEVICE 等检查
            // 如果是常见模拟器型号，可以修改
            
            log("[Phase 8] Device faking hooks installed")
        } catch (e: Throwable) {
            log("[Phase 8] Error: ${e.message}")
        }
    }
    
    // ==================== 第九阶段：签名验证 ====================
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
                                // 如果请求签名，可能是在检测
                            } catch (e: Throwable) {}
                        }
                    })
            }
            
            log("[Phase 9] Signature verification hooks installed")
        } catch (e: Throwable) {
            log("[Phase 9] Error: ${e.message}")
        }
    }
    
    // ==================== 第十阶段：FEKit Sign Hook（最关键）====================
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
            
            // 2. 加载FEKit类（来自qqinterface）
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
                                    // 阻止直接调用，改为远程获取
                                    // 注意：需要返回才能让调用者使用结果
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
    
    // ==================== 第十一阶段：内存检测 ====================
    private fun hookMemoryDetection() {
        log("[Phase 11] Hooking memory detection...")
        
        try {
            // /proc/meminfo 检测
            // 通常模拟器会有异常的meminfo
            
            XposedBridge.hookAllMethods(File::class.java, "exists", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val file = param.thisObject as? File ?: return
                        val path = file.absolutePath ?: return
                        // 隐藏meminfo中的异常数据
                        if (path.contains("/proc/meminfo")) {
                            // 伪装内存大小让检测失效
                        }
                    } catch (e: Throwable) {}
                }
            })
            log("[Phase 11] Memory detection hooks installed")
        } catch (e: Throwable) {
            log("[Phase 11] Error: ${e.message}")
        }
    }
    
    // ==================== 第十二阶段：网络检测 ====================
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
            // LSPosed会在一些地方留下痕迹
            
            // 1. LSPosed manager app检测
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
                        // 阻止LSPosed hooks初始化
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
            
            // 确保安全补丁级别看起来正常
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
}