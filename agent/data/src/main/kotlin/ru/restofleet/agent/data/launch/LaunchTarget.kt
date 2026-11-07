package ru.restofleet.agent.data.launch

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.shared.kernel.Outcome

/**
 * Intent целевого приложения: `ACTION_VIEW` с адресом (только https), ограниченный пакетом [pkg], либо его
 * стандартный launch-intent. Одна реализация на lock task (`LockTaskLauncher`, `LockTaskActivity`) и на
 * `AndroidAppLauncher`; флагов запуска (`NEW_TASK`) не добавляет — это дело вызывающего.
 */
fun resolveLaunchTarget(pm: PackageManager, pkg: String, url: String?): Outcome<Intent, LaunchTargetError> {
    val rejected = when {
        // Та же проверка, что в domain (`Capabilities.check`): только https, схема в нижнем регистре, без userinfo
        url != null && !Capabilities.isHttpsUrl(url) -> "invalid_url"
        !isInstalled(pm, pkg) -> "package_not_installed"
        else -> null
    }
    val intent = when {
        rejected != null -> null
        url != null -> Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
        else -> pm.getLaunchIntentForPackage(pkg)
    }
    return when {
        rejected != null -> Outcome.Failure(LaunchTargetError(rejected))
        intent == null -> Outcome.Failure(LaunchTargetError("no_launch_intent"))
        else -> Outcome.Success(intent)
    }
}

// getPackageInfo(String, Int) работает на всех API; перегрузка с флагами появилась только в 33
@Suppress("DEPRECATION", "SwallowedException")
private fun isInstalled(pm: PackageManager, pkg: String): Boolean =
    try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
