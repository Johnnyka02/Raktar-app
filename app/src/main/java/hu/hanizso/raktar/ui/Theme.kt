package hu.hanizso.raktar.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val OkGreen = Color(0xFF1D7A3A)
val OkSoft = Color(0xFFD9EFE0)
val WarnAmber = Color(0xFF9A5800)
val WarnSoft = Color(0xFFFBE7C6)
val BadRed = Color(0xFFB3261E)
val BadSoft = Color(0xFFF7DAD7)

private val Light = lightColorScheme(
    primary = Color(0xFF0E6E5C), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EAE3), onPrimaryContainer = Color(0xFF06231D),
    background = Color(0xFFEEF2F0), surface = Color.White, surfaceVariant = Color(0xFFE3E9E6),
    onBackground = Color(0xFF16201C), onSurface = Color(0xFF16201C), onSurfaceVariant = Color(0xFF58675F),
    outline = Color(0xFFCFD8D3),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF4CBFA5), onPrimary = Color(0xFF04211B),
    primaryContainer = Color(0xFF143A31), onPrimaryContainer = Color(0xFFD3EAE3),
    background = Color(0xFF0F1412), surface = Color(0xFF171F1C), surfaceVariant = Color(0xFF202A26),
    onBackground = Color(0xFFE4EBE7), onSurface = Color(0xFFE4EBE7), onSurfaceVariant = Color(0xFF97A69F),
    outline = Color(0xFF2C3833),
)

@Composable
fun RaktarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
