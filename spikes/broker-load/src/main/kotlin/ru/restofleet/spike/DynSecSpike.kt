package ru.restofleet.spike

import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val CONTROL = "\$CONTROL/dynamic-security/v1"
private const val RESPONSE_TIMEOUT_SEC = 120L
private const val PASSWORD_BYTES = 24
private const val NS_PER_SEC = 1_000_000_000.0

/** Подключение под admin и обмен пакетами команд DynSec через `$CONTROL`. */
class DynSecAdmin(broker: Broker) : AutoCloseable {
    private val responses = LinkedBlockingQueue<JsonObject>()
    private val client: Mqtt5BlockingClient = clientBuilder(
        broker, "rf-spike-admin-${System.nanoTime()}", "admin", adminPassword(), loadTrust(broker),
    ).build().toBlocking()

    init {
        client.connect()
        client.subscribeWith().topicFilter("$CONTROL/response").qos(MqttQos.AT_LEAST_ONCE).send()
        client.toAsync().publishes(MqttGlobalPublishFilter.ALL) { msg ->
            responses.add(Json.parseToJsonElement(String(msg.payloadAsBytes, Charsets.UTF_8)).jsonObject)
        }
    }

    /** Отправляет пакет команд и ждёт ответ. Возвращает список ответов (по одному на команду) и момент отправки/ответа. */
    fun send(commands: List<JsonObject>): Reply {
        val payload = buildJsonObject { put("commands", JsonArray(commands)) }.toString().toByteArray()
        val sentAt = System.nanoTime()
        client.publishWith().topic(CONTROL).qos(MqttQos.AT_LEAST_ONCE).payload(payload).send()
        val answer = responses.poll(RESPONSE_TIMEOUT_SEC, TimeUnit.SECONDS) ?: error("нет ответа DynSec за ${RESPONSE_TIMEOUT_SEC}с")
        return Reply(answer["responses"]!!.jsonArray.map { it.jsonObject }, sentAt, System.nanoTime())
    }

    override fun close() {
        runCatching { client.disconnect() }
    }

    class Reply(val items: List<JsonObject>, val sentAtNs: Long, val receivedAtNs: Long)
}

fun command(name: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
    buildJsonObject {
        put("command", name)
        build()
    }

/** Ошибки из ответов: текст `error` команды (без паролей — в ответах их нет). */
fun List<JsonObject>.errors(): List<String> =
    mapNotNull { r -> r["error"]?.jsonPrimitive?.content?.let { "${r["command"]?.jsonPrimitive?.content}: $it" } }

object DynSecSpike {
    /** Формат имени: `spike-00001`. */
    fun name(prefix: String, i: Int) = "$prefix%05d".format(i)

    /** S2: создание клиентов пакетами. Учётки пишутся в файл с правами 600 до отправки пакета. */
    fun create(args: Args) {
        val count = args.int("count", 1000)
        val batch = args.int("batch", 100)
        val prefix = args.str("prefix", "spike-")
        val credsFile = File(args.str("creds", "deploy/out/spike-creds.csv"))
        val rnd = SecureRandom()
        Files.deleteIfExists(credsFile.toPath())
        Files.createFile(credsFile.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))

        var errors = 0
        var ok = 0
        DynSecAdmin(args.broker()).use { admin ->
            val started = System.nanoTime()
            for (from in 1..count step batch) {
                val ids = (from..minOf(from + batch - 1, count)).map { name(prefix, it) }
                val passwords = ids.associateWith { Base64.getEncoder().encodeToString(ByteArray(PASSWORD_BYTES).also(rnd::nextBytes)) }
                credsFile.appendText(passwords.entries.joinToString("") { "${it.key},${it.value}\n" })
                val cmds = ids.flatMap { id ->
                    listOf(
                        command("createClient") {
                            put("username", id)
                            put("password", passwords.getValue(id))
                            put("clientid", id)
                        },
                        command("addClientRole") {
                            put("username", id)
                            put("rolename", "device")
                        },
                    )
                }
                val reply = admin.send(cmds)
                val errs = reply.items.errors()
                errors += errs.size
                ok += reply.items.size - errs.size
                errs.distinct().take(3).forEach { System.err.println("  ошибка DynSec: $it") }
                println("пакет с $from: ${cmds.size} команд, ошибок ${errs.size}, ${"%.2f".format((reply.receivedAtNs - reply.sentAtNs) / NS_PER_SEC)} с")
            }
            val total = (System.nanoTime() - started) / NS_PER_SEC
            println("S2 create: клиентов=$count команд ok=$ok ошибок=$errors общее время=${"%.2f".format(total)} с")
        }
    }

    /** Уборка: удаляет всех клиентов с префиксом (только `spike-`, остальных не трогает). */
    fun delete(args: Args) {
        val prefix = args.str("prefix")
        require(prefix.length >= 3) { "префикс слишком короткий: удалятся чужие клиенты" }
        val batch = args.int("batch", 100)
        DynSecAdmin(args.broker()).use { admin ->
            val listed = admin.send(listOf(command("listClients") {
                put("verbose", false)
                put("count", -1)
                put("offset", 0)
            })).items.single()
            val names = listed["data"]!!.jsonObject["clients"]!!.jsonArray.map { it.jsonPrimitive.content }.filter { it.startsWith(prefix) }
            var errors = 0
            names.chunked(batch).forEach { chunk ->
                val reply = admin.send(chunk.map { command("deleteClient") { put("username", it) } })
                errors += reply.items.errors().size
            }
            println("delete: удалено ${names.size - errors} из ${names.size} клиентов с префиксом $prefix, ошибок $errors")
        }
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

internal fun readCreds(path: String): Map<String, String> =
    File(path).readLines().filter { it.isNotBlank() }.associate { line -> line.substringBefore(',') to line.substringAfter(',') }
