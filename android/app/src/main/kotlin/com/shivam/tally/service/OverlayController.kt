package com.shivam.tally.service

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.shivam.tally.core.TrackedApp
import com.shivam.tally.data.TallySettings

/**
 * Floating counter drawn with TYPE_ACCESSIBILITY_OVERLAY (no SYSTEM_ALERT_WINDOW needed).
 * FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL: never steals focus, touches outside the window go to the app.
 *
 * The window is the pill plus a transparent [PAD] ring so the milestone "dance" (wobble / scale / hop) is not
 * clipped at the window edge. All calls must be made on the main thread.
 */
class OverlayController(
    private val service: AccessibilityService,
    private val settings: TallySettings,
    private val onAdjust: (TrackedApp, Int) -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val dm get() = service.resources.displayMetrics

    private var root: FrameLayout? = null   // the window content: transparent padding around the pill
    private var pill: LinearLayout? = null
    private var eye: EyeView? = null
    private var label: TextView? = null
    private var minus: TextView? = null
    private var plus: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var dance: AnimatorSet? = null
    private var flash: ValueAnimator? = null

    private var app: TrackedApp? = null
    private var count = 0
    private var minimal = false
    private var corrections = false
    private val hideCorrections = Runnable { corrections = false; render() }

    val isShowing get() = attached

    fun show(app: TrackedApp, count: Int) {
        this.count = count
        val changed = this.app != app
        if (changed) { this.app = app; corrections = false }
        if (!attached) attach()
        if (attached) { render(); applyStyle(); if (changed) reposition() }
    }

    fun setCount(count: Int) {
        this.count = count
        if (attached) render()
    }

    /** Re-read size/opacity/eye settings and re-clamp (settings change, rotation). */
    fun refresh() {
        if (!attached) return
        applyStyle(); render()
    }

    fun hide() {
        handler.removeCallbacks(hideCorrections)
        corrections = false
        stopDance()
        detach()
    }

    fun destroy() { hide(); app = null }

    /** Debug only: pin the eye to [level] regardless of the count (null releases it). */
    var eyeOverride: Float? = null

    /** Milestone warning: wobble + hop + scale pulse of the pill and a red flash of the eye. */
    fun warn() {
        val p = pill ?: return
        if (!attached) return
        stopDance()
        val rot = ObjectAnimator.ofFloat(p, View.ROTATION, 0f, -8f, 8f, -6f, 6f, -3f, 3f, 0f)
        val scale = ObjectAnimator.ofPropertyValuesHolder(
            p, PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f, 1f, 1.14f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f, 1f, 1.14f, 1f),
        )
        val hop = ObjectAnimator.ofFloat(p, View.TRANSLATION_Y, 0f, -dp(8f).toFloat(), 0f, -dp(5f).toFloat(), 0f)
        val set = AnimatorSet().apply {
            playTogether(rot, scale, hop)
            duration = 1300
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { p.rotation = 0f; p.scaleX = 1f; p.scaleY = 1f; p.translationY = 0f }
            })
        }
        dance = set; set.start()
        flash = ValueAnimator.ofFloat(0f, 1f, 0f, 1f, 0f, 1f, 0f).apply {
            duration = 1300
            addUpdateListener { eye?.alert = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { eye?.alert = 0f } })
            start()
        }
    }

    private fun stopDance() {
        dance?.cancel(); dance = null
        flash?.cancel(); flash = null
        pill?.let { it.rotation = 0f; it.scaleX = 1f; it.scaleY = 1f; it.translationY = 0f }
        eye?.alert = 0f
    }

    // ---- view construction ----

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()
    private val pad get() = dp(PAD_DP)

    private fun attach() {
        if (attached) return // guard double-add
        val r = buildView()
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            title = "TallyOverlay"
        }
        root = r; params = p
        applyStyle(); render()
        place(p, r)
        try {
            wm.addView(r, p)
            attached = true
        } catch (_: WindowManager.BadTokenException) {
            clearRefs()
        } catch (_: RuntimeException) {
            clearRefs()
        }
    }

    private fun detach() {
        val r = root
        if (r != null && attached) {
            try { wm.removeViewImmediate(r) } catch (_: IllegalArgumentException) {} catch (_: RuntimeException) {}
        }
        attached = false
        clearRefs()
    }

    private fun clearRefs() { root = null; pill = null; eye = null; label = null; minus = null; plus = null; params = null }

    private fun buildView(): FrameLayout {
        val ctx = service
        fun btn(t: String, delta: Int) = TextView(ctx).apply {
            text = t; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setPadding(dp(10f), dp(2f), dp(10f), dp(2f)); visibility = View.GONE
            setOnClickListener { app?.let { a -> onAdjust(a, delta); scheduleHideCorrections() } }
        }
        val m = btn("−", -1); val pl = btn("+", +1)
        val ev = EyeView(ctx)
        val l = TextView(ctx).apply { setTextColor(LIME); typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(m); addView(ev); addView(l); addView(pl)
        }
        val wrap = FrameLayout(ctx).apply {
            setPadding(pad, pad, pad, pad); clipChildren = false; clipToPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        minus = m; plus = pl; label = l; eye = ev; pill = row
        installTouch(row)
        return wrap
    }

    private fun installTouch(v: View) {
        var startX = 0; var startY = 0
        val det = GestureDetector(service, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { params?.let { startX = it.x; startY = it.y }; return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { minimal = !minimal; render(); return true }
            override fun onLongPress(e: MotionEvent) { corrections = !corrections; render(); if (corrections) scheduleHideCorrections() }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val p = params ?: return false
                val first = e1 ?: return false
                val r = root ?: return false
                p.x = startX + (e2.rawX - first.rawX).toInt(); p.y = startY + (e2.rawY - first.rawY).toInt()
                clamp(p, r); update(p, r)
                return true
            }
        })
        v.setOnTouchListener { _, ev ->
            val handled = det.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP) savePosition()
            handled
        }
    }

    private fun scheduleHideCorrections() {
        handler.removeCallbacks(hideCorrections)
        handler.postDelayed(hideCorrections, 5_000)
    }

    // ---- appearance ----

    private fun render() {
        val a = app ?: return
        val pl = pill ?: return
        label?.text = if (minimal) "$count" else "${a.unit} $count"
        val showButtons = corrections
        minus?.visibility = if (showButtons) View.VISIBLE else View.GONE
        plus?.visibility = if (showButtons) View.VISIBLE else View.GONE
        val eyeOn = settings.eyeEnabled
        eye?.visibility = if (eyeOn) View.VISIBLE else View.GONE
        eye?.level = eyeOverride ?: (count / (settings.warnEvery * 4f)).coerceIn(0f, 1f)
        val hp = if (!minimal || showButtons) 12f else 9f
        pl.setPadding(dp(hp), dp(5f), dp(hp), dp(5f))
        root?.post { params?.let { p -> root?.let { rt -> clamp(p, rt); update(p, rt) } } }
    }

    private fun applyStyle() {
        val pl = pill ?: return
        val medium = settings.overlaySize == "medium"
        val sp = if (medium) 18f else 13f
        label?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        minus?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp + 3); plus?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp + 3)
        eye?.layoutParams = LinearLayout.LayoutParams(dp(if (medium) 34f else 24f), dp(if (medium) 22f else 15f)).apply {
            marginEnd = dp(7f); marginStart = dp(1f)
        }
        // Dark pill + light stroke + lime text: legible on both light and dark app content.
        pl.background = GradientDrawable().apply {
            cornerRadius = dp(22f).toFloat(); setColor(0xE60B0B0D.toInt()); setStroke(dp(1f), 0x66FFFFFF)
        }
        pl.alpha = settings.overlayOpacity
    }

    // ---- geometry (the window includes the PAD ring, so its bounds may poke PAD past the usable area) ----

    /** Usable screen area in absolute coordinates (no system bars / cutouts), grown by the transparent ring. */
    private fun bounds(): Rect {
        val r: Rect
        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            val ins = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val b = m.bounds
            r = Rect(b.left + ins.left, b.top + ins.top, b.right - ins.right, b.bottom - ins.bottom)
        } else {
            val d = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(d)
            r = Rect(0, 0, d.widthPixels, d.heightPixels)
        }
        r.inset(-pad, -pad)
        return r
    }

    private fun size(v: View): Pair<Int, Int> {
        if (v.width > 0 && v.height > 0) return v.width to v.height
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        return v.measuredWidth to v.measuredHeight
    }

    private fun clamp(p: WindowManager.LayoutParams, v: View) {
        val a = bounds(); val (w, h) = size(v)
        p.x = p.x.coerceIn(a.left, maxOf(a.left, a.right - w))
        p.y = p.y.coerceIn(a.top, maxOf(a.top, a.bottom - h))
    }

    /** Position from the per-app saved fractions of the free area, so it survives rotation. */
    private fun place(p: WindowManager.LayoutParams, v: View) {
        val a = app ?: return
        val fx = settings.prefs.getFloat("overlay_fx_${a.id}", 0.88f)
        val fy = settings.prefs.getFloat("overlay_fy_${a.id}", 0.14f)
        val area = bounds(); val (w, h) = size(v)
        p.x = area.left + (fx * maxOf(0, area.width() - w)).toInt()
        p.y = area.top + (fy * maxOf(0, area.height() - h)).toInt()
        clamp(p, v)
    }

    private fun savePosition() {
        val a = app ?: return; val p = params ?: return; val v = root ?: return
        val area = bounds(); val (w, h) = size(v)
        val fx = (p.x - area.left).toFloat() / maxOf(1, area.width() - w)
        val fy = (p.y - area.top).toFloat() / maxOf(1, area.height() - h)
        settings.prefs.edit().putFloat("overlay_fx_${a.id}", fx.coerceIn(0f, 1f)).putFloat("overlay_fy_${a.id}", fy.coerceIn(0f, 1f)).apply()
    }

    private fun update(p: WindowManager.LayoutParams, v: View) {
        if (!attached) return
        try { wm.updateViewLayout(v, p) } catch (_: IllegalArgumentException) {} catch (_: RuntimeException) {}
    }

    /** Load the saved position of the current app (app switch, rotation). */
    fun reposition() {
        val p = params ?: return; val v = root ?: return
        place(p, v); update(p, v)
    }

    companion object {
        private const val LIME = 0xFFC8FF3C.toInt()
        private const val PAD_DP = 14f
    }
}
