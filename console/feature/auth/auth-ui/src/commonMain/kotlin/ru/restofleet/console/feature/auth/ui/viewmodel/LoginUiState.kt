package ru.restofleet.console.feature.auth.ui.viewmodel

import androidx.compose.runtime.Immutable

@Immutable
data class LoginUiState(
    val server: String,
    val login: String,
    val password: String,
    val loading: Boolean,
    val submitEnabled: Boolean,
    val serverError: String?,
    val loginError: String?,
    val passwordError: String?,
    val formError: String?,
)
