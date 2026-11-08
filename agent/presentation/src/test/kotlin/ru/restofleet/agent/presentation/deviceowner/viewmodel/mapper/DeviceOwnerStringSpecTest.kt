package ru.restofleet.agent.presentation.deviceowner.viewmodel.mapper

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.common.StringSpec
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerMessage

class DeviceOwnerStringSpecTest {
    @Test
    fun hashFetchedHasNoArguments() {
        assertThat(DeviceOwnerMessage.HashFetched.toStringSpec())
            .isEqualTo(StringSpec(R.string.do_message_hash_fetched))
    }

    @Test
    fun hashFailureUsesTheCode() {
        assertThat(DeviceOwnerMessage.HashFailed("http_404").toStringSpec())
            .isEqualTo(StringSpec(R.string.do_message_hash_failed, listOf("http_404")))
    }

    @Test
    fun errorUsesDetails() {
        assertThat(DeviceOwnerMessage.Error("denied").toStringSpec())
            .isEqualTo(StringSpec(R.string.do_message_error, listOf("denied")))
    }
}
