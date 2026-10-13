package com.shivam.tally.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import com.shivam.tally.core.TrackedApp
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * One row per counted view (delta = +1) plus manual corrections (delta = +1 / -1, source = "manual").
 * Daily totals are always derived: SUM(delta) per (day, app). Nothing is stored pre-aggregated.
 */
class CountStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "tally.db", null, 1) {

    private val io = Executors.newSingleThreadExecutor { Thread(it, "tally-db") }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** Today's totals per app id, valid after the first load. Read from any thread. */
    @Volatile private var cache: Pair<String, Map<String, Int>>? = null

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE views(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 ts INTEGER NOT NULL,
                 day TEXT NOT NULL,
                 app TEXT NOT NULL,
                 dwell_ms INTEGER NOT NULL,
                 delta INTEGER NOT NULL DEFAULT 1,
                 source TEXT NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_views_day_app ON views(day, app)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun today(): String = LocalDate.now().toString()

    /** Today's count per app, clamped to >= 0 for display. Instant when cached, otherwise one query. */
    fun todayCount(app: TrackedApp): Int {
        val d = today()
        val c = cache
        if (c != null && c.first == d) return (c.second[app.id] ?: 0).coerceAtLeast(0)
        return refreshCache()[app.id]?.coerceAtLeast(0) ?: 0
    }

    private fun refreshCache(): Map<String, Int> {
        val d = today()
        val m = HashMap<String, Int>()
        readableDatabase.rawQuery("SELECT app, SUM(delta) FROM views WHERE day=? GROUP BY app", arrayOf(d)).use {
            while (it.moveToNext()) m[it.getString(0)] = it.getInt(1)
        }
        cache = d to m
        return m
    }

    /** Inserts an auto-counted view. [onInserted] runs on the db thread with the new row id. */
    fun insertView(app: TrackedApp, tsMs: Long, dwellMs: Long, onInserted: (Long) -> Unit = {}) {
        // Bump the cache immediately so the overlay is up to date before the disk write lands.
        bump(app, +1)
        io.execute {
            val id = writableDatabase.insert("views", null, row(app, tsMs, dwellMs, +1, "auto"))
            onInserted(id)
            publish()
        }
    }

    /** [rowId] is resolved on the db thread, i.e. after the matching insert (same single-thread executor) has run. */
    fun updateDwell(rowId: () -> Long?, dwellMs: Long) {
        io.execute {
            val id = rowId() ?: return@execute
            writableDatabase.update("views", ContentValues().apply { put("dwell_ms", dwellMs) }, "id=?", arrayOf(id.toString()))
        }
    }

    /** Manual correction. [delta] is +1 or -1. */
    fun adjust(app: TrackedApp, delta: Int) {
        val d = if (delta >= 0) 1 else -1
        bump(app, d)
        io.execute {
            writableDatabase.insert("views", null, row(app, System.currentTimeMillis(), 0, d, "manual"))
            publish()
        }
    }

    fun resetToday() = runAndPublish { it.delete("views", "day=?", arrayOf(today())) }
    fun clearAll() = runAndPublish { it.delete("views", null, null) }

    private fun runAndPublish(op: (SQLiteDatabase) -> Unit) {
        cache = null
        io.execute { op(writableDatabase); publish() }
    }

    /** day -> (appId -> total), for the last [days] days including today. Blocking: call off the main thread. */
    fun dailyTotals(days: Int): Map<String, Map<String, Int>> {
        val since = LocalDate.now().minusDays((days - 1).toLong()).toString()
        val out = LinkedHashMap<String, MutableMap<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT day, app, SUM(delta) FROM views WHERE day>=? GROUP BY day, app ORDER BY day", arrayOf(since)
        ).use {
            while (it.moveToNext()) out.getOrPut(it.getString(0)) { HashMap() }[it.getString(1)] = it.getInt(2).coerceAtLeast(0)
        }
        return out
    }

    /** Today per app: count and total on-screen time of counted views. Blocking. */
    fun todaySummary(): Map<String, Map<String, Long>> {
        val out = HashMap<String, Map<String, Long>>()
        readableDatabase.rawQuery(
            "SELECT app, SUM(delta), SUM(CASE WHEN source='auto' THEN dwell_ms ELSE 0 END) FROM views WHERE day=? GROUP BY app",
            arrayOf(today())
        ).use { while (it.moveToNext()) out[it.getString(0)] = mapOf("count" to it.getLong(1).coerceAtLeast(0), "dwellMs" to it.getLong(2)) }
        return out
    }

    /** Views per local hour of day over the last [days] days, plus total counted on-screen time. Blocking. */
    fun patterns(days: Int): Map<String, Any> {
        val since = LocalDate.now().minusDays((days - 1).toLong()).toString()
        val hours = LongArray(24)
        readableDatabase.rawQuery(
            "SELECT CAST(strftime('%H', ts/1000, 'unixepoch', 'localtime') AS INTEGER), SUM(delta) FROM views WHERE day>=? GROUP BY 1",
            arrayOf(since)
        ).use { while (it.moveToNext()) hours[it.getInt(0).coerceIn(0, 23)] = it.getLong(1).coerceAtLeast(0) }
        var dwell = 0L
        readableDatabase.rawQuery("SELECT SUM(dwell_ms) FROM views WHERE day>=? AND source='auto'", arrayOf(since)).use { if (it.moveToFirst()) dwell = it.getLong(0) }
        return mapOf("hours" to hours.toList(), "dwellMs" to dwell)
    }

    /** Whole history as CSV. Blocking. */
    fun exportCsv(): String {
        val sb = StringBuilder("id,timestamp_ms,local_time,day,app,delta,dwell_ms,source\n")
        readableDatabase.rawQuery("SELECT id, ts, day, app, delta, dwell_ms, source FROM views ORDER BY ts", null).use {
            while (it.moveToNext()) {
                val ts = it.getLong(1)
                sb.append(it.getLong(0)).append(',').append(ts).append(',')
                    .append(java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()).append(',')
                    .append(it.getString(2)).append(',').append(it.getString(3)).append(',')
                    .append(it.getInt(4)).append(',').append(it.getLong(5)).append(',').append(it.getString(6)).append('\n')
            }
        }
        return sb.toString()
    }

    private fun row(app: TrackedApp, ts: Long, dwell: Long, delta: Int, source: String) = ContentValues().apply {
        put("ts", ts); put("day", today()); put("app", app.id)
        put("dwell_ms", dwell); put("delta", delta); put("source", source)
    }

    private fun bump(app: TrackedApp, d: Int) {
        val day = today()
        val c = cache
        val base = if (c != null && c.first == day) c.second else HashMap()
        cache = day to (base + (app.id to ((base[app.id] ?: 0) + d)))
        notifyListeners()
    }

    private fun publish() {
        refreshCache()
        notifyListeners()
    }

    private fun notifyListeners() { main.post { listeners.forEach { it() } } }

    companion object {
        @Volatile private var instance: CountStore? = null
        fun get(context: Context): CountStore =
            instance ?: synchronized(this) { instance ?: CountStore(context.applicationContext).also { instance = it } }
    }
}
