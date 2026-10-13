package com.shivam.tally.core

/**
 * Dwell / dedupe / debounce logic. Pure Kotlin, time is injected (nowMs) so it is fully testable.
 *
 * The caller (the accessibility service) feeds it three kinds of signal:
 *  - [onVideoShown]: a Reel/Short is on screen. [key] is an identity (author+caption, pager id...) or
 *    null when the detector can only tell "a page change happened".
 *  - [onPlayerExited] / [onScreenOff]: the player is gone.
 *  - [tick]: time passing. The caller should schedule a tick at [nextDeadlineMs].
 */
class CountingEngine(private val listener: Listener) {

    interface Listener {
        /** A view just crossed the dwell threshold. */
        fun onViewCounted(view: View, atMs: Long)

        /** A view ended. [counted] tells whether it had been counted; [dwellMs] is its total time on screen. */
        fun onViewEnded(view: View, dwellMs: Long, counted: Boolean) {}
    }

    data class View(val seq: Long, val app: TrackedApp, val key: String?, val startMs: Long)

    private class Active(val view: View, var key: String?, val suppressed: Boolean) {
        var counted = false
        val pending get() = !counted && !suppressed
        fun resolved() = view.copy(key = key)
    }

    var minDwellMs: Long = 2_000
        set(v) { field = v.coerceIn(MIN_DWELL_MS, MAX_DWELL_MS) }

    /** Same identity key counted again inside this window is ignored. */
    var dedupeWindowMs: Long = 30_000

    /** Page-change signals without identity closer together than this are treated as one swipe. */
    var switchDebounceMs: Long = 350

    var enabledApps: Set<TrackedApp> = TrackedApp.entries.toSet()

    private var active: Active? = null
    private var seq = 0L
    private data class Counted(val app: TrackedApp, val key: String)
    private val lastCountedAt = LinkedHashMap<Counted, Long>()

    val activeApp: TrackedApp? get() = active?.view?.app

    /** When the caller must call [tick] next, or null if nothing is pending. */
    fun nextDeadlineMs(): Long? =
        active?.takeIf { it.pending }?.let { it.view.startMs + minDwellMs }

    fun tick(nowMs: Long) {
        val a = active ?: return
        if (a.pending && nowMs - a.view.startMs >= minDwellMs) {
            a.counted = true
            a.key?.let { remember(a.view.app, it, nowMs) }
            listener.onViewCounted(a.resolved(), nowMs)
        }
    }

    fun onVideoShown(app: TrackedApp, key: String?, nowMs: Long) {
        tick(nowMs)
        if (app !in enabledApps) {
            endCurrent(nowMs)
            return
        }
        val cur = active
        if (cur != null && cur.view.app == app) {
            val age = nowMs - cur.view.startMs
            when {
                key != null && cur.key != null && sameVideo(key, cur.key!!) -> { cur.key = merge(cur.key!!, key); return }
                // Identity resolved a moment after the page appeared: same video, not a new one.
                key != null && cur.key == null && age < switchDebounceMs -> { cur.key = key; return }
                key == null && age < switchDebounceMs -> return
                key == null && cur.key != null -> { /* anonymous page change while we had an identity */ }
            }
        }
        endCurrent(nowMs)
        val view = View(++seq, app, key, nowMs)
        // A recently counted identity is shown again (back-swipe, re-render): never count it twice.
        val suppressed = key != null && lastCountedAt.any { (k, at) ->
            k.app == app && nowMs - at < dedupeWindowMs && sameVideo(k.key, key)
        }
        active = Active(view, key, suppressed)
    }

    fun onPlayerExited(nowMs: Long) {
        tick(nowMs)
        endCurrent(nowMs)
    }

    fun onScreenOff(nowMs: Long) = onPlayerExited(nowMs)

    private fun endCurrent(nowMs: Long) {
        val a = active ?: return
        active = null
        listener.onViewEnded(a.resolved(), nowMs - a.view.startMs, a.counted)
    }

    private fun remember(app: TrackedApp, key: String, nowMs: Long) {
        lastCountedAt.keys.removeAll { it.app == app && sameVideo(it.key, key) }
        lastCountedAt[Counted(app, key)] = nowMs
        while (lastCountedAt.size > MAX_REMEMBERED) lastCountedAt.remove(lastCountedAt.keys.first())
    }

    private fun segs(k: String) = k.split(SEP).map { it.trim().trimStart('@').lowercase() }

    /** Fill this key's empty slots from [other] (parts resolve one by one while a video is on screen). */
    private fun merge(a: String, b: String): String {
        val x = a.split(SEP); val y = b.split(SEP)
        return (0 until maxOf(x.size, y.size)).joinToString(SEP.toString()) { i ->
            x.getOrNull(i)?.takeIf { it.isNotBlank() } ?: y.getOrNull(i) ?: ""
        }
    }

    /**
     * Keys are SEP-joined identity slots (e.g. channel, duration); an unreadable slot is empty but keeps its position.
     * Two keys are the same video unless some slot is non-empty in BOTH and differs.
     */
    private fun sameVideo(a: String, b: String): Boolean {
        val x = segs(a); val y = segs(b)
        val n = minOf(x.size, y.size)
        return n > 0 && (0 until n).all { x[it].isEmpty() || y[it].isEmpty() || x[it] == y[it] }
    }

    companion object {
        const val SEP = '\u001F'
        const val MIN_DWELL_MS = 1_000L
        const val MAX_DWELL_MS = 10_000L
        private const val MAX_REMEMBERED = 64
    }
}
