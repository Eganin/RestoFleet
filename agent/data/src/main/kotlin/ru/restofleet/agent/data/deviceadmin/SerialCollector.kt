package ru.restofleet.agent.data.deviceadmin

import android.annotation.SuppressLint
import android.os.Build
import ru.restofleet.agent.data.collectors.guarded
import ru.restofleet.agent.data.platform.ApiLevel
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason

/**
 * R13: серийный номер через `Build.getSerial()`. По документации (API 29+) метод доступен Device Owner полностью
 * управляемого устройства; иначе — `SecurityException`, что здесь сообщается как нехватка `READ_PHONE_STATE`
 * (разрешение объявлено и выдаётся адаптером DO самому себе). `"unknown"` и пустое значение — `Failed`.
 * Полное значение наружу не логируется; в Lab и отчёт оно попадает только в маске (см. `CheckValues.maskSerial`).
 */
class SerialCollector(
    private val apiLevel: ApiLevel = ApiLevel.Device,
    @SuppressLint("HardwareIds", "MissingPermission") // доступ подтверждается ролью DO, а не манифестом
    private val getSerial: () -> String = { Build.getSerial() },
) {
    @Suppress("SwallowedException") // SecurityException = нет доступа к серийному номеру
    fun read(): Reading<String> = guarded {
        val serial = try {
            getSerial()
        } catch (e: SecurityException) {
            // С API 29 по javadoc Build.getSerial() доступен только Device Owner: выдача READ_PHONE_STATE не поможет
            return@guarded Reading.Unavailable(
                if (apiLevel.current() >= DEVICE_OWNER_ONLY_API) {
                    UnavailableReason.RequiresDeviceOwner
                } else {
                    UnavailableReason.PermissionMissing(PERMISSION)
                },
            )
        }
        if (serial.isBlank() || serial == Build.UNKNOWN) {
            Reading.Unavailable(UnavailableReason.Failed("serial_unknown"))
        } else {
            Reading.Available(serial)
        }
    }

    private companion object {
        const val PERMISSION = "READ_PHONE_STATE"
        const val DEVICE_OWNER_ONLY_API = 29
    }
}
