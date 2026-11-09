package ru.restofleet.agent.data.di

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.agent.data.deviceadmin.AndroidPackageInstallerSession
import ru.restofleet.agent.data.deviceadmin.ApkDownloader
import ru.restofleet.agent.data.deviceadmin.DeviceOwnerCheck
import ru.restofleet.agent.data.deviceadmin.DpmDeviceAdmin
import ru.restofleet.agent.data.collectors.AppVersionsCollector
import ru.restofleet.agent.data.collectors.CapabilityChecksFactory
import ru.restofleet.agent.data.deviceadmin.AndroidLockTaskState
import ru.restofleet.agent.data.deviceadmin.HttpApkChecksumSource
import ru.restofleet.agent.data.deviceadmin.LockTaskState
import ru.restofleet.agent.data.deviceadmin.InstalledVersions
import ru.restofleet.agent.data.deviceadmin.StaticWatchedApps
import ru.restofleet.agent.data.deviceadmin.LockTaskLauncher
import ru.restofleet.agent.data.deviceadmin.PackageInstallerSession
import ru.restofleet.agent.data.deviceadmin.apkHttpClient
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.port.ApkChecksumSource
import ru.restofleet.agent.domain.port.DeviceAdmin
import ru.restofleet.agent.domain.port.WatchedApps

/** Qualifier компонента администратора (`ComponentName` приёмника): его предоставляет `agent:app`. */
val ADMIN_COMPONENT = named("adminComponent")

/** Qualifier списка проверок Device Owner, которые считает `RunCapabilityChecks` (D1). */
val DEVICE_OWNER_CHECKS = named("deviceOwnerChecks")

/** Qualifier клиента загрузки APK (системный trust store, https→https, жёсткие таймауты). */
val APK_HTTP_CLIENT = named("apkHttpClient")

/**
 * Проводка Koin адаптера Device Owner; логики здесь нет. `agent:app` обязан предоставить `ComponentName` приёмника
 * администратора и `LegacyLockTaskEntry` (класс `LockTaskActivity` живёт в app).
 */
val deviceAdminModule: Module = module {
    single(APK_HTTP_CLIENT) { apkHttpClient() }
    single<ApkChecksumSource> { HttpApkChecksumSource(get(APK_HTTP_CLIENT)) }
    // D1: роль читается через CapabilitiesSource (commandModule); остальные D-проверки — ручные команды
    single<List<CapabilityCheck>>(DEVICE_OWNER_CHECKS) { listOf(DeviceOwnerCheck(get(), get())) }
    single { ApkDownloader(get(APK_HTTP_CLIENT), androidContext().cacheDir) }
    single<LockTaskState> {
        val context = androidContext()
        val activityManager = context.getSystemService(ActivityManager::class.java)
        AndroidLockTaskState(
            dpm = context.getSystemService(DevicePolicyManager::class.java),
            admin = get(ADMIN_COMPONENT),
            role = get(),
            lockTaskModeState = { activityManager.lockTaskModeState },
        )
    }
    single<PackageInstallerSession> { AndroidPackageInstallerSession(androidContext()) }
    single { LockTaskLauncher(androidContext(), get()) }
    // Отслеживаемые приложения: пока статический список M1; 3.6 заменит источником shadow.watchedApps
    single<WatchedApps> { StaticWatchedApps(CapabilityChecksFactory.DEFAULT_WATCHED) }
    single<DeviceAdmin> {
        val context = androidContext()
        DpmDeviceAdmin(
            context = context,
            dpm = context.getSystemService(DevicePolicyManager::class.java),
            admin = get(ADMIN_COMPONENT),
            role = get(),
            downloader = get(),
            installer = get(),
            lockTask = get(),
            watched = get(),
            versions = get<AppVersionsCollector>().let { collector -> InstalledVersions { collector.version(it) } },
            ioDispatcher = Dispatchers.IO,
        )
    }
}
