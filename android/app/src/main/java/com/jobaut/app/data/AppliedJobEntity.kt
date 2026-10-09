package com.jobaut.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing an applied job record stored in Room.
 * Replaces the legacy applied_tracker.csv and applied_jobs.txt.
 */
@Entity(
    tableName = "applied_jobs",
    indices = [Index(value = ["jobId"], unique = true)]
)
data class AppliedJobEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val jobId: String,
    val title: String,
    val company: String,
    val appliedAt: Long = System.currentTimeMillis(),
    val salary: String = "",
    val status: String = "APPLIED"
)
