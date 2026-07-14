package com.framenest.data.server

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ServerEntity)

    @Update
    suspend fun update(entity: ServerEntity)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: String)
}
