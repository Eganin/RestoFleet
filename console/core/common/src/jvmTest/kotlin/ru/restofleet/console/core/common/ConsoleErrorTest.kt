package ru.restofleet.console.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConsoleErrorTest {
    @Test
    fun `every error has a stable code`() {
        val codes = listOf(
            ConsoleError.Network, ConsoleError.Unauthorized, ConsoleError.Forbidden, ConsoleError.NotFound,
            ConsoleError.Conflict("x"), ConsoleError.Validation("f", "m"), ConsoleError.RateLimited(3),
            ConsoleError.Server(500, null), ConsoleError.InvalidAddress("r"), ConsoleError.InvalidInput(null, "m"),
            ConsoleError.Unexpected("d"),
        ).map { it.code }
        assertThat(codes).containsExactly(
            "network", "unauthorized", "forbidden", "not_found", "conflict", "validation", "rate_limited",
            "server", "invalid_address", "invalid_input", "unexpected",
        ).inOrder()
    }
}
