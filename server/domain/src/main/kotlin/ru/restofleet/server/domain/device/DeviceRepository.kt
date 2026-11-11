package ru.restofleet.server.domain.device

import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.shared.kernel.DeviceId

/**
 * Порт реестра устройств. Изменения статуса — отдельными атомарными операциями (а не «прочитать и записать целиком»):
 * их одновременно зовут ingest, polling и API. Ни одна операция, кроме [decommission], не трогает выведенное из
 * эксплуатации устройство. Вызывается только внутри транзакции.
 */
interface DeviceRepository {
    suspend fun insert(device: Device)

    suspend fun findById(id: DeviceId): Device?

    /** Упорядочено по времени регистрации, затем по id. */
    suspend fun list(filter: DeviceFilter, page: PageRequest): List<Device>

    /** Из [ids] — известные и не выведенные из эксплуатации. */
    suspend fun activeIds(ids: Collection<DeviceId>): Set<DeviceId>

    /** `ONLINE`, `last_seen_at = at`, транспорт и сведения о себе (поля `null` в [announcement] не затираются). */
    suspend fun markOnline(id: DeviceId, at: Long, transport: DeviceTransport, announcement: DeviceAnnouncement)

    suspend fun markOffline(id: DeviceId, at: Long)

    /** `last_seen_at = at` для пачки ingest. */
    suspend fun touch(ids: Collection<DeviceId>, at: Long)

    /** Опрашивающие (REST) устройства без опроса с [cutoff] → `OFFLINE` (реш. 214); число изменённых. */
    suspend fun markStalePollersOffline(cutoff: Long): Int

    suspend fun decommission(id: DeviceId, at: Long)
}

/** Учётка устройства для REST-polling (P9): bcrypt-хэш пароля; отзыв — при выводе из эксплуатации. */
interface CredentialRepository {
    suspend fun insert(deviceId: DeviceId, passwordHash: String, createdAt: Long)

    /** Хэш действующей (не отозванной) учётки. */
    suspend fun findActiveHash(deviceId: DeviceId): String?

    suspend fun revoke(deviceId: DeviceId, at: Long)
}

/**
 * Кэш успешных Basic-проверок устройства (реш. 213): `deviceId` → SHA-256 пароля, TTL задаёт реализация (5 мин).
 * Сбрасывается при выводе из эксплуатации.
 */
interface DeviceCredentialCache {
    fun get(deviceId: DeviceId): String?

    fun put(deviceId: DeviceId, passwordDigest: String)

    fun invalidate(deviceId: DeviceId)
}
