package ru.restofleet.shared.kernel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IdGeneratorTest {

    @Test
    fun generatesUniquePrefixedUuids() {
        val generator = UuidIdGenerator(prefix = "c-")
        val ids = List(1_000) { generator.newId() }

        assertThat(ids.toSet()).hasSize(1_000)
        ids.forEach { assertThat(it).matches("^c-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$") }
    }

    @Test
    fun emptyPrefixByDefault() {
        assertThat(UuidIdGenerator().newId()).hasLength(36)
    }
}
