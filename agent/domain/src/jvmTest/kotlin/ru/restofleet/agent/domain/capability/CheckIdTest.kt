package ru.restofleet.agent.domain.capability

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CheckIdTest {

    @Test
    fun catalogHasAllFortyThreeChecks() {
        assertThat(CheckId.entries).hasSize(43)
    }

    @Test
    fun everyCheckHasShortTitle() {
        CheckId.entries.forEach {
            assertThat(it.title).isNotEmpty()
            assertThat(it.title.length).isAtMost(40)
        }
    }

    @Test
    fun groupMatchesIdPrefix() {
        val expected = mapOf(
            "L" to CheckGroup.LIFECYCLE, "R" to CheckGroup.READING, "P" to CheckGroup.PROBE,
            "C" to CheckGroup.COMMAND, "D" to CheckGroup.DEVICE_OWNER, "M" to CheckGroup.MQTT,
            "RS" to CheckGroup.RESOURCES,
        )
        CheckId.entries.forEach { id ->
            val prefix = id.name.takeWhile { it.isLetter() }
            assertThat(id.group).isEqualTo(expected.getValue(prefix))
        }
    }
}
