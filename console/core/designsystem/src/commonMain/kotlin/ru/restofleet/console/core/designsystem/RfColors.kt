package ru.restofleet.console.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Статус для цвета: онлайн, предупреждение, критично, нет данных. */
enum class StatusTone { Ok, Warn, Crit, Off }

/** Токены цвета макета (`restofleet-blueprint.html`, свет и тьма). */
@Immutable
data class RfColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val fg: Color,
    val muted: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val ok: Color,
    val okSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val crit: Color,
    val critSoft: Color,
    val off: Color,
    val offSoft: Color,
    val scrim: Color,
    val dark: Boolean,
) {
    fun tone(tone: StatusTone): Color = when (tone) {
        StatusTone.Ok -> ok
        StatusTone.Warn -> warn
        StatusTone.Crit -> crit
        StatusTone.Off -> off
    }

    fun toneSoft(tone: StatusTone): Color = when (tone) {
        StatusTone.Ok -> okSoft
        StatusTone.Warn -> warnSoft
        StatusTone.Crit -> critSoft
        StatusTone.Off -> offSoft
    }

    companion object {
        val Light = RfColors(
            bg = Color(0xFFF3F5F9), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEBEFF5),
            line = Color(0xFFD9DFE8), fg = Color(0xFF121821), muted = Color(0xFF5A6577),
            accent = Color(0xFF3356D9), accentSoft = Color(0xFFE3E9FF), onAccent = Color(0xFFFFFFFF),
            ok = Color(0xFF1C9A5F), okSoft = Color(0xFFDDF3E8), warn = Color(0xFFB97206),
            warnSoft = Color(0xFFFBEFD9), crit = Color(0xFFCF3434), critSoft = Color(0xFFFBE3E3),
            off = Color(0xFF7F8898), offSoft = Color(0xFFE9ECF1), scrim = Color(0x6B0E131A), dark = false,
        )
        val Dark = RfColors(
            bg = Color(0xFF0C1118), surface = Color(0xFF141B24), surface2 = Color(0xFF1B2430),
            line = Color(0xFF273241), fg = Color(0xFFE5EAF1), muted = Color(0xFF8C98AA),
            accent = Color(0xFF7D95FF), accentSoft = Color(0xFF1E2747), onAccent = Color(0xFF0C1118),
            ok = Color(0xFF3CC985), okSoft = Color(0xFF12301F), warn = Color(0xFFEFA73A),
            warnSoft = Color(0xFF33260F), crit = Color(0xFFFF6A6A), critSoft = Color(0xFF3A1718),
            off = Color(0xFF6E7989), offSoft = Color(0xFF1F2630), scrim = Color(0x8C000000), dark = true,
        )

        fun of(dark: Boolean): RfColors = if (dark) Dark else Light
    }
}
