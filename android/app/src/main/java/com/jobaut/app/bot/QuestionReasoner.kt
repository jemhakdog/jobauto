package com.jobaut.app.bot

import android.util.Log
import com.jobaut.app.ai.LlamaBridge
import com.jobaut.app.data.AppDatabase
import com.jobaut.app.data.LearnedQuestionEntity
import com.jobaut.app.data.UserProfile
import java.security.MessageDigest

/**
 * Handles answering job screening questions using learned memory (Room DB),
 * rule-based candidate profile matching, and on-device Qwen LLM reasoning.
 */
object QuestionReasoner {

    private const val TAG = "QuestionReasoner"

    /**
     * Answers a screening question using a multi-tier resolution strategy:
     * 1. Check Room database for previously learned / memorized answers (`LearnedQuestionDao.findAnswer`).
     * 2. Rule-based lookup based on candidate facts in [UserProfile].
     * 3. On-device LLM inference via [LlamaBridge.generate] if [llmCtxPtr] != 0L.
     * 4. Safe fallback ("No" or "None").
     *
     * Persists newly answered questions to Room DB for future fast retrieval.
     */
    suspend fun answerQuestion(
        questionText: String,
        profile: UserProfile,
        llmCtxPtr: Long,
        db: AppDatabase
    ): String {
        val cleanQuestion = questionText.trim()
        if (cleanQuestion.isEmpty()) return "No"

        val hash = hashQuestion(cleanQuestion)

        // Tier 1: Check learned memory in Room DB
        try {
            val learned = db.learnedQuestionDao().findAnswer(hash)
            if (!learned.isNullOrBlank()) {
                Log.d(TAG, "Answer found in learned memory: '$learned' for '$cleanQuestion'")
                return learned
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading learned question from DB: ${e.message}")
        }

        // Tier 2: Deterministic profile rule match
        val ruleAnswer = matchProfileRules(cleanQuestion, profile)
        if (ruleAnswer != null) {
            Log.d(TAG, "Answer resolved via profile rule: '$ruleAnswer'")
            saveLearnedAnswer(db, hash, cleanQuestion, ruleAnswer, 1.0f)
            return ruleAnswer
        }

        // Tier 3: Query on-device LLM
        var answer = ""
        if (llmCtxPtr != 0L) {
            val prompt = buildPrompt(cleanQuestion, profile)
            try {
                val rawResponse = LlamaBridge.generate(llmCtxPtr, prompt, maxTokens = 64)
                answer = parseLlmResponse(rawResponse)
                Log.d(TAG, "Answer generated via LLM: '$answer'")
            } catch (e: Exception) {
                Log.w(TAG, "LLM generation failed: ${e.message}")
            }
        }

        // Tier 4: Fallback
        if (answer.isBlank()) {
            answer = "No"
            Log.d(TAG, "Default fallback answer applied: '$answer'")
        }

        // Save newly answered question to Room DB
        saveLearnedAnswer(db, hash, cleanQuestion, answer, if (llmCtxPtr != 0L) 0.8f else 0.5f)

        return answer
    }

    /**
     * Finds the closest matching choice among available UI options for a question.
     */
    fun selectBestOption(
        questionText: String,
        options: List<String>,
        suggestedAnswer: String
    ): String? {
        if (options.isEmpty()) return null
        val lowerAnswer = suggestedAnswer.lowercase().trim()

        // 1. Exact or contains match with suggested answer
        val directMatch = options.firstOrNull {
            val lowerOpt = it.lowercase().trim()
            lowerOpt == lowerAnswer || lowerOpt.contains(lowerAnswer) || (lowerAnswer.length > 2 && lowerAnswer.contains(lowerOpt))
        }
        if (directMatch != null) return directMatch

        // 2. Fallback matching for common negatives/zeros
        val fallbacks = listOf("no", "no experience", "none", "less than 1 year", "0", "not applicable")
        for (fallback in fallbacks) {
            val opt = options.firstOrNull { it.lowercase().trim() == fallback || it.lowercase().trim().contains(fallback) }
            if (opt != null) return opt
        }

        // 3. Fallback to first available option
        return options.firstOrNull()
    }

    private fun matchProfileRules(question: String, profile: UserProfile): String? {
        val q = question.lowercase()

        // Notice period
        if (q.contains("notice") || q.contains("start date") || q.contains("when can you start") || q.contains("availability")) {
            return profile.noticePeriod
        }

        // Expected salary
        if (q.contains("expected salary") || q.contains("monthly salary") || q.contains("basic salary") || q.contains("salary expectation")) {
            return profile.expectedSalary
        }

        // Phone number
        if (q.contains("phone") || q.contains("mobile") || q.contains("contact number")) {
            return profile.phone
        }

        // Email
        if (q.contains("email") || q.contains("e-mail")) {
            return profile.email
        }

        // Full name
        if (q.contains("full name") || q.contains("your name")) {
            return profile.name
        }

        // English proficiency
        if (q.contains("english") && (q.contains("proficien") || q.contains("rate") || q.contains("level") || q.contains("skill"))) {
            return "Limited proficiency"
        }

        // Travel / relocation
        if (q.contains("travel") || q.contains("relocate") || q.contains("relocation") || q.contains("willing to move")) {
            return "No"
        }

        // Degree / College graduate
        if (q.contains("bachelor") || q.contains("degree") || q.contains("college graduate") || q.contains("graduated")) {
            return "No"
        }

        // Disqualifying or non-experienced domains
        val noExpDomains = listOf(
            "customer service", "csr", "cold call", "cold calling", "sales", "telemarket",
            "appointment setter", "human resources", "recruitment", "accounting", "audit",
            "bookkeep", "social media", "virtual assistant"
        )
        if (noExpDomains.any { q.contains(it) }) {
            return "No experience"
        }

        // Years of experience
        if (q.contains("years of experience") || q.contains("how many years") || q.contains("years' experience")) {
            return if (profile.experienceYears == 0) "0" else "${profile.experienceYears}"
        }

        return null
    }

    private fun buildPrompt(question: String, profile: UserProfile): String {
        return """Candidate Profile:
- Name: ${profile.name}
- Years of Experience: ${profile.experienceYears}
- Notice Period: ${profile.noticePeriod}
- Expected Salary: ${profile.expectedSalary}
- Remote only, cannot relocate, entry-level/junior.

Question: $question

Instructions:
Provide a concise, factual 1-sentence or short-phrase answer from the candidate's perspective.
Answer:"""
    }

    private fun parseLlmResponse(raw: String): String {
        var clean = raw.trim()
        if (clean.contains("Answer:", ignoreCase = true)) {
            clean = clean.substringAfter("Answer:").trim()
        }
        val firstLine = clean.lines().firstOrNull { it.isNotBlank() } ?: ""
        return firstLine.trim('`', '"', '\'', ' ', '\n', '.')
    }

    private suspend fun saveLearnedAnswer(
        db: AppDatabase,
        hash: String,
        question: String,
        answer: String,
        confidence: Float
    ) {
        try {
            val entity = LearnedQuestionEntity(
                hash = hash,
                questionText = question,
                answerText = answer,
                confidence = confidence,
                updatedAt = System.currentTimeMillis()
            )
            db.learnedQuestionDao().insertOrUpdate(entity)
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving learned question to DB: ${e.message}")
        }
    }

    fun hashQuestion(question: String): String {
        val normalized = question.lowercase().replace("\\s+".toRegex(), " ").trim()
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(normalized.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
