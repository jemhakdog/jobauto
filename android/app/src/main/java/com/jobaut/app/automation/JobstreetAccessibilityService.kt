package com.jobaut.app.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Native Android Accessibility Service for automating Jobstreet / Seek job search and applications.
 *
 * Replaces legacy ADB commands (`adb shell input tap`, `uiautomator dump`, `input text`, `input keyevent 4`, etc.)
 * with high-performance, in-process accessibility node actions and gesture dispatches.
 */
class JobstreetAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "JobstreetAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event stream handling if needed for state tracking
    }

    override fun onInterrupt() {
        Log.w(TAG, "JobstreetAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "JobstreetAccessibilityService unbound")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "JobstreetAccessibilityService destroyed")
        instance = null
    }

    /**
     * Captures a snapshot of the current active window hierarchy as an immutable [ScreenMap].
     */
    fun getScreenMap(): ScreenMap {
        val root = rootInActiveWindow
        return ScreenMap.fromNode(root)
    }

    /**
     * Performs a click action on the specified [AccessibilityNodeInfo].
     *
     * 1. Attempts direct `ACTION_CLICK` on the node.
     * 2. If node is not clickable or direct click fails, traverses up parents to find a clickable ancestor.
     * 3. If no clickable ancestor succeeds, dispatches a hardware-level tap gesture at the node's center coordinate.
     *
     * @param node The accessibility node to click.
     * @return true if the click action or gesture succeeded, false otherwise.
     */
    fun click(node: AccessibilityNodeInfo): Boolean {
        // 1. Try direct click
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // 2. Try climbing up parent hierarchy to find clickable container
        var current: AccessibilityNodeInfo? = node.parent
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            val parent = current.parent
            current = parent
        }

        // 3. Fallback: gesture tap at center bounds
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            return dispatchClickGesture(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }

        return false
    }

    /**
     * Enters text into the specified [AccessibilityNodeInfo].
     *
     * Uses `ACTION_SET_TEXT` with the argument bundle.
     *
     * @param node The editable accessibility node.
     * @param text The text string to set.
     * @return true if successful, false otherwise.
     */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Clears text in the specified [AccessibilityNodeInfo].
     */
    fun clearText(node: AccessibilityNodeInfo): Boolean {
        return setText(node, "")
    }

    /**
     * Scrolls down the current screen.
     *
     * 1. Searches for a scrollable node in active window and invokes `ACTION_SCROLL_FORWARD`.
     * 2. If no scrollable node or action returns false, dispatches a vertical swipe gesture from bottom to top.
     *
     * @return true if scrolling succeeded, false otherwise.
     */
    fun scrollDown(): Boolean {
        val root = rootInActiveWindow
        val scrollableNode = findScrollableNode(root)
        if (scrollableNode != null && scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            return true
        }

        // Gesture fallback: Swipe up (moves content down)
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val startX = width / 2f
        val startY = height * 0.75f
        val endX = width / 2f
        val endY = height * 0.25f

        return dispatchSwipeGesture(startX, startY, endX, endY, 300L)
    }

    /**
     * Scrolls up the current screen.
     *
     * 1. Searches for a scrollable node in active window and invokes `ACTION_SCROLL_BACKWARD`.
     * 2. If no scrollable node or action returns false, dispatches a vertical swipe gesture from top to bottom.
     *
     * @return true if scrolling succeeded, false otherwise.
     */
    fun scrollUp(): Boolean {
        val root = rootInActiveWindow
        val scrollableNode = findScrollableNode(root)
        if (scrollableNode != null && scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            return true
        }

        // Gesture fallback: Swipe down (moves content up)
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val startX = width / 2f
        val startY = height * 0.25f
        val endX = width / 2f
        val endY = height * 0.75f

        return dispatchSwipeGesture(startX, startY, endX, endY, 300L)
    }

    /**
     * Navigates back by performing the system global back action.
     *
     * @return true if the global back action succeeded.
     */
    fun goBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Dispatches a tap gesture at the specified screen coordinate.
     */
    fun dispatchClickGesture(x: Float, y: Float, durationMs: Long = 50L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGestureSync(gesture)
    }

    /**
     * Dispatches a linear swipe gesture between two points on the screen.
     */
    fun dispatchSwipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300L
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGestureSync(gesture)
    }

    /**
     * Synchronously dispatches an accessibility gesture with a short latch timeout.
     */
    private fun dispatchGestureSync(gesture: GestureDescription, timeoutMs: Long = 1000L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val latch = CountDownLatch(1)
        var result = false

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                result = false
                latch.countDown()
            }
        }

        val dispatched = dispatchGesture(gesture, callback, null)
        if (!dispatched) {
            return false
        }

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }

        return result
    }

    /**
     * Recursively locates the first scrollable node in the given hierarchy.
     */
    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node

        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i)
            val scrollable = findScrollableNode(child)
            if (scrollable != null) return scrollable
        }
        return null
    }

    companion object {
        private const val TAG = "JobstreetAccessibility"

        /**
         * Singleton instance set when accessibility service is connected and active.
         */
        @Volatile
        var instance: JobstreetAccessibilityService? = null
            internal set

        /**
         * Returns true if the accessibility service is actively connected and ready for automation.
         */
        val isConnected: Boolean
            get() = instance != null
    }
}
