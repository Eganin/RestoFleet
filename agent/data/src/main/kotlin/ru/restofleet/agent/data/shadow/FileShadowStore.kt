package ru.restofleet.agent.data.shadow

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.model.Thresholds
import ru.restofleet.agent.domain.port.ShadowStore
import ru.restofleet.shared.protocol.RfJson
import java.io.File
import java.io.IOException

/**
 * Последняя применённая конфигурация в файле (каталог `noBackupFilesDir`, в облачный бэкап не попадает). Запись
 * атомарна: во временный файл, затем переименование, поэтому оборванная запись не оставляет полуфайла. Нечитаемый
 * файл считается отсутствующим (агент просто применит следующий `desired`). Блокирующий ввод-вывод: вызывающий
 * отвечает за диспетчер (в проде — `AGENT_DISPATCHER`).
 */
class FileShadowStore(private val file: File) : ShadowStore {
    override suspend fun appliedVersion(): Long? = load()?.version

    private val mutex = Mutex()

    override suspend fun save(shadow: DesiredShadow): Unit = mutex.withLock {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(RfJson.encodeToString(StoredShadow.serializer(), StoredShadow.from(shadow)))
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("shadow was not persisted")
        }
    }

    /** Сохранённая тень или `null`; нужна циклам, чтобы применить конфигурацию при старте (3.6b). */
    @Suppress("SwallowedException") // нечитаемый файл равен отсутствующему
    suspend fun load(): DesiredShadow? = mutex.withLock {
        try {
            file.takeIf { it.isFile }
                ?.let { RfJson.decodeFromString(StoredShadow.serializer(), it.readText()).toDomain() }
        } catch (e: SerializationException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    @Serializable
    private data class StoredShadow(
        val version: Long,
        val telemetryIntervalSec: Int,
        val watchedApps: List<String>,
        val probes: List<StoredProbe>,
        val batteryLowPct: Int,
        val tempHighC: Double,
        val fgAbsentMin: Int,
    ) {
        fun toDomain() = DesiredShadow(
            version = version,
            telemetryIntervalSec = telemetryIntervalSec,
            watchedApps = watchedApps,
            probes = probes.map { it.toDomain() },
            thresholds = Thresholds(batteryLowPct, tempHighC, fgAbsentMin),
        )

        companion object {
            fun from(s: DesiredShadow) = StoredShadow(
                version = s.version,
                telemetryIntervalSec = s.telemetryIntervalSec,
                watchedApps = s.watchedApps,
                probes = s.probes.map { StoredProbe.from(it) },
                batteryLowPct = s.thresholds.batteryLowPct,
                tempHighC = s.thresholds.tempHighC,
                fgAbsentMin = s.thresholds.fgAbsentMin,
            )
        }
    }

    @Serializable
    private data class StoredProbe(
        val id: String,
        val url: String,
        val timeoutMs: Long,
        val intervalSec: Int,
        val failThreshold: Int,
        val reference: Boolean,
    ) {
        fun toDomain() = ProbeTarget(id, url, timeoutMs, intervalSec, failThreshold, reference)

        companion object {
            fun from(p: ProbeTarget) =
                StoredProbe(p.id, p.url, p.timeoutMs, p.intervalSec, p.failThreshold, p.reference)
        }
    }
}
