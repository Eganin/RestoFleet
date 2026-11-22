package ru.restofleet.shared.shadow

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PackageNameTest {
    @Test
    fun realPackageNamesAreValid() {
        assertThat(isValidPackageName("com.android.chrome")).isTrue()
        assertThat(isValidPackageName("ru.restofleet.dummy.waiter")).isTrue()
        assertThat(isValidPackageName("a.b_c.D9")).isTrue()
    }

    @Test
    fun blankAndMalformedNamesAreInvalid() {
        listOf(
            "", " ", "chrome", "com..chrome", ".com.chrome", "com.chrome.", "1com.chrome", "com.chrome app",
            "com.хром", "com/chrome",
        )
            .forEach { assertThat(isValidPackageName(it)).isFalse() }
    }
}
