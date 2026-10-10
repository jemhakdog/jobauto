package com.jobaut.app.bot

import com.jobaut.app.data.UserProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobVerifierTest {

    private val sampleProfile = UserProfile(
        name = "Test Candidate",
        email = "test@example.com",
        phone = "1234567890",
        targetTitles = listOf("Software Engineer", "Android Developer"),
        blacklistedKeywords = listOf("senior", "lead", "crypto")
    )

    @After
    fun tearDown() {
        JobVerifier.rerankerScoreProvider = null
    }

    @Test
    fun testRejectsSeniorRole() = runBlocking {
        val result = JobVerifier.shouldApply(
            title = "Senior Android Developer",
            company = "Tech Corp",
            description = "Looking for an experienced engineer",
            config = sampleProfile
        )
        assertFalse("Should reject Senior role", result)
    }

    @Test
    fun testRejectsBlacklistedKeyword() = runBlocking {
        val result = JobVerifier.shouldApply(
            title = "Android Developer",
            company = "Crypto Startup",
            description = "Building blockchain and crypto wallet apps",
            config = sampleProfile
        )
        assertFalse("Should reject job containing blacklisted keyword 'crypto'", result)
    }

    @Test
    fun testAcceptsMatchingTargetRole() = runBlocking {
        val result = JobVerifier.shouldApply(
            title = "Junior Android Developer",
            company = "Mobile Studio",
            description = "Build awesome Kotlin apps",
            config = sampleProfile
        )
        assertTrue("Should accept matching role", result)
    }

    @Test
    fun testRerankerContextZeroFallbackMatchesTargetTitle() = runBlocking {
        val result = JobVerifier.shouldApply(
            title = "Software Engineer - Entry Level",
            company = "Tech Corp",
            description = "Great entry level software engineering role",
            config = sampleProfile,
            rerankerCtxPtr = 0L
        )
        assertTrue("Should accept when reranker context is 0 and title matches targetTitles", result)
    }

    @Test
    fun testRerankerContextZeroFallbackRejectsNonMatchingTitle() = runBlocking {
        val result = JobVerifier.shouldApply(
            title = "Sales Representative",
            company = "Tech Corp",
            description = "Inside sales specialist",
            config = sampleProfile,
            rerankerCtxPtr = 0L
        )
        assertFalse("Should reject when reranker context is 0 and title does not match targetTitles", result)
    }

    @Test
    fun testHighRerankerScoreAcceptsEvenIfTargetTitleDiffers() = runBlocking {
        JobVerifier.rerankerScoreProvider = { _, _, _ -> 0.85f }
        val result = JobVerifier.shouldApply(
            title = "Mobile Applications Specialist",
            company = "App Studio",
            description = "Work on mobile app architecture",
            config = sampleProfile,
            rerankerCtxPtr = 12345L
        )
        assertTrue("Should accept when reranker score >= 0.40f", result)
    }

    @Test
    fun testLowRerankerScoreWithMatchingTargetTitleAccepts() = runBlocking {
        // Reranker returned low/borderline score (0.20f), but title matches candidate target
        JobVerifier.rerankerScoreProvider = { _, _, _ -> 0.20f }
        val result = JobVerifier.shouldApply(
            title = "Junior Android Developer",
            company = "Mobile Studio",
            description = "General coding role",
            config = sampleProfile,
            rerankerCtxPtr = 12345L
        )
        assertTrue("Should accept via targetTitle fallback when reranker score is low", result)
    }

    @Test
    fun testLowRerankerScoreWithNonMatchingTargetTitleRejects() = runBlocking {
        // Reranker returned low/borderline score (0.20f) and title does NOT match candidate target
        JobVerifier.rerankerScoreProvider = { _, _, _ -> 0.20f }
        val result = JobVerifier.shouldApply(
            title = "Database Administrator",
            company = "Data Corp",
            description = "Manage SQL databases",
            config = sampleProfile,
            rerankerCtxPtr = 12345L
        )
        assertFalse("Should reject when reranker score is low and title does not match targetTitles", result)
    }

    @Test
    fun testZeroRerankerScoreWithMatchingTargetTitleAccepts() = runBlocking {
        // Reranker returned neutral/uninitialized 0.0f score
        JobVerifier.rerankerScoreProvider = { _, _, _ -> 0.0f }
        val result = JobVerifier.shouldApply(
            title = "Software Engineer",
            company = "Tech Hub",
            description = "Junior development",
            config = sampleProfile,
            rerankerCtxPtr = 12345L
        )
        assertTrue("Should accept via targetTitle fallback when reranker score is 0.0f", result)
    }

    @Test
    fun testZeroRerankerScoreWithNonMatchingTargetTitleRejects() = runBlocking {
        JobVerifier.rerankerScoreProvider = { _, _, _ -> 0.0f }
        val result = JobVerifier.shouldApply(
            title = "Graphic Designer",
            company = "Creative Agency",
            description = "Design UI assets",
            config = sampleProfile,
            rerankerCtxPtr = 12345L
        )
        assertFalse("Should reject when reranker score is 0.0f and title does not match", result)
    }

    @Test
    fun testRerankerErrorWithMatchingTargetTitleAccepts() = runBlocking {
        // Simulate exception during reranker inference
        JobVerifier.rerankerScoreProvider = { _, _, _ ->
            throw RuntimeException("Native llama scoring failed")
        }
        val result = JobVerifier.shouldApply(
            title = "Android Developer",
            company = "Tech Corp",
            description = "Kotlin app developer",
            config = sampleProfile,
            rerankerCtxPtr = 12345L
        )
        assertTrue("Should accept via targetTitle fallback when reranker throws error", result)
    }

    @Test
    fun testRerankerErrorWithNonMatchingTargetTitleRejects() = runBlocking {
        // Simulate exception during reranker inference
        JobVerifier.rerankerScoreProvider = { _, _, _ ->
            throw RuntimeException("Native llama scoring failed")
        }
        val result = JobVerifier.shouldApply(
            title = "Marketing Specialist",
            company = "Ad Agency",
            description = "Digital marketing",
            config = sampleProfile,
            rerankerCtxPtr = 12345L
        )
        assertFalse("Should reject when reranker throws error and title does not match", result)
    }

    @Test
    fun testBorderlineRerankerScoreAtThreshold() = runBlocking {
        JobVerifier.rerankerScoreProvider = { _, _, _ -> 0.40f }
        val atThreshold = JobVerifier.shouldApply(
            title = "Mobile Specialist",
            company = "Tech Corp",
            description = "Mobile systems",
            config = sampleProfile,
            rerankerCtxPtr = 12345L
        )
        assertTrue("Should accept when reranker score is exactly 0.40f", atThreshold)

        JobVerifier.rerankerScoreProvider = { _, _, _ -> 0.39f }
        val justBelowMatching = JobVerifier.shouldApply(
            title = "Android Developer",
            company = "Tech Corp",
            description = "Mobile systems",
            config = sampleProfile,
            rerankerCtxPtr = 12345L
        )
        assertTrue("Should accept 0.39f score if target title matches", justBelowMatching)

        val justBelowNonMatching = JobVerifier.shouldApply(
            title = "Mobile Specialist",
            company = "Tech Corp",
            description = "Mobile systems",
            config = sampleProfile,
            rerankerCtxPtr = 12345L
        )
        assertFalse("Should reject 0.39f score if target title does not match", justBelowNonMatching)
    }
}
