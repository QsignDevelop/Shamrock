package moe.RinShiona.Shamrock.xposed

import android.content.Context
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedBridge.log
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.RinShiona.Shamrock.utils.MMKVFetcher
import moe.RinShiona.Shamrock.xposed.actions.EarlyAntiDetection
import moe.RinShiona.Shamrock.xposed.loader.ActionLoader
import moe.RinShiona.Shamrock.xposed.ipc.impl.ShamrockNative
import moe.RinShiona.Shamrock.xposed.loader.FuckAMS
import moe.RinShiona.Shamrock.xposed.loader.LuoClassloader
import moe.RinShiona.Shamrock.xposed.helper.NativeCrashGuard
import moe.RinShiona.Shamrock.xposed.helper.NtTaskSecurityGuard
import moe.RinShiona.Shamrock.xposed.helper.KillGuardHooks
import moe.RinShiona.Shamrock.xposed.helper.DetectionKillShield
import moe.RinShiona.Shamrock.xposed.helper.MsfBootGuard
import moe.RinShiona.Shamrock.xposed.helper.XPrefConfigLoader
import moe.RinShiona.Shamrock.tools.FuzzySearchClass
import moe.RinShiona.Shamrock.tools.afterHook
import moe.RinShiona.Shamrock.utils.PlatformUtils
import mqq.app.MobileQQ
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

internal const val PACKAGE_NAME_QQ = "com.tencent.mobileqq"
internal const val PACKAGE_NAME_QQ_INTERNATIONAL = "com.tencent.mobileqqi"
internal const val PACKAGE_NAME_QQ_LITE = "com.tencent.qqlite"
internal const val PACKAGE_NAME_TIM = "com.tencent.tim"


internal class XposedEntry: IXposedHookLoadPackage {
    private data class HookInstallReport(
        val tier1: Boolean,
        val tier2: Boolean,
        val tier3: Boolean,
        val tier4: Boolean,
        val tier5: Boolean,
    ) {
        fun any(): Boolean = tier1 || tier2 || tier3 || tier4 || tier5
    }

    companion object {
        @JvmStatic
        var sec_static_stage_inited = false

        // Idempotency guard - the new multi-tier startup hook may fire from
        // several different methods on different QQ versions, but init must
        // only run once. Use AtomicBoolean for thread-safe one-shot semantics.
        private val initOnce = AtomicBoolean(false)
        private val msfInitOnce = AtomicBoolean(false)
        private val retryScheduled = AtomicBoolean(false)
        private val msfRetryScheduled = AtomicBoolean(false)
        private const val STARTUP_RETRY_MS = 500L
        private const val STARTUP_MAX_RETRIES = 60
        /** Pandora/StackTrace/native + ActionLoader after splash. */
        private const val DEFERRED_INIT_DELAY_MS = 3_000L

        private fun procTag(): String = kotlin.runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentProcessName")
                .invoke(null) as String
        }.getOrDefault("?")

        private fun isMsfProcess(): Boolean = procTag().endsWith(":MSF")

        private fun isMsfProcessName(processName: String): Boolean =
            processName.endsWith(":MSF")

        private fun plog(msg: String) {
            val line = "Shamrock[${procTag()}]: $msg"
            Log.i("Shamrock", line)
            log(line)
        }

        // QQ version code thresholds.
        // 9.2.90 build is 7560 series (NT architecture).
        // 9.2.85 is around 7415, 9.1.80 is around 6325.
        private const val QQ_NT_VERSION_CODE_THRESHOLD = 7500

