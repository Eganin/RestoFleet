package ru.restofleet.shared.kernel

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class DeviceIdTest {

    @Test
    fun acceptsSafeDeviceIds() {
        listOf("a", "dev_EMU-01", "x".repeat(64)).forEach {
            assertWithMessage(it).that(DeviceId.parse(it)?.value).isEqualTo(it)
        }
    }

    @Test
    fun rejectsUnsafeDeviceIds() {
        listOf("", "a/b", "a+b", "a#", "a b", "x".repeat(65), "дев", "a\u0000").forEach {
            assertWithMessage(it).that(DeviceId.parse(it)).isNull()
        }
    }
}
