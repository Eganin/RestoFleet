package ru.restofleet.agent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ru.restofleet.agent.data.lifecycle.ServiceStarter

/** Минимальный экран: заголовок, версия, запрос уведомлений и ручной запуск. Capability Lab — задача 1.9. */
class MainActivity : ComponentActivity(), KoinComponent {
    private val starter: ServiceStarter by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        starter.ensureRunning(REASON_ACTIVITY)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    MainScreen(
                        versionName = BuildConfig.VERSION_NAME,
                        notificationsGranted = ::notificationsGranted,
                        onStart = { starter.ensureRunning(REASON_MANUAL) },
                    )
                }
            }
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
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.main_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.main_version, versionName))
        if (!granted) Text(stringResource(R.string.main_notifications_denied))
        Button(onClick = onStart) { Text(stringResource(R.string.main_start_agent)) }
    }
}
