package ru.restofleet.agent

import android.content.Context
import ru.restofleet.agent.data.mqtt.CaSource

/**
 * Release: CA стенда в сборку не попадает и системному хранилищу брокер не доверен, поэтому источника нет и MQTT-сессия
 * не стартует (`ca_missing`, fail closed). Доставка CA в release — QR-провижининг, MVP-B.
 */
@Suppress("UnusedParameter")
class StandCaSource(context: Context) : CaSource {
    override fun pem(): ByteArray? = null
}
