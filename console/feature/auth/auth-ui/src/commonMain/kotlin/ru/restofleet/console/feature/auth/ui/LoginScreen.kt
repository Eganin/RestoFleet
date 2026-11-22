package ru.restofleet.console.feature.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.restofleet.console.core.designsystem.RfSpacing
import ru.restofleet.console.core.designsystem.RfTheme
import ru.restofleet.console.feature.auth.ui.viewmodel.LoginIntent
import ru.restofleet.console.feature.auth.ui.viewmodel.LoginUiState

/** Экран «Сервер · Логин · Пароль» (FR-CN-01). Без ViewModel: состояние и намерения снаружи. */
@Composable
fun LoginScreen(state: LoginUiState, onIntent: (LoginIntent) -> Unit, modifier: Modifier = Modifier) {
    val colors = RfTheme.colors
    Column(
        modifier.fillMaxSize().padding(RfSpacing.XL),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("RestoFleet", color = colors.fg, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Консоль службы поддержки", color = colors.muted)
        Column(
            Modifier.padding(top = RfSpacing.XL),
            verticalArrangement = Arrangement.spacedBy(RfSpacing.M),
        ) {
            Field("Сервер", state.server, state.serverError, KeyboardType.Uri) {
                onIntent(LoginIntent.User.ServerChanged(it))
            }
            Field("Логин", state.login, state.loginError, KeyboardType.Text) {
                onIntent(LoginIntent.User.LoginChanged(it))
            }
            Field("Пароль", state.password, state.passwordError, KeyboardType.Password, secret = true) {
                onIntent(LoginIntent.User.PasswordChanged(it))
            }
            state.formError?.let { Text(it, color = colors.crit) }
            Button(
                onClick = { onIntent(LoginIntent.User.Submit) },
                enabled = state.submitEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.loading) CircularProgressIndicator(strokeWidth = 2.dp) else Text("Войти")
            }
            Text("Соединение только по https", color = colors.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    error: String?,
    keyboard: KeyboardType,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
