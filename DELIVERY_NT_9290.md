# Shamrock NT — 9.2.90 适配版交付文档

> **包名**: `moe.RinShiona.Shamrock`
> **目标**: LSPosed 2.0.2+（基于 Zygisk Next 加载器）
> **测试机型**: 真机 Root，建议 Android 12+

---

## 0. 一句话总结

这是一个**为 QQ 9.2.90 NT 重构的 Shamrock** —— 包名换了、启动 hook 重写了、签名走原生快路径、装载已经从老 Xposed 迁到 LSPosed 2.0.2+（Zygisk Next）。

---

## 1. 关键变更清单

### 包名 / 元数据
| 项 | 旧 | 新 |
|---|---|---|
| Root 包 | `moe.fuqiuluo.*` | `moe.RinShiona.Shamrock.*` |
| `applicationId` | `moe.fuqiuluo.shamrock` | `moe.RinShiona.Shamrock` |
| AAR namespace | `moe.fuqiuluo.xposed` | `moe.RinShiona.Shamrock.xposed` |
| `xposedminversion` | 23 | **93** (LSPosed 2.0.2+ 必需) |
| `xposed_init` 入口 | `...fuqiuluo.shamrock.xposed.XposedEntry` | `moe.RinShiona.Shamrock.xposed.XposedEntry` |
| 受影响文件 | — | **251 个** 已自动迁移 |

### 启动 hook（XposedEntry.kt）—— 9.2.90 NT 关键修复
9.2.90 把老的 `LoadDex.b()` 删除了，所以原版 Shamrock 启动 hook 完全失效。新版用**四层降级链**：

1. **Tier 1** — `com.tencent.mobileqq.startup.step.LoadDex.b()`（兼容老版）
2. **Tier 2** — `com.tencent.common.app.BaseApplicationImpl.onCreate()` ✅ 9.2.90 推荐
3. **Tier 3** — `com.tencent.qqnt.startup.task.NtTask.onTaskStart()` (NT 任务基类)
4. **Tier 4** — 模糊扫 `startup.task.config` 兜底
5. **最后兜底** — `MobileQQ.onCreate()`

`AtomicBoolean.compareAndSet` 保证多 tier 同时触发时只初始化一次。

### Native hook 子系统（**重点**）
全部走 ByteDance **ShadowHook 1.0.10** 做 inline hook，源码在 `xposed/src/main/cpp/`：

```
shamrock_native.cpp     # JNI_OnLoad + 注册 native 方法
sign_native.cpp         # 签名 JNI 桥接 + 原生快路径
anti_detect_native.cpp  # fopen/openat 拦截 /proc/self/maps
hide_native.cpp         # dlopen 拦截 libxposed_*/lsposed/sandhook/zygisk
                        # readlink 拦截 Shamrock APK 路径
```

构建产物：**`libshamrocknt.so`**（注意是 `shamrocknt` —— "9.2.90 NT" —— 与 app 模块的 `libshamrock.so` 分离避免冲突）。

### sign 调用链
请求路径（HTTP `/sign` → 拿到结果）：

```
HTTP /sign 
   ↓ Ktor route GenerateQSign.kt:requestSign()
   ↓ IPC broadcast "fetch_ipc" → MSF 进程
   ↓ ShamrockIpc.get("qsign") → QSignerImpl
   ↓ QSignerImpl.sign()
      ├─ FAST PATH: ShamrockNative.getSign(qua, cmd, buf, seqBytes, uin)
      │      ↓ libshamrocknt.so:nativeGetSign
      │      ↓ 直接调用 QQ 进程内 QQSecuritySign.getSign 的 native 函数指针
      │      ↓ 绕过 Java 层 ArtTiHook 检测
      │      ↓ 返回原生 SignResult
      └─ SLOW PATH (fallback): 反射 FEKit.getSign(cmd, buf, seq, uin)
```

### 反检测层
两层叠加（**这就是"不强出 sign 而是过检测拿真 sign"的实现方式**）：

**Java 层（AntiDetection.kt）**：
- Class.forName/loadClass 过滤 xposed 关键字
- SystemProperties.get 屏蔽 `ro.debuggable`/`xposed*`
- File.exists/canRead/isFile 屏蔽 magisk/xposed 路径
- PackageManager.getInstalledPackages 过滤危险 APP
- **NEW 9.2.90**: `QSec.detectMethod` 强制返回 false（绕 ArtTiHook 探针）
- **NEW 9.2.90**: NT `NtTask.onTaskStart` 跳过 `ArtTiHook` / `GuardCheck` / `CodeCheck` 任务

