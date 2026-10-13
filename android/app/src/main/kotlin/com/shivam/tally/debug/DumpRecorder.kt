package com.shivam.tally.debug

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Calibration mode. Appends JSON lines to <externalFilesDir>/dumps/dump_<time>.jsonl:
 *   {"t":"event", ...}     one light line per accessibility event (no tree walk)
 *   {"t":"snapshot", ...}  a full flattened tree of the active window + the window list
 *   {"t":"mark", ...}      a label you fired with `adb shell am broadcast` so dumps can be matched to what you did
 *
 * Snapshots are taken (a) on demand via broadcast and (b) automatically, throttled, after scroll / window-state
 * events so a swipe to the next Reel produces a before/after pair.
 */
class DumpRecorder(private val service: AccessibilityService, private val trackedPackages: Set<String>) {
    private val handler = Handler(Looper.getMainLooper())
    private var writer: FileWriter? = null
    var currentFile: File? = null
        private set
    private var bytes = 0L
    private var autoSnapshotPending = false
    private val versions = HashMap<String, String>()

    val isRunning get() = writer != null

    fun start() {
        if (writer != null) return
        val dir = File(service.getExternalFilesDir(null), "dumps").apply { mkdirs() }
        val f = File(dir, "dump_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jsonl")
        writer = FileWriter(f, true)
        currentFile = f
        bytes = 0
        line(JSONObject().put("t", "start").put("ts", System.currentTimeMillis())
            .put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("versions", JSONObject().also { j -> trackedPackages.forEach { j.put(it, versionOf(it)) } }))
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        autoSnapshotPending = false
        runCatching { writer?.close() }
        writer = null
    }

    fun mark(label: String) {
        if (!isRunning) return
        line(JSONObject().put("t", "mark").put("ts", System.currentTimeMillis()).put("label", label))
    }

    fun onEvent(e: AccessibilityEvent) {
        if (!isRunning) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg !in trackedPackages) return
        val j = JSONObject().put("t", "event").put("ts", System.currentTimeMillis())
            .put("type", AccessibilityEvent.eventTypeToString(e.eventType))
            .put("pkg", pkg).put("cls", e.className?.toString())
            .put("contentChange", e.contentChangeTypes)
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            j.put("scrollX", e.scrollX).put("scrollY", e.scrollY)
                .put("fromIndex", e.fromIndex).put("toIndex", e.toIndex).put("itemCount", e.itemCount)
        }
        if (e.text.isNotEmpty()) j.put("text", e.text.joinToString("|") { it.toString().take(60) })
        e.contentDescription?.let { j.put("desc", it.toString().take(80)) }
        e.source?.let { s ->
            j.put("srcId", s.viewIdResourceName).put("srcCls", s.className?.toString())
            @Suppress("DEPRECATION") s.recycle()
        }
        line(j)
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SELECTED -> scheduleAutoSnapshot()
        }
    }

    private fun scheduleAutoSnapshot() {
        if (autoSnapshotPending) return
        autoSnapshotPending = true
        handler.postDelayed({ autoSnapshotPending = false; snapshot("auto") }, 900)
    }

    fun snapshot(label: String) {
        if (!isRunning) return
        val root = service.rootInActiveWindow ?: run {
            line(JSONObject().put("t", "snapshot").put("ts", System.currentTimeMillis()).put("label", label).put("error", "no active window"))
            return
        }
        val pkg = root.packageName?.toString()
        if (pkg == null || pkg !in trackedPackages) {
            @Suppress("DEPRECATION") root.recycle()
            if (label != "auto") line(JSONObject().put("t", "snapshot").put("ts", System.currentTimeMillis()).put("label", label).put("pkg", pkg).put("skipped", "not a tracked package"))
            return
        }
        val nodes = JSONArray()
        try {
            walk(root, 0, nodes)
        } finally {
            @Suppress("DEPRECATION") root.recycle()
        }
        val windows = JSONArray()
        runCatching {
            service.windows.forEach { w ->
                windows.put(JSONObject().put("type", w.type).put("layer", w.layer).put("title", w.title?.toString())
                    .put("active", w.isActive).put("focused", w.isFocused))
                @Suppress("DEPRECATION") w.recycle()
            }
        }
        line(JSONObject().put("t", "snapshot").put("ts", System.currentTimeMillis()).put("label", label)
            .put("pkg", pkg).put("version", versionOf(pkg)).put("nodeCount", nodes.length())
            .put("windows", windows).put("nodes", nodes))
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, out: JSONArray) {
        if (out.length() >= MAX_NODES || depth > MAX_DEPTH) return
        val b = Rect().also { n.getBoundsInScreen(it) }
        val o = JSONObject().put("d", depth).put("cls", n.className?.toString()).put("id", n.viewIdResourceName)
            .put("b", JSONArray(listOf(b.left, b.top, b.right, b.bottom)))
            .put("n", n.childCount)
        n.contentDescription?.let { o.put("desc", it.toString().take(120)) }
        n.text?.let { o.put("text", it.toString().take(80)) }
        // Only emit flags when set, keeps dumps small.
        if (n.isSelected) o.put("sel", true)
        if (n.isVisibleToUser) o.put("vis", true)
        if (n.isChecked) o.put("chk", true)
        if (n.isFocused) o.put("foc", true)
        if (n.isClickable) o.put("clk", true)
        if (n.isScrollable) o.put("scr", true)
        n.paneTitle?.let { o.put("pane", it.toString()) }
        n.collectionInfo?.let { o.put("coll", JSONArray(listOf(it.rowCount, it.columnCount))) }
        n.collectionItemInfo?.let { o.put("item", JSONArray(listOf(it.rowIndex, it.columnIndex, it.isSelected))) }
        out.put(o)
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            try { walk(c, depth + 1, out) } finally { @Suppress("DEPRECATION") c.recycle() }
        }
    }

    private fun versionOf(pkg: String): String = versions.getOrPut(pkg) {
        try { service.packageManager.getPackageInfo(pkg, 0).versionName ?: "?" } catch (_: PackageManager.NameNotFoundException) { "not-installed" }
    }

    private fun line(j: JSONObject) {
        val w = writer ?: return
        if (bytes > MAX_BYTES) { stop(); return }
        try {
            val s = j.toString() + "\n"
            w.write(s); w.flush(); bytes += s.length
        } catch (_: Exception) { stop() }
    }

    companion object {
        private const val MAX_NODES = 2500
        private const val MAX_DEPTH = 40
        private const val MAX_BYTES = 80L * 1024 * 1024
        const val ACTION_MARK = "com.shivam.tally.MARK"
        const val ACTION_SNAPSHOT = "com.shivam.tally.SNAPSHOT"
    }
}
