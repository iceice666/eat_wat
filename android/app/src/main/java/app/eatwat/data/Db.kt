package app.eatwat.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.eatwat.core.MIN_MS
import app.eatwat.core.Reading

data class Meal(
    val id: Long = 0,
    val ts: Long,
    val title: String,
    val kind: String = "",
    val carbs: Double? = null,
    val note: String = "",
    val photo: String? = null,
)

data class ReadingStats(val count: Int, val first: Long?, val last: Long?)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "eatwat.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE readings (ts INTEGER PRIMARY KEY, mgdl REAL NOT NULL, source TEXT NOT NULL, trend INTEGER)")
        db.execSQL(
            """CREATE TABLE meals (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, title TEXT NOT NULL,
               kind TEXT NOT NULL DEFAULT '', carbs REAL, note TEXT NOT NULL DEFAULT '', photo TEXT, created_at INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX meals_ts ON meals(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    /**
     * API readings. Later API data for the same minute replaces earlier data,
     * except that a graph point is never replaced by a logbook/latest point.
     * (Manual upsert: SQLite on API 29 predates ON CONFLICT DO UPDATE.)
     */
    fun upsertReadings(rows: List<Reading>): Int {
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            for (r in rows) {
                val existing = db.rawQuery("SELECT source FROM readings WHERE ts = ?", arrayOf(r.ts.toString())).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                val cv = ContentValues().apply {
                    put("ts", r.ts); put("mgdl", r.mgdl); put("source", r.source)
                    if (r.trend != null) put("trend", r.trend) else putNull("trend")
                }
                when {
                    existing == null -> { db.insert("readings", null, cv); n++ }
                    existing != "graph" || r.source == "graph" -> {
                        if (r.trend == null) cv.remove("trend")
                        n += db.update("readings", cv, "ts = ?", arrayOf(r.ts.toString()))
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    /** CSV readings: skip rows that have an API reading within [windowMin] minutes. */
    fun insertCsvReadings(rows: List<Reading>, windowMin: Int = 7): Pair<Int, Int> {
        val db = writableDatabase
        var inserted = 0
        var skipped = 0
        val w = windowMin * MIN_MS
        db.beginTransaction()
        try {
            for (r in rows) {
                val near = db.rawQuery(
                    "SELECT 1 FROM readings WHERE ts BETWEEN ? AND ? AND source != 'csv' LIMIT 1",
                    arrayOf((r.ts - w).toString(), (r.ts + w).toString()),
                ).use { it.moveToFirst() }
                if (near) { skipped++; continue }
                val id = db.insertWithOnConflict(
                    "readings", null,
                    ContentValues().apply { put("ts", r.ts); put("mgdl", r.mgdl); put("source", "csv") },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (id >= 0) inserted++ else skipped++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted to skipped
    }

    fun readings(from: Long, to: Long): List<Reading> =
        readableDatabase.rawQuery("SELECT ts, mgdl, source, trend FROM readings WHERE ts BETWEEN ? AND ? ORDER BY ts", arrayOf(from.toString(), to.toString())).use { c ->
            buildList {
                while (c.moveToNext()) add(Reading(c.getLong(0), c.getDouble(1), c.getString(2), if (c.isNull(3)) null else c.getInt(3)))
            }
        }

    fun latestReading(): Reading? =
        readableDatabase.rawQuery("SELECT ts, mgdl, source, trend FROM readings ORDER BY ts DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) Reading(c.getLong(0), c.getDouble(1), c.getString(2), if (c.isNull(3)) null else c.getInt(3)) else null
        }

    fun readingStats(): ReadingStats =
        readableDatabase.rawQuery("SELECT COUNT(*), MIN(ts), MAX(ts) FROM readings", null).use { c ->
            c.moveToFirst()
            ReadingStats(c.getInt(0), if (c.isNull(1)) null else c.getLong(1), if (c.isNull(2)) null else c.getLong(2))
        }

    private fun mealFrom(c: android.database.Cursor) = Meal(
        id = c.getLong(0), ts = c.getLong(1), title = c.getString(2), kind = c.getString(3),
        carbs = if (c.isNull(4)) null else c.getDouble(4), note = c.getString(5), photo = if (c.isNull(6)) null else c.getString(6),
    )

    fun meals(from: Long, to: Long): List<Meal> =
        readableDatabase.rawQuery("SELECT id, ts, title, kind, carbs, note, photo FROM meals WHERE ts BETWEEN ? AND ? ORDER BY ts", arrayOf(from.toString(), to.toString())).use { c ->
            buildList { while (c.moveToNext()) add(mealFrom(c)) }
        }

    fun meal(id: Long): Meal? =
        readableDatabase.rawQuery("SELECT id, ts, title, kind, carbs, note, photo FROM meals WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) mealFrom(c) else null
        }

    fun recentTitles(limit: Int = 50): List<String> =
        readableDatabase.rawQuery("SELECT title FROM meals GROUP BY title ORDER BY MAX(ts) DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun saveMeal(m: Meal): Long {
        val cv = ContentValues().apply {
            put("ts", m.ts); put("title", m.title); put("kind", m.kind)
            if (m.carbs != null) put("carbs", m.carbs) else putNull("carbs")
            put("note", m.note)
            if (m.photo != null) put("photo", m.photo) else putNull("photo")
        }
        return if (m.id == 0L) {
            cv.put("created_at", System.currentTimeMillis())
            writableDatabase.insert("meals", null, cv)
        } else {
            writableDatabase.update("meals", cv, "id = ?", arrayOf(m.id.toString()))
            m.id
        }
    }

    fun deleteMeal(id: Long) {
        writableDatabase.delete("meals", "id = ?", arrayOf(id.toString()))
    }

    companion object {
        @Volatile private var instance: Db? = null
        fun get(ctx: Context): Db = instance ?: synchronized(this) { instance ?: Db(ctx.applicationContext).also { instance = it } }
    }
}
