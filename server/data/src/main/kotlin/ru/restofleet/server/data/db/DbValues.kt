package ru.restofleet.server.data.db

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** epoch ms ↔ `timestamptz`. */
internal fun Long.toTimestamp(): OffsetDateTime = Instant.ofEpochMilli(this).atOffset(ZoneOffset.UTC)

internal fun OffsetDateTime.toMillis(): Long = toInstant().toEpochMilli()

/** Строковый id из домена → `uuid`; не UUID — `null` (такой строки в БД нет). */
internal fun String.toUuidOrNull(): UUID? = try {
    UUID.fromString(this).takeIf { it.toString() == lowercase() }
} catch (_: IllegalArgumentException) {
    null
}

internal fun String.toUuid(): UUID = toUuidOrNull() ?: throw IllegalArgumentException("not a UUID: $this")
