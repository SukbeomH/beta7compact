package dev.sukbeomh.beta7compact.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object BetaTokens {
    val warning = Color(0xFFF2C46B)
    val matte = Color.Black
    val compactPadding = 16.dp
    val widePadding = 24.dp
    val cardRadius = 12.dp
    val thumbnailRadius = 8.dp
    val touchTarget = 48.dp
    val pixelIcon = 18.dp
}
val BetaColors = darkColorScheme(
    primary = Color(0xFF8FD0B8), onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF244B3D), onPrimaryContainer = Color(0xFFE8E8E8),
    background = Color(0xFF121212), onBackground = Color(0xFFE8E8E8),
    surface = Color(0xFF121212), onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF2A2A2A), onSurfaceVariant = Color(0xFFA8A8A8),
    surfaceContainer = Color(0xFF1E1E1E), surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF333333), outline = Color(0xFF3D3D3D),
    outlineVariant = Color(0xFF3D3D3D), error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6)
)
private val BetaTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)
@Composable fun BetaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BetaColors, typography = BetaTypography, content = content)
}
