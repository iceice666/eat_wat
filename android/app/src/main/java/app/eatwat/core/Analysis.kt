package app.eatwat.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class Point(val ts: Long, val mgdl: Double)

enum class Flag(val text: String) {
    NO_BASELINE("餐前 30 分鐘內沒有讀值"),
    BASELINE_AFTER("餐前值取自餐後 10 分鐘內的讀值"),
    GAP("中間有超過 30 分鐘沒有資料"),
    LOW_COVERAGE("讀值涵蓋不足"),
    OVERLAP("3 小時內又吃了別的"),
    PRIOR_MEAL("餐前 3 小時內吃過別的，餐前值可能還在上一餐的曲線上"),
    INCOMPLETE("餐後 3 小時還沒過完，或之後沒有資料"),
    NO_DATA("沒有餐後血糖資料"),
}

private val LOW_FLAGS = setOf(Flag.NO_BASELINE, Flag.GAP, Flag.LOW_COVERAGE, Flag.OVERLAP, Flag.PRIOR_MEAL, Flag.INCOMPLETE, Flag.NO_DATA)

data class MealResponse(
    val baseline: Double? = null,
    val baselineTs: Long? = null,
    val peak: Double? = null,
    val peakTs: Long? = null,
    val minutesToPeak: Int? = null,
    val delta: Double? = null,
    val at2h: Double? = null,
    val delta2h: Double? = null,
    /** Incremental AUC above baseline, mg/dL·h. */
    val iauc: Double? = null,
    val minutesToReturn: Int? = null,
    val readingsInWindow: Int = 0,
    val flags: List<Flag> = emptyList(),
) {
    val lowConfidence: Boolean get() = flags.any { it in LOW_FLAGS }
}

private fun r1(v: Double) = (v * 10).roundToLong() / 10.0

private fun interpolate(points: List<Point>, t: Long, maxGap: Long): Double? {
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        if (a.ts <= t && t <= b.ts) {
            if (b.ts - a.ts > maxGap) return null
            if (b.ts == a.ts) return a.mgdl
            return a.mgdl + (b.mgdl - a.mgdl) * (t - a.ts) / (b.ts - a.ts)
        }
    }
    return null
}

/** [points] sorted by ts. [otherMeals] are other meal timestamps (this meal is ignored if present). */
fun analyzeMeal(
    mealTs: Long,
    points: List<Point>,
    otherMeals: List<Long> = emptyList(),
    now: Long = System.currentTimeMillis(),
    windowMin: Int = 180,
    baselineMin: Int = 30,
    maxGapMin: Int = 30,
): MealResponse {
    val windowMs = windowMin * MIN_MS
    val maxGap = maxGapMin * MIN_MS
    val end = mealTs + windowMs
    val flags = mutableListOf<Flag>()

    var base: Point? = points.lastOrNull { it.ts >= mealTs - baselineMin * MIN_MS && it.ts <= mealTs }
    if (base == null) {
        base = points.firstOrNull { it.ts > mealTs && it.ts <= mealTs + 10 * MIN_MS }
        flags += if (base != null) Flag.BASELINE_AFTER else Flag.NO_BASELINE
    }
    if (otherMeals.any { it > mealTs && it < end }) flags += Flag.OVERLAP
    if (otherMeals.any { it < mealTs && it >= mealTs - windowMs }) flags += Flag.PRIOR_MEAL

    val win = points.filter { it.ts > mealTs && it.ts <= end }
    if (win.isEmpty()) {
        flags += Flag.NO_DATA
        if (end > now) flags += Flag.INCOMPLETE
        return MealResponse(baseline = base?.mgdl, baselineTs = base?.ts, flags = flags)
    }

    val series = if (base != null) listOf(base) + win.filter { it.ts != base.ts } else win
    var maxSeen = 0L
    for (i in 1 until series.size) maxSeen = max(maxSeen, series[i].ts - series[i - 1].ts)
    val lastTs = win.last().ts
    if (end > now || end - lastTs > maxGap) flags += Flag.INCOMPLETE
    if (maxSeen > maxGap) flags += Flag.GAP
    val covered = minOf(lastTs, end) - max(base?.ts ?: win.first().ts, mealTs)
    if (covered < windowMs * 0.7 && Flag.INCOMPLETE !in flags) flags += Flag.LOW_COVERAGE

    val peak = win.maxBy { it.mgdl }
    val baseline = base?.mgdl
    val at2h = interpolate(series, mealTs + 120 * MIN_MS, maxGap)

    var iauc: Double? = null
    if (baseline != null) {
        var area = 0.0
        for (i in 1 until series.size) {
            val a = series[i - 1]
            val b = series[i]
            val dt = (b.ts - a.ts).toDouble()
            if (dt <= 0 || dt > maxGap) continue
            val ya = a.mgdl - baseline
            val yb = b.mgdl - baseline
            if (ya >= 0 && yb >= 0) area += (ya + yb) / 2 * dt
            else if (ya > 0 || yb > 0) {
                val pos = max(ya, yb)
                area += pos / 2 * dt * (pos / (abs(ya) + abs(yb)))
            }
        }
        iauc = r1(area / HOUR_MS)
    }
    val back = if (baseline != null) win.firstOrNull { it.ts > peak.ts && it.mgdl <= baseline + 10 } else null

    return MealResponse(
        baseline = baseline,
        baselineTs = base?.ts,
        peak = peak.mgdl,
        peakTs = peak.ts,
        minutesToPeak = ((peak.ts - mealTs) / MIN_MS.toDouble()).roundToInt(),
        delta = baseline?.let { r1(peak.mgdl - it) },
        at2h = at2h?.let(::r1),
        delta2h = if (at2h != null && baseline != null) r1(at2h - baseline) else null,
        iauc = iauc,
        minutesToReturn = back?.let { ((it.ts - mealTs) / MIN_MS.toDouble()).roundToInt() },
        readingsInWindow = win.size,
        flags = flags,
    )
}

data class CurvePoint(val min: Int, val mgdl: Double, val delta: Double?)

/** Readings relative to the meal, for overlay charts. */
fun relativeCurve(mealTs: Long, points: List<Point>, baseline: Double?, windowMin: Int = 180): List<CurvePoint> =
    points.filter { it.ts >= mealTs - 30 * MIN_MS && it.ts <= mealTs + windowMin * MIN_MS }.map {
        CurvePoint(((it.ts - mealTs) / MIN_MS.toDouble()).roundToInt(), it.mgdl, baseline?.let { b -> r1(it.mgdl - b) })
    }
