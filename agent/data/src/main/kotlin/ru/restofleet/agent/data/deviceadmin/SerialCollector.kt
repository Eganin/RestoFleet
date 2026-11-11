package ru.restofleet.agent.data.deviceadmin

import android.annotation.SuppressLint
import android.os.Build
import ru.restofleet.agent.data.collectors.guarded
import ru.restofleet.agent.data.platform.ApiLevel
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason

/**
 * R13: серийный номер через `Build.getSerial()`. С API 29 метод доступен только Device Owner при выданном
 * `READ_PHONE_STATE` (его выдаёт D6). `SecurityException`: не DO — `RequiresDeviceOwner`, DO без разрешения (и
 * любая роль на API 26–28) — `PermissionMissing("READ_PHONE_STATE")`. `"unknown"` и пустое значение — `Failed`.
 * Полное значение наружу не логируется; в Lab и отчёт оно попадает только в маске (см. `CheckValues.maskSerial`).
 */
class SerialCollector(
    private val apiLevel: ApiLevel = ApiLevel.Device,
    private val role: DeviceOwnerRole,
    private val getSerial: () -> String = ::readBuildSerial,
) {
    @Suppress("SwallowedException") // SecurityException = нет доступа к серийному номеру
    fun read(): Reading<String> = guarded {
        val serial = try {
            getSerial()
        } catch (e: SecurityException) {
            // С API 29 серийный номер отдаётся только Device Owner с выданным READ_PHONE_STATE (эмулятор API 36: после
            // выдачи разрешения значение есть, до — SecurityException у уже назначенного DO). Решение 118.
            return@guarded Reading.Unavailable(
                if (apiLevel.current() >= DEVICE_OWNER_ONLY_API && !role.isDeviceOwner()) {
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

// Доступ подтверждается ролью DO, а не манифестом; SecurityException обрабатывает read(). Подавление стоит на функции,
// а не на параметре конструктора: lint AGP 9.3 не распространяет его на лямбду по умолчанию.
@SuppressLint("HardwareIds", "MissingPermission")
private fun readBuildSerial(): String = Build.getSerial()
