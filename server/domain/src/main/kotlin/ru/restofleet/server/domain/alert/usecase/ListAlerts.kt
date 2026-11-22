package ru.restofleet.server.domain.alert.usecase

import ru.restofleet.server.domain.alert.AlertFilter
import ru.restofleet.server.domain.alert.AlertRepository
import ru.restofleet.server.domain.alert.AlertView
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction

/** `GET /alerts` (FR-CN-08): новые сначала; подавленные инцидентом — только с `includeSuppressed` (реш. 416). */
class ListAlerts(
    private val alerts: AlertRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(filter: AlertFilter, page: PageRequest = PageRequest.of()): List<AlertView> =
        tx.inTransaction { alerts.list(filter, page) }
}
