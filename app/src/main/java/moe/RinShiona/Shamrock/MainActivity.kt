@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package moe.RinShiona.Shamrock

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import kotlinx.coroutines.launch
import moe.RinShiona.Shamrock.ui.app.AppRuntime
import moe.RinShiona.Shamrock.ui.app.Logger
import moe.RinShiona.Shamrock.ui.app.RuntimeState
import moe.RinShiona.Shamrock.ui.app.ShamrockConfig
import moe.RinShiona.Shamrock.ui.fragment.HomeFragment
import moe.RinShiona.Shamrock.ui.fragment.LogFragment
import moe.RinShiona.Shamrock.ui.fragment.OneBotPage
import moe.RinShiona.Shamrock.ui.fragment.QSignPage
import moe.RinShiona.Shamrock.ui.fragment.SettingsPage
import moe.RinShiona.Shamrock.ui.service.DashboardInitializer
import moe.RinShiona.Shamrock.ui.service.internal.broadcastToModule
import moe.RinShiona.Shamrock.ui.theme.CUTE_SUBTITLES
import moe.RinShiona.Shamrock.ui.theme.DreamPalette
import moe.RinShiona.Shamrock.ui.theme.DreamyBackground
import moe.RinShiona.Shamrock.ui.theme.LiquidGlassBottomBar
import moe.RinShiona.Shamrock.ui.theme.LocalString
import moe.RinShiona.Shamrock.ui.theme.ShamrockTheme
import moe.RinShiona.Shamrock.ui.tools.NoIndication
import moe.RinShiona.Shamrock.ui.tools.getShamrockVersion

private enum class MainTab { Home, QSign, OneBot, Log, Settings }

@OptIn(ExperimentalFoundationApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        window.statusBarColor = DreamPalette.StatusBar.toArgb()
        window.navigationBarColor = DreamPalette.Pink100.toArgb()

        setContent {
            CompositionLocalProvider(LocalIndication provides NoIndication) {
                AppMainView()
            }
        }

        DashboardInitializer(this, ShamrockConfig.getHttpPort(this))
        broadcastToModule { putExtra("__cmd", "fetchPort") }
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("config", MODE_PRIVATE)
            .edit()
            .putLong("xqbot_sync", System.currentTimeMillis())
            .apply()
        ShamrockConfig.pushUpdate(this)
        AppRuntime.uiLogEnabled = ShamrockConfig.isOneBotV11Enabled(this)
        broadcastToModule { putExtra("__cmd", "checkAndStartService") }
        DashboardInitializer.refresh(this)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppMainView() {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val systemUiController = rememberSystemUiController()
    LaunchedEffect(systemUiController) {
        systemUiController.statusBarDarkContentEnabled = true
        systemUiController.navigationBarDarkContentEnabled = true
        systemUiController.setStatusBarColor(DreamPalette.StatusBar)
        systemUiController.setNavigationBarColor(DreamPalette.Pink100)
    }

    remember {
        if (!AppRuntime.isInit) {
            val isFined = mutableStateOf(false)
            val coreVersion = mutableStateOf(getShamrockVersion(ctx))
            val coreName = mutableStateOf("Xposed")
            val voiceSwitch = mutableStateOf(false)
            AppRuntime.state = RuntimeState(isFined, coreVersion, coreName, voiceSwitch)
            AppRuntime.logger = Logger(
                StringBuffer(),
                mutableIntStateOf(0),
                mutableListOf(),
                mutableStateOf(AnnotatedString("")),
            )
            AppRuntime.AccountInfo.uin = mutableStateOf("2854200454")
            AppRuntime.AccountInfo.nick = mutableStateOf("测试昵称")
            AppRuntime.requestCount = mutableIntStateOf(0)
            AppRuntime.uiLogEnabled = ShamrockConfig.isOneBotV11Enabled(ctx)
            AppRuntime.isInit = true
        }
    }

    val runtime = AppRuntime.state
    val accountNick by AppRuntime.AccountInfo.nick
    val accountUin by AppRuntime.AccountInfo.uin
    @Suppress("LocalVariableName") val LocalString = LocalString

    var oneBotEnabled by remember { mutableStateOf(ShamrockConfig.isOneBotV11Enabled(ctx)) }
    var cuteSubtitle by remember { mutableStateOf(CUTE_SUBTITLES.random()) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            oneBotEnabled = ShamrockConfig.isOneBotV11Enabled(ctx)
            AppRuntime.uiLogEnabled = oneBotEnabled
            cuteSubtitle = CUTE_SUBTITLES.random()
        }
    }

    var wasActive by remember { mutableStateOf(false) }
    LaunchedEffect(runtime.isFined.value) {
        if (runtime.isFined.value && !wasActive) {
            if (oneBotEnabled) {
                AppRuntime.log("日志框架激活成功，开放操作许可。")
            }
            Toast.makeText(ctx, LocalString.frameworkYes, Toast.LENGTH_SHORT).show()
        } else if (!runtime.isFined.value && wasActive && oneBotEnabled) {
            AppRuntime.log("日志框架已断开，请检查 QQ 是否在运行。")
        }
        wasActive = runtime.isFined.value
    }

    val visibleTabs = remember(LocalString.TitlesWithIcon) {
        listOf(
            MainTab.Home to LocalString.TitlesWithIcon[0],
            MainTab.QSign to LocalString.TitlesWithIcon[1],
            MainTab.OneBot to LocalString.TitlesWithIcon[2],
            MainTab.Log to LocalString.TitlesWithIcon[3],
            MainTab.Settings to LocalString.TitlesWithIcon[4],
        )
    }

    ShamrockTheme(darkTheme = false, dynamicColor = false) {
        Box(modifier = Modifier.fillMaxSize()) {
            DreamyBackground()
            val pagerState = rememberPagerState(pageCount = { visibleTabs.size })
            val scope = rememberCoroutineScope()

            LaunchedEffect(visibleTabs.size) {
                if (pagerState.currentPage >= visibleTabs.size) {
                    pagerState.scrollToPage(0)
                }
            }

            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = "CherryPop",
                                    color = DreamPalette.TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                )
                                Text(
                                    text = cuteSubtitle,
                                    color = DreamPalette.TextSecondary,
                                    fontSize = 13.sp,
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = DreamPalette.TextPrimary,
                        ),
                    )
                },
                bottomBar = {
                    LiquidGlassBottomBar(
                        tabs = visibleTabs.map { it.second },
                        selectedIndex = pagerState.currentPage,
                        onTabSelected = { index ->
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                    )
                },
            ) { padding ->
                HorizontalPager(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    state = pagerState,
                ) { page ->
                    when (visibleTabs[page].first) {
                        MainTab.Home -> HomeFragment(runtime)
                        MainTab.QSign -> QSignPage(accountNick, accountUin)
                        MainTab.OneBot -> OneBotPage(onOneBotToggle = { oneBotEnabled = it })
                        MainTab.Log -> LogFragment(AppRuntime.logger)
                        MainTab.Settings -> SettingsPage()
                    }
                }
            }
        }
    }
}
