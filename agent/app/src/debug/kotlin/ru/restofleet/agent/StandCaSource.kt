package ru.restofleet.agent

import android.content.Context
import ru.restofleet.agent.data.mqtt.CaSource

/**
 * Только debug: CA стенда из `R.raw.stand_ca` (его генерирует `generate<Variant>StandTrust` из
 * `deploy/certs/out/public/ca.crt`). Пустой ресурс значит «при сборке CA не было»: `null`, сессия не стартует
 * (`ca_missing`). Release-версия класса всегда возвращает `null`: системному хранилищу доверия нет.
 */
class StandCaSource(private val context: Context) : CaSource {
    override fun pem(): ByteArray? =
        context.resources.openRawResource(R.raw.stand_ca).use { it.readBytes() }.takeIf { it.isNotEmpty() }
}
