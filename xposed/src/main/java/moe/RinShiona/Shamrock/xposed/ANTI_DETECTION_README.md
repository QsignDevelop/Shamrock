# Shamrock Ultra Anti-Detection Module

## 概述

这是一个**完整兼容LSPosed**的超级反检测模块，设计目标是让QQ无法检测到任何Xposed/Magisk/Root环境，实现"全过检测"。

## 功能列表

### 14个防护阶段，50+检测点全覆盖

| 阶段 | 防护类型 | 主要检测点 |
|------|----------|-----------|
| Phase 1 | 核心检测 | Class.forName, ClassLoader.loadClass, getClassLoader |
| Phase 2 | 文件检测 | File.exists, canRead, isFile, FileInputStream |
| Phase 3 | 系统属性 | SystemProperties, System.getProperty |
| Phase 4 | Proc/Native | Runtime.exec, Process detection |
| Phase 5 | Package检测 | getPackageInfo, getApplicationInfo |
| Phase 6 | 模拟器检测 | Build model/board, cpuabi |
| Phase 7 | Magisk检测 | magisk paths, su.d, native lib |
| Phase 8 | 设备伪装 | Build.TAGS, DEVICE info |
| Phase 9 | 签名验证 | PackageManager signature flags |
| Phase 10 | **FEKit Sign Hook** | **核心签名拦截** |
| Phase 11 | 内存检测 | /proc/meminfo |
| Phase 12 | 网络检测 | proxyHost, proxyPort |
| Phase 13 | LSPosed特定 | LSPosedManager, LSPHooks |
| Phase 14 | Framework伪装 | VERSION.SDK_INT, SECURITY_PATCH |

## 检测关键词覆盖

### Xposed相关
- `xposed`, `Xposed`, `XPOSED`
- `LSPosed`, `lspx`
- `de.robv.android.xposed.installer`
- `com.swift.internal`
- `org.lycore.gg`
- `moe.shizuku`, `shizuku`

### Magisk相关
- `magisk`, `Magisk`, `MAGISK`
- `/sbin/.magisk`
- `/data/adb/magisk`
- `su.d`, `magiskhide`

### Root检测
- `/system/app/SuperSU`
- `/system/xbin/su`
- `/sbin/su`
- `/vendor/bin/su`
- `/data/local/xbin/su`

### 危险应用
- `de.robv.android.xposed.installer`
- `eu.chainfire.supersu`
- `com.koushikdutta.superuser`
- `com.noshufou.android.su`
- `com.topjohnwu.magisk`

## 配置说明

### 默认配置 (AntiDetectionConfig.kt)

```kotlin
// 主开关
enabled = true                      // 全部启用

// Xposed检测防护
hideXposed = true                   // 隐藏Xposed
hideLSPosed = true                  // 隐藏LSPosed

// Root/Magisk防护
hideRoot = true                     // 隐藏Root
hideMagisk = true                   // 隐藏Magisk

// 签名相关
hookSign = true                     // Hook签名获取（核心）
useRemoteQSign = true               // 使用远程服务器
qsignServerUrl = "http://127.0.0.1:8080"

// 调试
debugLog = false                    // 生产环境关闭调试
```

### 自定义配置

可以在Shamrock配置文件中动态修改：

```kotlin
// 禁用特定功能
AntiDetectionConfig.hideRoot = false      // 允许检测root
AntiDetectionConfig.debugLog = true       // 开启调试

// 修改qsign服务器
AntiDetectionConfig.qsignServerUrl = "http://your-server:8080"
```

## Sign工作流程

```
QQ App
    │
    ▼
FEKit.getSign(cmd, buffer, seq, uin)
    │
    ├─────────────────────────────────────┐
    ▼                                     ▼
[AntiDetection Hook]              [Before Hook]
拦截请求                            记录日志
    │                                     │
    ▼                                     ▼
检查 FEKit 结果                      检查 result
    │                                     │
    ├─ result.token 有效 ─────────────────┤
    │         │                           │
    │         ▼                           │
    │      返回结果                        │
    │                                     │
    └─ result.token 为空 ─────────────────┘
              │
              ▼
    [useRemoteQSign = true]
              │
              ▼
    请求远程 qsignServerUrl
    /sign?cmd=...&seq=...&uin=...
              │
              ▼
         解析JSON响应
    {"token":"...", "sign":"...", "extra":"..."}
              │
              ▼
         返回真实签名
```

## 文件说明

### AntiDetection.kt
主要反检测逻辑，包含14个阶段的Hook

### AntiDetectionConfig.kt  
全局配置对象，控制所有开关

### FEKit.java (qqinterface)
真实签名实现，转发请求到外部qsign服务器

## 编译要求

- LSPosed Framework 或 Xposed Framework
- Android 9+ (API 24+)
- QQ 9.2.85 或类似版本

## 已知限制

1. **Native层检测**: 部分QQ native层检测可能无法完全屏蔽
2. **硬件检测**: 模拟器硬件特征可能仍有残留
3. **服务器依赖**: 使用远程qsign需要稳定的服务器连接

## 调试

开启调试日志：
```kotlin
AntiDetectionConfig.debugLog = true
```

查看日志：
```bash
adb logcat | grep AntiDetection
```

## 免责声明

本模块仅供学习研究使用，请勿用于违规用途。