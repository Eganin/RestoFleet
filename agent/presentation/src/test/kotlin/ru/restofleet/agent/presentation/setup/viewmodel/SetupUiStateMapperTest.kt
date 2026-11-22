package ru.restofleet.agent.presentation.setup.viewmodel

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import ru.restofleet.agent.domain.error.ProvisionError
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.common.StringSpec
import ru.restofleet.agent.presentation.setup.viewmodel.mapper.SetupUiStateMapper
import ru.restofleet.agent.presentation.setup.viewmodel.mapper.toStringSpec
import ru.restofleet.agent.presentation.setup.viewmodel.model.SetupSession
import ru.restofleet.agent.presentation.setup.viewmodel.model.SetupStep

class SetupUiStateMapperTest {
    @Test
    fun everyProvisionErrorHasItsText() {
        val cases = mapOf(
            ProvisionError.InvalidBaseUrl to StringSpec(R.string.setup_error_invalid_base_url),
            ProvisionError.EmptyCode to StringSpec(R.string.setup_error_empty_code),
            ProvisionError.CodeInvalid to StringSpec(R.string.setup_error_code_invalid),
            ProvisionError.CodeUsed to StringSpec(R.string.setup_error_code_used),
            ProvisionError.CodeExpired to StringSpec(R.string.setup_error_code_expired),
            ProvisionError.RateLimited(null) to StringSpec(R.string.setup_error_rate_limited),
            ProvisionError.RateLimited(30) to StringSpec(R.string.setup_error_rate_limited_after, listOf("30")),
            ProvisionError.ServerUnavailable to StringSpec(R.string.setup_error_server_unavailable),
            ProvisionError.Rejected("validation") to StringSpec(R.string.setup_error_rejected, listOf("validation")),
            ProvisionError.NoNetwork to StringSpec(R.string.setup_error_no_network),
            ProvisionError.Timeout to StringSpec(R.string.setup_error_timeout),
            ProvisionError.Tls to StringSpec(R.string.setup_error_tls),
            ProvisionError.CaMissing to StringSpec(R.string.setup_error_ca_missing),
            ProvisionError.InvalidResponse to StringSpec(R.string.setup_error_invalid_response),
            ProvisionError.StorageFailed to StringSpec(R.string.setup_error_storage),
            ProvisionError.Failed("X") to StringSpec(R.string.setup_error_failed, listOf("X")),
        )
        cases.forEach { (error, text) -> assertWithMessage(error.code).that(error.toStringSpec()).isEqualTo(text) }
    }

    @Test
    fun stepTitlesFollowTheStep() {
        val mapper = SetupUiStateMapper()

        val titles = SetupStep.entries.map { mapper.map(SetupSession(step = it)).stepTitle.res }

        assertThat(titles).containsExactly(
            R.string.setup_step_permissions, R.string.setup_step_registration, R.string.setup_step_done,
        ).inOrder()
    }

    @Test
    fun withoutRegistrationThereIsNoResult() {
        assertThat(SetupUiStateMapper().map(SetupSession()).result).isEmpty()
    }
}