**Native 层（libshamrocknt.so）**：
- `fopen("/proc/self/maps")` → 返回过滤后的内容（剥离 xposed/lsposed/shamrock/magisk/zygisk 字符串）
- `openat()` 上同样的过滤（对付绕过 libc 的检测代码）
- `dlopen("libxposed_*"/"libsandhook"/"libzygisk"/"libfrida-*")` → 返回 NULL
- `readlink("/proc/self/exe")` → 把 Shamrock 路径改成 QQ APK 真实路径

---

## 2. 文件清单

### 新增
```
xposed/src/main/cpp/shamrock_native.cpp          # JNI 入口
xposed/src/main/cpp/sign_native.cpp              # 签名子系统
xposed/src/main/cpp/anti_detect_native.cpp       # 反检测子系统  
xposed/src/main/cpp/hide_native.cpp              # 隐藏子系统
xposed/src/main/java/moe/RinShiona/Shamrock/xposed/ipc/ShamrockIpc.kt          # Binder 注册表
xposed/src/main/java/moe/RinShiona/Shamrock/xposed/ipc/qsign/IQSign.kt         # Parcelable
xposed/src/main/java/moe/RinShiona/Shamrock/xposed/ipc/bytedata/IByteDataSign.kt
xposed/src/main/java/moe/RinShiona/Shamrock/xposed/ipc/impl/QSignerImpl.kt     # FEKit 反射桥
xposed/src/main/java/moe/RinShiona/Shamrock/xposed/ipc/impl/ByteDataImpl.kt
xposed/src/main/java/moe/RinShiona/Shamrock/xposed/ipc/impl/ShamrockNative.kt  # JNI 桥接
9.2.90_rev/class_locations.txt              # 9.2.90 类位置情报
9.2.90_rev/key_classes_methods.txt          # 9.2.90 方法签名情报
Shamrock-master/NT_9290_ADAPT_NOTES.md      # 适配速查表
```

### 重写
```
xposed/src/main/java/moe/RinShiona/Shamrock/xposed/XposedEntry.kt      # 四层降级启动 hook
xposed/src/main/java/moe/RinShiona/Shamrock/xposed/actions/AntiDetection.kt  # 干净重写
xposed/src/main/java/moe/RinShiona/Shamrock/xposed/actions/IpcService.kt     # 接入 QSignerImpl/ByteDataImpl
xposed/src/main/cpp/CMakeLists.txt                                # ShadowHook + 新源文件
xposed/build.gradle.kts                                            # prefab + shadowhook 依赖
xposed/src/main/aidl/.../IQSign.aidl                              # interface → parcelable
xposed/src/main/aidl/.../IByteDataSign.aidl                       # interface → parcelable
app/src/main/AndroidManifest.xml                                  # xposedminversion=93
xposed/src/main/assets/xposed_init                                # 新入口路径
```

---

## 3. 编译

### 前置条件
- Android Studio Hedgehog 或更新（AGP 8.1+）
- NDK 25.1.8937393（在 app/build.gradle.kts 里写死了）
- CMake 3.22.1
- 网络能访问 maven central（拉 ShadowHook AAR）

### 命令
```bash
cd Shamrock-master/
./gradlew clean
./gradlew :app:assembleAppRelease       # 全 ABI（arm64+x86_64）
./gradlew :app:assembleArm64Release     # 只要 arm64-v8a，更小
```

产物：`app/build/outputs/apk/.../Shamrock-vX.Y.Z-arm64.apk`

### 安装
1. 装好 LSPosed 2.0.2+ (Zygisk Next 模式)
2. 安装 `Shamrock-vX.Y.Z-arm64.apk`
3. 在 LSPosed 管理器里启用 Shamrock 模块，作用域：`com.tencent.mobileqq` + `android`
4. 强杀 QQ 重启

---

## 4. ⚠️ 我能交付的 vs. 你需要真机分析的

### ✅ 框架已就位（开箱即可）
- 包名、LSPosed 元数据、启动 hook 链、反射 sign fallback
- ShadowHook 集成与 PLT/inline hook 注册
- `/proc/self/maps` 过滤、dlopen 黑名单、readlink 路径伪装
- AntiDetection 全部 Java 层 hook（Class.forName / SystemProperties / File / PackageManager）

