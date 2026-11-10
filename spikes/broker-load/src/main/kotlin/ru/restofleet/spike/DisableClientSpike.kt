package ru.restofleet.spike

import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource
import kotlinx.serialization.json.put
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val NS_PER_MS = 1_000_000.0
private const val WAIT_SEC = 30L

/** S3: `disableClient` разрывает текущую сессию клиента спайка (не dev-учётки). */
object DisableClientSpike {
    fun run(args: Args) {
        val id = args.str("id")
        require(id.startsWith("spike-")) { "S3 выполняется только на клиенте спайка (spike-*)" }
        val broker = args.broker()
        val password = readCreds(args.str("creds", "deploy/out/spike-creds.csv")).getValue(id)
        val trust = loadTrust(broker)

        val disconnectedAtNs = CompletableFuture<Long>()
        var reason = "?"
        val device = clientBuilder(broker, id, id, password, trust)
            .addDisconnectedListener { ctx ->
                if (ctx.source != MqttDisconnectSource.USER) {
                    reason = "${ctx.source}: ${ctx.cause.javaClass.simpleName}"
                    disconnectedAtNs.complete(System.nanoTime())
                }
            }
            .buildBlocking()
        device.connect()
        println("S3: клиент $id подключён")

        DynSecAdmin(broker).use { admin ->
            val disable = admin.send(listOf(command("disableClient") { put("username", id) }))
            val errs = disable.items.errors()
            val toResponseMs = (disable.receivedAtNs - disable.sentAtNs) / NS_PER_MS
            val dropAt = runCatching { disconnectedAtNs.get(WAIT_SEC, TimeUnit.SECONDS) }.getOrNull()
            if (dropAt != null) {
                println("S3: disableClient ошибок=${errs.size}; ответ DynSec через %.0f мс; разрыв сессии через %.0f мс (%s)".format(
                    toResponseMs, (dropAt - disable.sentAtNs) / NS_PER_MS, reason,
                ))
            } else {
                println("S3: разрыва за ${WAIT_SEC}с не было (ошибки команды=${errs.size})")
            }
            // Контроль и восстановление: отключённый клиент не подключается, после enableClient — подключается
            val rejected = runCatching { clientBuilder(broker, id, id, password, trust).buildBlocking().connect() }.isFailure
            val enable = admin.send(listOf(command("enableClient") { put("username", id) }))
            val back = runCatching { clientBuilder(broker, id, id, password, trust).buildBlocking().also { it.connect() }.disconnect() }.isSuccess
            println("S3: вход отключённого отклонён=$rejected; enableClient ошибок=${enable.items.errors().size}; повторный вход после enable=$back")
        }
        runCatching { device.disconnect() }
    }
}
