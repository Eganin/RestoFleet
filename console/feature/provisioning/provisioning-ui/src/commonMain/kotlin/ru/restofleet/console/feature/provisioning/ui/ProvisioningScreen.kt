package ru.restofleet.console.feature.provisioning.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.restofleet.console.core.designsystem.RfFonts
import ru.restofleet.console.core.designsystem.RfSpacing
import ru.restofleet.console.core.designsystem.RfTheme
import ru.restofleet.console.core.ui.ObserveEffects
import ru.restofleet.console.feature.provisioning.domain.DeviceRole
import ru.restofleet.console.feature.provisioning.ui.viewmodel.ProvisioningEffect
import ru.restofleet.console.feature.provisioning.ui.viewmodel.ProvisioningIntent
import ru.restofleet.console.feature.provisioning.ui.viewmodel.ProvisioningUiState
import ru.restofleet.console.feature.provisioning.ui.viewmodel.ProvisioningViewModel

@Suppress("DEPRECATION")
@Composable
fun ProvisioningContainer(viewModel: ProvisioningViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val clipboard = LocalClipboardManager.current
    ObserveEffects(viewModel.effect) {
        when (it) {
            is ProvisioningEffect.CopyToClipboard -> clipboard.setText(AnnotatedString(it.text))
            is ProvisioningEffect.ShowMessage -> Unit
        }
    }
    ProvisioningScreen(state, viewModel::onIntent, modifier)
}

/** «Новое устройство» (FR-CN-09): ресторан, роль, имя → одноразовый код с обратным отсчётом и подсказкой для эмулятора. */
@Composable
fun ProvisioningScreen(state: ProvisioningUiState, onIntent: (ProvisioningIntent) -> Unit, modifier: Modifier = Modifier) {
    val colors = RfTheme.colors
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(RfSpacing.L),
        verticalArrangement = Arrangement.spacedBy(RfSpacing.S),
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text("Новое устройство", color = colors.fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { onIntent(ProvisioningIntent.User.Back) }) { Text("Закрыть") }
        }
        if (state.code == null) {
            Text("Ресторан", color = colors.muted, fontSize = 13.sp)
            state.restaurants.forEach { r ->
                FilterChip(
                    selected = r.selected,
                    onClick = { onIntent(ProvisioningIntent.User.SelectRestaurant(r.value)) },
                    label = { Text(r.label) },
                )
            }
            Text("Роль", color = colors.muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(RfSpacing.S)) {
                state.roles.forEach { r ->
                    FilterChip(
                        selected = r.selected,
                        onClick = { onIntent(ProvisioningIntent.User.SelectRole(DeviceRole.entries.first { it.wire == r.value })) },
                        label = { Text(r.label) },
                    )
                }
            }
            OutlinedTextField(
                value = state.name,
                onValueChange = { onIntent(ProvisioningIntent.User.NameChanged(it)) },
                label = { Text("Имя устройства") },
                isError = state.nameError != null,
                supportingText = state.nameError?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            state.errorText?.let { Text(it, color = colors.crit) }
            state.expiredText?.let { Text(it, color = colors.warn) }
            Button(onClick = { onIntent(ProvisioningIntent.User.Issue) }, enabled = state.canIssue, modifier = Modifier.fillMaxWidth()) {
                Text("Выпустить код")
            }
        } else {
            Text("Код регистрации", color = colors.muted, fontSize = 13.sp)
            Text(state.code, color = colors.fg, fontFamily = RfFonts.Mono, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            state.countdown?.let { Text(it, color = colors.muted, fontSize = 13.sp) }
            Text(
                "Как зарегистрировать: RestoFleet Agent → «Регистрация» → ввести код. На эмуляторе:",
                color = colors.muted, fontSize = 13.sp,
            )
            state.adbHint?.let { Text(it, color = colors.fg, fontFamily = RfFonts.Mono, fontSize = 12.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(RfSpacing.S)) {
                Button(onClick = { onIntent(ProvisioningIntent.User.Copy) }) { Text("Скопировать код") }
                TextButton(onClick = { onIntent(ProvisioningIntent.User.NewCode) }) { Text("Новый код") }
            }
        }
    }
}
