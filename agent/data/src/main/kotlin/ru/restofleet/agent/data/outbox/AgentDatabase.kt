package ru.restofleet.agent.data.outbox

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * База агента: очередь исходящих сообщений и журнал исполненных команд. Версия 1; `fallbackToDestructiveMigration`
 * не используется — потеря очереди при обновлении агента равна потере данных, будущие схемы получат явные миграции.
 */
@Database(
    entities = [OutboxEntity::class, ProcessedCommandEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class AgentDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
    abstract fun processedCommandDao(): ProcessedCommandDao

    companion object {
        const val FILE_NAME = "agent.db"

        fun open(context: Context, name: String = FILE_NAME): AgentDatabase =
            Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, name).build()
    }
}
