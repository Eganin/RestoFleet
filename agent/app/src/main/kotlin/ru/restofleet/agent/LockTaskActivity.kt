package ru.restofleet.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import ru.restofleet.agent.data.deviceadmin.resolveLockTaskTarget
import ru.restofleet.shared.kernel.Outcome

/**
 * Вход в режим киоска на API 26–27, где `ActivityOptions.setLockTaskEnabled` недоступен: включает lock task для
 * себя и запускает целевое приложение. Не экспортирована; принимает только имя пакета и адрес (не вложенный
 * Intent) и сама строит целевой Intent той же функцией, что и `LockTaskLauncher`. Пакет приложения должен быть в
 * списке `setLockTaskPackages` — это делает адаптер Device Owner до запуска.
 */
class LockTaskActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(source: Intent) {
        val pkg = source.getStringExtra(EXTRA_PKG)
        val target = pkg?.let { resolveLockTaskTarget(packageManager, it, source.getStringExtra(EXTRA_URL)) }
        if (target is Outcome.Success) {
            startLockTask()
            startActivity(target.value)
        } else {
            finish()
        }
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_URL = "url"

        fun intent(context: Context, pkg: String, url: String?): Intent =
            Intent(context, LockTaskActivity::class.java).putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_URL, url)
    }
}
