package dev.sukbeomh.beta7compact.ui.theme

import androidx.compose.material3.*
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object BetaTokens {
    val warning: Color
        @Composable get() = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFF765000) else Color(0xFFF2C46B)
    val matte = Color.Black
    val compactPadding = 16.dp
    val widePadding = 24.dp
    val cardRadius = 12.dp
    val thumbnailRadius = 8.dp
    val touchTarget = 48.dp
    val pixelIcon = 18.dp
}
val BetaColors = AppTheme.MINT.colors
private val BetaTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)
@Composable fun BetaTheme(settings: SharedPreferences? = null, content: @Composable () -> Unit) {
    var selected by remember(settings) { mutableStateOf(AppTheme.fromId(settings?.getString(AppTheme.PREFERENCE_KEY, null))) }
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == AppTheme.PREFERENCE_KEY || key == null) selected = AppTheme.fromId(prefs.getString(AppTheme.PREFERENCE_KEY, null))
        }
        settings?.registerOnSharedPreferenceChangeListener(listener)
        selected = AppTheme.fromId(settings?.getString(AppTheme.PREFERENCE_KEY, null))
        onDispose { settings?.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    CompositionLocalProvider(LocalAppTheme provides selected) {
        MaterialTheme(colorScheme = selected.colors, typography = BetaTypography, content = content)
    }
}
