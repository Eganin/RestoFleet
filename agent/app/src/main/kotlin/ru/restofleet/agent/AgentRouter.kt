package ru.restofleet.agent

import android.content.ActivityNotFoundException
import android.content.Context
import ru.restofleet.agent.presentation.common.SettingsTarget
import ru.restofleet.agent.presentation.root.viewmodel.RootIntent
import ru.restofleet.agent.presentation.root.viewmodel.RootViewModel
import ru.restofleet.agent.presentation.setup.api.ISetupRouter
import ru.restofleet.agent.presentation.status.api.IStatusRouter

/**
 * Роутер мастера и экрана статуса. Живёт столько же, сколько их ViewModel (в `InstanceKeeper`), поэтому держит не
 * Activity, а контекст приложения: экраны настроек открываются с `FLAG_ACTIVITY_NEW_TASK` ([SettingsIntents]).
 * Переходы между экранами агента — намерения навигации [root].
 */
class AgentRouter(
    private val root: RootViewModel,
    private val context: Context,
    private val intents: SettingsIntents,
) : ISetupRouter, IStatusRouter {
    override fun openSettings(target: SettingsTarget) {
        try {
            context.startActivity(intents.forTarget(target))
        } catch (_: ActivityNotFoundException) {
            // на устройстве нет такого экрана настроек: кнопка просто ничего не открывает
        }
    }

    override fun openStatus() = root.onIntent(RootIntent.External.SetupCompleted)

    override fun openLab() = root.onIntent(RootIntent.User.OpenLab)
}
