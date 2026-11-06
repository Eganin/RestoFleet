package ru.restofleet.agent.data.collectors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class PackageChange(val pkg: String, val change: Change, val versionCode: Long?) {
    enum class Change { ADDED, REPLACED, REMOVED }
}

/** R12: установка, обновление и удаление отслеживаемых пакетов через динамический ресивер. */
class PackageEventsObserver(private val context: Context) {
    private val versions = AppVersionsCollector(context)

    fun events(watched: Set<String>): Flow<PackageChange> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pkg = intent.data?.schemeSpecificPart
                val change = changeOf(intent.action, intent.getBooleanExtra(Intent.EXTRA_REPLACING, false))
                if (pkg == null || pkg !in watched || change == null) return
                val code = if (change == PackageChange.Change.REMOVED) null else versions.version(pkg)?.versionCode
                trySend(PackageChange(pkg, change, code))
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        // EXPORTED: ACTION_PACKAGE_* — защищённые системные широковещательные сообщения, подделать их нельзя;
        // NOT_EXPORTED на API < 33 добавил бы собственное разрешение, которое системе не нужно
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    internal companion object {
        /** При обновлении система шлёт REMOVED+ADDED с EXTRA_REPLACING и затем REPLACED — берём только REPLACED. */
        fun changeOf(action: String?, replacing: Boolean): PackageChange.Change? = when {
            action == Intent.ACTION_PACKAGE_REPLACED -> PackageChange.Change.REPLACED
            replacing -> null
            action == Intent.ACTION_PACKAGE_ADDED -> PackageChange.Change.ADDED
            action == Intent.ACTION_PACKAGE_REMOVED -> PackageChange.Change.REMOVED
            else -> null
        }
    }
}
