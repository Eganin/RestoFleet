package ru.restofleet.console.feature.devices.ui.api

interface IDevicesRouter {
    fun openDevice(deviceId: String)

    fun openProvisioning()
}
