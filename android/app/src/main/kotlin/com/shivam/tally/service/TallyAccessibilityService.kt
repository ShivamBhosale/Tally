package com.shivam.tally.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.shivam.tally.core.CountingEngine
import com.shivam.tally.core.TrackedApp
import com.shivam.tally.data.TallyApp
import com.shivam.tally.debug.DumpRecorder
import com.shivam.tally.rules.RuleSet
import com.shivam.tally.rules.RulesRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Event pipeline:  event (package-filtered, throttled)  ->  evaluate() reads the active window with the rules
 * ->  CountingEngine (pure Kotlin)  ->  CountStore  ->  overlay / notification / Flutter via store listeners.
 * All of this runs on the main thread. Time is SystemClock.elapsedRealtime() for the engine.
 */
class TallyAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tally: TallyApp
    private var recorder: DumpRecorder? = null
    private var overlay: OverlayController? = null
    private var notifier: OngoingNotifier? = null
    private var rules: RuleSet = RuleSet("none", emptyMap())
    private var receiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var storeListener: (() -> Unit)? = null
    private var engine: CountingEngine? = null

    private var foregroundPkg: String? = null
    private var screenOn = true
    private var inPlayer = false
    private var lastEval = 0L
    private var lastEvalSig = ""
    private val lastCounts = HashMap<TrackedApp, Int>()
    private var evalPending = false
    private val rowIds = ConcurrentHashMap<Long, Long>()
    private val debug by lazy { (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }

    private val evalRunnable = Runnable { evalPending = false; evaluate() }
    private val tickRunnable = Runnable { engine?.tick(now()); scheduleTick() }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (TrackedApp.fromPackage(foregroundPkg) != null && screenOn) { evaluate(); handler.postDelayed(this, HEARTBEAT_MS) }
        }
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun log(msg: String) { if (debug) Log.d("Tally", msg) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        cleanup() // a restarted service must never inherit a leaked overlay / receivers
        instance = this
        tally = TallyApp.get(this)
        val store = tally.store
        val listener = object : CountingEngine.Listener {
            override fun onViewCounted(view: CountingEngine.View, atMs: Long) {
                log("COUNT ${view.app} key=${view.key}")
                store.insertView(view.app, System.currentTimeMillis(), engine?.minDwellMs ?: 0) { id -> rowIds[view.seq] = id }
            }
            override fun onViewEnded(view: CountingEngine.View, dwellMs: Long, counted: Boolean) {
                log("END ${view.app} dwell=$dwellMs counted=$counted key=${view.key}")
                if (counted) { store.updateDwell({ rowIds.remove(view.seq) }, dwellMs) }
            }
        }
        engine = CountingEngine(listener)
        recorder = DumpRecorder(this, TrackedApp.entries.map { it.pkg }.toSet())
        if (tally.settings.calibrationEnabled) recorder?.start()
        overlay = OverlayController(this, tally.settings) { app, d -> tally.store.adjust(app, d) }
        notifier = OngoingNotifier(this, store)
        val sl = { onCountsChanged() }
        storeListener = sl; store.addListener(sl)
        registerReceivers()
        onSettingsChanged()
        onCountsChanged()
    }

    /** Called on service connect and by the Flutter bridge whenever a setting changed. */
    fun onSettingsChanged() {
        val e = engine ?: return
        val s = tally.settings
        e.minDwellMs = s.minDwellMs
        e.enabledApps = TrackedApp.entries.filter { s.isAppEnabled(it) }.toSet()
        rules = runCatching { RuleSet.parse(RulesRepository.load(this)) }.getOrElse { log("rules parse failed: $it"); rules }
        if (!s.notificationEnabled) notifier?.cancel()
        updateOverlay()
        onCountsChanged()
    }

    fun setCalibration(on: Boolean) { val r = recorder ?: return; if (on) r.start() else r.stop() }
    val calibrationFile get() = recorder?.currentFile
    val calibrationRunning get() = recorder?.isRunning == true
    val activeRulesVersion get() = rules.version

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return // our own overlay: never parsed, never counted
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) noteForeground(pkg)
        val app = TrackedApp.fromPackage(pkg) ?: return
        recorder?.onEvent(e)
        val rule = rules.forPackage(pkg) ?: return
        if (e.className?.toString() in rule.ignoreEventClasses) return // seekbar progress spam
        if (app !in (engine?.enabledApps ?: emptySet())) return
        scheduleEvaluate()
    }

    /** Only real app windows count as "foreground"; system UI and keyboards are transient and ignored. */
    private fun noteForeground(pkg: String) {
        if (pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        if (pkg == foregroundPkg) return
        foregroundPkg = pkg
        log("foreground=$pkg")
        if (TrackedApp.fromPackage(pkg) == null) {
            inPlayer = false
            engine?.onPlayerExited(now()); scheduleTick()
            updateOverlay()
        } else {
            handler.removeCallbacks(heartbeat); handler.postDelayed(heartbeat, HEARTBEAT_MS)
            scheduleEvaluate()
        }
    }

    /** Throttle: at most one evaluation per THROTTLE_MS, trailing edge so the last state always gets read. */
    private fun scheduleEvaluate() {
        if (evalPending) return
        evalPending = true
        val wait = (THROTTLE_MS - (now() - lastEval)).coerceAtLeast(DEBOUNCE_MS)
        handler.postDelayed(evalRunnable, wait)
    }

    private fun evaluate() {
        lastEval = now()
        val e = engine ?: return
        if (!screenOn) return
        val root = rootInActiveWindow ?: return
        try {
            val pkg = root.packageName?.toString()
            val app = TrackedApp.fromPackage(pkg)
            if (app == null || pkg == null) {
                // Active window belongs to something else (launcher, another app): the player was left.
                if (pkg != null && pkg != "com.android.systemui" && !pkg.contains("inputmethod")) {
                    foregroundPkg = pkg; inPlayer = false; e.onPlayerExited(now())
                }
                return
            }
            foregroundPkg = pkg
            val rule = rules.forPackage(pkg) ?: return
            val d = Detector.detect(root, rule)
            inPlayer = d.inPlayer
            if (!d.inPlayer) e.onPlayerExited(now())
            else if (d.key != null || e.activeApp != app) e.onVideoShown(app, d.key, now())
            // else: player visible but identity momentarily unreadable (e.g. comments sheet): keep the current view.
            val sig = "$app ${d.inPlayer} ${d.key}"
            if (sig != lastEvalSig) { lastEvalSig = sig; log("eval $sig") } // only log changes, not every heartbeat
        } finally {
            @Suppress("DEPRECATION") root.recycle()
        }
        scheduleTick()
        updateOverlay()
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tickRunnable)
        val dl = engine?.nextDeadlineMs() ?: return
        handler.postDelayed(tickRunnable, (dl - now()).coerceAtLeast(0))
    }

    private fun updateOverlay() {
        val s = tally.settings
        val app = TrackedApp.fromPackage(foregroundPkg)
        val want = s.overlayEnabled && screenOn && app != null && s.isAppEnabled(app) && (!s.overlayOnlyInPlayer || inPlayer)
        if (want && app != null) overlay?.show(app, tally.store.todayCount(app)).also { overlay?.refresh() } else overlay?.hide()
    }

    private fun onCountsChanged() {
        val fg = TrackedApp.fromPackage(foregroundPkg)
        val s = tally.settings
        for (a in TrackedApp.entries) {
            val c = tally.store.todayCount(a)
            val prev = lastCounts.put(a, c)
            // Milestone: the number on the pill just went UP onto a multiple of N, while that app is on screen.
            if (prev != null && c > prev && c % s.warnEvery == 0 && a == fg) {
                log("MILESTONE $a count=$c")
                if (s.warnVibrate) Haptics.warn(this)
                if (s.warnDance) overlay?.warn()
            }
        }
        if (fg != null) overlay?.setCount(tally.store.todayCount(fg))
        notifier?.update(s.notificationEnabled)
    }

    private fun onScreenOff() {
        screenOn = false
        handler.removeCallbacks(evalRunnable); evalPending = false
        engine?.onScreenOff(now()); scheduleTick()
        overlay?.hide()
    }

    private fun onScreenOn() { screenOn = true; scheduleEvaluate() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.reposition(); overlay?.refresh()
    }

    private fun registerReceivers() {
        val adb = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val label = i.getStringExtra("label") ?: "unlabeled"
                when (i.action) {
                    DumpRecorder.ACTION_MARK -> recorder?.mark(label)
                    DumpRecorder.ACTION_SNAPSHOT -> { recorder?.mark(label); recorder?.snapshot(label) }
                    ACTION_EYE -> if (debug) { overlay?.eyeOverride = i.getFloatExtra("level", -1f).takeIf { it >= 0 }; overlay?.refresh() }
                    ACTION_WARN -> if (debug) { if (tally.settings.warnVibrate) Haptics.warn(this@TallyAccessibilityService); if (tally.settings.warnDance) overlay?.warn() }
                }
            }
        }
        val f = IntentFilter().apply { addAction(DumpRecorder.ACTION_MARK); addAction(DumpRecorder.ACTION_SNAPSHOT); addAction(ACTION_EYE); addAction(ACTION_WARN) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(adb, f, Context.RECEIVER_EXPORTED) else registerReceiver(adb, f)
        receiver = adb

        val scr = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == Intent.ACTION_SCREEN_OFF) onScreenOff() else if (i.action == Intent.ACTION_SCREEN_ON) onScreenOn()
            }
        }
        val sf = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scr, sf, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(scr, sf)
        screenReceiver = scr
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        evalPending = false
        receiver?.let { runCatching { unregisterReceiver(it) } }; receiver = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }; screenReceiver = null
        if (this::tally.isInitialized) storeListener?.let { tally.store.removeListener(it) }
        storeListener = null
        overlay?.destroy(); overlay = null
        notifier?.cancel(); notifier = null
        recorder?.stop(); recorder = null
        engine?.onScreenOff(now()); engine = null
        foregroundPkg = null; inPlayer = false; lastCounts.clear()
        if (instance === this) instance = null
    }

    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { cleanup(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanup(); super.onDestroy() }

    companion object {
        const val ACTION_EYE = "com.shivam.tally.EYE"      // debug builds only
        const val ACTION_WARN = "com.shivam.tally.WARN"    // debug builds only
        private const val DEBOUNCE_MS = 120L
        private const val THROTTLE_MS = 250L
        private const val HEARTBEAT_MS = 1_000L
        @Volatile var instance: TallyAccessibilityService? = null
            private set
    }
}
