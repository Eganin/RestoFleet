package ru.restofleet.server.domain.common

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.domain.fake.InMemoryDb
import ru.restofleet.server.domain.fake.FakeTransactionRunner

class CommonTest {
    @Test
    fun constantTimeEqualsComparesFully() {
        assertThat(constantTimeEquals("abc", "abc")).isTrue()
        assertThat(constantTimeEquals("abc", "abd")).isFalse()
        assertThat(constantTimeEquals("abc", "ab")).isFalse()
        assertThat(constantTimeEquals("", "")).isTrue()
    }

    @Test
    fun inTransactionReturnsValue() = runTest {
        assertThat(FakeTransactionRunner(InMemoryDb()).inTransaction { 42 }).isEqualTo(42)
    }

    @Test
    fun errorCodesAreStable() {
        val codes = listOf(
            ServerError.NotFound("a", "b"), ServerError.Conflict("c"), ServerError.Validation("f", "r"),
            ServerError.Unauthorized, ServerError.TokenInvalid, ServerError.TokenExpired, ServerError.TokenUsed,
            ServerError.DeviceDecommissioned("d"), ServerError.BrokerUnavailable("o"),
        ).map { it.code }
        assertThat(codes).containsExactly(
            "not_found", "conflict", "validation", "unauthorized", "token_invalid", "token_expired", "token_used",
            "device_decommissioned", "broker_unavailable",
        ).inOrder()
    }

    @Test
    fun timeRangeDefaults() {
        assertThat(TimeRange()).isEqualTo(TimeRange(null, null))
    }
}
