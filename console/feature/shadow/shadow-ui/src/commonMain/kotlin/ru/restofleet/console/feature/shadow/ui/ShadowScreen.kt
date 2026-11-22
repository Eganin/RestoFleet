package ru.restofleet.console.feature.shadow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.restofleet.console.core.designsystem.RfFonts
import ru.restofleet.console.core.designsystem.RfSpacing
import ru.restofleet.console.core.designsystem.RfTheme
import ru.restofleet.console.core.ui.ErrorState
import ru.restofleet.console.feature.shadow.ui.viewmodel.ShadowIntent
import ru.restofleet.console.feature.shadow.ui.viewmodel.ShadowMode
import ru.restofleet.console.feature.shadow.ui.viewmodel.ShadowUiState
import ru.restofleet.console.feature.shadow.ui.viewmodel.ShadowViewModel

@Composable
fun ShadowContainer(viewModel: ShadowViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    ShadowScreen(state, viewModel::onIntent, modifier)
}

/** Вкладка «Тень» (FR-CN-06): desired, reported, delta на месте; правка формой или JSON. */
@Composable
fun ShadowScreen(state: ShadowUiState, onIntent: (ShadowIntent) -> Unit, modifier: Modifier = Modifier) {
    val colors = RfTheme.colors
    when {
        state.loading -> Column(modifier.fillMaxSize().padding(RfSpacing.L)) { CircularProgressIndicator() }
        state.errorText != null && state.header.isEmpty() ->
            ErrorState(state.errorText, { onIntent(ShadowIntent.User.Refresh) }, modifier)
        else -> LazyColumn(
            modifier.fillMaxSize().padding(horizontal = RfSpacing.L),
            verticalArrangement = Arrangement.spacedBy(RfSpacing.S),
        ) {
            item { Text(state.header, color = colors.fg, fontFamily = RfFonts.Mono, fontSize = 13.sp) }
            item { Text(state.banner, color = if (state.bannerPending) colors.warn else colors.ok, fontSize = 13.sp) }
            state.deviceErrors?.let { item { Text(it, color = colors.crit, fontSize = 13.sp) } }
            state.noticeText?.let { item { Text(it, color = colors.ok, fontSize = 13.sp) } }
            when (state.mode) {
                ShadowMode.View -> {
                    items(state.rows, key = { it.path }) { r ->
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text(r.path, color = colors.muted, fontFamily = RfFonts.Mono, fontSize = 13.sp)
                            Text(
                                r.text,
                                color = if (r.changed) colors.warn else colors.fg,
                                fontFamily = RfFonts.Mono,
                                fontWeight = if (r.changed) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(RfSpacing.S)) {
                            TextButton(onClick = { onIntent(ShadowIntent.User.StartJson) }, enabled = state.canEdit) { Text("JSON") }
                            Button(onClick = { onIntent(ShadowIntent.User.StartEdit) }, enabled = state.canEdit) { Text("Изменить") }
                        }
                    }
                }
                ShadowMode.Form -> formItems(state, onIntent)
                ShadowMode.Json -> jsonItems(state, onIntent)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.formItems(state: ShadowUiState, onIntent: (ShadowIntent) -> Unit) {
    items(state.fields, key = { it.field }) { f ->
        OutlinedTextField(
            value = f.value,
            onValueChange = { onIntent(ShadowIntent.User.FieldChanged(f.field, it)) },
            label = { Text(f.label) },
            isError = f.error != null,
            supportingText = f.error?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item { AppsEditor(state, onIntent) }
    item { SaveRow(state, onIntent) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.jsonItems(state: ShadowUiState, onIntent: (ShadowIntent) -> Unit) {
    item {
        OutlinedTextField(
            value = state.jsonText,
            onValueChange = { onIntent(ShadowIntent.User.JsonChanged(it)) },
            label = { Text("Патч desired (JSON)") },
            isError = state.jsonError != null,
            supportingText = state.jsonError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item { SaveRow(state, onIntent) }
}

@Composable
private fun AppsEditor(state: ShadowUiState, onIntent: (ShadowIntent) -> Unit) {
    val colors = RfTheme.colors
    var draft by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(RfSpacing.XS)) {
        Text("Отслеживаемые приложения", color = colors.fg, fontWeight = FontWeight.SemiBold)
        state.apps.forEach { app ->
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text(app, color = colors.fg, fontFamily = RfFonts.Mono, fontSize = 13.sp)
                TextButton(onClick = { onIntent(ShadowIntent.User.AppRemoved(app)) }) { Text("Убрать") }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Имя пакета") },
            isError = state.appsError != null,
            supportingText = state.appsError?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = {
            onIntent(ShadowIntent.User.AppAdded(draft))
            draft = ""
        }) { Text("Добавить") }
    }
}

@Composable
private fun SaveRow(state: ShadowUiState, onIntent: (ShadowIntent) -> Unit) {
    val colors = RfTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(RfSpacing.XS)) {
        state.saveErrorText?.let { Text(it, color = colors.crit, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(RfSpacing.S)) {
            Button(onClick = { onIntent(ShadowIntent.User.Save) }, enabled = !state.saving) { Text("Сохранить") }
            TextButton(onClick = { onIntent(ShadowIntent.User.Cancel) }) { Text("Отмена") }
        }
    }
}
