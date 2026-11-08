package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.fake.TestError
import ru.restofleet.shared.kernel.Outcome

class FetchApkChecksumTest {
    private val requested = mutableListOf<String>()
    private var answer: Outcome<String, ru.restofleet.shared.kernel.DomainError> = Outcome.Success(HASH)
    private val fetch = FetchApkChecksum { url ->
        requested += url
        answer
    }

    @Test
    fun asksForTheSiblingShaFileAndReturnsTheHash() = runTest {
        val outcome = fetch("https://10.0.2.2:8443/waiter-4.13.apk")

        assertThat(requested).containsExactly("https://10.0.2.2:8443/waiter-4.13.apk.sha256")
        assertThat(outcome).isEqualTo(Outcome.Success(HASH))
    }

    @Test
    fun sourceFailureIsPropagatedUnchanged() = runTest {
        answer = Outcome.Failure(TestError("checksum_invalid"))

        assertThat(fetch("https://host/a.apk")).isEqualTo(Outcome.Failure(TestError("checksum_invalid")))
    }

    @Test
    fun notHttpsOrMalformedOrWithQueryNeverReachesTheSource() = runTest {
        val bad = listOf(
            "", "   ", "http://host/a.apk", "ftp://host/a.apk", "https://", "https://u:p@host/a.apk",
            "https://host/a.apk?x=1", "https://host/a.apk#f", "https://host/a b.apk",
        )

        bad.forEach { assertThat(fetch(it)).isEqualTo(Outcome.Failure(InvalidChecksumUrl)) }

        assertThat(requested).isEmpty()
    }

    private companion object {
        val HASH = "ab".repeat(32)
    }
}
