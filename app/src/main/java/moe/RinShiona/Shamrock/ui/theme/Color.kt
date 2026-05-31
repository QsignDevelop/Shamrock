@file:Suppress("PropertyName")
package moe.RinShiona.Shamrock.ui.theme

import androidx.compose.ui.graphics.Color

object DreamPalette {
    val Pink50 = Color(0xFFFFF5F8)
    val Pink100 = Color(0xFFFFE4EE)
    val Pink200 = Color(0xFFFFC9DC)
    val Pink300 = Color(0xFFFFA8C8)
    val Pink400 = Color(0xFFFF8AB8)
    val Pink500 = Color(0xFFFF6BA8)
    val PinkAccent = Color(0xFFFF4D94)
    val Lavender = Color(0xFFE8D5FF)
    val GlassWhite = Color(0xCCFFFFFF)
    val GlassPink = Color(0xB3FFF0F5)
    val GlassBorder = Color(0x66FFFFFF)
    val TextPrimary = Color(0xFF5C3D4A)
    val TextSecondary = Color(0xFF9A7A88)
    val NavSelected = Color(0xFFFF6BA8)
    val NavUnselected = Color(0xFFBFA8B2)
    val StatusBar = Color(0xFFFFF5F8)
    val GradientTop = Color(0xFFFFF8FB)
    val GradientBottom = Color(0xFFFFE8F2)
}

interface ThemeColor {
    companion object {
        val ColorTabSelected = DreamPalette.PinkAccent
        val ColorTabUnSelected = DreamPalette.TextSecondary

        val ColorDarkTabSelected = DreamPalette.Pink300
        val ColorDarkTabUnSelected = DreamPalette.TextSecondary

        val ColorLightToolbarText = DreamPalette.TextPrimary
        val ColorDarkToolbarText = DreamPalette.Pink100

        val ColorLightStatusCardStart = DreamPalette.Pink400
        val ColorLightStatusCardEnd = DreamPalette.Lavender

        val ColorDarkStatusCardStart = Color(0xFF8B4A6B)
        val ColorDarkStatusCardEnd = Color(0xFF6B3A7B)

        val ColorNoticeBox = DreamPalette.GlassPink
        val ColorNoticeBoxText = DreamPalette.TextPrimary
        val ColorNoticeBoxIcon = DreamPalette.PinkAccent

        val ColorDarkNoticeBox = Color(0x33FFFFFF)
        val ColorDarkNoticeBoxText = DreamPalette.Pink100
        val ColorDarkNoticeBoxIcon = DreamPalette.Pink300

        val ColorAccountCardStart = DreamPalette.Pink400
        val ColorAccountCardEnd = DreamPalette.Pink300
    }

    val StatusBar: Color
    val Toolbar: Color
    val ToolbarText: Color
    val TabSelected: Color
    val TabItem: Color

    val StatusCardStart: Color
    val StatusCardEnd: Color

    val NoticeBox: Color
    val NoticeBoxText: Color
    val NoticeBoxIcon: Color

    val DataBoxTextLight: Color
    val DataBoxTextDark: Color

    val Divider: Color
}

object LightColor : ThemeColor {
    override val StatusBar: Color = DreamPalette.StatusBar
    override val Toolbar: Color = Color.Transparent
    override val ToolbarText = ThemeColor.ColorLightToolbarText
    override val TabSelected = ThemeColor.ColorTabSelected
    override val TabItem: Color = ThemeColor.ColorLightToolbarText

    override val StatusCardStart = ThemeColor.ColorLightStatusCardStart
    override val StatusCardEnd = ThemeColor.ColorLightStatusCardEnd

    override val NoticeBox = ThemeColor.ColorNoticeBox
    override val NoticeBoxText = ThemeColor.ColorNoticeBoxText
    override val NoticeBoxIcon = ThemeColor.ColorNoticeBoxIcon

    override val DataBoxTextLight = ThemeColor.ColorTabSelected
    override val DataBoxTextDark = ThemeColor.ColorTabUnSelected

    override val Divider = Color(0x33FF8AB8)
}

object DarkColor : ThemeColor {
    override val StatusBar: Color = Color(0xFF2A1A24)
    override val Toolbar: Color = Color.Transparent
    override val ToolbarText = ThemeColor.ColorDarkToolbarText
    override val TabSelected = ThemeColor.ColorTabSelected
    override val TabItem: Color = ThemeColor.ColorDarkToolbarText

    override val StatusCardStart = ThemeColor.ColorDarkStatusCardStart
    override val StatusCardEnd = ThemeColor.ColorDarkStatusCardEnd

    override val NoticeBox = ThemeColor.ColorDarkNoticeBox
    override val NoticeBoxText = ThemeColor.ColorDarkNoticeBoxText
    override val NoticeBoxIcon = ThemeColor.ColorDarkNoticeBoxIcon

    override val DataBoxTextLight = ThemeColor.ColorDarkTabSelected
    override val DataBoxTextDark = ThemeColor.ColorDarkTabUnSelected

    override val Divider = Color(0x33FF8AB8)
}
