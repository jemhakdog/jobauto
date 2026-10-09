package com.jobaut.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing an answered screening question stored in Room.
 * Replaces the legacy learned_memory.json key-value store.
 */
@Entity(tableName = "learned_questions")
data class LearnedQuestionEntity(
    @PrimaryKey
    val hash: String,
    val questionText: String,
    val answerText: String,
    val confidence: Float = 1.0f,
    val updatedAt: Long = System.currentTimeMillis()
)
