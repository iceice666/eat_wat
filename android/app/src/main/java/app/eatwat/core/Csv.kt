package app.eatwat.core

import java.time.ZoneId
import kotlin.math.roundToLong

data class Reading(val ts: Long, val mgdl: Double, val source: String, val trend: Int? = null)

private const val MMOL_TO_MGDL = 18.016

/** Minimal RFC 4180 parser (quotes, escaped quotes, CRLF). */
fun parseCsvRows(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    val s = text.removePrefix("﻿")
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (quoted) {
            if (ch == '"') {
                if (i + 1 < s.length && s[i + 1] == '"') { field.append('"'); i++ } else quoted = false
            } else field.append(ch)
        } else when (ch) {
            '"' -> quoted = true
            ',' -> { row.add(field.toString()); field.setLength(0) }
            '\n', '\r' -> {
                if (ch == '\r' && i + 1 < s.length && s[i + 1] == '\n') i++
                row.add(field.toString()); field.setLength(0)
                rows.add(row); row = mutableListOf()
            }
            else -> field.append(ch)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
    return rows.filter { r -> r.any { it.isNotBlank() } }
}

data class CsvResult(
    val readings: List<Reading>,
    val dateOrder: DateOrder,
    val unit: String,
    val rowsTotal: Int,
    val rowsSkipped: Int,
)

private fun norm(h: String) = h.lowercase().replace(Regex("""\s+"""), " ").trim()

/**
 * Parse a LibreView "Download glucose data" CSV. Row 1 is metadata, row 2 the header.
 * Columns are found by name, with LibreView's usual positions as fallback.
 * Timestamps are local device time, read in [zone].
 */
fun parseLibreViewCsv(text: String, zone: ZoneId): CsvResult {
    val rows = parseCsvRows(text)
    val hi = (0 until minOf(rows.size, 5)).firstOrNull { i ->
        val r = rows[i].map(::norm)
        r.any { "timestamp" in it || "時間" in it } && r.any { "glucose" in it || "血糖" in it }
    } ?: -1
    val header = if (hi >= 0) rows[hi].map(::norm) else emptyList()
    fun idx(fallback: Int, pred: (String) -> Boolean) = header.indexOfFirst(pred).let { if (it >= 0) it else fallback }
    val cTs = idx(2) { "timestamp" in it || "時間" in it }
    val cType = idx(3) { "record type" in it || "記錄類型" in it || "紀錄類型" in it }
    val cHist = idx(4) { ("historic" in it || "歷史" in it) && ("glucose" in it || "血糖" in it) }
    val cScan = idx(5) { ("scan" in it || "掃描" in it) && ("glucose" in it || "血糖" in it) }
    val body = rows.drop(if (hi >= 0) hi + 1 else 0)

    val anyUnit = header.any { "mg/dl" in it || "mmol" in it }
    fun num(r: List<String>, c: Int) = r.getOrNull(c)?.trim()?.replace(',', '.')?.toDoubleOrNull()
    val guessMmol = !anyUnit && body.flatMap { listOfNotNull(num(it, cHist), num(it, cScan)) }.filter { it > 0 }
        .let { it.isNotEmpty() && it.max() < 35 }
    val histMmol = if (anyUnit) "mmol" in header.getOrElse(cHist) { "" } else guessMmol
    val scanMmol = if (anyUnit) "mmol" in header.getOrElse(cScan) { "" } else guessMmol

    val parsed = body.map { it to splitLocalTimestamp(it.getOrElse(cTs) { "" }) }
    val order = detectDateOrder(parsed.mapNotNull { it.second })
    val out = LinkedHashMap<Long, Reading>()
    var skipped = 0
    for ((r, p) in parsed) {
        if (p == null) { skipped++; continue }
        val type = r.getOrElse(cType) { "" }.trim()
        val (raw, mmol) = when (type) {
            "0" -> num(r, cHist) to histMmol
            "1" -> num(r, cScan) to scanMmol
            else -> { skipped++; continue }
        }
        val ts = partsToEpoch(p, order, zone)
        if (ts == null || raw == null || raw <= 0) { skipped++; continue }
        val mg = ((if (mmol) raw * MMOL_TO_MGDL else raw) * 10).roundToLong() / 10.0
        val key = toMinute(ts)
        if (!out.containsKey(key) || type == "0") out[key] = Reading(key, mg, "csv")
    }
    return CsvResult(out.values.sortedBy { it.ts }, order, if (histMmol) "mmol/L" else "mg/dL", body.size, skipped)
}
