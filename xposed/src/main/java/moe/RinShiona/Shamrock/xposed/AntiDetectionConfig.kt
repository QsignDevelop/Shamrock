package moe.RinShiona.Shamrock.xposed

/**
 * ============================================
 *  Anti-Detection Configuration
 *  全局反检测配置管理器
 * ============================================
 * 
 * 控制所有反检测功能的开关
 * 默认全部开启以获得最大保护
 * 
 * 使用方法:
 *   AntiDetectionConfig.hideXposed = false  // 禁用Xposed隐藏
 *   AntiDetectionConfig.debugLog = true     // 开启调试日志
 */
object AntiDetectionConfig {
    
    // ====== 主开关 ======
    /** 总开关，false则全部禁用 */
    var enabled = true
    /** 反检测 hook（EarlyAntiDetection）。9.2.90 在 Application 就绪后安装，默认开启。 */
    var earlyEnabled = true
    
    // ====== Xposed检测防护 =====
    /** 隐藏Xposed模块存在性 */
    var hideXposed = true
    
    /** 隐藏LSPosed痕迹 */
    var hideLSPosed = true
    
    /** 隐藏ClassLoader检测 */
    var hideClassLoader = true
    
    // ====== Root/Magisk防护 =====
    /** 隐藏Root存在性 */
    var hideRoot = true
    
    /** 隐藏Magisk特定检测 */
    var hideMagisk = true
    
    /** 隐藏su命令执行 */
    var hideSuCommand = true
    
    // ====== Debug检测防护 =====
    /** 隐藏Debug标志 */
    var hideDebug = true
    
    /** 隐藏ApplicationInfo flags */
    var hideAppFlags = true
    
    // ====== 文件/路径检测防护 =====
    /** 隐藏Xposed/Magisk相关文件 */
    var hideFiles = true
    
    /** 隐藏PackageInfo签名 */
    var hideSignature = true
    
    /** 隐藏APK存在性 */
    var hideApk = true
    
    // ====== 系统属性检测防护 =====
    /** 隐藏SystemProperties */
    var hideProps = true
    
    /** 隐藏SELinux状态 */
    var hideSELinux = true
    
    /** 隐藏ro.debuggable等安全属性 */
    var hideSecureProps = true
    
    // ====== Proc/Native检测防护 =====
    /** 隐藏/proc/检测 */
    var hideProc = true
    
    /** 隐藏Native层检测 */
    var hideNative = true
    
    /** 隐藏内存检测 */
    var hideMemory = true
    
    /** 隐藏栈跟踪检测 */
    var hideTrace = true
    
    // ====== 设备信息伪装 =====
    /** 伪装设备信息 */
    var fakeDevice = true
    
    /** 伪装Framework版本 */
    var fakeFramework = true
    
    /** 模拟器检测防护 */
    var hideEmulator = true
    
    // ====== 网络检测防护 =====
    /** 隐藏代理/抓包工具检测 */
    var hideNetwork = true
    
    /** 电池状态检测防护 */
    var hideBattery = true
    
    // ====== Sign相关 =====
    /** Hook签名获取（核心功能） */
    var hookSign = true
    
    /** 使用远程qsign服务器 */
    var useRemoteQSign = true
    
    /** 远程qsign服务器地址 */
    var qsignServerUrl = "http://127.0.0.1:8080"
    
    // ====== 调试 =====
    /** 开启详细调试日志 */
    var debugLog = false
    
    /**
     * 重置所有设置为默认值
     */
    fun resetToDefaults() {
        enabled = true
        earlyEnabled = true
        hideXposed = true
        hideLSPosed = true
        hideClassLoader = true
        hideRoot = true
        hideMagisk = true
        hideSuCommand = true
        hideDebug = true
        hideAppFlags = true
        hideFiles = true
        hideSignature = true
        hideApk = true
        hideProps = true
        hideSELinux = true
        hideSecureProps = true
        hideProc = true
        hideNative = true
        hideMemory = true
        hideTrace = true
        fakeDevice = true
        fakeFramework = true
        hideEmulator = true
        hideNetwork = true
        hideBattery = true
        hookSign = true
        useRemoteQSign = true
        qsignServerUrl = "http://127.0.0.1:8080"
        debugLog = false
    }
    
    /**
     * 获取当前配置摘要
     */
    fun getSummary(): String {
        return buildString {
            appendLine("=== AntiDetection Config ===")
            appendLine("Enabled: $enabled")
            appendLine("Early Enabled: $earlyEnabled")
            appendLine("Xposed Hide: $hideXposed")
            appendLine("LSPosed Hide: $hideLSPosed")
            appendLine("Root Hide: $hideRoot")
            appendLine("Magisk Hide: $hideMagisk")
            appendLine("Debug Hide: $hideDebug")
            appendLine("Files Hide: $hideFiles")
            appendLine("Props Hide: $hideProps")
            appendLine("Proc Hide: $hideProc")
            appendLine("Device Fake: $fakeDevice")
            appendLine("Emulator Hide: $hideEmulator")
            appendLine("Network Hide: $hideNetwork")
            appendLine("Sign Hook: $hookSign")
            appendLine("Remote QSign: $useRemoteQSign ($qsignServerUrl)")
            appendLine("Debug Log: $debugLog")
        }
    }

    /**
     * 是否允许安装 EarlyAntiDetection。
     * 关闭总开关时始终禁用；早期开关单独可控，便于排查库加载误伤。
     */
    fun allowEarlyHooks(): Boolean = enabled && earlyEnabled
}