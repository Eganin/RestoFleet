package ru.restofleet.spike

import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.Topics
import ru.restofleet.shared.protocol.dto.AppVersionDto
import ru.restofleet.shared.protocol.dto.BatteryDto
import ru.restofleet.shared.protocol.dto.MemoryDto
import ru.restofleet.shared.protocol.dto.NetDto
import ru.restofleet.shared.protocol.dto.StorageDto
import ru.restofleet.shared.protocol.dto.TelemetryDto
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

private const val SAMPLE_SEC = 10
private const val MB = 1024.0 * 1024.0
private const val NS_PER_SEC = 1_000_000_000.0
private const val KEEPALIVE_SEC = 60
private const val JITTER = 0.1
private const val SHUTDOWN_WAIT_SEC = 30L

/** S1: N MQTT-клиентов в одном процессе с общим Netty-пулом, телеметрия `TelemetryDto` с jitter. */
object LoadSpike {
    private val connected = AtomicInteger()
    private val connectFailed = AtomicInteger()
    private val connectionLost = AtomicInteger()
    private val pubOk = AtomicLong()
    private val pubFail = AtomicLong()
    private val failures = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile private var stopping = false

    private fun note(kind: String, e: Throwable?) {
        failures.computeIfAbsent("$kind:${e?.javaClass?.simpleName ?: "-"}") { AtomicInteger() }.incrementAndGet()
    }

    fun run(args: Args) {
        val n = args.int("clients", 1000)
        val ramp = args.int("ramp-per-sec", 200)
        val intervalSec = args.int("interval", 30)
        val holdSec = args.int("hold", 180)
        val out = File(args.str("out", "results/s1-$n.csv")).also { it.absoluteFile.parentFile.mkdirs() }
        val creds = readCreds(args.str("creds", "deploy/out/spike-creds.csv"))
        val ids = creds.keys.sorted().take(n)
        require(ids.size == n) { "в файле учёток только ${ids.size} клиентов из $n" }
        val broker = args.broker()
        val trust = loadTrust(broker)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clients = ConcurrentHashMap<String, Mqtt5AsyncClient>()
        val rampEndNs = AtomicLong(0)
        val startNs = System.nanoTime()
        out.writeText("t_sec,phase,connected,connect_failed,connection_lost,pub_ok,pub_fail,msgs_per_sec,heap_used_mb,cpu_pct_of_one_core,threads\n")
        println("S1: clients=$n ramp=$ramp/s interval=${intervalSec}s hold=${holdSec}s -> ${out.path}")

        scope.launch {
            ids.chunked(ramp).forEach { chunk ->
                chunk.forEach { id ->
                    launch { clientLoop(id, creds.getValue(id), broker, trust, intervalSec, clients) }
                }
                delay(1000)
            }
            rampEndNs.set(System.nanoTime())
        }

        val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val mem = ManagementFactory.getMemoryMXBean()
        val threads = ManagementFactory.getThreadMXBean()
        var lastOk = 0L
        var lastCpu = os.processCpuTime
        var lastNs = System.nanoTime()
        var peakHeap = 0.0
        val holdConnected = mutableListOf<Int>()
        val holdRates = mutableListOf<Double>()
        val cpuHold = mutableListOf<Double>()
        while (true) {
            Thread.sleep(SAMPLE_SEC * 1000L)
            val now = System.nanoTime()
            val cpuNow = os.processCpuTime
            val ok = pubOk.get()
            val dt = (now - lastNs) / NS_PER_SEC
            val rate = (ok - lastOk) / dt
            val cpu = (cpuNow - lastCpu) / (now - lastNs).toDouble() * 100
            val heap = mem.heapMemoryUsage.used / MB
            peakHeap = maxOf(peakHeap, heap)
            val rampEnd = rampEndNs.get()
            val phase = if (rampEnd == 0L) "ramp" else "hold"
            out.appendText(
                "%.0f,%s,%d,%d,%d,%d,%d,%.2f,%.1f,%.1f,%d\n".format(
                    java.util.Locale.ROOT, (now - startNs) / NS_PER_SEC, phase, connected.get(), connectFailed.get(),
                    connectionLost.get(), ok, pubFail.get(), rate, heap, cpu, threads.threadCount,
                ),
            )
            if (rampEnd != 0L) {
                holdConnected += connected.get()
                holdRates += rate
                cpuHold += cpu
            }
            lastOk = ok
            lastCpu = cpuNow
            lastNs = now
            if (rampEnd != 0L && (now - rampEnd) / NS_PER_SEC >= holdSec) break
        }
        stopping = true
        scope.cancel()
        val disconnects = clients.values.map { it.disconnect() }
        runCatching { java.util.concurrent.CompletableFuture.allOf(*disconnects.toTypedArray()).get(SHUTDOWN_WAIT_SEC, TimeUnit.SECONDS) }

        println(
            "S1 итог: подключено в конце=%d (мин за hold=%d) не удалось подключить=%d потеряно соединений=%d pub ok=%d fail=%d".format(
                holdConnected.lastOrNull() ?: 0, holdConnected.minOrNull() ?: 0, connectFailed.get(), connectionLost.get(), pubOk.get(), pubFail.get(),
            ),
        )
        println(
            "S1 итог: heap пик=%.0f МБ; msgs/s за hold: среднее=%.1f мин=%.1f макс=%.1f; CPU за hold: среднее=%.1f%% одного ядра".format(
                peakHeap, holdRates.average(), holdRates.minOrNull() ?: 0.0, holdRates.maxOrNull() ?: 0.0, cpuHold.average(),
            ),
        )
        println("S1 ошибки по типам: ${failures.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }.ifEmpty { "нет" }}")
    }

