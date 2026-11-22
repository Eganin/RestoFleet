package ru.restofleet.server.domain.auth

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.domain.auth.usecase.Login
import ru.restofleet.server.domain.auth.usecase.RefreshSession
import ru.restofleet.server.domain.auth.usecase.UpsertUser
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.fake.World
import ru.restofleet.shared.kernel.Outcome

class AuthUseCasesTest {
    private val w = World()
    private val upsert = UpsertUser(w.users, w.hasher, w.tx, w.ids, w.clock)
    private val login = Login(w.users, w.sessionIssuer, w.hasher, w.tx, w.clock)
    private val refresh = RefreshSession(w.refreshTokens, w.users, w.sessionIssuer, w.digest, w.tx, w.clock)

    private val password = "correct horse battery"

    @Test
    fun upsertCreatesThenChangesPassword() = runTest {
        val user = (upsert(" admin ", password) as Outcome.Success).value
        assertThat(user.login).isEqualTo("admin")
        assertThat(user.role).isEqualTo(User.ROLE_ADMIN)
        assertThat(user.passwordHash).isEqualTo("bcrypt($password)")
        assertThat(user.toString()).doesNotContain(password)
        val changed = (upsert("admin", "another long pass") as Outcome.Success).value
        assertThat(changed.id).isEqualTo(user.id)
        assertThat(w.db.state.users.getValue(user.id).passwordHash).isEqualTo("bcrypt(another long pass)")
    }

    @Test
    fun upsertValidates() = runTest {
        assertThat(upsert("a b", password))
            .isEqualTo(Outcome.Failure(ServerError.Validation("login", "invalid_format")))
        assertThat(upsert("admin", "short")).isEqualTo(Outcome.Failure(ServerError.Validation("password", "length")))
        assertThat(upsert("admin", "x".repeat(73)))
            .isEqualTo(Outcome.Failure(ServerError.Validation("password", "length")))
    }

    @Test
    fun loginIssuesTokensAndStoresRefreshHash() = runTest {
        upsert("admin", password)
        val tokens = (login("admin", password) as Outcome.Success).value
        assertThat(tokens.accessToken).startsWith("jwt-")
        assertThat(tokens.refreshExpiresAt).isEqualTo(w.clock.now + AuthSettings.DEFAULT_REFRESH_TTL_MS)
        assertThat(w.db.state.refreshTokens.values.single().tokenHash).isEqualTo("sha(${tokens.refreshToken})")
        assertThat(tokens.toString()).doesNotContain(tokens.refreshToken)
    }

    @Test
    fun loginFailuresVerifyAgainstDummyHash() = runTest {
        upsert("admin", password)
        val before = w.hasher.verifies
        assertThat(login("admin", "wrong")).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
        assertThat(login("ghost", password)).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
        assertThat(w.hasher.verifies - before).isEqualTo(2)
        assertThat(w.db.state.refreshTokens).isEmpty()
    }

    @Test
    fun refreshRotates() = runTest {
        upsert("admin", password)
        val first = (login("admin", password) as Outcome.Success).value
        w.clock.now += 1_000
        val second = (refresh(first.refreshToken) as Outcome.Success).value
        assertThat(second.refreshToken).isNotEqualTo(first.refreshToken)
        val stored = w.db.state.refreshTokens.values.associateBy { it.tokenHash }
        assertThat(stored.getValue("sha(${first.refreshToken})").revokedAt).isEqualTo(w.clock.now)
        assertThat(stored.getValue("sha(${second.refreshToken})").revokedAt).isNull()
    }

    @Test
    fun reuseOfRotatedTokenRevokesAll() = runTest {
        upsert("admin", password)
        val first = (login("admin", password) as Outcome.Success).value
        val second = (refresh(first.refreshToken) as Outcome.Success).value
        assertThat(refresh(first.refreshToken)).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
        assertThat(refresh(second.refreshToken)).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
        assertThat(w.db.state.refreshTokens.values.all { it.revokedAt != null }).isTrue()
    }

    @Test
    fun unknownExpiredAndMalformedRefresh() = runTest {
        upsert("admin", password)
        val tokens = (login("admin", password) as Outcome.Success).value
        assertThat(refresh("")).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
        assertThat(refresh("x".repeat(257))).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
        assertThat(refresh("unknown")).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
        w.clock.now += AuthSettings.DEFAULT_REFRESH_TTL_MS
        assertThat(refresh(tokens.refreshToken)).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
        // Просроченный, но не погашенный — не повод отзывать остальные
        assertThat(w.db.state.refreshTokens.values.single().revokedAt).isNull()
    }

    @Test
    fun secretsAreMaskedInToString() {
        assertThat(AccessToken("abc", 1).toString()).doesNotContain("abc")
    }
}
