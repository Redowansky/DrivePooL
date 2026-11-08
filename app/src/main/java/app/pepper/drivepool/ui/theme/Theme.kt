package app.pepper.drivepool.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Bg = Color(0xFF0B0B0F)
private val Surface = Color(0xFF121216)
private val Card = Color(0xFF1C1C22)
private val Border = Color(0xFF2A2A32)
private val Accent = Color(0xFF4A90E2)

private val DrivePoolColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Accent,
    background = Bg,
    surface = Surface,
    surfaceVariant = Card,
    onBackground = Color.White,
    onSurface = Color.White,
    error = Color(0xFFEF5350)
)

@Composable
fun DrivePoolTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DrivePoolColors, content = content)
}

val CardBg = Card
val CardBorder = Border
val PageBg = Bg
