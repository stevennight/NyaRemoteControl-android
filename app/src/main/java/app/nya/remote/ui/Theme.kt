package app.nya.remote.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF3B6CF6)
val AccentCyan = Color(0xFF29B6F6)
val PanelBg = Color(0xFFF4F6FA)
val OverlayDark = Color(0xE6303236)
val Danger = Color(0xFFE5484D)
val Ok = Color(0xFF2E9E6A)
val Warn = Color(0xFFD98A00)

private val light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3ECFF),
    onPrimaryContainer = Color(0xFF1D3F9E),
    secondary = AccentCyan,
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFEFF1F5),
    onSurfaceVariant = Color(0xFF5B6070),
    surfaceContainer = Color(0xFFF2F3F6),
    outline = Color(0xFFD8DBE2),
    outlineVariant = Color(0xFFE6E8ED),
    error = Danger,
)

private val dark = darkColorScheme(
    primary = Color(0xFF8AA9FF),
    onPrimary = Color(0xFF0E2A78),
    primaryContainer = Color(0xFF233A7A),
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = AccentCyan,
    background = Color(0xFF17181C),
    surface = Color(0xFF212227),
    surfaceVariant = Color(0xFF2B2D33),
    onSurfaceVariant = Color(0xFFA5AAB5),
    surfaceContainer = Color(0xFF26282D),
    outline = Color(0xFF3A3D45),
    outlineVariant = Color(0xFF30333A),
    error = Color(0xFFFF8F86),
)

@Composable
fun NyaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) dark else light, content = content)
}

/** Secondary text colour. */
val muted: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurfaceVariant
