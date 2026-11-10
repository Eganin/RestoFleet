package ru.restofleet.spike

import com.hivemq.client.mqtt.MqttClientExecutorConfig
import com.hivemq.client.mqtt.MqttClientSslConfig
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.hivemq.client.mqtt.mqtt5.Mqtt5ClientBuilder
import java.io.FileInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.system.exitProcess

/** Общие параметры подключения к стенду. Пароль admin — только из переменной окружения (в argv виден в `ps`). */
data class Broker(val host: String, val port: Int, val caPath: String)

/** Простой разбор `--ключ значение` после имени команды. */
class Args(private val map: Map<String, String>) {
    fun str(key: String, default: String? = null): String =
        map[key] ?: default ?: error("нужен аргумент --$key")

    fun int(key: String, default: Int? = null): Int = map[key]?.toInt() ?: default ?: error("нужен аргумент --$key")

    fun broker() = Broker(str("host", "localhost"), int("port", 8883), str("ca", "deploy/certs/out/ca.crt"))

    companion object {
        fun parse(raw: List<String>): Args {
            require(raw.size % 2 == 0) { "аргументы — пары --ключ значение" }
            return Args(raw.chunked(2).associate { (k, v) -> k.removePrefix("--") to v })
        }
    }
}

/** Общий пул Netty на все клиенты процесса (S1: один процесс, event loop на `availableProcessors`). */
val sharedExecutor: MqttClientExecutorConfig = MqttClientExecutorConfig.builder()
    .nettyThreads(Runtime.getRuntime().availableProcessors())
    .build()

private fun trustFactory(caPath: String): TrustManagerFactory {
    val ca = FileInputStream(caPath).use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        setCertificateEntry("rf-ca", ca)
    }
    return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
}

/** Билдер MQTT 5-клиента со TLS по CA стенда и общим executor'ом; логин/пароль — байтами, не логируются. */
fun clientBuilder(broker: Broker, clientId: String, user: String, password: String, trust: TrustManagerFactory): Mqtt5ClientBuilder =
    Mqtt5Client.builder()
        .identifier(clientId)
        .serverHost(broker.host)
        .serverPort(broker.port)
        .executorConfig(sharedExecutor)
        .sslConfig(MqttClientSslConfig.builder().trustManagerFactory(trust).build())
        .simpleAuth()
        .username(user)
        .password(password.toByteArray(Charsets.UTF_8))
        .applySimpleAuth()

fun loadTrust(broker: Broker): TrustManagerFactory = trustFactory(broker.caPath)

fun adminPassword(): String = System.getenv("RF_ADMIN_PASSWORD")?.takeIf { it.isNotEmpty() }
    ?: error("задайте RF_ADMIN_PASSWORD в окружении (значение из deploy/.env)")

private const val USAGE = """
Команды (аргументы — пары --ключ значение; --host localhost --port 8883 --ca deploy/certs/out/ca.crt по умолчанию):
  dynsec-create  --count 1000 --batch 100 --creds deploy/out/spike-creds.csv [--prefix spike-]   (RF_ADMIN_PASSWORD в окружении)
  dynsec-delete  --prefix spike-  [--batch 100]                                                  (RF_ADMIN_PASSWORD в окружении)
  load           --clients 1000 --ramp-per-sec 200 --interval 30 --hold 180 --out results/s1-1000.csv --creds deploy/out/spike-creds.csv
  disable-client --id spike-00001 --creds deploy/out/spike-creds.csv                             (RF_ADMIN_PASSWORD в окружении)
"""

fun main(argv: Array<String>) {
    val cmd = argv.firstOrNull()
    val args = Args.parse(argv.drop(1))
    when (cmd) {
        "dynsec-create" -> DynSecSpike.create(args)
        "dynsec-delete" -> DynSecSpike.delete(args)
        "load" -> LoadSpike.run(args)
        "disable-client" -> DisableClientSpike.run(args)
        else -> {
            System.err.println(USAGE)
            exitProcess(2)
        }
    }
    exitProcess(0)
}
