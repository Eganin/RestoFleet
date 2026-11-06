package ru.restofleet.shared.protocol

/** Дерево топиков протокола `rf/v1` (ТЗ 7.1). */
object Topics {
    const val PREFIX = "rf/v1"
    private const val DEVICE_ROOT = "$PREFIX/dev/"

    fun status(id: DeviceId): String = device(id, "status")
    fun telemetry(id: DeviceId): String = device(id, "telemetry")
    fun probe(id: DeviceId): String = device(id, "probe")
    fun events(id: DeviceId): String = device(id, "events")
    fun shadowReported(id: DeviceId): String = device(id, "shadow/reported")
    fun shadowDesired(id: DeviceId): String = device(id, "shadow/desired")
    fun cmdReq(id: DeviceId): String = device(id, "cmd/req")
    fun cmdRes(id: DeviceId): String = device(id, "cmd/res")

    /** deviceId из топика вида `rf/v1/dev/{id}/<suffix>`; для чужих и некорректных топиков — `null`. */
    fun deviceIdOf(topic: String): DeviceId? {
        val rest = topic.takeIf { it.startsWith(DEVICE_ROOT) }?.removePrefix(DEVICE_ROOT)
        val slash = rest?.indexOf('/') ?: -1
        val hasSuffix = rest != null && slash > 0 && slash < rest.lastIndex
        return if (hasSuffix) DeviceId.parse(rest.substring(0, slash)) else null
    }

    private fun device(id: DeviceId, suffix: String) = "$DEVICE_ROOT${id.value}/$suffix"
}
