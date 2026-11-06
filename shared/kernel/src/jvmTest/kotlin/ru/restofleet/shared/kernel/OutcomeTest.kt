package ru.restofleet.shared.kernel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OutcomeTest {

    private val failure: Outcome<Int, String> = Outcome.Failure("e")

    @Test
    fun mapTransformsSuccess() {
        assertThat(Outcome.Success(2).map { it * 2 }).isEqualTo(Outcome.Success(4))
    }

    @Test
    fun mapKeepsFailure() {
        assertThat(failure.map { it * 2 }).isEqualTo(failure)
    }

    @Test
    fun flatMapChainsSuccess() {
        val result = Outcome.Success(2).flatMap { Outcome.Success(it + 1) }
        assertThat(result).isEqualTo(Outcome.Success(3))
    }

    @Test
    fun flatMapShortCircuitsOnFailure() {
        var called = false
        val result = failure.flatMap {
            called = true
            Outcome.Success(it)
        }
        assertThat(called).isFalse()
        assertThat(result).isEqualTo(failure)
    }

    @Test
    fun mapErrorTransformsOnlyFailure() {
        assertThat(failure.mapError { it.length }).isEqualTo(Outcome.Failure(1))
        val success: Outcome<Int, String> = Outcome.Success(5)
        assertThat(success.mapError { it.length }).isEqualTo(Outcome.Success(5))
    }

    @Test
    fun foldSelectsBranch() {
        assertThat(Outcome.Success(2).fold({ "ok $it" }, { "err" })).isEqualTo("ok 2")
        assertThat(failure.fold({ "ok" }, { "err $it" })).isEqualTo("err e")
    }

    @Test
    fun getOrNullAndErrorOrNull() {
        assertThat(Outcome.Success(7).getOrNull()).isEqualTo(7)
        assertThat(failure.getOrNull() as Any?).isNull()
        assertThat(failure.errorOrNull()).isEqualTo("e")
        val success: Outcome<Int, String> = Outcome.Success(7)
        assertThat(success.errorOrNull()).isNull()
    }

    @Test
    fun unexpectedErrorHasStableCode() {
        val error: DomainError = UnexpectedError("boom")
        assertThat(error.code).isEqualTo("unexpected")
    }
}
