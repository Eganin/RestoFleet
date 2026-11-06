package ru.restofleet.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ru.restofleet.agent.data.lifecycle.LifecycleJournal
import ru.restofleet.agent.data.lifecycle.ServiceStarter

/** Экспортирован ради системных широковещательных сообщений: действие проверяется, остальное игнорируется. */
class BootReceiver : BroadcastReceiver(), KoinComponent {
    private val starter: ServiceStarter by inject()
    private val journal: LifecycleJournal by inject()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                journal.recordBoot()
                starter.ensureRunning(REASON_BOOT)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> starter.ensureRunning(REASON_PACKAGE_REPLACED)
            else -> Unit
        }
    }

    companion object {
        const val REASON_BOOT = "boot"
        const val REASON_PACKAGE_REPLACED = "package_replaced"
    }
}