    private suspend fun clientLoop(
        id: String,
        password: String,
        broker: Broker,
        trust: javax.net.ssl.TrustManagerFactory,
        intervalSec: Int,
        registry: ConcurrentHashMap<String, Mqtt5AsyncClient>,
    ) {
        val client = clientBuilder(broker, id, id, password, trust)
            .addDisconnectedListener { ctx ->
                if (!stopping && ctx.source != MqttDisconnectSource.USER) {
                    connectionLost.incrementAndGet()
                    connected.decrementAndGet()
                    note("lost", ctx.cause)
                }
            }
            .buildAsync()
        registry[id] = client
        try {
            client.connectWith().keepAlive(KEEPALIVE_SEC).cleanStart(true).send().await()
            connected.incrementAndGet()
        } catch (e: Exception) {
            connectFailed.incrementAndGet()
            note("connect", e.cause ?: e)
            return
        }
        val topic = Topics.telemetry(DeviceId.parse(id)!!)
        val period = intervalSec * 1000L
        delay(Random.nextLong(period))
        var seq = 0L
        while (currentCoroutineContext().isActive && client.state == MqttClientState.CONNECTED) {
            val payload = RfJson.encodeToString(telemetry(seq++)).toByteArray()
            try {
                client.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE).payload(payload).send().await()
                pubOk.incrementAndGet()
            } catch (e: Exception) {
                pubFail.incrementAndGet()
                note("publish", e.cause ?: e)
            }
            delay((period * (1 + Random.nextDouble(-JITTER, JITTER))).toLong())
        }
    }

    private fun telemetry(seq: Long) = TelemetryDto(
        ts = System.currentTimeMillis(),
        seq = seq,
        battery = BatteryDto(level = Random.nextInt(20, 100), charging = false, plugged = "none"),
        net = NetDto(type = "wifi", rssi = Random.nextInt(-80, -40), validated = true, ssid = "resto-wifi"),
        storage = StorageDto(freeMb = 8_000L),
        memory = MemoryDto(availMb = 600L),
        uptimeSec = seq * 30,
        foregroundApp = "ru.restofleet.dummy.waiter",
        apps = listOf(AppVersionDto(pkg = "ru.restofleet.dummy.waiter", versionName = "4.13", versionCode = 13)),
    )
}
