# Shamrock 9.2.90 NT 适配 — 关键签名速查表

本文档基于 9.2.90 APK 逆向得出，标注了与 9.2.85 / 9.1.80 的差异。

---

## 1. 启动 Hook 点

### 旧版（9.2.85 及之前）
```
com.tencent.mobileqq.startup.step.LoadDex.b() → boolean
```
**9.2.90 已完全删除该类！** Shamrock 现有 `XposedEntry.kt` 的 hook 100% 失败。

### 新版（9.2.90）

#### 推荐顺序 A — 最稳定（强烈推荐）
```kotlin
// 永远会被调用，无混淆，不会被 NT 重构干掉
classLoader.loadClass("com.tencent.common.app.BaseApplicationImpl")
    .getDeclaredMethod("onCreate")
    .let { XposedBridge.hookMethod(it, hook) }
```

#### 备选顺序 B — 早期 attach
```kotlin
classLoader.loadClass("com.tencent.common.app.BaseApplicationImpl")
    .getDeclaredMethod("attachBaseContext", Context::class.java)
```

#### 备选顺序 C — 通过 ColdStartupTask 枚举定位
```
Lcom/tencent/mobileqq/startup/task/config/ColdStartupTask;
  关键 enum 值：
    LoadDexTask                ← 加载 dex
    LoadFeKitSoTask            ← 加载 libfekit.so（QSign 核心）
    ArtTiHookTask              ← 反 Xposed/LSPosed 检测 🔥
    SafeO3InitTask             ← Safe O3 初始化
    GuardInitTask              ← Guard 启动
    CodeCheckTask              ← 代码完整性检查
    WTRefreshNTSignTask        ← NT 签名刷新
    WTSigCheckTask             ← WT 签名校验
```

---

## 2. FEKit 类（核心签名类）

`Lcom/tencent/mobileqq/fe/FEKit;`

```java
public static FEKit getInstance()

// 主要的 sign 入口（4 参数版本！9.2.90 是 4 参数）
public QQSecuritySign$SignResult getSign(
    String cmd,
    byte[] buffer,
    int seq,
    String uin          // ← 9.2.90 新增的第 4 参数
)

// 旧版兼容（3 参数）
public QQSecuritySign$SignResult getSign(
    String cmd,
    byte[] buffer,
    int seq
)

public synchronized void init(
    Context, String, String, String, String, String
)

public void dispatchEvent(String, String)
public void dispatchEvent(String, String, EventCallback)
public byte[] getFeKitAttach(Context, String, String, String)

public List getCmdWhiteList()
public boolean needSign(String cmd)
public boolean checkStatus()
public boolean loadSo()
public void requestToken()
public void onConnOpened()
public synchronized void onReceiveSecError(String, long, long)
public synchronized void onAccountChange(String)
```

---

## 3. FEKitMain（9.2.90 新增主进程入口）

`Lcom/tencent/mobileqq/fe/FEKitMain;`

```java
public static FEKitMain getInstance()
public synchronized void initM(
    Context,
    String,
    String,
    String,
    String,
    ChannelProxy        // 通过 ChannelProxy 桥接 MSF
)
public void appForeground(boolean)
public synchronized void stopM()
public void updateUin(String)
```

---

## 4. QQSecuritySign（native 方法直连 libfekit.so）

`Lcom/tencent/mobileqq/sign/QQSecuritySign;`

```java
public static synchronized QQSecuritySign getInstance()
public void init(String)

// 4+1 参数（QSec, cmd, buffer, seq_bytes, uin）— 9.2.90 主入口
public QQSecuritySign$SignResult getSign(
    QSec qsec, String cmd, byte[] buffer, byte[] seq_bytes, String uin
)

// === native 方法 ===
private native QQSecuritySign$SignResult getSign(
    QSec, String, String, byte[], byte[], String
)
public native void dispatchEvent(String, String, EventCallback)
public native void dispatchEventPB(           // ← 9.2.90 新增 PB 版
    String, String, byte[], EventCallback
)
public native void initSafeMode(boolean)
public native void requestToken()
public native void requestTokenMain(boolean)
public native void notifyCamera(             // ← 9.2.90 新增
    String, String, String, String, String, String, EventCallback
)
public native void notifyFaceDetect(         // ← 9.2.90 新增
    String, String, String, EventCallback
)
public native void ocrAndEmbedingReport(     // ← 9.2.90 新增
    String, String, String, String, float[], float
)
public native void safeUiReport(             // ← 9.2.90 新增
    String, String, String, EventCallback
)
```

