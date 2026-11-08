package ru.restofleet.agent.presentation.deviceowner.view.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerIntent
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerIntent.User
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerUiState

/** «APK для теста установки» (D3): адрес, SHA-256 и кнопка «Подтянуть хэш» (читает `<адрес>.sha256`). */
@Composable
internal fun ApkSection(
    uiState: DeviceOwnerUiState,
    onIntent: (DeviceOwnerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.do_apk_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = uiState.apkUrl,
            onValueChange = { onIntent(User.EditApkUrl(it)) },
            label = { Text(stringResource(R.string.do_apk_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = uiState.apkSha256,
            onValueChange = { onIntent(User.EditApkSha256(it)) },
            label = { Text(stringResource(R.string.do_apk_sha256)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { onIntent(User.FetchChecksum) }, enabled = uiState.canFetchHash) {
            if (uiState.isFetchingHash) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Text(stringResource(R.string.do_fetch_hash), Modifier.padding(start = 4.dp))
        }
    }
}

/** Цель D4 (lock task) и D5 (запуск из фона): пакет и необязательный адрес. */
@Composable
internal fun TargetSection(
    uiState: DeviceOwnerUiState,
    onIntent: (DeviceOwnerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.do_target_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = uiState.targetPackage,
            onValueChange = { onIntent(User.EditTargetPackage(it)) },
            label = { Text(stringResource(R.string.do_target_package)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = uiState.targetUrl,
            onValueChange = { onIntent(User.EditTargetUrl(it)) },
            label = { Text(stringResource(R.string.do_target_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
