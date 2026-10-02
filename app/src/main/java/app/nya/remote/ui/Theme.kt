package app.nya.remote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF3B6CF6)
val AccentCyan = Color(0xFF29B6F6)
val PanelBg = Color(0xFFF4F6FA)
val OverlayDark = Color(0xE6303236)

private val light = lightColorScheme(
    primary = Accent,
    secondary = AccentCyan,
    background = PanelBg,
    surface = Color.White,
    surfaceVariant = Color(0xFFEDEFF4),
)

private val dark = darkColorScheme(primary = Color(0xFF8AA9FF), secondary = AccentCyan)

@Composable
fun NyaTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) dark else light, content = content)
}
