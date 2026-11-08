package ru.restofleet.agent.presentation.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val OkColor = Color(0xFF2E7D32)
private val WarnColor = Color(0xFFB26A00)
private val BadgeSize = 28.dp

/** Круглый значок статуса строки; во время выполнения — индикатор. */
@Composable
internal fun BadgeMark(badge: Badge, isRunning: Boolean) {
    Box(Modifier.size(BadgeSize), contentAlignment = Alignment.Center) {
        if (isRunning) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            val (glyph, color) = badge.style()
            Surface(shape = CircleShape, color = color.copy(alpha = BADGE_BACKGROUND_ALPHA)) {
                Text(
                    glyph,
                    modifier = Modifier.size(BadgeSize).padding(top = 3.dp),
                    color = color,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Badge.style(): Pair<String, Color> = when (this) {
    Badge.OK -> "✓" to OkColor
    Badge.WARN -> "!" to WarnColor
    Badge.FAIL -> "✕" to MaterialTheme.colorScheme.error
    Badge.NA -> "—" to MaterialTheme.colorScheme.outline
    Badge.PENDING -> "○" to MaterialTheme.colorScheme.outline
}

private const val BADGE_BACKGROUND_ALPHA = 0.15f
