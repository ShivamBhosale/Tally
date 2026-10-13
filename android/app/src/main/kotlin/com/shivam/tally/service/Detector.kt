package com.shivam.tally.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.shivam.tally.rules.AppRule
import com.shivam.tally.rules.Part

/** Result of looking at one window: is the Reels/Shorts player on screen, and which video is it (if resolvable). */
class Detection(val inPlayer: Boolean, val key: String?)

/**
 * Rule-driven detection. Uses findAccessibilityNodeInfosByViewId / ByText (native, no manual full-tree walk).
 * Every node obtained is recycled (a no-op on API 33+, required before).
 */
object Detector {
    @Suppress("DEPRECATION")
    private fun recycleAll(l: List<AccessibilityNodeInfo>?) = l?.forEach { it.recycle() }

    fun detect(root: AccessibilityNodeInfo, rule: AppRule): Detection {
        if (!playerVisible(root, rule)) return Detection(false, null)
        // Keep a slot per part (empty when unreadable) so identity comparison never shifts positions.
        val values = rule.parts.map { resolve(root, rule, it)?.takeIf { v -> v.isNotBlank() } ?: "" }
        return Detection(true, if (values.all { it.isEmpty() }) null else values.joinToString(com.shivam.tally.core.CountingEngine.SEP.toString()))
    }

    private fun onScreen(n: AccessibilityNodeInfo, rule: AppRule): Boolean {
        if (!n.isVisibleToUser) return false
        val r = Rect().also { n.getBoundsInScreen(it) }
        return r.width() >= rule.minW && r.height() >= rule.minH
    }

    private fun playerVisible(root: AccessibilityNodeInfo, rule: AppRule): Boolean {
        for (id in rule.playerIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId("${rule.pkg}:id/$id")
            try { if (nodes.any { onScreen(it, rule) }) return true } finally { recycleAll(nodes) }
        }
        return false
    }

    private fun resolve(root: AccessibilityNodeInfo, rule: AppRule, part: Part): String? {
        val nodes = when {
            part.id != null -> root.findAccessibilityNodeInfosByViewId("${rule.pkg}:id/${part.id}")
            part.match != null -> root.findAccessibilityNodeInfosByText(part.match)
            else -> return null
        }
        try {
            for (n in nodes) {
                if (part.visibleOnly && !n.isVisibleToUser) continue
                val raw = if (part.descendant) firstDescendantValue(n, part.field) else value(n, part.field)
                if (raw.isNullOrBlank()) continue
                if (part.match != null && !raw.startsWith(part.match)) continue
                return if (part.after != null && raw.contains(part.after)) raw.substringAfterLast(part.after) else raw
            }
        } finally { recycleAll(nodes) }
        return null
    }

    private fun value(n: AccessibilityNodeInfo, field: String): String? =
        (if (field == "text") n.text else n.contentDescription)?.toString()

    /** Breadth-first, capped so a huge subtree can never make this expensive. */
    @Suppress("DEPRECATION")
    private fun firstDescendantValue(n: AccessibilityNodeInfo, field: String): String? {
        value(n, field)?.takeIf { it.isNotBlank() }?.let { return it }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        var seen = 0
        try {
            while (queue.isNotEmpty() && seen < 40) {
                val c = queue.removeFirst(); seen++
                try {
                    value(c, field)?.takeIf { it.isNotBlank() }?.let { return it }
                    for (i in 0 until c.childCount) c.getChild(i)?.let { queue.add(it) }
                } finally { c.recycle() }
            }
        } finally { queue.forEach { it.recycle() } }
        return null
    }
}
