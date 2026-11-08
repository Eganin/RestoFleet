package ru.restofleet.agent.data.outbox

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Запись журнала исполненных команд. [seq] задаёт порядок вставки, по нему вытесняются самые старые. */
@Entity(tableName = "processed_commands", indices = [Index(value = ["cmdId"], unique = true)])
internal data class ProcessedCommandEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val cmdId: String,
)
