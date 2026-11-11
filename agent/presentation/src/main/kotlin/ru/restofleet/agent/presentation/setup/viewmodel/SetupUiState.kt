package ru.restofleet.agent.presentation.setup.viewmodel

import androidx.compose.runtime.Immutable
import ru.restofleet.agent.presentation.common.InfoRow
import ru.restofleet.agent.presentation.common.PermissionRow
import ru.restofleet.agent.presentation.common.StringSpec
import ru.restofleet.agent.presentation.setup.viewmodel.model.SetupStep

@Immutable
data class SetupUiState(
    val step: SetupStep,
    val stepTitle: StringSpec,
    val permissions: List<PermissionRow>,
    val baseUrl: String,
    val code: String,
    val canSubmit: Boolean,
    val isSubmitting: Boolean,
    val error: StringSpec?,
    val result: List<InfoRow>,
    val labAvailable: Boolean,
)
