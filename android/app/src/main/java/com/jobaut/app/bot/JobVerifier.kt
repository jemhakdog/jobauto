package com.jobaut.app.bot

import android.util.Log
import com.jobaut.app.ai.LlamaBridge
import com.jobaut.app.data.UserProfile
import java.util.regex.Pattern

/**
 * Evaluates candidate fit for job postings using blacklists, regex checks,
 * semantic cross-encoder reranking via [LlamaBridge], and keyword fallbacks.
 */
object JobVerifier {

    private const val TAG = "JobVerifier"

    private val DISQUALIFYING_ROLES = listOf(
        "\\bsenior\\b",
        "\\bsr\\.?\\b",
        "\\blead\\b",
        "\\bprincipal\\b",
        "\\bdirector\\b",
        "\\barchitect\\b",
        "\\bhead of\\b",
        "\\bexecutive\\b"
    ).map { Pattern.compile(it, Pattern.CASE_INSENSITIVE) }

    private val DISQUALIFYING_EXP = listOf(
        "\\b[3-9]\\+?\\s*(years?|yrs?)\\b",
        "\\b1[0-9]\\+?\\s*(years?|yrs?)\\b",
        "\\bminimum\\s+of\\s+[3-9]\\s+years\\b",
        "\\bat\\s+least\\s+[3-9]\\s+years\\b"
    ).map { Pattern.compile(it, Pattern.CASE_INSENSITIVE) }

    private val RELOCATION_TERMS = listOf(
        "\\bmust\\s+relocate\\b",
        "\\brelocation\\s+required\\b",
        "\\bwilling\\s+to\\s+relocate\\b"
    ).map { Pattern.compile(it, Pattern.CASE_INSENSITIVE) }

    /**
     * Evaluates whether a job should be applied for.
     *
     * 1. Fast pre-check: Disqualifying seniority, multi-year experience (>=3 yrs), mandatory relocation.
     * 2. User blacklisted keywords check against title & description.
     * 3. AI semantic scoring: If [rerankerCtxPtr] != 0L, uses [LlamaBridge.scoreRelevance].
     * 4. Fallback: Keyword matching against target job titles.
     *
     * @param title Job title.
     * @param company Company name.
     * @param description Job description snippet or details text.
     * @param config Candidate profile and filter settings.
     * @param rerankerCtxPtr Native reranker context pointer (0L if not initialized).
     * @return true if the job is accepted, false if rejected.
     */
    suspend fun shouldApply(
        title: String,
        company: String,
        description: String,
        config: UserProfile,
        rerankerCtxPtr: Long = 0L
    ): Boolean {
        val cleanTitle = title.trim()
        val combinedText = "$cleanTitle $company $description".trim()

        if (cleanTitle.isEmpty()) {
            Log.d(TAG, "Empty job title, skipping")
            return false
        }

        // 1. Check built-in disqualifying roles (senior/lead/director etc.)
        for (pattern in DISQUALIFYING_ROLES) {
            if (pattern.matcher(cleanTitle).find()) {
                Log.i(TAG, "Rejected by role pattern (${pattern.pattern()}): '$cleanTitle'")
                return false
            }
        }

        // Check experience requirement patterns in combined text
        for (pattern in DISQUALIFYING_EXP) {
            val matcher = pattern.matcher(combinedText)
            while (matcher.find()) {
                val matchIdx = matcher.start()
                val start = (matchIdx - 30).coerceAtLeast(0)
                val end = (matcher.end() + 30).coerceAtMost(combinedText.length)
                val surrounding = combinedText.substring(start, end).lowercase()
                val isExpContext = listOf("experience", "exp", "background", "required", "qualifi")
                    .any { surrounding.contains(it) }
                if (isExpContext) {
                    Log.i(TAG, "Rejected by experience requirement (${matcher.group()}): '$cleanTitle'")
                    return false
                }
            }
        }

        // Check relocation patterns
        for (pattern in RELOCATION_TERMS) {
            if (pattern.matcher(combinedText).find()) {
                Log.i(TAG, "Rejected by relocation requirement: '$cleanTitle'")
                return false
            }
        }

        // 2. Check user blacklisted keywords from UserProfile
        val lowerCombined = combinedText.lowercase()
        for (blacklisted in config.blacklistedKeywords) {
            val trimmed = blacklisted.trim().lowercase()
            if (trimmed.isNotEmpty() && lowerCombined.contains(trimmed)) {
                Log.i(TAG, "Rejected by blacklisted keyword '$trimmed': '$cleanTitle'")
                return false
            }
        }

        // 3. AI Semantic match via Reranker model if context is active
        if (rerankerCtxPtr != 0L) {
            val candidateQuery = buildCandidateQuery(config)
            val docText = "Job Title: $cleanTitle. Company: $company. Details: ${description.take(500)}"
            try {
                val score = LlamaBridge.scoreRelevance(rerankerCtxPtr, candidateQuery, docText)
                Log.i(TAG, "Reranker score: $score for '$cleanTitle'")
                // Relevance threshold 0.40 (matches design spec & python bot prob)
                return score >= 0.40f
            } catch (e: Exception) {
                Log.w(TAG, "Reranker inference error, falling back to keyword matching: ${e.message}")
            }
        }

        // 4. Fallback: Keyword matching against target titles
        return matchTargetTitles(cleanTitle, config.targetTitles)
    }

    private fun buildCandidateQuery(config: UserProfile): String {
        val targets = config.targetTitles.joinToString(", ")
        return "Candidate Profile: Junior entry-level software/IT candidate (${config.experienceYears} yrs exp). Target titles: $targets. Remote preferred."
    }

    private fun matchTargetTitles(title: String, targetTitles: List<String>): Boolean {
        if (targetTitles.isEmpty()) return true

        val lowerTitle = title.lowercase()
        for (target in targetTitles) {
            val targetLower = target.trim().lowercase()
            if (targetLower.isEmpty()) continue

            // Direct substring match
            if (lowerTitle.contains(targetLower)) {
                return true
            }

            // Word-level partial match (all words in target title appear in job title)
            val words = targetLower.split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (words.isNotEmpty() && words.all { lowerTitle.contains(it) }) {
                return true
            }
        }

        Log.i(TAG, "No target title match found for '$title' in $targetTitles")
        return false
    }
}
