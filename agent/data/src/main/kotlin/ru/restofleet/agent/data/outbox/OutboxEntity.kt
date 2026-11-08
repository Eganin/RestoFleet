package ru.restofleet.agent.data.outbox

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Строка очереди неотправленных сообщений. [kind] хранится именем enum, а не `ordinal`: новый вид сообщения
 * не ломает уже записанные данные. [priority] скопирован из вида для индекса `(priority, id)`, по которому идёт
 * и досылка, и вытеснение.
 */
@Entity(tableName = "outbox", indices = [Index(value = ["priority", "id"])])
internal data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val priority: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
    val qos: Int,
    val retain: Boolean,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val correlationData: ByteArray?,
    val responseTopic: String?,
    val createdAt: Long,
)
