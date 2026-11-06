package ru.restofleet.agent.data.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmulatorDetectionTest {
    @Test
    fun genericFingerprintIsEmulator() {
        assertThat(isEmulator("google/sdk_gphone64_arm64/emu64a:16/BP22:eng/generic")).isTrue()
    }

    @Test
    fun emuFingerprintIsEmulator() {
        assertThat(isEmulator("Android/sdk/EMU64A:15")).isTrue()
    }

    @Test
    fun realDeviceFingerprintIsNot() {
        assertThat(isEmulator("samsung/gta9/gta9:14/UP1A:user/release-keys")).isFalse()
    }
}
