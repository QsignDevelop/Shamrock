package moe.RinShiona.Shamrock.ui.fragment

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.RinShiona.Shamrock.ui.app.RuntimeState
import moe.RinShiona.Shamrock.ui.app.ShamrockConfig
import moe.RinShiona.Shamrock.ui.theme.DreamGlassCard
import moe.RinShiona.Shamrock.ui.theme.DreamPalette
import moe.RinShiona.Shamrock.ui.theme.DreamStatusChip
import moe.RinShiona.Shamrock.ui.theme.LocalString
import moe.RinShiona.Shamrock.ui.tools.DeviceStatus
import moe.RinShiona.Shamrock.ui.tools.getShamrockVersion

@Composable
fun HomeFragment(
    runtime: RuntimeState,
) {
    val ctx = LocalContext.current
    val active by runtime.isFined
    val coreVer by runtime.coreVersion
    val coreLabel by runtime.coreName

    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    var suOk by remember { mutableStateOf<Boolean?>(null) }
    val oneBotEnabled = remember { ShamrockConfig.isOneBotV11Enabled(ctx) }
    val qqInfo = remember { DeviceStatus.getQqVersionLabel(ctx) }
    val cherryVer = remember { getShamrockVersion(ctx) }
    val antiLabel = remember { DeviceStatus.getAntiDetectLabel(ctx) }
    val antiOk = ShamrockConfig.isAntiDetectionEnabled(ctx)
    val ready = remember { ctx.getSharedPreferences("config", 0).all.isNotEmpty() }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            rootOk = DeviceStatus.detectRoot()
            suOk = DeviceStatus.detectSu()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DreamGlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (ready) "已就绪" else "未就绪（先打开一次设置页）",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (ready) DreamPalette.Pink500 else DreamPalette.TextSecondary,
                )
                Text(
                    text = "服务连通：${if (oneBotEnabled) (if (active) "是" else "否") else "OneBot 已关闭"}",
                    fontSize = 13.sp,
                    color = if (!oneBotEnabled) DreamPalette.TextSecondary else if (active) DreamPalette.TextPrimary else DreamPalette.TextSecondary,
                )
                Text(
                    text = "$coreVer · $coreLabel",
                    fontSize = 13.sp,
                    color = DreamPalette.TextSecondary,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DreamStatusChip(
                label = "Root 状态",
                value = when (rootOk) {
                    true -> "已检测到"
                    false -> "未检测到"
                    null -> "检测中…"
                },
                ok = rootOk?.let { !it },
                modifier = Modifier.weight(1f),
            )
            DreamStatusChip(
                label = "SU 状态",
                value = when (suOk) {
                    true -> "可用"
                    false -> "不可用"
                    null -> "检测中…"
                },
                ok = suOk,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DreamStatusChip(
                label = "反检测",
                value = antiLabel,
                ok = antiOk,
                modifier = Modifier.weight(1f),
            )
            DreamStatusChip(
                label = "CherryPop",
                value = "v$cherryVer",
                ok = true,
                modifier = Modifier.weight(1f),
            )
        }

        DreamGlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "QQ 版本",
                    fontSize = 11.sp,
                    color = DreamPalette.TextSecondary,
                )
                Text(
                    text = qqInfo,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = DreamPalette.TextPrimary,
                )
            }
        }

        NoticeBox(
            text = LocalString.legalWarning,
        ) {
            Toast.makeText(
                ctx,
                arrayOf(
                    "请严格遵守哦喵～",
                    "点我又不能下崽…",
                    "记得遵守规则呀 ♡",
                    "CherryPop 免责声明",
                ).random(),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MainPreview() {
    val runtime = remember {
        RuntimeState(
            androidx.compose.runtime.mutableStateOf(true),
            androidx.compose.runtime.mutableStateOf("1.0.5"),
            androidx.compose.runtime.mutableStateOf("LSPosed"),
            androidx.compose.runtime.mutableStateOf(false),
        )
    }
    HomeFragment(runtime)
}
