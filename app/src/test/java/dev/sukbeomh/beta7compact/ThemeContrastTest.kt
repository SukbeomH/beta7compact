package dev.sukbeomh.beta7compact

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.sukbeomh.beta7compact.ui.theme.AppTheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val x = a.luminance().toDouble()
        val y = b.luminance().toDouble()
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
    private fun readable(theme: AppTheme, role: String, fg: Color, bg: Color, minimum: Double = 4.5) {
        val ratio = contrast(fg, bg)
        assertTrue(ratio >= minimum, "${theme.id} $role contrast $ratio < $minimum")
    }
    @Test fun lutBadgeFitsLowAndHighDensityCanvases() {
        for (density in listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 3f)) {
            val side = 24f * density
            val cell = dev.sukbeomh.beta7compact.ui.components.filmIconCellSize(side, side, 32)
            assertTrue(cell > 0 && cell * 32 <= side, "LUT badge exceeds canvas at density $density")
        }
    }
    @Test fun stablePreferenceIdsRoundTripAndUnknownValuesUseDefault() {
        assertEquals(4, AppTheme.entries.map { it.id }.toSet().size)
        AppTheme.entries.forEach { assertEquals(it, AppTheme.fromId(it.id)) }
        assertEquals(AppTheme.MINT, AppTheme.fromId(null))
        assertEquals(AppTheme.MINT, AppTheme.fromId("removed-theme"))
    }
    @Test fun textAndIconsRemainVisibleOnEverySurface() {
        AppTheme.entries.forEach { theme ->
            val c = theme.colors
            readable(theme, "background", c.onBackground, c.background)
            listOf(c.surface, c.surfaceVariant, c.surfaceDim, c.surfaceBright,
                c.surfaceContainerLowest, c.surfaceContainerLow, c.surfaceContainer,
                c.surfaceContainerHigh, c.surfaceContainerHighest).forEach { surface ->
                readable(theme, "body", c.onSurface, surface)
                readable(theme, "secondary text", c.onSurfaceVariant, surface)
                readable(theme, "text action", c.primary, surface)
                readable(theme, "control outline", c.outline, surface, 3.0)
                readable(theme, "warning", if (theme.dark) Color(0xFFF2C46B) else Color(0xFF765000), surface)
            }
        }
    }
    @Test fun actionAndStatusColorPairsMeetTextContrast() {
        AppTheme.entries.forEach { theme ->
            val c = theme.colors
            listOf(c.onPrimary to c.primary, c.onSecondary to c.secondary, c.onTertiary to c.tertiary,
                c.onPrimaryContainer to c.primaryContainer, c.onSecondaryContainer to c.secondaryContainer,
                c.onTertiaryContainer to c.tertiaryContainer, c.onError to c.error,
                c.onErrorContainer to c.errorContainer, c.inverseOnSurface to c.inverseSurface,
                c.inversePrimary to c.inverseSurface).forEach { (fg, bg) -> readable(theme, "paired role", fg, bg) }
            readable(theme, "error text", c.error, c.surfaceContainerHighest)
        }
    }
}
