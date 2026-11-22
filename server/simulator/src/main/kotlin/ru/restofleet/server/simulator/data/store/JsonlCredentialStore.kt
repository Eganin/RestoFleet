package ru.restofleet.server.simulator.data.store

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import ru.restofleet.server.simulator.domain.model.DeviceCredentials
import ru.restofleet.server.simulator.domain.model.DeviceRole
import ru.restofleet.server.simulator.domain.port.CredentialStore
import ru.restofleet.shared.protocol.RfJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/** Строка кэша учёток. */
@Serializable
private data class CredentialLine(
    val deviceId: String,
    val password: String,
    val restaurantId: String,
    val role: String,
)

/**
 * Кэш учёток виртуальных устройств — JSON Lines, по строке на устройство (дописывание дешевле перезаписи 5 000
 * записей). Файл создаётся с правами 600, каталог — 700; место — `deploy/out/sim/` (вне git, как прочие секреты
 * стенда). Битые строки (обрыв записи) пропускаются.
 */
class JsonlCredentialStore(private val file: Path) : CredentialStore {
    override fun load(): List<DeviceCredentials> {
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file).mapNotNull { line -> line.takeIf { it.isNotBlank() }?.let(::decode) }
    }

    @Synchronized
    override fun append(credentials: DeviceCredentials) {
        ensureFile()
        val line = with(credentials) { CredentialLine(deviceId, password, restaurantId, role.code) }
        Files.writeString(
            file, RfJson.encodeToString(CredentialLine.serializer(), line) + "\n", StandardOpenOption.APPEND,
        )
    }

    private fun decode(line: String): DeviceCredentials? = try {
        val dto = RfJson.decodeFromString(CredentialLine.serializer(), line)
        DeviceRole.fromCode(dto.role)?.let { DeviceCredentials(dto.deviceId, dto.password, dto.restaurantId, it) }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun ensureFile() {
        if (Files.exists(file)) return
        val parent = file.toAbsolutePath().parent
        if (!Files.exists(parent)) {
            Files.createDirectories(parent)
            setPermissions(parent, DIR_MODE)
        }
        Files.createFile(file)
        setPermissions(file, FILE_MODE)
    }

    private fun setPermissions(path: Path, mode: String) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
        } catch (_: UnsupportedOperationException) {
            // не POSIX-файловая система: права задаёт окружение
        }
    }

    private companion object {
        const val FILE_MODE = "rw-------"
        const val DIR_MODE = "rwx------"
    }
}
