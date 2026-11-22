package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.model.DeviceRole
import ru.restofleet.agent.domain.model.Registration
import ru.restofleet.agent.domain.model.RegistrationState
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.CredentialStore
import ru.restofleet.shared.kernel.DeviceId
import java.io.IOException

class GetRegistrationTest {
    private val id = DeviceId.parse("d-9")!!

    private fun store(load: suspend () -> BrokerSettings?) = object : CredentialStore {
        override suspend fun save(s: BrokerSettings) = Unit

        override suspend fun load(): BrokerSettings? = load()
    }

    @Test
    fun storedCredentialsAreARegistrationWithoutPassword() = runTest {
        val sut = GetRegistration(store { BrokerSettings("h", 8883, id, "pw", DeviceRole.HALL, "r-2") })

        assertThat(sut()).isEqualTo(
            RegistrationState.Registered(Registration(id, DeviceRole.HALL, "r-2", "h", 8883)),
        )
    }

    @Test
    fun devConfigCredentialsHaveNoRoleOrRestaurant() = runTest {
        val state = GetRegistration(store { BrokerSettings("h", 8883, id, "pw") })() as RegistrationState.Registered

        assertThat(state.registration.role).isNull()
        assertThat(state.registration.restaurantId).isNull()
    }

    @Test
    fun noCredentialsMeansNotRegistered() = runTest {
        assertThat(GetRegistration(store { null })()).isEqualTo(RegistrationState.NotRegistered)
    }

    @Test
    fun temporarilyUnreadableStoreIsUnavailableNotUnregistered() = runTest {
        // Реш. 174: временный сбой Keystore не стирает учётку — мастер не должен предлагать новый код
        assertThat(GetRegistration(store { throw IOException("keystore") })()).isEqualTo(RegistrationState.Unavailable)
    }
}