        private fun shortClassName(name: String): String =
            name.substringAfterLast('.')
    }

    private var firstStageInit = false

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName == PACKAGE_NAME_QQ || param.packageName == PACKAGE_NAME_TIM) {
            Log.i(
                "Shamrock",
                "loadPackage pkg=${param.packageName} proc=${param.processName}",
            )
        }
        if (param.packageName == PACKAGE_NAME_QQ && isMsfProcessName(param.processName)) {
            entryMsf(param.classLoader)
            return
        }
        when (param.packageName) {
            PACKAGE_NAME_QQ -> entryMQQ(param.classLoader)
            "android" -> FuckAMS.injectAMS(param.classLoader)
            PACKAGE_NAME_TIM -> entryTim(param.classLoader)
        }
    }

    private fun entryTim(classLoader: ClassLoader) {
        entryMQQ(classLoader)
    }

    /**
     * MSF 子进程。历史上为避免 libbasic_share 崩溃只跑 IPC-only，但签名
     * (QQSecuritySign/libfekit) 正是在 MSF 生成的——检测位也在这里被原生扫描打包。
     * 因此这里必须装上 **签名进程专用反检测**（QSecBypass + HookEvasion + 原生
     * maps 过滤 + libfekit probe hook），否则检测位永远不变。
     * 同时尽早装 NativeCrashGuard，吞掉 cmark.NativeLib 之类的非致命 JNI 崩溃。
     */
    private fun entryMsf(classLoader: ClassLoader) {
        plog("entryMsf — sign-process init")
        if (AntiDetectionConfig.connectivitySafeMode) {
            DetectionKillShield.arm(45_000L)
            KillGuardHooks.enableLiteColdStartWindow(45_000L)
        } else {
            DetectionKillShield.arm(180_000L)
            KillGuardHooks.enableLiteColdStartWindow(180_000L)
        }
        kotlin.runCatching { KillGuardHooks.install(classLoader) }
        // ArtTiHook 之前：MSF 也需尽早 Java + libfekit load 监听
        kotlin.runCatching { EarlyAntiDetection.installLiteBeforeArtTi(classLoader) }
            .onFailure { plog("MSF pre-ArtTi failed: ${it.message}") }
        kotlin.runCatching { XPrefConfigLoader.loadIfAvailable() }
        kotlin.runCatching { MsfBootGuard.installMsf(classLoader) }
        kotlin.runCatching { NativeCrashGuard.install(classLoader) }
        if (!AntiDetectionConfig.connectivitySafeMode) {
            kotlin.runCatching { EarlyAntiDetection.installForMsf(classLoader) }
                .onFailure { plog("MSF anti-detect install failed: ${it.message}") }
        } else {
            plog("connectivity-safe: MSF anti-detect hooks + QSign (after MSF onCreate)")
        }
        val startup = afterHook(51) { param ->
            val loader = param.thisObject?.javaClass?.classLoader
                ?: param.args.firstOrNull()?.javaClass?.classLoader
                ?: return@afterHook
            tryMsfStartupInit(loader, param, "${param.method.declaringClass.simpleName}.${param.method.name}")
        }
        if (!tryHookMobileQQOnCreate(classLoader, startup)) {
            plog("MSF FATAL — MobileQQ.onCreate hook failed")
        }
    }

    private fun tryMsfStartupInit(
        loader: ClassLoader,
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam?,
        source: String,
    ) {
        if (msfInitOnce.get()) return
        try {
            LuoClassloader.ctxClassLoader = loader
            val app = if (param != null) {
                resolveBaseApplicationContext(loader, param)
            } else {
                resolveContextFromLoader(loader)
            } ?: run {
                scheduleMsfStartupRetry(loader, source)
                return
            }
            if (!isStartupReady(loader, param)) {
                scheduleMsfStartupRetry(loader, source)
                return
            }
            synchronized(XposedEntry::class.java) {
                if (msfInitOnce.get()) return
                if (execMsfStartupInit(app)) {
                    msfInitOnce.set(true)
                    msfRetryScheduled.set(false)
                    plog("MSF startup triggered via $source")
                } else {
                    scheduleMsfStartupRetry(loader, source)
                }
            }
        } catch (e: Throwable) {
            plog("MSF startup error from $source: ${e.message}")
            log(e)
            scheduleMsfStartupRetry(loader, source)
        }
    }

    private fun scheduleMsfStartupRetry(loader: ClassLoader, source: String) {
        if (msfInitOnce.get()) return
        if (!msfRetryScheduled.compareAndSet(false, true)) return
        Thread({
            var attempts = 0
            while (!msfInitOnce.get() && attempts < STARTUP_MAX_RETRIES) {
                try {
                    Thread.sleep(STARTUP_RETRY_MS)
                } catch (_: InterruptedException) {
                    break
                }
                attempts++
                val app = resolveContextFromLoader(loader)
                if (app != null && isStartupReady(loader, null)) {
                    synchronized(XposedEntry::class.java) {
                        if (!msfInitOnce.get() && execMsfStartupInit(app)) {
                            msfInitOnce.set(true)
                            msfRetryScheduled.set(false)
                            plog("MSF startup via retry (attempt $attempts)")
                            return@Thread
                        }
                    }
                }
            }
            msfRetryScheduled.set(false)
        }, "Shamrock-MsfStartupRetry").apply {
            isDaemon = true
            start()
        }
    }

    private fun execMsfStartupInit(ctx: Context): Boolean {
        val classLoader = ctx.classLoader ?: return false
        LuoClassloader.hostClassLoader = classLoader
        if (!injectClassloader(XposedEntry::class.java.classLoader)) {
            plog("MSF execStartupInit aborted — classloader inject failed")
            return false
        }
        if (AntiDetectionConfig.connectivitySafeMode) {
            kotlin.runCatching { EarlyAntiDetection.installLiteMsfDeferred(classLoader) }
                .onFailure { plog("MSF deferred lite anti-detect failed: ${it.message}") }
        } else {
            kotlin.runCatching { EarlyAntiDetection.installLite(classLoader) }
        }
        kotlin.runCatching {
            moe.RinShiona.Shamrock.xposed.helper.QSecContextBridge.installHooks(classLoader)
        }
        kotlin.runCatching {
            moe.RinShiona.Shamrock.xposed.helper.ChannelResponseCapture.ensureHook(classLoader)
        }.onFailure { plog("MSF ChannelResponseCapture hook failed: ${it.message}") }
        kotlin.runCatching {
            ActionLoader.runMsf(ctx)
        }.onFailure {
            plog("MSF ActionLoader.runMsf failed")
            log(it)
            return false
        }
        kotlin.runCatching {
            moe.RinShiona.Shamrock.xposed.helper.QuaBootstrap.forceApply(classLoader, null)
        }
        return true
    }

    /**
     * 主入口 hook 安装。9.2.90 NT 之后，旧的 LoadDex.b() 已被删除，
     * 必须改用 BaseApplicationImpl.onCreate() 作为最稳定的启动入口。
     *
     * 策略：所有可能的入口点全部 hook，第一个触发的负责初始化（AtomicBoolean 保证只跑一次）。
     * 这样无论用户用的是 9.1.x、9.2.85 还是 9.2.90 NT 都能正常启动。
     */
    private fun entryMQQ(classLoader: ClassLoader) {
        plog("entryMQQ — NtTask guard + pre-ArtTi anti-detect")
        if (AntiDetectionConfig.connectivitySafeMode) {
            DetectionKillShield.arm(45_000L)
            KillGuardHooks.enableLiteColdStartWindow(45_000L)
        } else {
            DetectionKillShield.arm(180_000L)
            KillGuardHooks.enableLiteColdStartWindow(180_000L)
        }
        kotlin.runCatching { KillGuardHooks.install(classLoader) }
        // 必须在 attach / ArtTiHook 之前装好 QSec.detectMethod=false 等，否则 QQ 直接自杀
        kotlin.runCatching { EarlyAntiDetection.installLiteBeforeArtTi(classLoader) }
            .onFailure { plog("pre-ArtTi failed: ${it.message}") }
        kotlin.runCatching { XPrefConfigLoader.loadIfAvailable() }
        kotlin.runCatching { NativeCrashGuard.install(classLoader) }
        kotlin.runCatching { NtTaskSecurityGuard.install(classLoader) }
        if (AntiDetectionConfig.connectivitySafeMode) {
            plog("connectivity-safe: pre-ArtTi armed; full lite after Application ready")
        }

        val startup = afterHook(51) { param ->
            val loader = param.thisObject?.javaClass?.classLoader
                ?: param.args.firstOrNull()?.javaClass?.classLoader
                ?: return@afterHook
            val source = "${param.method.declaringClass.simpleName}.${param.method.name}"
            tryStartupInit(loader, param, source)
        }

        val report = installStartupHookTiers(classLoader, startup)
        firstStageInit = report.any()
        if (!firstStageInit) {
            plog("FATAL — no startup hook tier succeeded. QQ version may be too new/old.")
        } else {
            scheduleStartupRetry(classLoader, "entryMQQ-fallback")
        }
    }

    /**
     * 9.2.90 NT：只在 Application 已就绪后触发初始化。
     * 不 hook attachBaseContext / NtTask.onTaskStart，避免干扰 QQ 早期 native 加载。
     */
    private fun installStartupHookTiers(
        classLoader: ClassLoader,
        startup: de.robv.android.xposed.XC_MethodHook
    ): HookInstallReport {
        val tier2 = tryHookBaseApplicationOnCreate(classLoader, startup)
        val tierAttach = tryHookBaseApplicationAttach(classLoader, startup)
        val tier5 = tryHookMobileQQOnCreate(classLoader, startup)
        return HookInstallReport(
            tier1 = false,
            tier2 = tier2 || tierAttach,
            tier3 = false,
            tier4 = false,
            tier5 = tier5
        )
    }

    private fun tryStartupInit(
        loader: ClassLoader,
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam?,
        source: String
    ) {
        if (initOnce.get()) return
        try {
            LuoClassloader.ctxClassLoader = loader

            val app = if (param != null) {
                resolveBaseApplicationContext(loader, param)
            } else {
                resolveContextFromLoader(loader)
            }
            if (app == null) {
                plog("startup hook fired before context ready: $source — will retry")
                scheduleStartupRetry(loader, source)
                return
            }
            if (!isStartupReady(loader, param)) {
                plog("startup prerequisites not ready yet from $source — will retry")
                scheduleStartupRetry(loader, source)
                return
            }

            synchronized(XposedEntry::class.java) {
                if (initOnce.get()) return
                if (execStartupInit(app)) {
                    initOnce.set(true)
                    retryScheduled.set(false)
                    plog("startup triggered via $source")
                } else {
                    plog("execStartupInit failed from $source — will retry")
                    scheduleStartupRetry(loader, source)
                }
            }
        } catch (e: Throwable) {
            plog("startup hook error from $source")
            log(e)
            scheduleStartupRetry(loader, source)
        }
    }

    private fun scheduleStartupRetry(loader: ClassLoader, source: String) {
        if (initOnce.get()) return
        if (!retryScheduled.compareAndSet(false, true)) return

        Thread({
            var attempts = 0
            while (!initOnce.get() && attempts < STARTUP_MAX_RETRIES) {
                try {
                    Thread.sleep(STARTUP_RETRY_MS)
                } catch (_: InterruptedException) {
                    break
                }
                attempts++
                val app = resolveContextFromLoader(loader)
                if (app != null && isStartupReady(loader, null)) {
                    synchronized(XposedEntry::class.java) {
                        if (!initOnce.get() && execStartupInit(app)) {
                            initOnce.set(true)
                            retryScheduled.set(false)
                            plog("startup triggered via delayed retry (after $source, attempt $attempts)")
                            return@Thread
                        }
                    }
                } else if (attempts % 10 == 0) {
                    plog("startup retry waiting (after $source, attempt $attempts/$STARTUP_MAX_RETRIES)")
                }
            }
            if (!initOnce.get()) {
                plog("FATAL — startup retry exhausted after $source ($attempts attempts)")
            }
            retryScheduled.set(false)
        }, "Shamrock-StartupRetry").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Do not call [MobileQQ.getMobileQQ] from module bytecode — qqinterface stub throws.
     * Resolve the live QQ instance via hook param or reflection on [loader].
     */
    private fun isStartupReady(
        loader: ClassLoader,
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam?,
    ): Boolean {
        param?.thisObject?.let { obj ->
            kotlin.runCatching {
                val mqqClass = loader.loadClass("mqq.app.MobileQQ")
                if (mqqClass.isInstance(obj)) return true
            }
        }
        if (resolveContextFromLoader(loader) != null) return true
        return resolveMobileQQApp(loader) != null
    }

    private fun resolveMobileQQApp(loader: ClassLoader): Any? {
        val mqqClass = kotlin.runCatching { loader.loadClass("mqq.app.MobileQQ") }.getOrNull()
            ?: return null
        kotlin.runCatching {
            val m = mqqClass.getMethod("getMobileQQ")
            m.invoke(null)?.let { return it }
        }
        kotlin.runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null)
            if (app != null && mqqClass.isInstance(app)) return app
        }
        for (fieldName in listOf("sMobileQQ", "sInstance", "mobileQQ", "instance")) {
            kotlin.runCatching {
                val f = mqqClass.getDeclaredField(fieldName)
                f.isAccessible = true
                f.get(null)?.let { return it }
            }
        }
        return null
    }

    private fun resolveContextFromLoader(loader: ClassLoader): Context? {
        kotlin.runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull()?.let { return it }

        kotlin.runCatching {
            MobileQQ.getContext()?.let { ctx ->
                return ctx.applicationContext ?: ctx
            }
        }
        return try {
            val clz = loader.loadClass("com.tencent.common.app.BaseApplicationImpl")
            try {
                clz.getField("sApplication").get(null) as? Context
            } catch (_: NoSuchFieldException) {
                clz.declaredFields.firstOrNull { it.type == clz && Modifier.isStatic(it.modifiers) }?.let { f ->
                    if (!f.isAccessible) f.isAccessible = true
                    f.get(null) as? Context
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ============ Tier 1: 旧版 LoadDex ============
    private fun tryHookLoadDex(classLoader: ClassLoader, hook: de.robv.android.xposed.XC_MethodHook): Boolean {
        return try {
            val loadDex = classLoader.loadClass("com.tencent.mobileqq.startup.step.LoadDex")
            val methods = loadDex.declaredMethods.filter {
                it.returnType == java.lang.Boolean.TYPE && it.parameterTypes.isEmpty()
            }
            if (methods.isEmpty()) {
                log("Shamrock: [Tier 1] LoadDex class found but no boolean() method present (9.2.x?)")
                return false
            }
            methods.forEach { XposedBridge.hookMethod(it, hook) }
            log("Shamrock: [Tier 1] hooked legacy LoadDex.b() (${methods.size} methods)")
            true
        } catch (e: ClassNotFoundException) {
            log("Shamrock: [Tier 1] LoadDex not present (this is normal on 9.2.90 NT)")
            false
        } catch (e: Throwable) {
            log("Shamrock: [Tier 1] unexpected error: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun tryHookBaseApplicationAttach(classLoader: ClassLoader, hook: de.robv.android.xposed.XC_MethodHook): Boolean {
        return try {
            XposedHelpers.findAndHookMethod(
                "com.tencent.common.app.BaseApplicationImpl",
                classLoader,
                "attachBaseContext",
                Context::class.java,
                hook,
            )
            log("Shamrock: [Tier 2a] hooked BaseApplicationImpl.attachBaseContext()")
            true
        } catch (e: Throwable) {
            log("Shamrock: [Tier 2a] attach hook failed: ${e.message}")
            false
        }
    }

    // ============ Tier 2: 9.2.90 NT 主路径 ============
    private fun tryHookBaseApplicationOnCreate(classLoader: ClassLoader, hook: de.robv.android.xposed.XC_MethodHook): Boolean {
        return try {
            XposedHelpers.findAndHookMethod(
                "com.tencent.common.app.BaseApplicationImpl",
                classLoader,
                "onCreate",
                hook,
            )
            log("Shamrock: [Tier 2] hooked BaseApplicationImpl.onCreate() (NT main entry)")
            true
        } catch (e: ClassNotFoundException) {
            log("Shamrock: [Tier 2] BaseApplicationImpl not found (unexpected!): ${e.message}")
            false
        } catch (e: Throwable) {
            log("Shamrock: [Tier 2] hook failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ============ Tier 3: NT ColdStartupTask ============
    /**
     * 9.2.90 把启动任务搬到 com.tencent.qqnt.startup.task.NtTask（抽象类）。
     * 我们 hook NtTask.onTaskStart()，对所有 NT task 都生效。
     * 用 taskId 过滤，只在 LoadDexTask / SafeO3InitTask 阶段触发一次。
     */
    private fun tryHookNTColdStartupTask(classLoader: ClassLoader, hook: de.robv.android.xposed.XC_MethodHook): Boolean {
        return try {
            val ntTask = classLoader.loadClass("com.tencent.qqnt.startup.task.NtTask")
            val onTaskStart = ntTask.getDeclaredMethod("onTaskStart")
            XposedBridge.hookMethod(onTaskStart, hook)
            log("Shamrock: [Tier 3] hooked NtTask.onTaskStart() (NT task base class)")
            true
        } catch (e: ClassNotFoundException) {
            log("Shamrock: [Tier 3] NtTask not found (QQ may not be NT version)")
            false
        } catch (e: Throwable) {
            log("Shamrock: [Tier 3] hook failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ============ Tier 4: 历史模糊搜索 ============
    private fun tryHookLegacyFuzzy(classLoader: ClassLoader, hook: de.robv.android.xposed.XC_MethodHook): Boolean {
        return try {
            val fieldList = arrayListOf<Field>()
            FuzzySearchClass.findAllClassByField(classLoader, "com.tencent.mobileqq.startup.task.config") { _, field ->
                (field.type == HashMap::class.java || field.type == Map::class.java) && Modifier.isStatic(field.modifiers)
            }.forEach {
                it.declaredFields.forEach { field ->
                    if ((field.type == HashMap::class.java || field.type == Map::class.java) &&
                        Modifier.isStatic(field.modifiers)
                    ) fieldList.add(field)
                }
            }
            if (fieldList.isEmpty()) return false

            var hookedCount = 0
            fieldList.forEach { field ->
                if (!field.isAccessible) field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (field.get(null) as? Map<String, Class<*>>)?.forEach { (key, clazz) ->
                    if (key.contains("LoadDex", ignoreCase = true)) {
                        clazz.declaredMethods.forEach { m ->
                            if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Context::class.java) {
                                XposedBridge.hookMethod(m, hook)
                                hookedCount++
                            }
                        }
                    }
                }
            }
            if (hookedCount > 0) {
                log("Shamrock: [Tier 4] fuzzy LoadDex hook installed on $hookedCount methods")
                true
            } else false
        } catch (e: Throwable) {
            log("Shamrock: [Tier 4] fuzzy search failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ============ 最后兜底 ============
    private fun tryHookMobileQQOnCreate(classLoader: ClassLoader, hook: de.robv.android.xposed.XC_MethodHook): Boolean {
        return try {
            val mqq = classLoader.loadClass("mqq.app.MobileQQ")
            val onCreate = mqq.getDeclaredMethod("onCreate")
            XposedBridge.hookMethod(onCreate, hook)
            log("Shamrock: [Tier 5] hooked MobileQQ.onCreate()")
            true
        } catch (e: Throwable) {
            log("Shamrock: [Tier 5] hook failed: ${e.message}")
            false
        }
    }

    /**
     * 从 hook 触发的 param 里解析出 application context。
     * 不同的 hook 点用不同方式拿 context：
     *   - LoadDex.b() / NtTask.onTaskStart()：通过反射 BaseApplicationImpl.sApplication
     *   - BaseApplicationImpl.onCreate()：thisObject 就是 Application
     *   - BaseApplicationImpl.attachBaseContext(Context)：args[0] 是 base context
     */
    private fun resolveBaseApplicationContext(
        loader: ClassLoader,
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam
    ): Context? {
        // 优先尝试：thisObject 本身就是 Application / Context
        (param.thisObject as? Context)?.let { return it }

        // 9.2.90 NT：thisObject 常为 BaseApplicationImpl（非 Context），尝试 getApplication()
        param.thisObject?.let { obj ->
            kotlin.runCatching {
                obj.javaClass.getMethod("getApplication").invoke(obj) as? Context
            }.getOrNull()?.let { return it }
            kotlin.runCatching {
                obj.javaClass.getMethod("getApplicationContext").invoke(obj) as? Context
            }.getOrNull()?.let { return it }
        }

        // 次优：attachBaseContext(Context) 的第一个参数
        if (param.args.isNotEmpty()) {
            (param.args[0] as? Context)?.let { return it }
        }

        return resolveContextFromLoader(loader)
    }

    private fun execStartupInit(ctx: Context): Boolean {
        if (sec_static_stage_inited) return true

        val classLoader = ctx.classLoader ?: run {
            plog("execStartupInit aborted — null classLoader")
            return false
        }

        LuoClassloader.hostClassLoader = classLoader

        if (!injectClassloader(XposedEntry::class.java.classLoader)) {
            plog("execStartupInit aborted — classloader inject failed")
            return false
        }

        val processName = if (isMsfProcess()) {
            procTag()
        } else {
            readQqProcessName(classLoader) ?: procTag()
        }
        plog("Process Name = $processName")

        if (AntiDetectionConfig.connectivitySafeMode) {
            plog("connectivity-safe: lite anti-detect + QSign bootstrap")
            kotlin.runCatching { EarlyAntiDetection.installLite(classLoader) }
        } else if (AntiDetectionConfig.allowEarlyHooks()) {
            kotlin.runCatching {
                EarlyAntiDetection.installCritical(classLoader)
                plog("critical anti-detection installed (main thread)")
            }.onFailure {
                plog("critical anti-detection failed: ${it.message}")
            }
        } else {
            plog("anti-detection disabled by config")
        }

        kotlin.runCatching { MMKVFetcher.initMMKV(ctx) }
            .onFailure { plog("MMKV init skipped/failed: ${it.message}") }

        if (AntiDetectionConfig.connectivitySafeMode) {
            scheduleConnectivitySafeStartup(ctx, classLoader)
        } else {
            scheduleDeferredStartup(ctx, classLoader)
        }
        return true
    }

    /** 联网优先：等 QQ 过 ArtTiHook 后再启 HTTP/QSign，避免 startup 阶段干扰。 */
    private fun scheduleConnectivitySafeStartup(ctx: Context, classLoader: ClassLoader) {
        Thread({
            try {
                Thread.sleep(DEFERRED_INIT_DELAY_MS)
                kotlin.runCatching { ActionLoader.runFirst(ctx) }
                    .onFailure { plog("runFirst failed: ${it.message}"); log(it) }
                kotlin.runCatching { ActionLoader.runService(ctx) }
                    .onFailure { plog("runService failed: ${it.message}"); log(it) }
                kotlin.runCatching {
                    moe.RinShiona.Shamrock.xposed.helper.QSecContextBridge.installHooks(classLoader)
                    moe.RinShiona.Shamrock.xposed.helper.QuaBootstrap.forceApply(classLoader, null)
                }
                EarlyAntiDetection.installLiteDeferred(classLoader)
                sec_static_stage_inited = true
                System.setProperty("qxbot_flag", "1")
                plog("connectivity-safe: deferred QSign bootstrap done")
            } catch (t: Throwable) {
                plog("connectivity-safe deferred bootstrap error: ${t.message}")
                log(t)
            }
        }, "Shamrock-LiteService").apply {
            isDaemon = true
            start()
        }
    }

    private fun scheduleDeferredStartup(ctx: Context, classLoader: ClassLoader) {
        Thread({
            try {
                Thread.sleep(DEFERRED_INIT_DELAY_MS)
                if (AntiDetectionConfig.allowEarlyHooks() && !EarlyAntiDetection.fullyInstalled) {
                    kotlin.runCatching {
                        EarlyAntiDetection.installDeferred(classLoader)
                        plog("deferred anti-detection installed")
                    }.onFailure {
                        plog("deferred anti-detection failed: ${it.message}")
                    }
                }
                kotlin.runCatching {
                    ActionLoader.runFirst(ctx)
                }.onFailure {
                    plog("ActionLoader.runFirst failed (deferred)")
                    log(it)
                    return@Thread
                }
                sec_static_stage_inited = true
                System.setProperty("qxbot_flag", "1")
                plog("deferred startup complete")
            } catch (t: Throwable) {
                plog("deferred startup error: ${t.message}")
                log(t)
            }
        }, "Shamrock-DeferredInit").apply {
            isDaemon = true
            start()
        }
    }

    private fun readQqProcessName(loader: ClassLoader): String? {
        val mqq = resolveMobileQQApp(loader) ?: return null
        for (methodName in listOf("getQQProcessName", "getQqProcessName", "getProcessName")) {
            kotlin.runCatching {
                val m = mqq.javaClass.getMethod(methodName)
                return m.invoke(mqq) as? String
            }
        }
        kotlin.runCatching {
            val f = mqq.javaClass.getDeclaredField("qqProcessName")
            f.isAccessible = true
            return f.get(mqq) as? String
        }
        return null
    }

    private fun injectClassloader(moduleLoader: ClassLoader?): Boolean {
        if (moduleLoader != null) {
            if (kotlin.runCatching {
                moduleLoader.loadClass("mqq.app.MobileQQ")
            }.isSuccess) {
                plog("ModuleClassloader already injected.")
                return true
            }

            val parent = moduleLoader.parent
            val field = ClassLoader::class.java.declaredFields
                .first { it.name == "parent" }
            field.isAccessible = true

            field.set(LuoClassloader, parent)

            if (LuoClassloader.load("mqq.app.MobileQQ") == null) {
                plog("LuoClassloader init failed.")
                return false
            }

            field.set(moduleLoader, LuoClassloader)

            return kotlin.runCatching {
                Class.forName("mqq.app.MobileQQ")
            }.onFailure {
                plog("Classloader inject failed.")
            }.onSuccess {
                plog("Classloader inject successfully.")
            }.isSuccess
        }
        return false
    }
}
