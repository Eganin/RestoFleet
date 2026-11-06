package ru.restofleet.agent.data.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.model.ThermalStatus

class ThermalMappingTest {

    @Test
    fun statusInRangeMapsByOrdinal() {
        (0..6).forEach { raw ->
            assertThat(ThermalMapping.status(raw)).isEqualTo(ThermalStatus.entries[raw])
        }
    }

    @Test
    fun statusOutOfRangeIsNull() {
        assertThat(ThermalMapping.status(7) as Any?).isNull()
        assertThat(ThermalMapping.status(-1) as Any?).isNull()
    }

    @Test
    fun headroomNaNIsNull() {
        assertThat(ThermalMapping.headroom(Float.NaN) as Any?).isNull()
    }

    @Test
    fun headroomValueIsPreserved() {
        assertThat(ThermalMapping.headroom(0.62f)).isWithin(1e-6).of(0.62)
    }
}
