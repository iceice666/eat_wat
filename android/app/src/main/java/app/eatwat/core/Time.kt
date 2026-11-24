package app.eatwat.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

const val MIN_MS = 60_000L
const val HOUR_MS = 3_600_000L
const val DAY_MS = 86_400_000L

/** Round to the nearest minute; used as the reading key. */
fun toMinute(ms: Long): Long = Math.floorDiv(ms + MIN_MS / 2, MIN_MS) * MIN_MS

private val US_TS = Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?\s*([AaPp][Mm])?$""")
private val ISO_TS = Regex("""^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$""")

private fun hour12(h: Int, ampm: String?): Int {
    if (ampm == null) return h
    val pm = ampm.uppercase() == "PM"
    return when {
        h == 12 -> if (pm) 12 else 0
        pm -> h + 12
        else -> h
    }
}

/**
 * Parse a LibreLinkUp `FactoryTimestamp`, which is UTC in the form
 * "11/24/2026 10:05:00 AM" (confirmed against the live API). Also accepts ISO.
 * Parsing is locale-independent (no SimpleDateFormat). Returns epoch ms or null.
 */
fun parseLluTimestamp(raw: Any?): Long? {
    if (raw is Number) {
        val v = raw.toLong()
        return if (v < 1_000_000_000_000L) v * 1000 else v
    }
    val s = (raw as? String)?.trim().orEmpty()
    if (s.isEmpty()) return null
    US_TS.matchEntire(s)?.let { m ->
        val g = m.groupValues
        return runCatching {
            LocalDateTime.of(g[3].toInt(), g[1].toInt(), g[2].toInt(), hour12(g[4].toInt(), g[7].ifEmpty { null }), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
                .toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }
    ISO_TS.matchEntire(s)?.let { m ->
        val g = m.groupValues
        return runCatching {
            val ldt = LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
            val zone = g[7]
            val off = when {
                zone.isEmpty() || zone == "Z" -> ZoneOffset.UTC
                else -> ZoneOffset.of(if (zone.contains(":")) zone else zone.substring(0, 3) + ":" + zone.substring(3))
            }
            ldt.toInstant(off).toEpochMilli()
        }.getOrNull()
    }
    return null
}

enum class DateOrder { MDY, DMY, YMD }

data class LocalParts(val a: Int, val b: Int, val c: Int, val h: Int, val mi: Int, val ss: Int, val ymd: Boolean)

private val LOCAL_TS = Regex("""^(\d{1,4})[-/.](\d{1,2})[-/.](\d{1,4})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?$""")
private val MERIDIEM = Regex("""上午|下午|[AaPp]\.?[Mm]\.?""")

/** Split a local CSV timestamp ("11-24-2026 10:05 PM", "24-11-2026 22:05", "2026/11/24 下午 10:05"). */
fun splitLocalTimestamp(raw: String): LocalParts? {
    var s = raw.trim()
    if (s.isEmpty()) return null
    var meridiem: String? = null
    if (Regex("""上午|AM|am|a\.m\.""").containsMatchIn(s)) meridiem = "AM"
    if (Regex("""下午|PM|pm|p\.m\.""").containsMatchIn(s)) meridiem = "PM"
    s = s.replace(MERIDIEM, " ").replace(Regex("""\s+"""), " ").trim()
    val m = LOCAL_TS.matchEntire(s) ?: return null
    val g = m.groupValues
    return LocalParts(
        a = g[1].toInt(), b = g[2].toInt(), c = g[3].toInt(),
        h = hour12(g[4].toInt(), meridiem), mi = g[5].toInt(), ss = g[6].ifEmpty { "0" }.toInt(),
        ymd = g[1].length == 4,
    )
}

/** Any first field > 12 means DMY; ambiguous data defaults to MDY (LibreView's US default). */
fun detectDateOrder(samples: List<LocalParts>): DateOrder {
    if (samples.isNotEmpty() && samples.all { it.ymd }) return DateOrder.YMD
    var firstOver = false
    var secondOver = false
    for (p in samples) {
        if (p.ymd) continue
        if (p.a > 12) firstOver = true
        if (p.b > 12) secondOver = true
    }
    return if (firstOver && !secondOver) DateOrder.DMY else DateOrder.MDY
}

fun partsToEpoch(p: LocalParts, order: DateOrder, zone: ZoneId): Long? {
    var (y, mo, d) = when {
        p.ymd || order == DateOrder.YMD -> Triple(p.a, p.b, p.c)
        order == DateOrder.DMY -> Triple(p.c, p.b, p.a)
        else -> Triple(p.c, p.a, p.b)
    }
    if (y < 100) y += 2000
    if (mo !in 1..12 || d !in 1..31 || p.h > 23 || p.mi > 59) return null
    return runCatching { LocalDateTime.of(y, mo, d, p.h, p.mi, p.ss).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
}
