package ru.restofleet.agent.data.collectors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import ru.restofleet.agent.data.platform.BatteryMapping
import ru.restofleet.agent.domain.model.BatteryState
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason

/** R1, R2: sticky ACTION_BATTERY_CHANGED -> [BatteryMapping]. */
class BatteryCollector(private val context: Context) {
    fun read(): Reading<BatteryState> = guarded {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return@guarded Reading.Unavailable(UnavailableReason.Failed("no battery broadcast"))
        BatteryMapping.from(
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE),
        )
    }
}