### SignResult 字段（无变化）
```java
public byte[] token
public byte[] sign
public byte[] extra
```

---

## 5. QSec（包含新增的 getFeKitAttach）

`Lcom/tencent/mobileqq/qsec/qsecurity/QSec;`

```java
public static QSec getInstance()
public void init(Context, String, String, String, String, String)
public void initSign()

public byte[] getSign(String, byte[])
public byte[] getLiteSign(String, byte[])
public byte[] getSignEntry(String, byte[])
public byte[] getFeKitAttach(                 // ← 9.2.90 新增
    Context, String, String, String
)

public String getEstInfo()                    // 9.1.x 仅此版本
public String getEstInfo(Context, String)     // ← 9.2.90 新增带 ctx
public boolean detectMethod(String, String)
public int execTasks(Context, int)
public synchronized byte[] getXpsInfo()
public void updateO3DID(String)
public void updateUserID(String)

// native
private native int doReport(String, String, String, String)
private native int doSomething(Context, int)
private native byte[] getXwDebugID(String)
```

---

## 6. QSecConfig

`Lcom/tencent/mobileqq/qsec/qsecurity/QSecConfig;`

```java
public static String business_uin
public static String business_seed
public static String business_guid
public static String business_q36
public static String business_o3did
public static String business_qua
public static int    business_os
public static byte[] CONFIG_KEY_BUF
public static int    CONFIG_KEY_ID
public static int    CONFIG_TIME_GAP
public static int    HEART_BEAT_SEQ_NUM
public static int    sign_strategy
public static Context sContext

public static void setupBusinessInfo(
    Context, String, String, String, String, String, String
)
```

---

## 7. QsecEst（est 信息）

`Lcom/tencent/mobileqq/qsec/qsecest/QsecEst;`

```java
public static String a(Context, String, String)
private static native byte[] d(Context, String, String)
```

**注意：9.1.x 的 `p(Context, int)` 接口在 9.2.90 已被弃用，** 改成 `a(Context, String, String)`。

---

## 8. Dandelion（energy）

`Lcom/tencent/mobileqq/qsec/qsecdandelionsdk/Dandelion;`

```java
public static Dandelion getInstance()
public void init()
public String getVersion()
public byte[] fly(String, byte[])
private native byte[] energy(Object, Object)
```

---

## 9. ByteData（getByte / sign）

`Lcom/tencent/mobileqq/qsec/qsecprotocol/ByteData;`  
`Lcom/tencent/secprotocol/ByteData;`（TIM 用）

```java
public static ByteData getInstance()
public void init(Context)
public byte[] getSign(String, String, byte[])
private native byte[] getByte(Context, Object)
```

---

## 10. ChannelProxy（FEKitMain 用）

`Lcom/tencent/mobileqq/channel/ChannelProxy;`

```java
public abstract void sendMessageInner(String cmd, byte[] data, long seq)
```

---

## 11. DeepSleepDetector

`Lcom/tencent/mobileqq/fe/utils/DeepSleepDetector;`

```java
public static String getCheckResult()
public static void startCheck()
private static void stopCheck()        // ← 注意是 private
```

---

## 12. FEBound（变换器）

`Lcom/tencent/mobileqq/dt/model/FEBound;`

```java
public static byte[] transform(int mode, byte[] data)
```

---

## 13. Dtc（设备指纹）

`Lcom/tencent/mobileqq/dt/app/Dtc;`

**核心方法（被 native 反查）：**
```java
public static void init(Context, String)
public static String getOaid()
public static String getQimei36()
public static String getPropSafe(String)
public static String mmKVValue(String)
public static String mmQsecKVValue(String)
public static void mmKVSaveValue(String, String)
public static String getAndroidID()
public static String getApkPath(String)
public static String getAppVersionName(String)
public static String getAppVersionCode(String)
public static String getAppInstallTime(String)
public static String getDensity(String)
public static String getFontDpi(String)
public static String getDisplayInfo(String)
public static String getScreenSize(String)
public static String getStorage(String)
public static String getNativeLibraryDir()
public static String getPackageName()
public static String getNetworkInterfaceNames()
public static String getCpuCores()
public static String getBSSID(Context)
public static String getLoginUins(String)
public static String getBatteryCap(String)
public static String getSystemFont()
public static String getSystemStartTime()
public static String getLibraryList(String)
public static String getPluginInfo(String)
public static String getGoogleCertsVerify()
public static String getNetWorkInfo(String)
public static String getOaid()
public static String getUid(String)
public static String getUUID(String)
public static String getCMC(String)
public static String getIME(String)
public static String systemGetSafe(String)
public static boolean checkAppInstalled(String)
public static boolean isAbnormalConfig()
public static boolean isDebugVersion()
public static boolean isMsfConnected()
public static int dtcSendMessage(String, byte[], long)
public static void runNativeFuncOnJavaThread(long)
public static void runNativeFuncOnUiThread(long)
public static String mmkvQsecAllKeys(String)
public static String mmkvQsecDeleteKeys(String)
public static byte[] mmQsecKVValueBytes(String)
public static String[] dtcBL(byte[])    // ← 9.2.90 新签名
```

