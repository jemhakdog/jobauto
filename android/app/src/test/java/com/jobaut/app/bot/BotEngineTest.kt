package com.jobaut.app.bot

import android.graphics.Rect
import com.jobaut.app.automation.ScreenMap
import com.jobaut.app.automation.UIElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotEngineTest {

    private fun createElement(
        text: String = "",
        contentDesc: String = "",
        className: String = "android.widget.TextView",
        isClickable: Boolean = false,
        isEditable: Boolean = false,
        isCheckable: Boolean = false,
        bounds: Rect = Rect()
    ): UIElement {
        return UIElement(
            text = text,
            contentDescription = contentDesc,
            className = className,
            bounds = bounds,
            isClickable = isClickable,
            isEditable = isEditable,
            isCheckable = isCheckable
        )
    }

    @Test
    fun testFindNewToYouTab() {
        val newToYouTab = createElement(
            text = "New to you",
            className = "android.widget.TextView",
            isClickable = true,
            bounds = Rect(200, 300, 400, 400)
        )
        val allJobsTab = createElement(
            text = "All jobs",
            className = "android.widget.TextView",
            isClickable = true,
            bounds = Rect(0, 300, 200, 400)
        )
        val screenMap = ScreenMap(listOf(allJobsTab, newToYouTab), "All jobs New to you")

        val match = BotEngine.findNewToYouTab(screenMap)
        assertNotNull("Should locate 'New to you' tab", match)
        assertEquals("New to you", match?.label)
    }

    @Test
    fun testFindNewToYouTabIgnoresBottomNav() {
        val footerText = createElement(
            text = "New to you",
            className = "android.widget.TextView",
            isClickable = true,
            bounds = Rect(0, 1900, 200, 2100) // Lower portion (> 700)
        )
        val screenMap = ScreenMap(listOf(footerText), "New to you")

        val match = BotEngine.findNewToYouTab(screenMap)
        assertNull("Should ignore elements not in top search/tab area", match)
    }

    @Test
    fun testFindSearchBarContinueSearch() {
        val continueSearch = createElement(
            text = "Continue your job search",
            className = "android.widget.TextView",
            isClickable = true,
            bounds = Rect(50, 200, 1000, 350)
        )
        val screenMap = ScreenMap(listOf(continueSearch), "Continue your job search")

        val match = BotEngine.findSearchBarElement(screenMap)
        assertNotNull("Should find 'Continue your job search' search bar", match)
        assertEquals("Continue your job search", match?.label)
    }

    @Test
    fun testFindSearchBarSearchJobsPrompt() {
        val searchPrompt = createElement(
            text = "Search jobs",
            className = "android.widget.TextView",
            isClickable = true,
            bounds = Rect(50, 200, 1000, 350)
        )
        val screenMap = ScreenMap(listOf(searchPrompt), "Search jobs")

        val match = BotEngine.findSearchBarElement(screenMap)
        assertNotNull("Should find 'Search jobs' prompt", match)
        assertEquals("Search jobs", match?.label)
    }

    @Test
    fun testFindSearchBarDoesNotMatchBottomNavSearch() {
        val bottomNavSearch = createElement(
            text = "Search",
            className = "android.widget.TextView",
            isClickable = true,
            bounds = Rect(0, 2000, 200, 2200)
        )
        val screenMap = ScreenMap(listOf(bottomNavSearch), "Search")

        val match = BotEngine.findSearchBarElement(screenMap)
        assertNull("Should not match bottom navigation 'Search' tab", match)
    }

    @Test
    fun testFindKeywordInput() {
        val input = createElement(
            text = "",
            className = "android.widget.EditText",
            isEditable = true,
            bounds = Rect(50, 150, 900, 300)
        )
        val screenMap = ScreenMap(listOf(input), "")

        val match = BotEngine.findKeywordInput(screenMap)
        assertNotNull("Should find keyword input EditText", match)
    }

    @Test
    fun testFindSeekButton() {
        val seekBtn = createElement(
            text = "Seek",
            className = "android.widget.Button",
            isClickable = true,
            bounds = Rect(50, 500, 1000, 650)
        )
        val bottomNavSearch = createElement(
            text = "Search",
            className = "android.widget.TextView",
            isClickable = true,
            bounds = Rect(0, 2000, 200, 2200)
        )
        val screenMap = ScreenMap(listOf(seekBtn, bottomNavSearch), "Seek Search")

        val match = BotEngine.findSeekButton(screenMap)
        assertNotNull("Should find 'Seek' button", match)
        assertEquals("Seek", match?.label)
    }

    @Test
    fun testFindClearAllButton() {
        val clearBtn = createElement(
            text = "Clear all",
            className = "android.widget.Button",
            isClickable = true,
            bounds = Rect(800, 200, 1000, 300)
        )
        val screenMap = ScreenMap(listOf(clearBtn), "Clear all")

        val match = BotEngine.findClearAllButton(screenMap)
        assertNotNull("Should find 'Clear all' button", match)
        assertEquals("Clear all", match?.label)
    }

    @Test
    fun testIsHomeScreenDetectsContinueYourJobSearch() {
        val continueSearch = createElement(
            text = "Continue your job search",
            className = "android.widget.TextView",
            isClickable = true,
            bounds = Rect(50, 200, 1000, 350)
        )
        val screenMap = ScreenMap(listOf(continueSearch), "Continue your job search\nRecommended jobs")

        assertTrue("Should identify Home Screen with 'Continue your job search'", BotEngine.isHomeScreen(screenMap))
    }

    @Test
    fun testIsHomeScreenRejectsWhenSearchResultsOrNewToYouPresent() {
        val newToYouTab = createElement(
            text = "New to you",
            className = "android.widget.TextView",
            isClickable = true,
            bounds = Rect(200, 300, 400, 400)
        )
        val screenMap = ScreenMap(listOf(newToYouTab), "Search results\nNew to you")

        assertFalse("Should not identify as Home Screen when New to you / Search results are active", BotEngine.isHomeScreen(screenMap))
    }

    @Test
    fun testIsSearchFormScreen() {
        val input = createElement(
            text = "",
            className = "android.widget.EditText",
            isEditable = true,
            bounds = Rect(50, 150, 900, 300)
        )
        val seekBtn = createElement(
            text = "Seek",
            className = "android.widget.Button",
            isClickable = true,
            bounds = Rect(50, 500, 1000, 650)
        )
        val screenMap = ScreenMap(listOf(input, seekBtn), "Describe what you're looking for\nSeek")

        assertTrue("Should detect Search Form screen with Seek button and search prompt", BotEngine.isSearchFormScreen(screenMap))
    }
}
