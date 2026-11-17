package dev.sukbeomh.beta7compact.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Stable IDs are stored in preferences; display names may change independently. */
enum class AppTheme(val id: String, val label: String, val dark: Boolean, val colors: ColorScheme) {
    MINT("mint", "차콜 민트", true, palette(true, 0xFF121212, 0xFF1E1E1E, 0xFF333333,
        0xFFE8E8E8, 0xFFB8B8B8, 0xFF8FD0B8, 0xFF00382B, 0xFF244B3D, 0xFF9A9A9A)),
    PAPER("paper", "페이퍼 블루", false, palette(false, 0xFFF6F7FA, 0xFFFFFFFF, 0xFFE2E7EE,
        0xFF172231, 0xFF505C6C, 0xFF2056A0, 0xFFFFFFFF, 0xFFD9E7FF, 0xFF647184)),
    BLUE("blue", "미드나이트 블루", true, palette(true, 0xFF101824, 0xFF1C2939, 0xFF293B50,
        0xFFEFF4FC, 0xFFB4C4D8, 0xFFA4CAFF, 0xFF102D51, 0xFF253F60, 0xFF94A7BF)),
    AMBER("amber", "웜 앰버", true, palette(true, 0xFF1C1713, 0xFF2B231D, 0xFF40332A,
        0xFFF4EDE6, 0xFFCDBCAF, 0xFFEFBE7A, 0xFF39230A, 0xFF513B21, 0xFFAD9988));

    companion object {
        const val PREFERENCE_KEY = "appTheme"
        fun fromId(value: String?): AppTheme = entries.firstOrNull { it.id == value } ?: MINT
    }
}

val LocalAppTheme = staticCompositionLocalOf { AppTheme.MINT }

private fun palette(dark: Boolean, background: Long, panel: Long, highest: Long,
    text: Long, muted: Long, accent: Long, onAccent: Long, container: Long, outline: Long): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Color(accent), onPrimary = Color(onAccent),
        primaryContainer = Color(container), onPrimaryContainer = Color(text),
        secondary = Color(accent), onSecondary = Color(onAccent),
        secondaryContainer = Color(container), onSecondaryContainer = Color(text),
        tertiary = Color(accent), onTertiary = Color(onAccent),
        tertiaryContainer = Color(container), onTertiaryContainer = Color(text),
        background = Color(background), onBackground = Color(text),
        surface = Color(background), onSurface = Color(text),
        surfaceVariant = Color(highest), onSurfaceVariant = Color(muted),
        surfaceDim = Color(background), surfaceBright = Color(highest),
        surfaceContainerLowest = Color(background), surfaceContainerLow = Color(panel),
        surfaceContainer = Color(panel), surfaceContainerHigh = Color(highest),
        surfaceContainerHighest = Color(highest), surfaceTint = Color(accent),
        outline = Color(outline), outlineVariant = Color(outline),
        inverseSurface = if (dark) Color(0xFFE8E8E8) else Color(0xFF172231),
        inverseOnSurface = if (dark) Color(0xFF172231) else Color(0xFFE8E8E8),
        inversePrimary = if (dark) Color(0xFF2056A0) else Color(0xFFA4CAFF),
    )
}
