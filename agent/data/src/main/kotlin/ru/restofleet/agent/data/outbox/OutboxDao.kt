package ru.restofleet.agent.data.outbox

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface OutboxDao {
    @Insert
    suspend fun insert(entity: OutboxEntity): Long

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    @Query("SELECT * FROM outbox ORDER BY priority ASC, id ASC LIMIT :limit")
    suspend fun peek(limit: Int): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    /** Удаляет [count] наименее важных записей: наибольший `priority`, внутри него наименьший `id`. */
    @Query(
        "DELETE FROM outbox WHERE id IN " +
            "(SELECT id FROM outbox ORDER BY priority DESC, id ASC LIMIT :count)",
    )
    suspend fun evictWorst(count: Int)
}
