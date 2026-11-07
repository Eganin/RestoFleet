package ru.restofleet.agent

import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.rules.ExternalResource

/**
 * Возвращает WorkManager в исходное состояние после теста: закрывает БД (иначе Robolectric пишет «A resource failed to
 * call SQLiteConnection.close») и сбрасывает глобальный экземпляр, чтобы следующий тест не наследовал чужой
 * WorkManager (и его конфигурацию) из того же sandbox. `setDelegate(null)` — единственный способ сброса синглтона
 * в work-runtime 2.12 (так же устроен и `WorkManagerTestInitHelper`).
 */
class WorkManagerIsolationRule : ExternalResource() {
    @Suppress("RestrictedApi") // сброс синглтона WorkManager доступен только через внутренний API
    override fun after() {
        if (WorkManager.isInitialized()) WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }
}
