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
    private val refresh =
        RefreshSession(w.refreshTokens, w.users, w.sessionIssuer, w.digest, w.tx, w.clock, w.authSettings)
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
    fun replayOfConsumedTokenAfterTheGraceWindowStillRevokesEveryFamilyOfTheUser() = runTest {
        val phone = signIn()
        val desktop = (login("support", password) as Outcome.Success).value
        rotate(phone.refreshToken)
        w.clock.now += AuthSettings.DEFAULT_REUSE_GRACE_MS + 1

        assertThat(refresh(phone.refreshToken)).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
        assertThat(refresh(desktop.refreshToken)).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
    }

    /** RV-4: ответ на refresh потерян — клиент повторяет старый токен; остальные консоли `support` не выходят. */
    @Test
    fun lostRefreshResponseIsForgivenWithinTheGraceWindow() = runTest {
        val phone = signIn()
        val desktop = (login("support", password) as Outcome.Success).value
        w.clock.now += 1_000
        val lost = rotate(phone.refreshToken)
        w.clock.now += AuthSettings.DEFAULT_REUSE_GRACE_MS

        val retried = rotate(phone.refreshToken)

        assertThat(stored(retried.refreshToken).familyId).isEqualTo(stored(phone.refreshToken).familyId)
        assertThat(stored(lost.refreshToken).revokedAt).isEqualTo(w.clock.now)
        assertThat(refresh(desktop.refreshToken)).isInstanceOf(Outcome.Success::class.java)
        assertThat(refresh(retried.refreshToken)).isInstanceOf(Outcome.Success::class.java)
    }

    @Test
    fun repeatedRetryWithinTheWindowKeepsOnlyTheLastPair() = runTest {
        val phone = signIn()
        val desktop = (login("support", password) as Outcome.Success).value
        rotate(phone.refreshToken)
        val first = rotate(phone.refreshToken)
        val second = rotate(phone.refreshToken)

        assertThat(stored(first.refreshToken).revokedAt).isNotNull()
        assertThat(w.db.state.refreshTokens.values.count {
            it.familyId == stored(phone.refreshToken).familyId && it.revokedAt == null
        }).isEqualTo(1)
        assertThat(refresh(second.refreshToken)).isInstanceOf(Outcome.Success::class.java)
        assertThat(refresh(desktop.refreshToken)).isInstanceOf(Outcome.Success::class.java)
    }

    @Test
    fun replayWithinTheWindowAfterTheSuccessorWasUsedIsTheft() = runTest {
        val phone = signIn()
        val desktop = (login("support", password) as Outcome.Success).value
        val next = rotate(phone.refreshToken)
        rotate(next.refreshToken)

        assertThat(refresh(phone.refreshToken)).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
        assertThat(refresh(desktop.refreshToken)).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
    }

    /** Вор повторил старый токен в окне и получил пару — кража обнаруживается, когда владелец предъявит свой. */
    @Test
    fun forgivenReplayByAThiefIsDetectedOnTheOwnersNextRefresh() = runTest {
        val phone = signIn()
        val desktop = (login("support", password) as Outcome.Success).value
        val owner = rotate(phone.refreshToken)
        val thief = rotate(phone.refreshToken)

        assertThat(refresh(owner.refreshToken)).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
        assertThat(refresh(thief.refreshToken)).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
        assertThat(refresh(desktop.refreshToken)).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
    }

    @Test
    fun replayAfterLogoutIsNotForgiven() = runTest {
        val phone = signIn()
        val next = rotate(phone.refreshToken)
        logout(next.refreshToken)

        assertThat(refresh(phone.refreshToken)).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
    }

    @Test
    fun settingsRejectIdleAboveCeiling() {
        val error = runCatching { AuthSettings(refreshIdleMillis = 2, sessionMaxMillis = 1) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        val grace = runCatching { AuthSettings(reuseGraceMillis = -1) }.exceptionOrNull()
        assertThat(grace).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(AuthSettings.DEFAULT_REUSE_GRACE_MS).isEqualTo(60_000L)
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
