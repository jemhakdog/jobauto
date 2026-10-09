package com.jobaut.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Main Room database for JobAut.
 * Manages persisted applied jobs and learned question responses.
 */
@Database(
    entities = [
        AppliedJobEntity::class,
        LearnedQuestionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun appliedJobDao(): AppliedJobDao
    abstract fun learnedQuestionDao(): LearnedQuestionDao

    companion object {
        private const val DATABASE_NAME = "jobaut.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Returns thread-safe singleton instance of [AppDatabase].
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