---

## 14. mqq.app.MobileQQ（不变）

```java
public static MobileQQ getMobileQQ()
public String getQQProcessName()
public String getLastLoginUin()
```

---

## 15. NtTask（9.2.90 NT 任务基类）

`Lcom/tencent/qqnt/startup/task/NtTask;`（abstract）

```java
public abstract class NtTask implements b {
    private String taskId
    private ArrayList dependencies
    
    public NtTask()
    public NtTask(String)
    public final String getTaskId()
    public final ArrayList getDependencies()
    
    public void onTaskStart()    // ← 这是入口
    public void onTaskFinish()
    public void onNotify()
    public void onWait()
    public boolean runOnMainThread()
    public boolean blockUntilFinish()
    public int getPriority()
}
```

---

## 16. 适配总策略

### 启动入口替换（XposedEntry.kt）
```kotlin
// 删除：findAndHookMethod("com.tencent.mobileqq.startup.step.LoadDex", ...)
// 新增：
classLoader.loadClass("com.tencent.common.app.BaseApplicationImpl")
    .getDeclaredMethod("onCreate")
    .let { XposedBridge.hookMethod(it, hook) }
```

### Sign 调用替换（GenerateQSign.kt 中的 requestSign）
```kotlin
// 9.2.90 优先用 4 参数版本
val getSign4 = feKit.javaClass.getMethod("getSign", String::class.java, ByteArray::class.java, Int::class.java, String::class.java)
val result = getSign4.invoke(feKit, cmd, buffer, seq, uin) as SignResult
```

### ArtTiHookTask 绕过（最关键）
9.2.90 在启动时会扫 ArtMethod 的 hook 标志位。必须在 ColdStartupTask 调度到 `ArtTiHookTask` 之前，把 Shamrock 自身的 hook 痕迹隐藏：
1. **APK-Patcher 加载**而不是 LSPosed Manager 注册（LSPatch 直接打包成新 APK，无 modules.list）
2. **/proc/self/maps 过滤**：在 ArtTiHookTask 运行前，hook `Os.open`/`FileInputStream` 把 `de.robv.android.xposed`、`org.lsposed` 字符串删掉
3. **dlopen 拦截**：避免 libxposed_art.so 被 native 端读到

### Anti-Xposed 探针 hook
9.2.90 在 `Lcom/tencent/mobileqq/qsec/qsecurity/QSec;` 的 `detectMethod(String, String)` 会主动扫某些 Java 方法。Shamrock 应该 hook 这个返回 false。

---

## 17. 9.2.90 检测链逆向摘要（detection_scan.txt）

| 层级 | 类 / 任务 | APK 验证的方法 | Shamrock 处理 |
|------|-----------|----------------|---------------|
| 启动 | `ColdStartupTask.ArtTiHookTask` | 枚举名，实现在 libfekit | `offsets_9290` 探针 stub + fopen/openat maps 过滤 |
| Java | `Dtc` | `checkAppInstalled`, `dtcBL`, `getLibraryList`, `mmQsecKV*` | `QQ9290DetectionHooks` |
| Java | `QSec` | `detectMethod`, `getXpsInfo`, `doReport` | `QQ9290DetectionHooks` |
| Pandora | `InstalledAppListMonitor` | `getPackageInfo`, `getInstalledPackages`, … | `PandoraHideHooks` |
| Pandora | `DexMonitor` | `loadLibrary`, `load`, `dexClassLoader` | `PandoraHideHooks`（仅清洗返回值） |
| Pandora | `RuntimeMonitor` | `exec(Runtime, String)` 等 | `QQ9290DetectionHooks` |
| 隐私 | `PackageInstallMonitorKt` | `a`..`g(Context, String, …)` | `PackageInstallMonitorHooks` |
| 进程 | `app.guard.GuardManager` | `exit()` | `KillGuardHooks` |

重新扫描：`python rev_9290_detection.py` → `9.2.90_rev/detection_scan.txt`
