package ru.restofleet.server.domain.shadow

import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.delta

/** `desired` с версией — то, что публикуется retained в `shadow/desired` (ключ `version` добавляет data-слой). */
data class DesiredState(val version: Long, val document: ShadowDocument)

/**
 * Тень устройства (FR-BE-20). [desired] и [reported] хранятся без служебных ключей (`version`, `appliedVersion`,
 * `appliedAt`, `errors`) — они в отдельных полях. [reportedVersion] — `appliedVersion` последнего `reported`.
 */
data class Shadow(
    val deviceId: DeviceId,
    val desired: ShadowDocument,
    val desiredVersion: Long,
    val desiredUpdatedAt: Long,
    val reported: ShadowDocument?,
    val reportedVersion: Long?,
    val appliedAt: Long?,
    val reportedAt: Long?,
    val errors: List<String>,
) {
    /** Что из `desired` устройство ещё не применило; пустая — тень применена (S4). */
    val delta: ShadowDocument get() = delta(desired, reported ?: ShadowDocument.EMPTY)

    /** Устройство не подтвердило текущую версию `desired`: при подключении её стоит опубликовать снова. */
    val isPending: Boolean get() = desiredVersion > 0 && (reportedVersion ?: 0L) < desiredVersion

    fun desiredState(): DesiredState = DesiredState(desiredVersion, desired)

    companion object {
        /** Тень нового устройства: `desired` версии 1. */
        fun initial(deviceId: DeviceId, desired: ShadowDocument, now: Long): Shadow =
            Shadow(deviceId, desired, 1, now, null, null, null, null, emptyList())
    }
}

/** Отчёт устройства `shadow/reported` (метаданные отделены от документа). */
data class ReportedShadow(
    val deviceId: DeviceId,
    val document: ShadowDocument,
    val appliedVersion: Long,
    val appliedAt: Long,
    val errors: List<String>,
)

/** Итог [ru.restofleet.server.domain.shadow.usecase.UpdateDesired]: [published] = `false` — повтор при подключении. */
data class UpdatedShadow(val shadow: Shadow, val published: Boolean)

/** Порт теней. `findForUpdate` блокирует строку до конца транзакции (версия++ без гонок). Только в транзакции. */
interface ShadowRepository {
    suspend fun insert(shadow: Shadow)

    suspend fun find(deviceId: DeviceId): Shadow?

    suspend fun findForUpdate(deviceId: DeviceId): Shadow?

    suspend fun updateDesired(deviceId: DeviceId, desired: ShadowDocument, version: Long, at: Long)

    suspend fun updateReported(report: ReportedShadow, at: Long)
}
