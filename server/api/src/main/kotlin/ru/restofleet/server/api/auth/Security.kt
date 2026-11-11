package ru.restofleet.server.api.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.basic
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.restofleet.server.api.JwtSettings
import ru.restofleet.server.api.error.ErrorBody
import ru.restofleet.server.api.error.ErrorCodes
import ru.restofleet.server.domain.auth.AccessToken
import ru.restofleet.server.domain.auth.AccessTokenIssuer
import ru.restofleet.server.domain.auth.User
import ru.restofleet.server.domain.polling.usecase.AuthenticateDevice
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.IdGenerator
import ru.restofleet.shared.kernel.getOrNull
import java.util.Date

/** Имена провайдеров аутентификации. */
object AuthProviders {
    /** Пользователь консоли/Swagger: `Authorization: Bearer <access JWT>`, роль `admin` (реш. 216). */
    const val ADMIN = "admin"

    /** Устройство в режиме REST-polling: HTTP Basic `deviceId:password` (P9, реш. 213). */
    const val DEVICE = "device"
}

/** Аутентифицированное устройство (REST-polling): всё, что оно видит и пишет, — только своё (BOLA). */
data class DevicePrincipal(val deviceId: DeviceId)

internal object JwtClaims {
    const val LOGIN = "login"
    const val ROLE = "role"
}

/**
 * Выпуск access JWT (порт домена [AccessTokenIssuer], реш. 250): HS256, `sub` = id пользователя, `login`, `role`,
 * `jti`, срок — [JwtSettings.accessTtlMillis] (15 мин).
 */
class JwtAccessTokenIssuer(
    private val settings: JwtSettings,
    private val clock: Clock,
    private val ids: IdGenerator,
) : AccessTokenIssuer {
    private val algorithm = Algorithm.HMAC256(settings.secret)

    override fun issue(user: User): AccessToken {
        val now = clock.nowMillis()
        val expiresAt = now + settings.accessTtlMillis
        val token = JWT.create()
            .withIssuer(settings.issuer)
            .withAudience(settings.audience)
            .withSubject(user.id)
            .withClaim(JwtClaims.LOGIN, user.login)
            .withClaim(JwtClaims.ROLE, user.role)
            .withJWTId(ids.newId())
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(expiresAt))
            .sign(algorithm)
        return AccessToken(token, expiresAt)
    }
}

internal fun jwtVerifier(settings: JwtSettings): JWTVerifier = JWT.require(Algorithm.HMAC256(settings.secret))
    .withIssuer(settings.issuer)
    .withAudience(settings.audience)
    .build()

/**
 * Провайдеры: JWT для API консоли (только роль `admin`, иначе 401) и Basic для `/poll/…`. Проверка пароля
 * устройства (bcrypt при промахе кэша, реш. 213) идёт на `Dispatchers.Default`: CPU-работа не занимает потоки вызовов
 * Netty.
 */
fun Application.installSecurity(settings: JwtSettings, authenticateDevice: AuthenticateDevice) {
    install(Authentication) {
        jwt(AuthProviders.ADMIN) {
            realm = settings.realm
            verifier(jwtVerifier(settings))
            validate { credential ->
                credential.payload.takeIf { it.getClaim(JwtClaims.ROLE).asString() == User.ROLE_ADMIN }
                    ?.let { JWTPrincipal(it) }
            }
            challenge { _, realm ->
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"$realm\"")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorBody(ErrorCodes.UNAUTHORIZED, "valid admin access token required"),
                )
            }
        }
        basic(AuthProviders.DEVICE) {
            realm = "${settings.realm}-device"
            validate { credentials ->
                withContext(Dispatchers.Default) { authenticateDevice(credentials.name, credentials.password) }
                    .getOrNull()
                    ?.let { DevicePrincipal(it) }
            }
        }
    }
}

/** Устройство запроса под `authenticate(DEVICE)`. */
fun ApplicationCall.device(): DeviceId =
    checkNotNull(principal<DevicePrincipal>()) { "route is not under device authentication" }.deviceId
