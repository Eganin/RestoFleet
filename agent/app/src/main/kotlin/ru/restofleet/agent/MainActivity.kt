package ru.restofleet.agent

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.essenty.instancekeeper.instanceKeeper
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import ru.restofleet.agent.data.lifecycle.ServiceStarter
import ru.restofleet.agent.presentation.lab.LabContainer
import ru.restofleet.agent.presentation.lab.viewmodel.LabViewModel
import ru.restofleet.agent.presentation.lab.viewmodel.model.SettingsTarget

/** Верхняя полоса (версия, запрос уведомлений, ручной запуск агента) и Capability Lab. */
class MainActivity : ComponentActivity(), KoinComponent {
    private val starter: ServiceStarter by inject()
    private val settingsIntents: SettingsIntents by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        starter.ensureRunning(REASON_ACTIVITY)
        // ViewModel живёт в InstanceKeeper (переживает поворот экрана); onDestroy вызывается при окончательном закрытии
        val lab = instanceKeeper().getOrCreate { get<LabViewModel>() }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
                        MainScreen(
                            versionName = BuildConfig.VERSION_NAME,
                            notificationsGranted = ::notificationsGranted,
                            onStart = { starter.ensureRunning(REASON_MANUAL) },
                        )
                        LabContainer(lab, onOpenSettings = ::openSettings)
                    }
                }
            }
        }
    }

    private fun openSettings(target: SettingsTarget) {
        try {
            startActivity(settingsIntents.forTarget(target))
        } catch (_: ActivityNotFoundException) {
            // на устройстве нет такого экрана настроек: кнопка просто ничего не открывает
        }
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val REASON_ACTIVITY = "activity"
        const val REASON_MANUAL = "manual"
    }
}

@Composable
private fun MainScreen(versionName: String, notificationsGranted: () -> Boolean, onStart: () -> Unit) {
    var granted by remember { mutableStateOf(notificationsGranted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.main_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.main_version, versionName), style = MaterialTheme.typography.bodySmall)
            if (!granted) {
                Text(stringResource(R.string.main_notifications_denied), style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(onClick = onStart) { Text(stringResource(R.string.main_start_agent)) }
    }
}
