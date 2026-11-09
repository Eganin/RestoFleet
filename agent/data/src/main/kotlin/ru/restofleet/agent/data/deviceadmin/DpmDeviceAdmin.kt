package ru.restofleet.agent.data.deviceadmin

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import ru.restofleet.agent.data.platform.ApiLevel
import ru.restofleet.agent.domain.port.DeviceAdmin
import ru.restofleet.agent.domain.port.WatchedApps
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.map
import java.io.File

/**
 * Адаптер Device Owner поверх `DevicePolicyManager` (D1–D6). Каждая операция сначала проверяет роль и без неё не
 * обращается ни к сети, ни к DPM, ни к установщику. Вход не считается доверенным: хотя `Capabilities.check` уже
 * проверил формат, SHA-256 и схема URL проверяются здесь повторно. Ставится только пакет из [watched] (FR-AG-68), APK
 * другого пакета отклоняет сама система (`setAppPackageName`).
 */
@Suppress("LongParameterList") // зависимости адаптера: контекст, DPM, администратор, три шва и среда
class DpmDeviceAdmin(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
    private val role: DeviceOwnerRole,
    private val downloader: ApkDownloader,
    private val installer: PackageInstallerSession,
    private val lockTask: LockTaskLauncher,
    private val watched: WatchedApps,
    private val versions: InstalledVersions,
    private val apiLevel: ApiLevel = ApiLevel.Device,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val rebootScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val rebootDelayMs: Long = REBOOT_DELAY_MS,
) : DeviceAdmin {
    /** Одновременно идёт не более одной установки: вторая сразу получает `install_in_progress` (решение 99). */
    private val installLock = Mutex()
    private val self: String get() = context.packageName

    override suspend fun isDeviceOwner(): Boolean = role.isDeviceOwner()

    /**
     * Планирует `dpm.reboot` через [rebootDelayMs] на собственном scope приложения и сразу возвращает `Success`
     * (двухфазный контракт порта, решение 142): результат команды успевает попасть в outbox до перезагрузки. Без роли
     * Device Owner планировать нечего — сразу `Failure`. Сбой самого `dpm.reboot` после задержки (звонок в процессе,
     * потеря роли) вызывающему уже не сообщить: он пишется в лог.
     */
    override suspend fun reboot(): Outcome<Unit, DomainError> {
        if (!isDeviceOwner()) return Outcome.Failure(DeviceAdminError.NotDeviceOwner)
        rebootScope.launch {
            delay(rebootDelayMs)
            rebootNow()
        }
        return Outcome.Success(Unit)
    }

    @Suppress("SwallowedException") // причина — класс исключения: пишется в лог
    private fun rebootNow() {
        try {
            dpm.reboot(admin)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "scheduled reboot failed: ${e::class.simpleName}")
        } catch (e: SecurityException) {
            Log.w(TAG, "scheduled reboot failed: ${e::class.simpleName}")
        }
    }

    override suspend fun installApk(
        pkg: String,
        url: String,
        sha256: String,
    ): Outcome<Map<String, String>, DomainError> = when {
        !isDeviceOwner() -> Outcome.Failure(DeviceAdminError.NotDeviceOwner)
        !Sha256Verifier.isValid(sha256) -> Outcome.Failure(DeviceAdminError.Unsupported("invalid_sha256"))
        pkg !in watched.packages() -> Outcome.Failure(DeviceAdminError.Unsupported("package_not_watched"))
        !installLock.tryLock() -> Outcome.Failure(DeviceAdminError.Unsupported("install_in_progress"))
        else -> try {
            // Под замком установка одна, поэтому в apk/ остались только файлы прошлого, убитого процесса
            downloader.clearStale()
            downloadVerifyInstall(pkg, url, sha256).map { installedResult(pkg) }
        } finally {
            installLock.unlock()
        }
    }

    /** `package` всегда; `versionCode`/`versionName` — если версию установленного пакета удалось прочитать. */
    private fun installedResult(pkg: String): Map<String, String> = buildMap {
        put("package", pkg)
        versions.version(pkg)?.let { version ->
            put("versionCode", version.versionCode.toString())
            version.versionName?.let { put("versionName", it) }
        }
    }

    /** Строгий порядок: скачать, сверить SHA-256, только затем сессия установщика; файл удаляется на всех путях. */
    private suspend fun downloadVerifyInstall(
        pkg: String,
        url: String,
        sha256: String,
    ): Outcome<Unit, DeviceAdminError> =
        when (val downloaded = downloader.download(url)) {
            is Outcome.Failure -> downloaded
            is Outcome.Success -> verifyAndInstall(pkg, downloaded.value, sha256)
        }

    private suspend fun verifyAndInstall(pkg: String, file: File, sha256: String): Outcome<Unit, DeviceAdminError> =
        try {
            if (withContext(ioDispatcher) { Sha256Verifier.matches(file, sha256) }) {
                installer.install(file, pkg)
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

    companion object {
        /** Задержка между ответом на `reboot` и самой перезагрузкой: за это время итог попадает в outbox. */
        const val REBOOT_DELAY_MS = 3_000L
        private const val TAG = "DpmDeviceAdmin"
        private const val NOTIFICATIONS_API = 33
    }
}