### ⚠️ 需要真机动态分析才能 100% 闭环
**`sign_native.cpp:resolve_orig_getSign()`** 目前返回 NULL，意味着 sign 走的是**反射慢路径**（仍然能拿到 sign，但走 Java 层，会被 QQ 的 ArtTiHook 探针扫到）。

要变成真正的 **"绕过检测直接拿原生 sign"**，需要在你真机上做：

1. 在 `libfekit.so` 加载后用 Frida hook `JNIEnv->RegisterNatives`，捕获 `getSign` 注册时的函数指针：
   ```js
   Interceptor.attach(Module.getExportByName("libfekit.so", "JNI_OnLoad"), {
       onEnter: function(args) {
           const env = args[0];
           // ... 拦截 RegisterNatives，记录 getSign fnPtr
       }
   });
   ```
2. 把得到的 `getSign` 函数指针偏移（相对 libfekit.so base）填到 `sign_native.cpp` 的 `g_orig_getSign` 解析逻辑里
3. 用 IDA/Ghidra 找到 libfekit.so 里的 ArtTiHook 探针函数（通常在 `JNI_OnLoad` 之后调用），把它 inline hook 成恒返 0

只有这一步做完，sign 才真正达到你说的"完全 Native + 过检测"。我在代码里用 `TODO[real-device]` 标记了所有需要填的位置。

### ⚠️ 真机调试技术栈推荐
- **Frida 16+** —— 动态分析
- **IDA Pro 9.x** 或 **Ghidra 11** —— 静态分析 libfekit.so
- **adb logcat** + `ShamrockNative` tag —— 看 native 日志

---

## 5. 已知 gotcha

1. **`qqinterface` 模块依然是 disabled 状态** —— `settings.gradle.kts` 没包含它。`xposed/build.gradle.kts` 第 100 行 `// compileOnly (project(":qqinterface")) // Temporarily disabled` 保留原状。如果以后要启用，记得修复 `qqinterface/src/main/java/com/tencent/mobileqq/fe/FEKit.java`（实际内容是 Kotlin 但扩展名 `.java`，必然编译失败）。

2. **空的 `moe.RinShiona.Shamrock`-style 目录**: 已经清理。如果你看到 `moe/fuqiuluo` 残留，那只是 `xposed/build/generated/aidl_source_output_dir/` 里的旧生成物，下次 `gradlew clean` 后自动重生。

3. **ShadowHook 必须能从 maven central 下载**。如果你的环境没网，可以手动放 `com.bytedance.android:shadowhook:1.0.10` 的 AAR 到本地仓库。

4. **App 模块的 `libshamrock.so` 与我们的 `libshamrocknt.so` 互不冲突**，但都得在 APK 里。Gradle 会自动打包两者。

---

## 6. 测试清单

启动 QQ 后，logcat 应该能看到：

```
ShamrockZygisk  : (无输出，因为我们不是 Zygisk 模块)
XposedBridge    : Shamrock: [Tier 2] hooked BaseApplicationImpl.onCreate() (NT main entry)
XposedBridge    : Shamrock: startup triggered via BaseApplicationImpl.onCreate
XposedBridge    : Shamrock: Process Name = com.tencent.mobileqq
ShamrockNative  : JNI_OnLoad: registered 3 native methods on moe/RinShiona/Shamrock/...
ShamrockSign    : sign_init: starting
ShamrockSign    : shadowhook initialized (SHARED mode)
ShamrockAnti    : anti_detect_init: starting
ShamrockAnti    : fopen hook installed
ShamrockAnti    : openat hook installed
ShamrockHide    : hide_init: starting
ShamrockHide    : dlopen hook installed
ShamrockHide    : readlink hook installed
ShamrockNative  : nativeInit: sign=0 anti=0 hide=0  ← 三个 0 = 成功
XposedBridge    : [AntiDetection] [Phase CoreDetection] OK
XposedBridge    : [AntiDetection] [Phase FEKitSignHook] OK
XposedBridge    : [AntiDetection] [Phase ArtTiHookBypass] OK
```

如果 sign 接口能拿到非空 sign：✅
如果 QQ 不再启动后秒退：✅
