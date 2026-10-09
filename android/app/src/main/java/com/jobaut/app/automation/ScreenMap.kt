package com.jobaut.app.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Immutable representation of a UI element extracted from AccessibilityNodeInfo.
 */
data class UIElement(
    val node: AccessibilityNodeInfo,
    val text: String,
    val contentDescription: String,
    val className: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean
) {
    /**
     * Center X coordinate of this element's bounds.
     */
    val centerX: Int get() = bounds.centerX()

    /**
     * Center Y coordinate of this element's bounds.
     */
    val centerY: Int get() = bounds.centerY()

    /**
     * Convenient display string representation.
     */
    val label: String
        get() = when {
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            else -> ""
        }
}

/**
 * Snapshot of the current screen containing parsed UI elements and raw aggregated text.
 */
class ScreenMap(
    val elements: List<UIElement>,
    val rawText: String,
    val packageName: String = ""
) {
    /**
     * Finds a clickable or button element whose text or contentDescription matches [name] (case-insensitive).
     * If no clickable match is found, falls back to any element matching [name].
     */
    fun findButton(name: String): UIElement? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null

        // Priority 1: Clickable or checkable element matching exactly or containing the text
        val clickableMatch = elements.firstOrNull { elem ->
            (elem.isClickable || elem.isCheckable) && (
                elem.text.contains(trimmed, ignoreCase = true) ||
                elem.contentDescription.contains(trimmed, ignoreCase = true)
            )
        }
        if (clickableMatch != null) return clickableMatch

        // Priority 2: Button/ImageButton class name match
        val buttonClassMatch = elements.firstOrNull { elem ->
            elem.className.contains("Button", ignoreCase = true) && (
                elem.text.contains(trimmed, ignoreCase = true) ||
                elem.contentDescription.contains(trimmed, ignoreCase = true)
            )
        }
        if (buttonClassMatch != null) return buttonClassMatch

        // Priority 3: Any element containing the text/description
        return elements.firstOrNull { elem ->
            elem.text.contains(trimmed, ignoreCase = true) ||
            elem.contentDescription.contains(trimmed, ignoreCase = true)
        }
    }

    /**
     * Finds any element whose text or contentDescription contains [text] (case-insensitive).
     */
    fun findText(text: String): UIElement? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return elements.firstOrNull { elem ->
            elem.text.contains(trimmed, ignoreCase = true) ||
            elem.contentDescription.contains(trimmed, ignoreCase = true)
        }
    }

    /**
     * Finds all editable or input elements (e.g. EditText, editable nodes).
     */
    fun findInputs(): List<UIElement> {
        return elements.filter { elem ->
            elem.isEditable || elem.className.contains("EditText", ignoreCase = true)
        }
    }

    /**
     * Returns all elements matching a predicate.
     */
    fun filter(predicate: (UIElement) -> Boolean): List<UIElement> {
        return elements.filter(predicate)
    }

    companion object {
        /**
         * Recursively parses an AccessibilityNodeInfo hierarchy into an immutable ScreenMap.
         * Safe against nulls, cycles, and empty trees.
         */
        fun fromNode(root: AccessibilityNodeInfo?): ScreenMap {
            if (root == null) {
                return ScreenMap(emptyList(), "")
            }

            val collectedElements = mutableListOf<UIElement>()
            val textBuilder = StringBuilder()
            val visited = HashSet<Int>()

            fun traverse(node: AccessibilityNodeInfo?) {
                if (node == null) return

                val nodeId = System.identityHashCode(node)
                if (visited.contains(nodeId)) {
                    return
                }
                visited.add(nodeId)

                val nodeText = node.text?.toString()?.trim() ?: ""
                val nodeDesc = node.contentDescription?.toString()?.trim() ?: ""
                val className = node.className?.toString() ?: ""

                val rect = Rect()
                node.getBoundsInScreen(rect)
                val boundsCopy = Rect(rect)

                val isClickable = node.isClickable
                val isEditable = node.isEditable
                val isCheckable = node.isCheckable
                val isChecked = node.isChecked

                // We collect elements that have meaningful text, description, or interactivity
                if (nodeText.isNotEmpty() || nodeDesc.isNotEmpty() || isClickable || isEditable || isCheckable) {
                    val element = UIElement(
                        node = node,
                        text = nodeText,
                        contentDescription = nodeDesc,
                        className = className,
                        bounds = boundsCopy,
                        isClickable = isClickable,
                        isEditable = isEditable,
                        isCheckable = isCheckable,
                        isChecked = isChecked
                    )
                    collectedElements.add(element)
                }

                if (nodeText.isNotEmpty()) {
                    if (textBuilder.isNotEmpty()) {
                        textBuilder.append("\n")
                    }
                    textBuilder.append(nodeText)
                } else if (nodeDesc.isNotEmpty()) {
                    if (textBuilder.isNotEmpty()) {
                        textBuilder.append("\n")
                    }
                    textBuilder.append(nodeDesc)
                }

                val childCount = node.childCount
                for (i in 0 until childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        traverse(child)
                    }
                }
            }

            traverse(root)

            val rootPackage = root?.packageName?.toString() ?: ""

            return ScreenMap(
                elements = collectedElements,
                rawText = textBuilder.toString(),
                packageName = rootPackage
            )
        }
    }
}
