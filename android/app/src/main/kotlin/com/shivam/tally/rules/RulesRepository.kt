package com.shivam.tally.rules

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Loads detection_rules.json. An override at <externalFilesDir>/detection_rules.json wins over the bundled asset,
 * so after an app update you can `adb push` fixed rules without rebuilding.
 */
object RulesRepository {
    data class Info(val version: String, val source: String, val perApp: Map<String, String>)

    fun load(context: Context): JSONObject {
        val override = File(context.getExternalFilesDir(null), "detection_rules.json")
        val text = runCatching { if (override.exists()) override.readText() else null }.getOrNull()
            ?: context.assets.open("detection_rules.json").bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    fun info(context: Context): Info {
        val override = File(context.getExternalFilesDir(null), "detection_rules.json").exists()
        return runCatching {
            val j = load(context)
            val apps = j.optJSONObject("apps")
            val per = HashMap<String, String>()
            apps?.keys()?.forEach { per[it] = apps.getJSONObject(it).optString("targetAppVersion", "unknown") }
            Info(j.optString("rulesVersion", "?"), if (override) "override file" else "bundled", per)
        }.getOrElse { Info("invalid: ${it.message}", if (override) "override file" else "bundled", emptyMap()) }
    }
}
