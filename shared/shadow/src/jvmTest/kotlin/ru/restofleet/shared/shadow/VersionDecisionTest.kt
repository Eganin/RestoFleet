package ru.restofleet.shared.shadow

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class VersionDecisionTest {

    @Test
    fun decisionTable() {
        val cases = listOf(
            Triple(null, 1L, VersionDecision.APPLY_NEWER),
            Triple(null, 0L, VersionDecision.APPLY_NEWER),
            Triple(11L, 12L, VersionDecision.APPLY_NEWER),
            Triple(12L, 12L, VersionDecision.ECHO_EQUAL),
            Triple(12L, 10L, VersionDecision.IGNORE_OLDER),
            Triple(Long.MAX_VALUE, Long.MIN_VALUE, VersionDecision.IGNORE_OLDER),
        )
        cases.forEach { (stored, incoming, expected) ->
            assertWithMessage("stored=$stored incoming=$incoming")
                .that(VersionDecision.of(stored, incoming)).isEqualTo(expected)
        }
    }
}
