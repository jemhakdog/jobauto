package com.jobaut.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_profile")

/**
 * Data class representing user configuration and candidate profile facts.
 */
data class UserProfile(
    val name: String = "Jem Carlo G. Austria",
    val email: String = "jemcarloadb@gmail.com",
    val phone: String = "09123456789",
    val experienceYears: Int = 0,
    val noticePeriod: String = "Less than 2 weeks",
    val expectedSalary: String = "25000",
    val minSalary: Int = 15000,
    val targetTitles: List<String> = listOf("junior python", "python developer", "software engineer", "web developer"),
    val blacklistedKeywords: List<String> = listOf(
        "sales",
        "cold call",
        "cold calling",
        "telemarket",
        "telemarketing",
        "outbound",
        "appointment setter",
        "account executive",
        "bdr",
        "sdr",
        "lead gen",
        "lead generation",
        "voice",
        "call center",
        "phone support",
        "inbound call",
        "math",
        "accounting",
        "accountant",
        "bookkeeper",
        "bookkeeping",
        "audit",
        "auditor",
        "finance",
        "financial analyst",
        "social media",
        "content creator",
        "tiktok",
        "instagram",
        "facebook ads",
        "media buyer",
        "copywriter",
        "unpaid",
        "commission only"
    )
)

/**
 * Manages persisting and loading candidate profile and application configuration
 * using Jetpack DataStore Preferences.
 */
class UserConfigManager(private val context: Context) {

    private object PreferencesKeys {
        val KEY_NAME = stringPreferencesKey("name")
        val KEY_EMAIL = stringPreferencesKey("email")
        val KEY_PHONE = stringPreferencesKey("phone")
        val KEY_EXPERIENCE_YEARS = intPreferencesKey("experience_years")
        val KEY_NOTICE_PERIOD = stringPreferencesKey("notice_period")
        val KEY_EXPECTED_SALARY = stringPreferencesKey("expected_salary")
        val KEY_MIN_SALARY = intPreferencesKey("min_salary")
        val KEY_TARGET_TITLES = stringPreferencesKey("target_titles")
        val KEY_BLACKLISTED_KEYWORDS = stringPreferencesKey("blacklisted_keywords")
    }

    private val defaultProfile = UserProfile()

    /**
     * Flow emitting [UserProfile] updates reactively.
     */
    val profileFlow: Flow<UserProfile> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            mapPreferencesToProfile(preferences)
        }

    /**
     * One-shot suspend loader for [UserProfile].
     */
    suspend fun loadProfile(): UserProfile {
        return profileFlow.first()
    }

    /**
     * Persists updated [UserProfile] to DataStore.
     */
    suspend fun saveProfile(profile: UserProfile) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_NAME] = profile.name
            preferences[PreferencesKeys.KEY_EMAIL] = profile.email
            preferences[PreferencesKeys.KEY_PHONE] = profile.phone
            preferences[PreferencesKeys.KEY_EXPERIENCE_YEARS] = profile.experienceYears
            preferences[PreferencesKeys.KEY_NOTICE_PERIOD] = profile.noticePeriod
            preferences[PreferencesKeys.KEY_EXPECTED_SALARY] = profile.expectedSalary
            preferences[PreferencesKeys.KEY_MIN_SALARY] = profile.minSalary
            preferences[PreferencesKeys.KEY_TARGET_TITLES] = profile.targetTitles.joinToString("\n")
            preferences[PreferencesKeys.KEY_BLACKLISTED_KEYWORDS] = profile.blacklistedKeywords.joinToString("\n")
        }
    }

    private fun mapPreferencesToProfile(preferences: Preferences): UserProfile {
        val name = preferences[PreferencesKeys.KEY_NAME] ?: defaultProfile.name
        val email = preferences[PreferencesKeys.KEY_EMAIL] ?: defaultProfile.email
        val phone = preferences[PreferencesKeys.KEY_PHONE] ?: defaultProfile.phone
        val experienceYears = preferences[PreferencesKeys.KEY_EXPERIENCE_YEARS] ?: defaultProfile.experienceYears
        val noticePeriod = preferences[PreferencesKeys.KEY_NOTICE_PERIOD] ?: defaultProfile.noticePeriod
        val expectedSalary = preferences[PreferencesKeys.KEY_EXPECTED_SALARY] ?: defaultProfile.expectedSalary
        val minSalary = preferences[PreferencesKeys.KEY_MIN_SALARY] ?: defaultProfile.minSalary

        val targetTitles = preferences[PreferencesKeys.KEY_TARGET_TITLES]?.let { raw ->
            raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        } ?: defaultProfile.targetTitles

        val blacklistedKeywords = preferences[PreferencesKeys.KEY_BLACKLISTED_KEYWORDS]?.let { raw ->
            raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        } ?: defaultProfile.blacklistedKeywords

        return UserProfile(
            name = name,
            email = email,
            phone = phone,
            experienceYears = experienceYears,
            noticePeriod = noticePeriod,
            expectedSalary = expectedSalary,
            minSalary = minSalary,
            targetTitles = targetTitles,
            blacklistedKeywords = blacklistedKeywords
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: UserConfigManager? = null

        fun getInstance(context: Context): UserConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserConfigManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
