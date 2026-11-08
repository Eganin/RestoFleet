package ru.restofleet.agent.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.command.Command

class IncomingCommandTest {
    private fun command(correlation: ByteArray? = byteArrayOf(1, 2), parseError: String? = null) = IncomingCommand(
        cmdId = "c-1",
        command = Command.Ping,
        rawType = "ping",
        responseTopic = "rf/v1/dev/x/cmd/res",
        correlationData = correlation,
        expiresAt = 5L,
        parseError = parseError,
    )

    @Test
    fun commandsWithEqualCorrelationContentAreEqual() {
        val a = command()
        val b = command(correlation = byteArrayOf(1, 2))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun differentCorrelationOrParseErrorMakesCommandsDifferent() {
        assertThat(command()).isNotEqualTo(command(correlation = byteArrayOf(2, 1)))
        assertThat(command()).isNotEqualTo(command(correlation = null))
        assertThat(command()).isNotEqualTo(command(parseError = "x"))
    }
}
