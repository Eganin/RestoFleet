package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException

class AndroidBootIdSourceTest {
    @Test
    fun usesKernelBootIdTrimmed() {
        val source = AndroidBootIdSource({ "b7f2c1d0-aaaa-bbbb-cccc-000011112222\n" }, { 5 })

        assertThat(source.bootId()).isEqualTo("b7f2c1d0-aaaa-bbbb-cccc-000011112222")
    }

    @Test
    fun fallsBackToBootCountWhenKernelIdIsUnreadable() {
        assertThat(AndroidBootIdSource({ null }, { 5 }).bootId()).isEqualTo("bc-5")
        assertThat(AndroidBootIdSource({ "  " }, { 7 }).bootId()).isEqualTo("bc-7")
    }

    @Test
    fun readFailureFallsBack() {
        assertThat(AndroidBootIdSource({ throw IOException("denied") }, { 3 }).bootId()).isEqualTo("bc-3")
    }

    @Test
    fun unknownWhenNothingIsAvailable() {
        assertThat(AndroidBootIdSource({ null }, { null }).bootId()).isNull()
    }
}
