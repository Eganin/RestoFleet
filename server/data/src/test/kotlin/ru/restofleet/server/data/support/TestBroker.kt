package ru.restofleet.server.data.support

import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5ConnAckException
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAckReasonCode
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import ru.restofleet.server.data.config.MqttConfig
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Mosquitto 2.1.2 (образ стенда) с Dynamic Security для интеграционных тестов. Отличие от стенда — **только** листенер:
 * TCP 1883 без TLS (реш. 232); роли `device` и `backend-service` применяются тем же `deploy/mosquitto/dynsec-roles.sh`,
 * что и на стенде (реш. 231), клиент `backend` создаётся так же, как в `dynsec-dev.sh`. Пароли — только тестовые.
 * Один контейнер на JVM; состояние DynSec общее, поэтому тесты берут уникальные `deviceId`.
 */
object TestBroker {
    const val IMAGE = "eclipse-mosquitto:2.1.2-alpine"
    private const val PORT = 1883
    private const val ADMIN_PASSWORD = "admin-test-only"
    const val BACKEND_PASSWORD = "backend-test-only"

    private val CONF = """
        per_listener_settings false
        allow_anonymous false
        listener $PORT
        protocol mqtt
        plugin /usr/lib/mosquitto_dynamic_security.so
        plugin_opt_config_file /mosquitto/data/dynamic-security.json
        persistence true
        persistence_location /mosquitto/data/
        log_dest stdout
        log_type error
        log_type warning
        log_type notice
        log_type information
        connection_messages true
    """.trimIndent()

    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse(IMAGE))
            .withExposedPorts(PORT)
            .withCopyToContainer(Transferable.of(CONF), "/mosquitto/config/mosquitto.conf")
            .withCommand(
                "sh", "-c",
                "mosquitto_ctrl dynsec init /mosquitto/data/dynamic-security.json admin '$ADMIN_PASSWORD' && " +
                    "chown -R 1883:1883 /mosquitto/data && " +
                    "exec /usr/sbin/mosquitto -c /mosquitto/config/mosquitto.conf",
            )
            .waitingFor(Wait.forLogMessage(".*running.*", 1).withStartupTimeout(Duration.ofSeconds(60)))
            .also {
                it.start()
                applyRoles(it)
            }
    }

    val host: String get() = container.host

    val port: Int get() = container.getMappedPort(PORT)

    /** Конфигурация бэкенда; [clientId] уникален по умолчанию, чтобы тесты не вытесняли друг друга. */
    fun backendConfig(clientId: String = "backend-${UUID.randomUUID()}", sessionExpirySec: Long = 3_600) = MqttConfig(
        host = host,
        port = port,
        username = "backend",
        password = BACKEND_PASSWORD,
        clientId = clientId,
        caFile = null,
        sessionExpirySec = sessionExpirySec,
        requestTimeoutMs = 5_000,
        publishTimeoutMs = 5_000,
    )

    /** Роли продукта из `deploy/mosquitto/dynsec-roles.sh` + клиент `backend` (как в `dynsec-dev.sh`). */
    private fun applyRoles(broker: GenericContainer<*>) {
        val roles = File(System.getProperty("rf.deployDir"), "mosquitto/dynsec-roles.sh").readText()
        // backend создаётся без clientid: тесты подключают несколько экземпляров (на стенде clientid = backend)
        val script = """
            set -eu
            ctrl() { mosquitto_ctrl -h localhost -p $PORT -u admin -P '$ADMIN_PASSWORD' "${'$'}@"; }
            run() {
              out="${'$'}(ctrl dynsec "${'$'}@" 2>&1)" || { echo "FAIL: dynsec ${'$'}1 ${'$'}2: ${'$'}out"; exit 1; }
              case "${'$'}out" in *rror*) echo "FAIL: dynsec ${'$'}1 ${'$'}2: ${'$'}out"; exit 1 ;; esac
            }
            try() { ctrl dynsec "${'$'}@" >/dev/null 2>&1 || true; }
            has_role() { ctrl dynsec listRoles 2>&1 | grep -qx "${'$'}1"; }
            $roles
            run createClient backend -p '$BACKEND_PASSWORD'
            run addClientRole backend backend-service
            echo ROLES_APPLIED
        """.trimIndent()
        val result = broker.execInContainer("sh", "-c", script)
        check(result.exitCode == 0 && "ROLES_APPLIED" in result.stdout) {
            "DynSec roles were not applied: ${result.stdout.takeLast(500)} ${result.stderr.takeLast(500)}"
        }
    }

    /** Права роли в DynSec (`getRole`) — для проверок, что тест опирается на роли из файла. */
    fun describeRole(role: String): String =
        container.execInContainer(
            "mosquitto_ctrl", "-h", "localhost", "-p", "$PORT", "-u", "admin", "-P", ADMIN_PASSWORD,
            "dynsec", "getRole", role,
        ).stdout

    /**
     * Удаляет постоянную сессию бэкенда после теста: в shared-группе `backend` Mosquitto раздаёт сообщения и
     * отключённым участникам с живой сессией — иначе следующий тест не получил бы часть своих сообщений.
     */
    fun purgeSession(clientId: String) {
        val c = Mqtt5Client.builder().identifier(clientId).serverHost(host).serverPort(port).buildBlocking()
        c.connectWith()
            .cleanStart(true)
            .sessionExpiryInterval(0)
            .simpleAuth().username("backend").password(BACKEND_PASSWORD.toByteArray()).applySimpleAuth()
            .send()
        c.disconnect()
    }

    /** Клиент «устройства» на HiveMQ (как агент: MQTT 5, постоянная сессия). */
    fun deviceClient(clientId: String): Mqtt5BlockingClient = Mqtt5Client.builder()
        .identifier(clientId)
        .serverHost(host)
        .serverPort(port)
        .buildBlocking()

    /** Подключение с постоянной сессией; `null` — успех, иначе код отказа CONNACK. */
    fun Mqtt5BlockingClient.connectAs(
        username: String,
        password: String,
        cleanStart: Boolean = false,
    ): Mqtt5ConnAckReasonCode? = try {
        connectWith()
            .cleanStart(cleanStart)
            .sessionExpiryInterval(3_600)
            .simpleAuth().username(username).password(password.toByteArray()).applySimpleAuth()
            .send()
        null
    } catch (e: Mqtt5ConnAckException) {
        e.mqttMessage.reasonCode
    }

    /** Ждёт сообщение не дольше [timeoutMs]; `null` — не пришло. */
    fun Mqtt5BlockingClient.Mqtt5Publishes.next(timeoutMs: Long): Mqtt5Publish? =
        receive(timeoutMs, TimeUnit.MILLISECONDS).orElse(null)

    fun Mqtt5BlockingClient.publishQos1(topic: String, payload: String, retain: Boolean = false) =
        publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE).retain(retain).payload(payload.toByteArray()).send()
}
