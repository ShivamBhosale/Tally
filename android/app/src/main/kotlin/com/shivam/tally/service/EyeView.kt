package com.shivam.tally.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.max

/**
 * A small eye that gets progressively worse with [level] (0 = fresh, 1 = wrecked):
 * the upper lid droops, the whites turn pink then red with more veins, the iris dulls, the sparkle fades, bags appear.
 * [alert] (0..1) flashes it red during a milestone warning.
 */
class EyeView(context: Context) : View(context) {
    var level = 0f
        set(v) { val c = v.coerceIn(0f, 1f); if (c != field) { field = c; invalidate() } }
    var alert = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val almond = Path()
    private val lid = Path()
    private val arc = RectF()

    // Fixed vein layout (fractions of width/height) so the eye does not "jitter" between redraws.
    private val veins = arrayOf(
        floatArrayOf(0.00f, 0.52f, 0.34f, 0.48f), floatArrayOf(1.00f, 0.50f, 0.66f, 0.54f),
        floatArrayOf(0.06f, 0.30f, 0.30f, 0.44f), floatArrayOf(0.94f, 0.70f, 0.70f, 0.56f),
        floatArrayOf(0.10f, 0.74f, 0.32f, 0.58f), floatArrayOf(0.90f, 0.28f, 0.68f, 0.42f),
        floatArrayOf(0.20f, 0.18f, 0.36f, 0.40f), floatArrayOf(0.80f, 0.84f, 0.64f, 0.60f),
    )

    private fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(s: Int) = ((a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * k).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val cx = w / 2; val cy = h / 2
        val sw = max(1f, h * 0.075f)
        val lv = level

        almond.reset()
        almond.moveTo(sw, cy)
        almond.quadTo(cx, cy - h * 0.95f, w - sw, cy)
        almond.quadTo(cx, cy + h * 0.95f, sw, cy)
        almond.close()

        c.save()
        c.clipPath(almond)

        // sclera: white -> bloodshot, plus a red wash while alerting
        paint.style = Paint.Style.FILL
        paint.color = mix(mix(0xFFF6F5EF.toInt(), 0xFFE9948A.toInt(), lv), 0xFFFF3B30.toInt(), alert * 0.55f)
        c.drawRect(0f, 0f, w, h, paint)

        // veins
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, h * 0.045f)
        paint.color = 0xFFC62828.toInt()
        val shown = (lv * veins.size + 0.4f).toInt().coerceAtMost(veins.size)
        for (i in 0 until shown) {
            val v = veins[i]
            paint.alpha = (110 + 120 * lv).toInt().coerceAtMost(255)
            c.drawLine(v[0] * w, v[1] * h, v[2] * w, v[3] * h, paint)
        }

        // iris + pupil (iris sinks a little as the lid gets heavy)
        val ir = h * 0.36f
        val icy = cy + lv * h * 0.05f
        paint.style = Paint.Style.FILL
        paint.color = mix(0xFF2FB27A.toInt(), 0xFF7A7F58.toInt(), lv)
        c.drawCircle(cx, icy, ir, paint)
        paint.color = 0xFF101012.toInt()
        c.drawCircle(cx, icy, ir * (0.48f + 0.12f * lv), paint)
        paint.color = 0xFFFFFFFF.toInt(); paint.alpha = (255 * (1f - lv)).toInt()
        c.drawCircle(cx - ir * 0.32f, icy - ir * 0.34f, ir * 0.17f, paint)
        paint.alpha = 255

        // upper lid droops over the eye
        val topY = cy - h * 0.475f
        val lidY = topY + lv * 0.62f * h * 0.95f
        if (lv > 0.01f) {
            lid.reset()
            lid.moveTo(0f, 0f); lid.lineTo(w, 0f); lid.lineTo(w, lidY)
            lid.quadTo(cx, lidY + h * 0.14f, 0f, lidY); lid.close()
            paint.style = Paint.Style.FILL
            paint.color = mix(0xFF4A3F46.toInt(), 0xFF2B2126.toInt(), lv)
            c.drawPath(lid, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = sw
            paint.color = 0xFF14100F.toInt()
            c.drawPath(lid, paint)
        }
        c.restore()

        // outline
        paint.style = Paint.Style.STROKE; paint.strokeWidth = sw
        paint.color = 0xE6FFFFFF.toInt()
        c.drawPath(almond, paint)

        // bags under the eye
        if (lv > 0.25f) {
            arc.set(w * 0.14f, cy + h * 0.22f, w * 0.86f, cy + h * 0.22f + h * 0.62f)
            paint.color = 0xFF9B7FB5.toInt(); paint.alpha = (200 * (lv - 0.2f)).toInt().coerceIn(0, 200)
            paint.strokeWidth = max(1f, h * 0.06f)
            c.drawArc(arc, 25f, 130f, false, paint)
            paint.alpha = 255
        }
    }
}
