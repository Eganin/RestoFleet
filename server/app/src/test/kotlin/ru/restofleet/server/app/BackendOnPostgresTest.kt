package ru.restofleet.server.app

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import ru.restofleet.server.api.ApiSettings
import ru.restofleet.server.api.DevicePayloadDecoder
import ru.restofleet.server.app.config.AppConfigLoader
import ru.restofleet.server.app.config.RuntimeConfig
import ru.restofleet.server.app.di.ServeJobs
import ru.restofleet.server.app.di.apiUseCases
import ru.restofleet.server.app.di.serveModules
import ru.restofleet.server.app.serve.IngestLifecycle
import ru.restofleet.server.app.serve.backendModule
import ru.restofleet.server.domain.auth.AccessTokenIssuer
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.getOrNull
import java.io.File
import java.sql.DriverManager

/**
 * Composition root на настоящем PostgreSQL (Testcontainers, образ стенда; реш. 215): CLI `migrate` и `seed` через
 * `run(...)` с конфигурацией из окружения и секретами из файлов, весь граф Koin `serve`, затем приложение Ktor
 * (`backendModule`) — вход seed-администратора, список ресторанов, `/health` без брокера. MQTT указывает на закрытый
 * порт: ingest пытается подключиться и корректно останавливается вместе с приложением.
 */
class BackendOnPostgresTest {

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    @Test
    fun `migrate applies the schema once and is idempotent`() {
        assertThat(run(listOf("migrate"), env)).isEqualTo(0)
        assertThat(count("flyway_schema_history WHERE success")).isAtLeast(1)
        assertThat(run(listOf("migrate"), env)).isEqualTo(0)
    }

    @Test
    fun `seed loads the stand files through use cases and a rerun updates instead of duplicating`() {
        run(listOf("migrate"), env)
        val args = listOf("seed", "--restaurants", "$seedDir/restaurants.yaml", "--users", "$seedDir/users.yaml")

        assertThat(run(args, env)).isEqualTo(0)
        val restaurants = count("restaurants")
        assertThat(run(args, env)).isEqualTo(0)

        assertThat(restaurants).isAtLeast(40)
        assertThat(count("restaurants")).isEqualTo(restaurants)
        assertThat(count("users WHERE login = 'admin' AND role = 'admin'")).isEqualTo(1)
    }

    @Test
    fun `seed fails when the admin secret is missing`() {
        run(listOf("migrate"), env)
        val args = listOf(
            "seed", "--restaurants", "$seedDir/restaurants.yaml", "--users", "$seedDir/users.yaml",
            "--secrets-dir", secrets.newFolder().path,
        )

        assertThat(runCatching { run(args, env) }.isFailure).isTrue()
    }

    @Test
    fun `every bean of the serve graph resolves`() {
        val runtime = RuntimeConfig.forServe(AppConfigLoader.load(env))
        val app = koinApplication { modules(serveModules(runtime)) }
        try {
            val koin = app.koin
            assertThat(apiUseCases(koin)).isNotNull()
            assertThat(koin.get<ApiSettings>().jwt.secret).isEqualTo(JWT_SECRET)
            assertThat(koin.get<AccessTokenIssuer>()).isNotNull()
            assertThat(koin.get<IngestLifecycle>()).isNotNull()
            assertThat(koin.get<ServeJobs>().all.map { it.name })
                .containsExactly("expire_commands", "mark_stale_pollers", "ingest_metrics")
            val decoder = koin.get<DevicePayloadDecoder>()
            val id = DeviceId.parse("d-x")!!
            assertThat(decoder.telemetryBatch(id, """{"telemetry":{"ts":1793865630000,"seq":1}}""").getOrNull())
                .isNotNull()
            assertThat(decoder.commandReport(id, """{"cmdId":"c-1","status":"received","ts":1}""").getOrNull())
                .isNotNull()
        } finally {
            app.close()
        }
    }

    @Test
    fun `serve application logs the seeded admin in and reports a missing broker as degraded`() {
        run(listOf("migrate"), env)
        run(listOf("seed", "--restaurants", "$seedDir/restaurants.yaml", "--users", "$seedDir/users.yaml"), env)
        val runtime = RuntimeConfig.forServe(AppConfigLoader.load(env))

        testApplication {
            application { backendModule(runtime) }

            val login = client.post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"login":"admin","password":"$ADMIN_PASSWORD"}""")
            }
            val token = Json.parseToJsonElement(login.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
            val restaurants = client.get("/api/v1/restaurants") { header(HttpHeaders.Authorization, "Bearer $token") }
            val health = client.get("/health")

            assertThat(login.status).isEqualTo(HttpStatusCode.OK)
            assertThat(Json.parseToJsonElement(restaurants.bodyAsText()).jsonObject["items"]!!.jsonArray.size)
                .isAtLeast(40)
            assertThat(health.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
            val components = Json.parseToJsonElement(health.bodyAsText()).jsonObject["components"]!!.jsonObject
            assertThat(components["database"]!!.jsonPrimitive.content).isEqualTo("true")
            assertThat(components["broker"]!!.jsonPrimitive.content).isEqualTo("false")
        }
    }

    companion object {
        private const val IMAGE = "timescale/timescaledb:2.31.0-pg17"
        private const val DB_PASSWORD = "test-only-db"
        private const val JWT_SECRET = "jwt-secret-for-tests-jwt-secret-for-tests"
        private const val ADMIN_PASSWORD = "admin-password-for-tests"

        private val seedDir = System.getProperty("rf.seedDir")

        @get:ClassRule
        @JvmStatic
        val secrets = TemporaryFolder()

        private lateinit var postgres: PostgreSQLContainer
        private lateinit var env: Map<String, String>

        @BeforeClass
        @JvmStatic
        fun start() {
            postgres = PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("restofleet")
                .withUsername("restofleet")
                .withPassword(DB_PASSWORD)
                .also { it.start() }
            val dir = secrets.newFolder("run-secrets")
            fun secret(name: String, value: String) = File(dir, name).apply { writeText("$value\n") }.path
            val ca = File(dir, "ca.crt").apply {
                writeText(checkNotNull(BackendOnPostgresTest::class.java.getResource("/tls/test-ca.crt")).readText())
            }
            env = mapOf(
                "RF_DATABASE_JDBCURL" to postgres.jdbcUrl,
                "RF_DATABASE_USER" to postgres.username,
                "RF_DATABASE_PASSWORDFILE" to secret("postgres-password", DB_PASSWORD),
                "RF_DATABASE_MAXPOOLSIZE" to "4",
                "RF_MQTT_HOST" to "127.0.0.1",
                "RF_MQTT_PORT" to "1",
                "RF_MQTT_PASSWORDFILE" to secret("backend-mqtt-password", "unused"),
                "RF_MQTT_CAFILE" to ca.path,
                "RF_JWT_SECRETFILE" to secret("jwt-secret", JWT_SECRET),
                "RF_SECRETSDIR" to dir.path,
            )
            secret("admin-password", ADMIN_PASSWORD)
        }

        @AfterClass
        @JvmStatic
        fun stop() {
            postgres.stop()
        }

        private fun count(from: String): Int =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, DB_PASSWORD).use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT count(*) FROM $from").use { it.next(); it.getInt(1) }
                }
            }
    }
}
