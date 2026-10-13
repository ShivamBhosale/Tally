package com.shivam.tally.service

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** The milestone buzz: three pulses and a long one. Uses the accessibility usage so it is not suppressed in the background. */
object Haptics {
    private val timings = longArrayOf(0, 140, 90, 140, 90, 140, 140, 420)

    fun warn(ctx: Context) {
        val v: Vibrator = (if (Build.VERSION.SDK_INT >= 31)
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator) ?: return
        if (!v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(timings, -1)
        try {
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY))
            else @Suppress("DEPRECATION") v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build())
        } catch (_: RuntimeException) {}
    }
}
