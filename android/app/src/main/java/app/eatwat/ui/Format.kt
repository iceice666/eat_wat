package app.eatwat.ui

import app.eatwat.core.MIN_MS
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.roundToInt

private val WEEK = listOf("一", "二", "三", "四", "五", "六", "日")

private fun z(ts: Long): ZonedDateTime = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())

fun fmtTime(ts: Long): String = z(ts).let { String.format(Locale.ROOT, "%02d:%02d", it.hour, it.minute) }

fun fmtDate(d: LocalDate, withYear: Boolean = false): String =
    "${if (withYear) "${d.year}/" else ""}${d.monthValue}/${d.dayOfMonth}（${WEEK[d.dayOfWeek.value - 1]}）"

fun fmtDate(ts: Long): String = fmtDate(z(ts).toLocalDate())

fun fmtDateTime(ts: Long): String = "${fmtDate(ts)} ${fmtTime(ts)}"

fun ago(ts: Long): String {
    val m = ((System.currentTimeMillis() - ts) / MIN_MS.toDouble()).roundToInt()
    return when {
        m < 1 -> "剛剛"
        m < 60 -> "$m 分鐘前"
        m < 1440 -> "${m / 60} 小時前"
        else -> fmtDateTime(ts)
    }
}

fun fmtN(v: Double?): String = v?.let { it.roundToInt().toString() } ?: "—"

fun signed(v: Double?): String = v?.roundToInt()?.let { if (it > 0) "+$it" else "$it" } ?: "—"

fun trendArrow(t: Int?): String = when (t) {
    1 -> "↓"; 2 -> "↘"; 3 -> "→"; 4 -> "↗"; 5 -> "↑"; else -> ""
}

val MEAL_KINDS = listOf("早餐", "午餐", "晚餐", "點心", "飲料", "宵夜")

fun kindFor(ts: Long): String = when (z(ts).hour) {
    in 5..9 -> "早餐"
    in 10..13 -> "午餐"
    in 14..16 -> "點心"
    in 17..20 -> "晚餐"
    else -> "宵夜"
}
