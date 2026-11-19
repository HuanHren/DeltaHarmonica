package com.huanhren.deltaharmonica.ui

import com.huanhren.deltaharmonica.ui.theme.ThemeMode
import kotlin.math.floor

enum class StudioTab(val route: String) {
    HOME("home"), LIBRARY("library"), PLAYER("player"), SETTINGS("settings");

    companion object { val primary = setOf(LIBRARY, PLAYER, SETTINGS) }
}

enum class HomeLayout { WORKBENCH, LIST, GRID }
enum class StudioLanguage(val tag: String) { SYSTEM(""), CHINESE("zh-CN"), ENGLISH("en") }
enum class LauncherStyle { WAVE, COMPASS }

enum class StudioSeed(val hex: Long, val title: String) {
    TEAL(0xFF007F77, "Teal"), CYAN(0xFF078394, "Cyan"), BLUE(0xFF276AC7, "Blue"),
    INDIGO(0xFF474EC1, "Indigo"), PURPLE(0xFF784DB0, "Purple"), VIOLET(0xFF8545BE, "Violet"),
    MAGENTA(0xFF9C3A9D, "Magenta"), PINK(0xFFC44383, "Pink"), ROSE(0xFFBF4463, "Rose"),
    RED(0xFFC33B39, "Red"), ORANGE(0xFFB65312, "Orange"), AMBER(0xFFA4630D, "Amber"),
    YELLOW(0xFF90721A, "Yellow"), LIME(0xFF667D18, "Lime"), GREEN(0xFF2C804F, "Green"),
    BLUE_GREY(0xFF536B84, "Blue Grey"),
}

data class StudioAppearance(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val seed: StudioSeed? = null,
    val layout: HomeLayout = HomeLayout.WORKBENCH,
    val navigation: Set<StudioTab> = StudioTab.primary,
    val showLabels: Boolean = true,
    val hideOnScroll: Boolean = true,
    val blur: Boolean = true,
    val glass: Boolean = true,
    val reducedEffects: Boolean = false,
    val scale: Float = 1f,
    val predictiveBack: Boolean = true,
    val language: StudioLanguage = StudioLanguage.SYSTEM,
    val launcher: LauncherStyle = LauncherStyle.WAVE,
) {
    val tabs: List<StudioTab> get() = StudioTab.entries.filter(navigation::contains).ifEmpty { listOf(StudioTab.LIBRARY) }

    fun toggle(tab: StudioTab): StudioAppearance {
        val current = tabs.toSet()
        if (current.size == 1 && tab in current) return this
        return copy(navigation = if (tab in current) current - tab else current + tab)
    }

    fun resize(value: Float) = copy(scale = if (value.isFinite()) value.coerceIn(.8f, 1.1f) else 1f)

    fun effects(sdk: Int, lowRam: Boolean) = SurfaceEffects(
        blur = sdk >= 33 && !lowRam && !reducedEffects && blur,
        glass = sdk >= 33 && !lowRam && !reducedEffects && glass,
    )
}

data class SurfaceEffects(val blur: Boolean, val glass: Boolean)

internal fun studioTabAt(x: Float, width: Float, count: Int, rtl: Boolean): Int {
    require(width > 0f && count > 0)
    val slot = floor(x / width * count).toInt().coerceIn(0, count - 1)
    return if (rtl) count - 1 - slot else slot
}
