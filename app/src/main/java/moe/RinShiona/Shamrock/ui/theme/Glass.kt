package moe.RinShiona.Shamrock.ui.theme

import android.os.Build
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/** iOS 26 风格悬浮液态玻璃底栏 */
@Composable
fun LiquidGlassBottomBar(
    modifier: Modifier = Modifier,
    tabs: List<Pair<String, Int>>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(24.dp, RoundedCornerShape(28.dp), ambientColor = DreamPalette.Pink300.copy(0.22f))
                .clip(RoundedCornerShape(28.dp))
                .graphicsLayer {
                    // Real blur to mimic liquid glass. Safe fallback on < 31.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        renderEffect = RenderEffect.createBlurEffect(
                            26f,
                            26f,
                            Shader.TileMode.CLAMP
                        ).asComposeRenderEffect()
                    }
                }
                .background(
                    Brush.verticalGradient(
                        listOf(
                            DreamPalette.GlassWhite.copy(alpha = 0.78f),
                            DreamPalette.GlassPink.copy(alpha = 0.62f),
                        ),
                    ),
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.9f),
                            DreamPalette.GlassBorder.copy(alpha = 0.8f),
                        ),
                    ),
                    shape = RoundedCornerShape(28.dp),
                )
                .padding(horizontal = 6.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, (title, icon) ->
                    val selected = selectedIndex == index
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onTabSelected(index) }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (selected) 40.dp else 36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) {
                                        Brush.radialGradient(
                                            listOf(
                                                DreamPalette.Pink200.copy(0.9f),
                                                DreamPalette.GlassWhite.copy(0.5f),
                                            ),
                                        )
                                    } else {
                                        Brush.linearGradient(
                                            listOf(Color.Transparent, Color.Transparent),
                                        )
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(id = icon),
                                contentDescription = title,
                                modifier = Modifier.size(22.dp),
                                tint = if (selected) DreamPalette.NavSelected else DreamPalette.NavUnselected,
                            )
                        }
                        Text(
                            text = title,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) DreamPalette.NavSelected else DreamPalette.NavUnselected,
                        )
                    }
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
