package ru.restofleet.agent.data.outbox

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface ProcessedCommandDao {
    /** Повторная запись того же `cmdId` игнорируется: порядок вытеснения не меняется. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ProcessedCommandEntity): Long

    @Query("SELECT COUNT(*) FROM processed_commands WHERE cmdId = :cmdId")
    suspend fun countOf(cmdId: String): Int

    @Query("DELETE FROM processed_commands")
    suspend fun deleteAll()

    /** Оставляет [keep] последних по порядку вставки. */
    @Query(
        "DELETE FROM processed_commands WHERE seq NOT IN " +
            "(SELECT seq FROM processed_commands ORDER BY seq DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)
}
