package com.jobaut.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for learned/memorized screening questions.
 */
@Dao
interface LearnedQuestionDao {

    /**
     * Inserts or updates a question-answer pair.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(question: LearnedQuestionEntity)

    /**
     * Looks up an existing answer by its question hash.
     * @return Answer text if memorized, null otherwise.
     */
    @Query("SELECT answerText FROM learned_questions WHERE hash = :hash LIMIT 1")
    suspend fun findAnswer(hash: String): String?

    /**
     * Observes all learned questions sorted by most recently updated.
     */
    @Query("SELECT * FROM learned_questions ORDER BY updatedAt DESC")
    fun getAllQuestionsFlow(): Flow<List<LearnedQuestionEntity>>
}
