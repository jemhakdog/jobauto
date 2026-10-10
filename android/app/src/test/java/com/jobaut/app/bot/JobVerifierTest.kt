package com.jobaut.app.bot

import com.jobaut.app.data.UserProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobVerifierTest {

    private val sampleProfile = UserProfile(
        fullName = "Test Candidate",
        email = "test@example.com",
        phone = "1234567890",
        targetTitles = listOf("Software Engineer", "Android Developer"),
        blacklistedKeywords = listOf("senior", "lead", "crypto")
    )

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
}
