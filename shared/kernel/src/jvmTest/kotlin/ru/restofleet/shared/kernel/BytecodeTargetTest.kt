package ru.restofleet.shared.kernel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * jvmTarget из libs.versions.toml (17 → major 61) реально применяется:
 * иначе Android-модули не смогут инлайнить Outcome.map и др..
 */
class BytecodeTargetTest {

    @Test
    fun productionClassesTargetJava17() {
        val stream = requireNotNull(Outcome::class.java.getResourceAsStream("Outcome.class"))
        val bytes = stream.use { it.readNBytes(8) }
        val major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        assertThat(major).isEqualTo(61)
    }
}
