package com.parcelgrade.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Surveyor's palette: pine-green ink on cool field-book paper, with an ochre survey-flag accent.
private val Pine = Color(0xFF1F3B2D)
private val PineLight = Color(0xFF8FC4A2)
private val Ochre = Color(0xFFB57B12)
private val OchreLight = Color(0xFFE2B04A)

private val LightColors = lightColorScheme(
    primary = Pine,
    onPrimary = Color(0xFFF4F6F1),
    primaryContainer = Color(0xFFD7E6DA),
    onPrimaryContainer = Pine,
    secondary = Ochre,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E3BE),
    onSecondaryContainer = Color(0xFF4A3305),
    background = Color(0xFFEEF0EA),
    onBackground = Color(0xFF1B2420),
    surface = Color(0xFFF8F9F5),
    onSurface = Color(0xFF1B2420),
    surfaceVariant = Color(0xFFE2E7DF),
    onSurfaceVariant = Color(0xFF55625A),
    outline = Color(0xFFC6CEC4),
    error = Color(0xFFB3362C),
)

private val DarkColors = darkColorScheme(
    primary = PineLight,
    onPrimary = Color(0xFF0E2318),
    primaryContainer = Color(0xFF26402F),
    onPrimaryContainer = Color(0xFFCFE5D5),
    secondary = OchreLight,
    onSecondary = Color(0xFF2E2004),
    secondaryContainer = Color(0xFF4A3A14),
    onSecondaryContainer = Color(0xFFF3E3BE),
    background = Color(0xFF111713),
    onBackground = Color(0xFFE4E9E2),
    surface = Color(0xFF19211C),
    onSurface = Color(0xFFE4E9E2),
    surfaceVariant = Color(0xFF243029),
    onSurfaceVariant = Color(0xFFA9B6AD),
    outline = Color(0xFF3A473F),
    error = Color(0xFFF08A7F),
)

/** Letter-grade colors, tuned to read on both themes. */
object GradeColors {
    fun forLetter(letter: String, dark: Boolean): Color = when (letter.firstOrNull()) {
        'A' -> if (dark) Color(0xFF6CC28F) else Color(0xFF2E7D4F)
        'B' -> if (dark) Color(0xFFA6C95C) else Color(0xFF5C7F1E)
        'C' -> if (dark) Color(0xFFE2B04A) else Color(0xFFA9740F)
        'D' -> if (dark) Color(0xFFF0915A) else Color(0xFFC0561E)
        'F' -> if (dark) Color(0xFFF08A7F) else Color(0xFFB3362C)
        else -> if (dark) Color(0xFF8A968E) else Color(0xFF7A867E)
    }
}

val Serif = FontFamily.Serif

private val AppTypography = Typography().let { t ->
    t.copy(
        displayLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 66.sp),
        headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
        titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        labelSmall = t.labelSmall.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun ParcelGradeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
