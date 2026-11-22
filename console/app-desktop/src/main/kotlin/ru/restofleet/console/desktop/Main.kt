package ru.restofleet.console.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.arkivanov.decompose.DecomposeSettings
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.lifecycle.LifecycleController
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import org.koin.dsl.koinApplication
import ru.restofleet.console.core.common.AppDispatchers
import ru.restofleet.console.core.storage.InMemoryTokenStore

import ru.restofleet.console.root.RootContent
import ru.restofleet.console.root.di.PlatformDependencies
import ru.restofleet.console.root.di.consoleModules
import ru.restofleet.console.root.di.createRootComponent
import java.io.File
import javax.swing.SwingUtilities


private const val WINDOW_WIDTH = 420
private const val WINDOW_HEIGHT = 860
private const val STAND_CA_PROPERTY = "rf.standCa"
private const val SCREENSHOT_FLAG = "--screenshot"

/**
 * Заглушка Desktop (реш. 422): то же дерево, что на Android, в окне 420×860. Токены только в памяти.
 * CA стенда — `-Drf.standCa=<pem>`. `--screenshot <png>` рисует корень вне экрана и завершается.
 */
fun main(args: Array<String>) {
    val screenshot = args.indexOf(SCREENSHOT_FLAG).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val platform = desktopPlatform(System.getProperty(STAND_CA_PROPERTY)?.let(::File))
    val koin = koinApplication { modules(consoleModules(platform)) }.koin
    val lifecycle = LifecycleRegistry()
    if (screenshot != null) {
        DecomposeSettings.update { it.copy(mainThreadCheckEnabled = false) }
        val root = koin.createRootComponent(DefaultComponentContext(lifecycle), Dispatchers.Unconfined)
        lifecycle.resume()
        renderScreenshot(root, File(screenshot), WINDOW_WIDTH, WINDOW_HEIGHT)
        return
    }
    val root = onUiThread { koin.createRootComponent(DefaultComponentContext(lifecycle), Dispatchers.Main) }
    application {
        val state = WindowState(size = DpSize(WINDOW_WIDTH.dp, WINDOW_HEIGHT.dp))
        LifecycleController(lifecycle, state)
        Window(onCloseRequest = ::exitApplication, state = state, title = "RestoFleet") {
            RootContent(root)
        }
    }
}

internal fun desktopPlatform(standCa: File?): PlatformDependencies {
    val trust = StandTrust.trustManager(standCa)
    return PlatformDependencies(
        engineFactory = {
            OkHttp.create {
                config { sslSocketFactory(StandTrust.sslContext(trust).socketFactory, trust) }
            }
        },
        tokenStore = InMemoryTokenStore(),
        dispatchers = AppDispatchers(Dispatchers.Main, Dispatchers.IO, Dispatchers.Default),
    )
}

private fun <T> onUiThread(block: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}

