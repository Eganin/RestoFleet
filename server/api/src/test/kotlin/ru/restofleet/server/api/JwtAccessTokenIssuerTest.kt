package ru.restofleet.server.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.server.api.auth.JwtAccessTokenIssuer
import ru.restofleet.server.api.auth.jwtVerifier
import ru.restofleet.server.domain.auth.User
import ru.restofleet.shared.kernel.IdGenerator
import kotlin.test.assertFailsWith

class JwtAccessTokenIssuerTest {
    private val settings = JwtSettings(secret = "0123456789abcdef0123456789abcdef")
    private val now = System.currentTimeMillis()
    private val user = User("u-42", "admin", "hash", User.ROLE_ADMIN, 0)

    @Test
    fun `access token is a verifiable jwt for 15 minutes with subject, login and role`() {
        val token = JwtAccessTokenIssuer(settings, { now }, IdGenerator { "jti-1" }).issue(user)

        val jwt = jwtVerifier(settings).verify(token.token)

        assertThat(token.expiresAt).isEqualTo(now + 15 * 60 * 1000)
        assertThat(jwt.subject).isEqualTo("u-42")
        assertThat(jwt.issuer).isEqualTo("restofleet")
        assertThat(jwt.audience).containsExactly("restofleet-api")
        assertThat(jwt.getClaim("login").asString()).isEqualTo("admin")
        assertThat(jwt.getClaim("role").asString()).isEqualTo("admin")
        assertThat(jwt.id).isEqualTo("jti-1")
        assertThat(jwt.expiresAt.time / 1000).isEqualTo(token.expiresAt / 1000)
    }

    @Test
    fun `short secrets and non positive ttl are rejected, secret never printed`() {
        assertFailsWith<IllegalArgumentException> { JwtSettings(secret = "short") }
        assertFailsWith<IllegalArgumentException> { settings.copy(accessTtlMillis = 0) }
        assertFailsWith<IllegalArgumentException> { RateLimitSettings(loginPerPeriod = 0) }

        assertThat(settings.toString()).doesNotContain(settings.secret)
    }
}
