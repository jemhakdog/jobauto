package com.jobaut.app.bot

import com.jobaut.app.data.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionReasonerTest {

    private val sampleProfile = UserProfile(
        name = "Jem Carlo G. Austria",
        email = "austriajemcarlo@gmail.com",
        phone = "09461234567",
        experienceYears = 0,
        noticePeriod = "Less than 2 weeks",
        expectedSalary = "₱25,000",
        targetTitles = listOf("Software Engineer", "Android Developer")
    )

    @Test
    fun testBuildPromptFormatsWithChatMLTags() {
        val question = "How many years of Kotlin experience do you have?"
        val prompt = QuestionReasoner.buildPrompt(question, sampleProfile)

        // Verify ChatML system block
        assertTrue("Prompt must start with ChatML system tag", prompt.startsWith("<|im_start|>system\n"))
        assertTrue("Prompt must close system block with <|im_end|>", prompt.contains("<|im_end|>\n<|im_start|>user\n"))

        // Verify Candidate Profile interpolation
        assertTrue("Prompt must include candidate name", prompt.contains("- Name: Jem Carlo G. Austria"))
        assertTrue("Prompt must include candidate experience", prompt.contains("- Years of Experience: 0"))
        assertTrue("Prompt must include candidate notice period", prompt.contains("- Notice Period: Less than 2 weeks"))
        assertTrue("Prompt must include candidate expected salary", prompt.contains("- Expected Salary: ₱25,000"))

        // Verify question and assistant block
        assertTrue("Prompt must include question", prompt.contains("Question: $question"))
        assertTrue("Prompt must close user block and initiate assistant", prompt.contains("Answer:<|im_end|>\n<|im_start|>assistant\n"))
        assertTrue("Prompt must end with assistant tag and newline", prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun testParseLlmResponseCleansChatMLStopTokens() {
        assertEquals("Yes, 2 years", QuestionReasoner.parseLlmResponse("Yes, 2 years.<|im_end|>"))
        assertEquals("No", QuestionReasoner.parseLlmResponse("No<|endoftext|>"))
        assertEquals("Less than 1 year", QuestionReasoner.parseLlmResponse("<|im_start|>assistant\nLess than 1 year<|im_end|>"))
    }

    @Test
    fun testParseLlmResponseStripsAssistantPrefix() {
        assertEquals("Remote only", QuestionReasoner.parseLlmResponse("assistant: Remote only"))
        assertEquals("Remote only", QuestionReasoner.parseLlmResponse("assistant\nRemote only"))
        assertEquals("Remote only", QuestionReasoner.parseLlmResponse("assistant: Remote only<|im_end|>"))
    }

    @Test
    fun testParseLlmResponseExtractsAfterAnswerPrefix() {
        assertEquals("₱25,000", QuestionReasoner.parseLlmResponse("Answer: ₱25,000<|im_end|>"))
        assertEquals("Immediate", QuestionReasoner.parseLlmResponse("assistant\nAnswer: Immediate"))
    }

    @Test
    fun testParseLlmResponseStripsMarkdownTicksAndQuotes() {
        assertEquals("1 year", QuestionReasoner.parseLlmResponse("`1 year`"))
        assertEquals("Yes", QuestionReasoner.parseLlmResponse("\"Yes\""))
        assertEquals("No", QuestionReasoner.parseLlmResponse("'No'"))
        assertEquals("Less than 2 weeks", QuestionReasoner.parseLlmResponse("**Less than 2 weeks**"))
        assertEquals("No experience", QuestionReasoner.parseLlmResponse("```\nNo experience\n```"))
    }

    @Test
    fun testParseLlmResponseMultiLineExtractsFirstLine() {
        val multiline = """
            0 years
            I have academic and personal project experience only.
        """.trimIndent()
        assertEquals("0 years", QuestionReasoner.parseLlmResponse(multiline))
    }

    @Test
    fun testParseLlmResponseHandlesEmptyAndStopTokensOnly() {
        assertEquals("", QuestionReasoner.parseLlmResponse("<|im_end|>"))
        assertEquals("", QuestionReasoner.parseLlmResponse("<|im_start|>assistant\n<|im_end|>"))
        assertEquals("", QuestionReasoner.parseLlmResponse("assistant"))
    }
}
