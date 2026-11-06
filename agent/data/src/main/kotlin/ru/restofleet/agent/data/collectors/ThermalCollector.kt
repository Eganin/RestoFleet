package ru.restofleet.agent.data.collectors

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import ru.restofleet.agent.data.platform.ApiLevel
import ru.restofleet.agent.data.platform.ThermalMapping
import ru.restofleet.agent.data.platform.isEmulator
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.ThermalState
import ru.restofleet.agent.domain.model.UnavailableReason

/** R3: `getCurrentThermalStatus` (API 29+) и `getThermalHeadroom` (API 30+). */
class ThermalCollector(
    private val context: Context,
    private val apiLevel: ApiLevel = ApiLevel.Device,
    private val fingerprint: String = Build.FINGERPRINT,
) {
    @SuppressLint("NewApi") // API проверяется через apiLevel
    fun read(): Reading<ThermalState> = guarded {
        val api = apiLevel.current()
        if (api < MIN_API_STATUS) {
            return@guarded Reading.Unavailable(UnavailableReason.ApiLevelTooLow(MIN_API_STATUS, api))
        }
        val headroom = headroom()
        val status = ThermalMapping.status(powerManager().currentThermalStatus) // null — неизвестный код платформы
        // Эмулятор не имеет термодатчиков: статус есть, а запас всегда NaN или 0
        if (isEmulator(fingerprint) && (headroom == null || headroom == 0.0)) {
            Reading.Unavailable(UnavailableReason.EmulatorStub)
        } else {
            Reading.Available(ThermalState(status, headroom))
        }
    }

    /** Запас до троттлинга на 0 с вперёд; `null` — API < 30 или платформа не может оценить. */
    @SuppressLint("NewApi") // API проверяется через apiLevel
    fun headroom(): Double? =
        if (apiLevel.current() >= MIN_API_HEADROOM) {
            ThermalMapping.headroom(headroomRaw())
        } else {
            null
        }

    @RequiresApi(MIN_API_HEADROOM)
    private fun headroomRaw(): Float = powerManager().getThermalHeadroom(0)

    private fun powerManager() = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private companion object {
        const val MIN_API_STATUS = 29
        const val MIN_API_HEADROOM = 30
    }
}
