package ru.restofleet.console.app

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.logging.Logger
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import ru.restofleet.console.core.common.AppDispatchers
import ru.restofleet.console.core.storage.DataStoreKeyValueStore
import ru.restofleet.console.core.storage.EncryptedTokenStore
import ru.restofleet.console.core.storage.KeystoreSecretCipher
import ru.restofleet.console.root.di.PlatformDependencies
import ru.restofleet.console.root.di.consoleModules
import java.io.File

/** Composition root Android: движок OkHttp, Keystore-хранилище токенов, Koin. */
class ConsoleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val debug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val dataStore = PreferenceDataStoreFactory.create { File(filesDir, "rf_console.preferences_pb") }
        val platform = PlatformDependencies(
            engineFactory = { OkHttp.create() },
            tokenStore = EncryptedTokenStore(DataStoreKeyValueStore(dataStore), KeystoreSecretCipher()),
            dispatchers = AppDispatchers(Dispatchers.Main, Dispatchers.IO, Dispatchers.Default),
            debugLogger = if (debug) AndroidLogger else null,
        )
        startKoin {
            androidContext(this@ConsoleApp)
            modules(consoleModules(platform))
        }
    }

    /** Журнал Ktor только в debug; заголовок Authorization вырезает сам клиент. */
    private object AndroidLogger : Logger {
        override fun log(message: String) {
            android.util.Log.d("RfConsoleHttp", message)
        }
    }
}
