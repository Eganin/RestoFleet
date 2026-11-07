package ru.restofleet.agent.data.di

import android.app.admin.DevicePolicyManager
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.agent.data.deviceadmin.AndroidPackageInstallerSession
import ru.restofleet.agent.data.deviceadmin.ApkDownloader
import ru.restofleet.agent.data.deviceadmin.DpmDeviceAdmin
import ru.restofleet.agent.data.deviceadmin.LockTaskLauncher
import ru.restofleet.agent.data.deviceadmin.PackageInstallerSession
import ru.restofleet.agent.data.deviceadmin.apkHttpClient
import ru.restofleet.agent.domain.port.DeviceAdmin

/** Qualifier компонента администратора (`ComponentName` приёмника): его предоставляет `agent:app`. */
val ADMIN_COMPONENT = named("adminComponent")

/** Qualifier клиента загрузки APK (системный trust store, https→https, жёсткие таймауты). */
val APK_HTTP_CLIENT = named("apkHttpClient")

/**
 * Проводка Koin адаптера Device Owner; логики здесь нет. `agent:app` обязан предоставить `ComponentName` приёмника
 * администратора и `LegacyLockTaskEntry` (класс `LockTaskActivity` живёт в app).
 */
val deviceAdminModule: Module = module {
    single(APK_HTTP_CLIENT) { apkHttpClient() }
    single { ApkDownloader(get(APK_HTTP_CLIENT), androidContext().cacheDir) }
    single<PackageInstallerSession> { AndroidPackageInstallerSession(androidContext()) }
    single { LockTaskLauncher(androidContext(), get()) }
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
            ioDispatcher = Dispatchers.IO,
        )
    }
}
