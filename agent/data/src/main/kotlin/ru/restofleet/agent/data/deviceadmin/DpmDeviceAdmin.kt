package ru.restofleet.agent.data.deviceadmin

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import ru.restofleet.agent.data.platform.ApiLevel
import ru.restofleet.agent.domain.port.DeviceAdmin
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome
import java.io.File

/**
 * Адаптер Device Owner поверх `DevicePolicyManager` (D1–D6). Каждая операция сначала проверяет роль и без неё не
 * обращается ни к сети, ни к DPM, ни к установщику. Вход не считается доверенным: хотя `Capabilities.check` уже
 * проверил формат, SHA-256 и схема URL проверяются здесь повторно.
 */
@Suppress("LongParameterList") // зависимости адаптера: контекст, DPM, администратор, три шва и среда
class DpmDeviceAdmin(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
    private val downloader: ApkDownloader,
    private val installer: PackageInstallerSession,
    private val lockTask: LockTaskLauncher,
    private val apiLevel: ApiLevel = ApiLevel.Device,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DeviceAdmin {
    /** Одновременно идёт не более одной установки: вторая сразу получает `install_in_progress` (решение 99). */
    private val installLock = Mutex()
    private val self: String get() = context.packageName

    override suspend fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(self)

    @Suppress("SwallowedException") // тип исключения и есть причина отказа
    override suspend fun reboot(): Outcome<Unit, DomainError> {
        if (!isDeviceOwner()) return Outcome.Failure(DeviceAdminError.NotDeviceOwner)
        return try {
            dpm.reboot(admin)
            Outcome.Success(Unit)
        } catch (e: IllegalStateException) {
            Outcome.Failure(DeviceAdminError.Unsupported("call_in_progress"))
        } catch (e: SecurityException) {
            Outcome.Failure(DeviceAdminError.NotDeviceOwner)
        }
    }

    override suspend fun installApk(url: String, sha256: String): Outcome<Unit, DomainError> = when {
        !isDeviceOwner() -> Outcome.Failure(DeviceAdminError.NotDeviceOwner)
        !Sha256Verifier.isValid(sha256) -> Outcome.Failure(DeviceAdminError.Unsupported("invalid_sha256"))
        !installLock.tryLock() -> Outcome.Failure(DeviceAdminError.Unsupported("install_in_progress"))
        else -> try {
            downloadVerifyInstall(url, sha256)
        } finally {
            installLock.unlock()
        }
    }

    /** Строгий порядок: скачать, сверить SHA-256, только затем сессия установщика; файл удаляется на всех путях. */
    private suspend fun downloadVerifyInstall(url: String, sha256: String): Outcome<Unit, DeviceAdminError> =
        when (val downloaded = downloader.download(url)) {
            is Outcome.Failure -> downloaded
            is Outcome.Success -> verifyAndInstall(downloaded.value, sha256)
        }

    private suspend fun verifyAndInstall(file: File, sha256: String): Outcome<Unit, DeviceAdminError> =
        try {
            if (withContext(ioDispatcher) { Sha256Verifier.matches(file, sha256) }) {
                installer.install(file)
            } else {
                Outcome.Failure(DeviceAdminError.HashMismatch)
            }
        } finally {
            file.delete()
        }

    override suspend fun startLockTask(pkg: String, url: String?): Outcome<Unit, DomainError> {
        if (!isDeviceOwner()) return Outcome.Failure(DeviceAdminError.NotDeviceOwner)
        return when (val resolved = lockTask.target(pkg, url)) {
            is Outcome.Failure -> resolved
            is Outcome.Success ->
                whitelist(pkg)?.let { Outcome.Failure(it) } ?: lockTask.start(resolved.value, pkg, url)
        }
    }

    /** Список lock task = [своё приложение, pkg]; `null` — готово, иначе причина отказа. */
    @Suppress("SwallowedException") // SecurityException означает потерю роли
    private fun whitelist(pkg: String): DeviceAdminError? =
        try {
            dpm.setLockTaskPackages(admin, arrayOf(self, pkg).distinct().toTypedArray())
            null
        } catch (e: SecurityException) {
            DeviceAdminError.NotDeviceOwner
        }

    override suspend fun grantSelfPermissions(): Map<String, Boolean> {
        val owner = isDeviceOwner()
        val granted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (apiLevel.current() >= NOTIFICATIONS_API) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_PHONE_STATE)
        }.associateWith { owner && grant(it) }
        // Доступ к данным об использовании — appop, через DPM не выдаётся: D6 ожидаемо ⚠️ (решение 99)
        return granted + (Manifest.permission.PACKAGE_USAGE_STATS to false)
    }

    @Suppress("SwallowedException") // отказ системы — это `false` в результате
    private fun grant(permission: String): Boolean =
        try {
            dpm.setPermissionGrantState(admin, self, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        } catch (e: SecurityException) {
            false
        }

    private companion object {
        const val NOTIFICATIONS_API = 33
    }
}
