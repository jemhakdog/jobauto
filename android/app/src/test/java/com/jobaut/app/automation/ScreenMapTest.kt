package com.jobaut.app.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Constructor

class ScreenMapTest {

    private fun createElement(
        text: String = "",
        contentDesc: String = "",
        className: String = "android.widget.TextView",
        isClickable: Boolean = false,
        isCheckable: Boolean = false
    ): UIElement {
        // Construct dummy AccessibilityNodeInfo if possible, or create a mock/stub
        val node: AccessibilityNodeInfo = try {
            AccessibilityNodeInfo.obtain()
        } catch (e: Throwable) {
            val constructor: Constructor<AccessibilityNodeInfo> =
                AccessibilityNodeInfo::class.java.getDeclaredConstructor()
            constructor.isAccessible = true
            constructor.newInstance()
        }

        return UIElement(
            node = node,
            text = text,
            contentDescription = contentDesc,
            className = className,
            bounds = Rect(0, 0, 100, 100),
            isClickable = isClickable,
            isEditable = false,
            isCheckable = isCheckable,
            isChecked = false
        )
    }

    @Test
    fun testFindButtonDoesNotMatchApplicationsTab() {
        val applicationsTab = createElement(
            text = "Applications",
            className = "android.widget.TextView",
            isClickable = true
        )
        val appliedChip = createElement(
            text = "Applied",
            className = "android.widget.TextView",
            isClickable = true
        )

        val screenMap = ScreenMap(listOf(applicationsTab, appliedChip), "Applications Applied")

        val match = screenMap.findButton("apply")
        assertNull("findButton('apply') should not match 'Applications' or 'Applied'", match)
    }

    @Test
    fun testFindButtonMatchesQuickApplyAndApply() {
        val quickApplyBtn = createElement(
            text = "Quick Apply",
            className = "android.widget.Button",
            isClickable = true
        )
        val screenMap = ScreenMap(listOf(quickApplyBtn), "Quick Apply")

        val match = screenMap.findButton("quick apply")
        assertNotNull(match)
        assertEquals("Quick Apply", match?.label)
    }

    @Test
    fun testFindButtonIgnoresNonClickableText() {
        val nonClickableText = createElement(
            text = "Apply within 3 days",
            className = "android.widget.TextView",
            isClickable = false
        )
        val screenMap = ScreenMap(listOf(nonClickableText), "Apply within 3 days")

        val match = screenMap.findButton("apply")
        assertNull("findButton should not return non-interactive text", match)
    }
}
