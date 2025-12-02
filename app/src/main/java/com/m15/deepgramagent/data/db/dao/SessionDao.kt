package com.m15.deepgramagent.data.db.dao

import androidx.room.*
import com.m15.deepgramagent.data.db.ChatSession
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ChatSession)

    @Query("SELECT * FROM ChatSession ORDER BY createdAt DESC LIMIT 20")
    fun recent(): Flow<List<ChatSession>>
}
