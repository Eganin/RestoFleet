package ru.restofleet.console.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Радиусы макета: 6, 10, 16. */
object RfRadius {
    val S = 6.dp
    val M = 10.dp
    val L = 16.dp
}

/** Отступы и размеры. */
object RfSpacing {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 24.dp
}

/** Моно-шрифт: системный (реш. 472), для значений метрик и кодов. */
object RfFonts {
    val Mono: FontFamily = FontFamily.Monospace
    val MonoValue = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 15.sp)
    val Caption = TextStyle(fontSize = 12.sp)
}

val LocalRfColors = staticCompositionLocalOf { RfColors.Light }

/** Доступ к токенам: `RfTheme.colors`. */
object RfTheme {
    val colors: RfColors
        @Composable
        @ReadOnlyComposable
        get() = LocalRfColors.current
}

/** Тема консоли: токены макета поверх Material 3. По умолчанию следует системной. */
@Composable
fun RfTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = RfColors.of(dark)
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.fg,
            surface = c.surface, onSurface = c.fg, surfaceVariant = c.surface2, onSurfaceVariant = c.muted,
            outline = c.line, error = c.crit, primaryContainer = c.accentSoft, onPrimaryContainer = c.fg,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.fg,
            surface = c.surface, onSurface = c.fg, surfaceVariant = c.surface2, onSurfaceVariant = c.muted,
            outline = c.line, error = c.crit, primaryContainer = c.accentSoft, onPrimaryContainer = c.fg,
        )
    }
    val shapes = Shapes(
        small = RoundedCornerShape(RfRadius.S),
        medium = RoundedCornerShape(RfRadius.M),
        large = RoundedCornerShape(RfRadius.L),
    )
    CompositionLocalProvider(LocalRfColors provides c) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, content = content)
    }
}
