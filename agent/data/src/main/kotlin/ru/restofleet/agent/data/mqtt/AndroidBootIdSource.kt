package ru.restofleet.agent.data.mqtt

import java.io.IOException

/**
 * `bootId` для `status online`: идентификатор загрузки ядра (`/proc/sys/kernel/random/boot_id`); если файл недоступен
 * приложению, то `bc-<Settings.Global.BOOT_COUNT>`; иначе `null` (поле опускается). Источники — лямбды, чтобы класс
 * проверялся без Android.
 */
class AndroidBootIdSource(
    private val readBootId: () -> String?,
    private val bootCount: () -> Int?,
) : BootIdSource {
    override fun bootId(): String? =
        kernelId() ?: bootCount()?.let { "bc-$it" }

    @Suppress("SwallowedException") // файл недоступен приложению: переходим к запасному источнику
    private fun kernelId(): String? =
        try {
            readBootId()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
}
