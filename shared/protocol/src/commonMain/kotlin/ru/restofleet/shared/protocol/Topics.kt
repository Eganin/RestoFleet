package ru.restofleet.shared.protocol

import ru.restofleet.shared.kernel.DeviceId

/** Канал устройства — хвост топика `rf/v1/dev/{id}/<suffix>`; [fromDevice] — публикует устройство (ТЗ 7.1, P5). */
enum class DeviceChannel(val suffix: String, val fromDevice: Boolean) {
    STATUS("status", fromDevice = true),
    TELEMETRY("telemetry", fromDevice = true),
    PROBE("probe", fromDevice = true),
    EVENTS("events", fromDevice = true),
    SHADOW_REPORTED("shadow/reported", fromDevice = true),
    CMD_RES("cmd/res", fromDevice = true),
    SHADOW_DESIRED("shadow/desired", fromDevice = false),
    CMD_REQ("cmd/req", fromDevice = false),
}

/** Разобранный топик устройства. */
data class DeviceTopic(val deviceId: DeviceId, val channel: DeviceChannel)

/** Дерево топиков протокола `rf/v1` (ТЗ 7.1). */
object Topics {
    const val PREFIX = "rf/v1"
    internal const val DEVICE_ROOT = "$PREFIX/dev/"

    fun status(id: DeviceId): String = of(id, DeviceChannel.STATUS)
    fun telemetry(id: DeviceId): String = of(id, DeviceChannel.TELEMETRY)
    fun probe(id: DeviceId): String = of(id, DeviceChannel.PROBE)
    fun events(id: DeviceId): String = of(id, DeviceChannel.EVENTS)
    fun shadowReported(id: DeviceId): String = of(id, DeviceChannel.SHADOW_REPORTED)
    fun shadowDesired(id: DeviceId): String = of(id, DeviceChannel.SHADOW_DESIRED)
    fun cmdReq(id: DeviceId): String = of(id, DeviceChannel.CMD_REQ)
    fun cmdRes(id: DeviceId): String = of(id, DeviceChannel.CMD_RES)

    fun of(id: DeviceId, channel: DeviceChannel): String = "$DEVICE_ROOT${id.value}/${channel.suffix}"

    /** deviceId из топика вида `rf/v1/dev/{id}/<suffix>`; для чужих и некорректных топиков — `null`. */
    fun deviceIdOf(topic: String): DeviceId? {
        val rest = topic.takeIf { it.startsWith(DEVICE_ROOT) }?.removePrefix(DEVICE_ROOT)
        val slash = rest?.indexOf('/') ?: -1
        val hasSuffix = rest != null && slash > 0 && slash < rest.lastIndex
        return if (hasSuffix) DeviceId.parse(rest.substring(0, slash)) else null
    }

    /** Устройство и канал; неизвестный хвост (в том числе лишний уровень) — `null`. Маршрутизация ingest бэкенда. */
    fun parse(topic: String): DeviceTopic? {
        val id = deviceIdOf(topic) ?: return null
        val suffix = topic.removePrefix("$DEVICE_ROOT${id.value}/")
        return DeviceChannel.entries.firstOrNull { it.suffix == suffix }?.let { DeviceTopic(id, it) }
    }
}

/** Фильтры shared subscription (`$share/<group>/...`): бэкенд и симулятор делят поток между экземплярами (ТЗ 7.1). */
object SharedSubscriptions {
    /** Группа бэкенда (`$share/backend/...`). */
    const val BACKEND_GROUP = "backend"

    /** Канал всех устройств: `$share/<group>/rf/v1/dev/+/<suffix>`. */
    fun of(group: String, channel: DeviceChannel): String = "\$share/$group/${Topics.DEVICE_ROOT}+/${channel.suffix}"

    /** Подписки бэкенда — все каналы от устройств. */
    fun backend(): List<String> = DeviceChannel.entries.filter { it.fromDevice }.map { of(BACKEND_GROUP, it) }
}
