package com.shivam.tally.data

import android.content.Context
import android.content.SharedPreferences
import com.shivam.tally.core.CountingEngine
import com.shivam.tally.core.TrackedApp

/** Thin typed wrapper around SharedPreferences. Overlay options are read by the service later on. */
class TallySettings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("tally_settings", Context.MODE_PRIVATE)

    var minDwellMs: Long
        get() = prefs.getLong("min_dwell_ms", 2_000).coerceIn(CountingEngine.MIN_DWELL_MS, CountingEngine.MAX_DWELL_MS)
        set(v) = prefs.edit().putLong("min_dwell_ms", v.coerceIn(CountingEngine.MIN_DWELL_MS, CountingEngine.MAX_DWELL_MS)).apply()

    fun isAppEnabled(app: TrackedApp) = prefs.getBoolean("enabled_${app.id}", true)
    fun setAppEnabled(app: TrackedApp, v: Boolean) = prefs.edit().putBoolean("enabled_${app.id}", v).apply()

    var overlayEnabled: Boolean
        get() = prefs.getBoolean("overlay_enabled", true)
        set(v) = prefs.edit().putBoolean("overlay_enabled", v).apply()

    /** "small" | "medium" */
    var overlaySize: String
        get() = prefs.getString("overlay_size", "small") ?: "small"
        set(v) = prefs.edit().putString("overlay_size", v).apply()

    var overlayOpacity: Float
        get() = prefs.getFloat("overlay_opacity", 0.8f).coerceIn(0.3f, 1f)
        set(v) = prefs.edit().putFloat("overlay_opacity", v.coerceIn(0.3f, 1f)).apply()

    var overlayOnlyInPlayer: Boolean
        get() = prefs.getBoolean("overlay_only_in_player", false)
        set(v) = prefs.edit().putBoolean("overlay_only_in_player", v).apply()

    var eyeEnabled: Boolean
        get() = prefs.getBoolean("eye_enabled", true)
        set(v) = prefs.edit().putBoolean("eye_enabled", v).apply()

    /** Warn every Nth counted video (per app, matching the number on the pill). */
    var warnEvery: Int
        get() = prefs.getInt("warn_every", 15).coerceIn(5, 50)
        set(v) = prefs.edit().putInt("warn_every", v.coerceIn(5, 50)).apply()

    var warnVibrate: Boolean
        get() = prefs.getBoolean("warn_vibrate", true)
        set(v) = prefs.edit().putBoolean("warn_vibrate", v).apply()

    var warnDance: Boolean
        get() = prefs.getBoolean("warn_dance", true)
        set(v) = prefs.edit().putBoolean("warn_dance", v).apply()

    var notificationEnabled: Boolean
        get() = prefs.getBoolean("notification_enabled", false)
        set(v) = prefs.edit().putBoolean("notification_enabled", v).apply()

    var calibrationEnabled: Boolean
        get() = prefs.getBoolean("calibration_enabled", false)
        set(v) = prefs.edit().putBoolean("calibration_enabled", v).apply()

    fun toMap(): Map<String, Any> = mapOf(
        "minDwellMs" to minDwellMs,
        "instagramEnabled" to isAppEnabled(TrackedApp.INSTAGRAM),
        "youtubeEnabled" to isAppEnabled(TrackedApp.YOUTUBE),
        "overlayEnabled" to overlayEnabled,
        "overlaySize" to overlaySize,
        "overlayOpacity" to overlayOpacity.toDouble(),
        "overlayOnlyInPlayer" to overlayOnlyInPlayer,
        "eyeEnabled" to eyeEnabled,
        "warnEvery" to warnEvery,
        "warnVibrate" to warnVibrate,
        "warnDance" to warnDance,
        "notificationEnabled" to notificationEnabled,
        "calibrationEnabled" to calibrationEnabled,
    )

    fun apply(m: Map<*, *>) {
        (m["minDwellMs"] as? Number)?.let { minDwellMs = it.toLong() }
        (m["instagramEnabled"] as? Boolean)?.let { setAppEnabled(TrackedApp.INSTAGRAM, it) }
        (m["youtubeEnabled"] as? Boolean)?.let { setAppEnabled(TrackedApp.YOUTUBE, it) }
        (m["overlayEnabled"] as? Boolean)?.let { overlayEnabled = it }
        (m["overlaySize"] as? String)?.let { overlaySize = it }
        (m["overlayOpacity"] as? Number)?.let { overlayOpacity = it.toFloat() }
        (m["overlayOnlyInPlayer"] as? Boolean)?.let { overlayOnlyInPlayer = it }
        (m["eyeEnabled"] as? Boolean)?.let { eyeEnabled = it }
        (m["warnEvery"] as? Number)?.let { warnEvery = it.toInt() }
        (m["warnVibrate"] as? Boolean)?.let { warnVibrate = it }
        (m["warnDance"] as? Boolean)?.let { warnDance = it }
        (m["notificationEnabled"] as? Boolean)?.let { notificationEnabled = it }
        (m["calibrationEnabled"] as? Boolean)?.let { calibrationEnabled = it }
    }
}
