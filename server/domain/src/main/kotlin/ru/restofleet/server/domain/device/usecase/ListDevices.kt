package ru.restofleet.server.domain.device.usecase

import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.Device
import ru.restofleet.server.domain.device.DeviceFilter
import ru.restofleet.server.domain.device.DeviceRepository

/** `GET /devices?restaurant=&role=&status=`. */
class ListDevices(
    private val devices: DeviceRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(filter: DeviceFilter, page: PageRequest = PageRequest.of()): List<Device> =
        tx.inTransaction { devices.list(filter, page) }
}
