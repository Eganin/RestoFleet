package ru.restofleet.server.domain.auth

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.domain.auth.usecase.Login
import ru.restofleet.server.domain.auth.usecase.Logout
import ru.restofleet.server.domain.auth.usecase.RefreshSession
import ru.restofleet.server.domain.auth.usecase.UpsertUser
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.fake.World
import ru.restofleet.shared.kernel.Outcome

/** Сессии пользователя (реш. 417): семейство refresh-токенов, потолок 7 сут, простой 24 ч, выход. */
class SessionLifetimeTest {
    private val w = World()
    private val upsert = UpsertUser(w.users, w.hasher, w.tx, w.ids, w.clock)
    private val login = Login(w.users, w.sessionIssuer, w.hasher, w.tx, w.clock)
    private val refresh = RefreshSession(w.refreshTokens, w.users, w.sessionIssuer, w.digest, w.tx, w.clock)
    private val logout = Logout(w.refreshTokens, w.digest, w.tx, w.clock)
    private val password = "correct horse battery"

    private suspend fun signIn(): SessionTokens {
        upsert("support", password)
        return (login("support", password) as Outcome.Success).value
    }

    private suspend fun rotate(token: String) = (refresh(token) as Outcome.Success).value

    private fun stored(token: String) = w.db.state.refreshTokens.values.single { it.tokenHash == "sha($token)" }

    @Test
    fun loginStartsFamilyWithCeilingAndIdleExpiry() = runTest {
        val tokens = signIn()
        val token = stored(tokens.refreshToken)
        assertThat(token.familyId).isNotEmpty()
        assertThat(token.sessionExpiresAt).isEqualTo(w.clock.now + AuthSettings.DEFAULT_SESSION_MAX_MS)
        assertThat(tokens.refreshExpiresAt).isEqualTo(w.clock.now + AuthSettings.DEFAULT_REFRESH_IDLE_MS)
        assertThat(AuthSettings.DEFAULT_REFRESH_IDLE_MS).isEqualTo(24L * 3_600_000)
        assertThat(AuthSettings.DEFAULT_SESSION_MAX_MS).isEqualTo(7L * 86_400_000)
    }

    @Test
    fun rotationInheritsFamilyAndNeverPassesTheCeiling() = runTest {
        val first = signIn()
        val start = w.clock.now
        val family = stored(first.refreshToken).familyId
        var current = first
        // каждые 23 ч — сессия жива, пока не упрётся в потолок 7 сут
        repeat(7) {
            w.clock.now += 23 * HOUR
            current = rotate(current.refreshToken)
            assertThat(stored(current.refreshToken).familyId).isEqualTo(family)
            assertThat(current.refreshExpiresAt).isAtMost(start + AuthSettings.DEFAULT_SESSION_MAX_MS)
        }
        assertThat(current.refreshExpiresAt).isEqualTo(start + AuthSettings.DEFAULT_SESSION_MAX_MS)
        w.clock.now = start + AuthSettings.DEFAULT_SESSION_MAX_MS
        assertThat(refresh(current.refreshToken)).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
    }

    @Test
    fun idleLongerThanADayNeedsANewLogin() = runTest {
        val tokens = signIn()
        w.clock.now += 24 * HOUR
        assertThat(refresh(tokens.refreshToken)).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
    }

    @Test
    fun logoutRevokesOnlyItsFamilyAndIsIdempotent() = runTest {
        val phone = signIn()
        val desktop = (login("support", password) as Outcome.Success).value
        val rotated = rotate(phone.refreshToken)

        logout(rotated.refreshToken)
        logout(rotated.refreshToken)
        logout("unknown")
        logout("")

        assertThat(refresh(rotated.refreshToken)).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
        assertThat(refresh(desktop.refreshToken)).isInstanceOf(Outcome.Success::class.java)
        assertThat(w.db.state.refreshTokens.values.filter { it.familyId == stored(phone.refreshToken).familyId }
            .all { it.revokedAt != null }).isTrue()
    }

    @Test
    fun replayOfConsumedTokenStillRevokesEveryFamilyOfTheUser() = runTest {
        val phone = signIn()
        val desktop = (login("support", password) as Outcome.Success).value
        rotate(phone.refreshToken)

        assertThat(refresh(phone.refreshToken)).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
        assertThat(refresh(desktop.refreshToken)).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
    }

    @Test
    fun settingsRejectIdleAboveCeiling() {
        val error = runCatching { AuthSettings(refreshIdleMillis = 2, sessionMaxMillis = 1) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
