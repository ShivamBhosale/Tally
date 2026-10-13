package com.shivam.tally.rules

import org.json.JSONObject

/** One piece of the identity key. Either [id] (short view id) or [match] (desc/text prefix search) locates the node. */
data class Part(
    val id: String?, val match: String?, val field: String,
    val visibleOnly: Boolean, val descendant: Boolean, val after: String?,
)

data class AppRule(
    val pkg: String,
    val targetVersion: String,
    val ignoreEventClasses: Set<String>,
    val playerIds: List<String>,
    val minW: Int,
    val minH: Int,
    val parts: List<Part>,
)

class RuleSet(val version: String, private val apps: Map<String, AppRule>) {
    fun forPackage(pkg: String): AppRule? = apps[pkg]

    companion object {
        fun parse(j: JSONObject): RuleSet {
            val out = HashMap<String, AppRule>()
            val apps = j.optJSONObject("apps")
            apps?.keys()?.forEach { pkg ->
                val a = apps.getJSONObject(pkg)
                val p = a.optJSONObject("player") ?: JSONObject()
                val ids = p.optJSONArray("anyVisibleId")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
                val ign = a.optJSONArray("ignoreEventClasses")?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() } ?: emptySet()
                val parts = a.optJSONArray("identity")?.let { arr ->
                    (0 until arr.length()).map {
                        val o = arr.getJSONObject(it)
                        Part(
                            o.optString("id").ifEmpty { null }, o.optString("match").ifEmpty { null }, o.optString("field", "desc"),
                            o.optBoolean("visibleOnly", false), o.optBoolean("descendant", false), o.optString("after").ifEmpty { null },
                        )
                    }
                } ?: emptyList()
                if (ids.isNotEmpty()) {
                    out[pkg] = AppRule(pkg, a.optString("targetAppVersion", "unknown"), ign, ids,
                        p.optInt("minVisibleWidthPx", 200), p.optInt("minVisibleHeightPx", 200), parts)
                }
            }
            return RuleSet(j.optString("rulesVersion", "?"), out)
        }
    }
}
