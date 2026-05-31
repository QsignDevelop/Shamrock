package moe.RinShiona.Shamrock.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

@Composable
fun DreamyBackground(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(DreamPalette.GradientTop, DreamPalette.Pink100, DreamPalette.GradientBottom),
            ),
        ),
    )
}

@Composable
fun GlassNavBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(DreamPalette.GlassPink)
            .border(1.dp, DreamPalette.GlassBorder, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
    ) { content() }
}
