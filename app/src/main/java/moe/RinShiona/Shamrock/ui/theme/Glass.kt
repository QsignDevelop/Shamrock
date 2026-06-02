package moe.RinShiona.Shamrock.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

@Composable
fun DreamyBackground(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(
                    DreamPalette.GradientTop,
                    DreamPalette.Pink100,
                    DreamPalette.Lavender.copy(alpha = 0.35f),
                    DreamPalette.GradientBottom,
                ),
            ),
        ),
    )
}

/** 粉白底栏：Material NavigationBar，避免自定义分层导致图标不可见或点不到 */
@Composable
fun LiquidGlassBottomBar(
    modifier: Modifier = Modifier,
    tabs: List<Pair<String, Int>>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
) {
    val barShape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(1f)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(12.dp, barShape, ambientColor = DreamPalette.Pink300.copy(0.3f))
                .clip(barShape)
                .border(1.dp, DreamPalette.Pink200, barShape),
            color = Color.White,
            tonalElevation = 6.dp,
            shadowElevation = 0.dp,
        ) {
            NavigationBar(
                modifier = Modifier.fillMaxWidth(),
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
            ) {
                tabs.forEachIndexed { index, (title, iconRes) ->
                    val selected = selectedIndex == index
                    NavigationBarItem(
                        selected = selected,
                        onClick = { onTabSelected(index) },
                        icon = {
                            Icon(
                                painter = painterResource(id = iconRes),
                                contentDescription = title,
                                tint = if (selected) DreamPalette.NavSelected else DreamPalette.NavUnselected,
                            )
                        },
                        label = {
                            Text(
                                text = title,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = DreamPalette.NavSelected,
                            selectedTextColor = DreamPalette.NavSelected,
                            unselectedIconColor = DreamPalette.NavUnselected,
                            unselectedTextColor = DreamPalette.NavUnselected,
                            indicatorColor = DreamPalette.Pink100,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
fun GlassNavBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    LiquidGlassBottomBarWrapper(modifier, content)
}

@Composable
private fun LiquidGlassBottomBarWrapper(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Color.Transparent),
    ) { content() }
}

@Composable
fun DreamGlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp), ambientColor = DreamPalette.Pink300.copy(0.2f))
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        DreamPalette.GlassWhite.copy(0.88f),
                        DreamPalette.GlassPink.copy(0.72f),
                    ),
                ),
            )
            .border(1.dp, DreamPalette.GlassBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        content()
    }
}

@Composable
fun DreamStatusChip(
    label: String,
    value: String,
    ok: Boolean?,
    modifier: Modifier = Modifier,
) {
    val accent = when (ok) {
        true -> DreamPalette.Pink400
        false -> Color(0xFFE57399)
        null -> DreamPalette.TextSecondary
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(DreamPalette.GlassWhite.copy(0.65f))
            .border(1.dp, DreamPalette.GlassBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text = label, fontSize = 11.sp, color = DreamPalette.TextSecondary)
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = accent,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
