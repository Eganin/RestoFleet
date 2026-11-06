package ru.restofleet.agent.data.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmulatorDetectionTest {
    @Test
    fun ranchuHardwareIsEmulator() {
        assertThat(isEmulator("ranchu", "google/sdk_gphone64_arm64/emu64a:16/BP22:eng/dev-keys")).isTrue()
    }

    @Test
    fun goldfishHardwareIsEmulator() {
        assertThat(isEmulator("goldfish", "samsung/gta9/gta9:14/UP1A:user/release-keys")).isTrue()
    }

    @Test
    fun fingerprintStartingWithGenericIsEmulator() {
        assertThat(isEmulator("unknown", "generic/sdk_x86/generic_x86:8.0.0/OSR1:eng/test-keys")).isTrue()
    }

    @Test
    fun sdkGphoneSegmentIsEmulator() {
        assertThat(isEmulator("unknown", "google/sdk_gphone64_x86_64/emu64x:15/AE3A:userdebug/dev-keys")).isTrue()
    }

    @Test
    fun sdkGoogleSegmentIsEmulator() {
        assertThat(isEmulator("unknown", "Android/sdk_google_phone_x86/generic_x86:9/PSR1:userdebug/dev-keys")).isTrue()
    }

    @Test
    fun realDeviceIsNot() {
        assertThat(isEmulator("exynos", "samsung/gta9/gta9:14/UP1A:user/release-keys")).isFalse()
    }

    @Test
    fun capitalizedGenericTabletWithRealHardwareIsNot() {
        assertThat(isEmulator("mt6765", "Generic/tablet/tablet:11/RP1A.200720.011:user/release-keys")).isFalse()
    }

    @Test
    fun gsiWithRealHardwareIsNot() {
        assertThat(isEmulator("qcom", "google/gsi_arm64/gsi_arm64:14/UP1A:userdebug/dev-keys")).isFalse()
    }

    @Test
    fun emuSubstringInOtherWordIsNot() {
        assertThat(isEmulator("qcom", "brand/premium_pro/premium:12/SP1A:user/release-keys")).isFalse()
    }
}
