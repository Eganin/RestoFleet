package ru.restofleet.agent

import android.app.admin.DeviceAdminReceiver

/**
 * Компонент администратора устройства (`ru.restofleet.agent/.AdminReceiver`): назначается Device Owner командой
 * `dpm set-device-owner` или по QR. Логики не содержит — адаптер Device Owner живёт в `agent:data`. Экспортирован
 * по требованию системы, доступ ограничен `BIND_DEVICE_ADMIN` в манифесте (его держит только система).
 */
class AdminReceiver : DeviceAdminReceiver()
