package com.jobaut.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for applied jobs.
 */
@Dao
interface AppliedJobDao {

    /**
     * Inserts an applied job. Replaces if conflict occurs.
     * @return the row ID of the inserted record.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: AppliedJobEntity): Long

    /**
     * Checks if a job with the specified ID has already been applied for.
     */
    @Query("SELECT COUNT(*) > 0 FROM applied_jobs WHERE jobId = :jobId")
    suspend fun isApplied(jobId: String): Boolean

    /**
     * Observes all applied jobs sorted by most recent first.
     */
    @Query("SELECT * FROM applied_jobs ORDER BY appliedAt DESC")
    fun getAllJobsFlow(): Flow<List<AppliedJobEntity>>

    /**
     * Counts how many jobs were applied since the given timestamp (e.g. start of today).
     */
    @Query("SELECT COUNT(*) FROM applied_jobs WHERE appliedAt >= :sinceTimestamp")
    suspend fun getTodayCount(sinceTimestamp: Long): Int
}
