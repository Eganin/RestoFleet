package ru.restofleet.server.app

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.restofleet.server.app.config.AppConfigLoader
import ru.restofleet.server.app.config.RuntimeConfig
import ru.restofleet.server.app.config.SecretFiles
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.errorOrNull
import ru.restofleet.shared.kernel.getOrNull
import kotlin.test.assertFailsWith

class CliAndConfigTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `serve is the default, migrate and seed are parsed, anything else prints usage`() {
        assertThat(Cli.parse(emptyList()).getOrNull()).isEqualTo(Command.Serve)
        assertThat(Cli.parse(listOf("serve")).getOrNull()).isEqualTo(Command.Serve)
        assertThat(Cli.parse(listOf("migrate")).getOrNull()).isEqualTo(Command.Migrate)
        assertThat(Cli.parse(listOf("seed", "--restaurants", "r.yaml", "--users", "u.yaml")).getOrNull())
            .isEqualTo(Command.Seed("r.yaml", "u.yaml", null))
        val reordered = listOf("seed", "--users", "u.yaml", "--restaurants", "r.yaml", "--secrets-dir", "/s")
        assertThat(Cli.parse(reordered).getOrNull()).isEqualTo(Command.Seed("r.yaml", "u.yaml", "/s"))

        listOf(
            listOf("serve", "now"),
            listOf("migrate", "--force"),
            listOf("seed", "--restaurants", "r.yaml"),
            listOf("seed", "--restaurants", "r.yaml", "--users"),
            listOf("seed", "--restaurants", "r.yaml", "--users", "u.yaml", "--password", "x"),
            listOf("drop-database"),
        ).forEach { assertThat(Cli.parse(it).errorOrNull()).isEqualTo(Cli.USAGE) }
    }

    @Test
    fun `stand defaults come from application yaml`() {
        val config = AppConfigLoader.load(emptyMap())

        assertThat(config.http.port).isEqualTo(8080)
        assertThat(config.database.jdbcUrl).isEqualTo("jdbc:postgresql://postgres:5432/restofleet")
        assertThat(config.database.passwordFile).isEqualTo("/run/secrets/postgres-password")
        assertThat(config.mqtt.host).isEqualTo("mosquitto")
        assertThat(config.mqtt.caFile).isEqualTo("/certs/ca.crt")
        assertThat(config.provisioning.brokerHost).isEqualTo("10.0.2.2")
        assertThat(config.jobs.expireCommandsMs).isEqualTo(5_000)
        assertThat(config.jobs.markStalePollersMs).isEqualTo(5_000)
    }

    @Test
    fun `environment variables with the RF prefix override the file, unrelated ones are ignored`() {
        val config = AppConfigLoader.load(
            mapOf(
                "RF_DATABASE_JDBCURL" to "jdbc:postgresql://localhost:5432/x",
                "RF_MQTT_PORT" to "18883",
                "RF_RATELIMIT_PROVISIONPERMINUTE" to "100000",
                "RF_PROVISIONING_BROKERHOST" to "192.168.1.5",
                "DATABASE_JDBCURL" to "jdbc:ignored",
            ),
        )

        assertThat(config.database.jdbcUrl).isEqualTo("jdbc:postgresql://localhost:5432/x")
        assertThat(config.mqtt.port).isEqualTo(18883)
        assertThat(config.rateLimit.provisionPerMinute).isEqualTo(100_000)
        assertThat(config.provisioning.brokerHost).isEqualTo("192.168.1.5")
    }

    @Test
    fun `an extra config file from RF_CONFIG sits between the environment and the defaults`() {
        val file = tmp.newFile("backend.yaml").apply { writeText("http:\n  port: 9090\nmqtt:\n  host: broker\n") }

        val config = AppConfigLoader.load(mapOf("RF_CONFIG" to file.path, "RF_MQTT_HOST" to "from-env"))

        assertThat(config.http.port).isEqualTo(9090)
        assertThat(config.mqtt.host).isEqualTo("from-env")
    }

    @Test
    fun `serve reads db, mqtt and jwt secrets, other commands only the db password`() {
        val config = AppConfigLoader.load(emptyMap())
        val servePaths = mutableListOf<String>()
        val databasePaths = mutableListOf<String>()

        val serve = RuntimeConfig.forServe(config) { path -> servePaths += path; "secret-of-$path".padEnd(40, 'x') }
        RuntimeConfig.forDatabaseOnly(config) { path -> databasePaths += path; "db" }

        assertThat(servePaths).containsExactly(
            "/run/secrets/postgres-password",
            "/run/secrets/backend-mqtt-password",
            "/run/secrets/jwt-secret",
        )
        assertThat(databasePaths).containsExactly("/run/secrets/postgres-password")
        assertThat(serve.api!!.jwt.accessTtlMillis).isEqualTo(15 * 60_000L)
        assertThat(serve.api!!.rateLimit.provisionPerPeriod).isEqualTo(60)
        assertThat(serve.data.mqtt.caFile).isEqualTo("/certs/ca.crt")
        assertThat(serve.provisioning.tokenTtlMillis).isEqualTo(24 * 3_600_000L)
        assertThat(serve.polling.staleAfterMillis).isEqualTo(90_000)
        assertThat(serve.auth.refreshTtlMillis).isEqualTo(7 * 86_400_000L)
        assertThat(serve.data.toString()).doesNotContain("secret-of")
    }

    @Test
    fun `secret files are trimmed, errors name the path only, names cannot escape the directory`() {
        val dir = tmp.newFolder("secrets")
        dir.resolve("admin-password").writeText("s3cret-value\n")
        dir.resolve("empty").writeText("\n")

        assertThat(SecretFiles.readNamed(dir.path, "admin-password")).isEqualTo("s3cret-value")
        val missing = assertFailsWith<IllegalStateException> { SecretFiles.read(dir.resolve("nope").path) }
        assertThat(missing.message).contains("nope")
        assertFailsWith<IllegalStateException> { SecretFiles.readNamed(dir.path, "empty") }
        assertFailsWith<IllegalArgumentException> { SecretFiles.readNamed(dir.path, "../admin-password") }
        assertFailsWith<IllegalArgumentException> { SecretFiles.readNamed(dir.path, "/etc/passwd") }
    }

    @Test
    fun `unknown command exits with usage code without touching configuration`() {
        assertThat(run(listOf("drop-database"), emptyMap())).isEqualTo(2)
        assertThat(Cli.parse(listOf("x"))).isInstanceOf(Outcome.Failure::class.java)
    }
}
