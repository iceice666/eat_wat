package app.eatwat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

data class ChartPoint(val x: Double, val y: Double)
data class ChartSeries(val color: Color, val label: String, val points: List<ChartPoint>)
data class ChartMarker(val x: Double, val label: String, val onClick: (() -> Unit)? = null)
data class XTick(val x: Double, val label: String, val minor: Boolean = false)
data class HLine(val y: Double, val label: String? = null, val dashed: Boolean = true)
data class Hit(val series: ChartSeries, val point: ChartPoint)

/**
 * Line chart with optional target band, meal pins and a scrub crosshair.
 * Lines break where consecutive points are more than [gap] apart.
 * Tap a pin to open it; touch or drag anywhere else to read values.
 */
@Composable
fun LineChart(
    modifier: Modifier,
    xRange: Pair<Double, Double>,
    yRange: Pair<Double, Double>,
    xTicks: List<XTick>,
    yTicks: List<Double>,
    series: List<ChartSeries>,
    gap: Double,
    tooltip: (Double, List<Hit>) -> List<String>,
    band: Pair<Double, Double>? = null,
    hlines: List<HLine> = emptyList(),
    markers: List<ChartMarker> = emptyList(),
    endLabels: Boolean = false,
) {
    val viz = LocalViz.current
    val tm = rememberTextMeasurer()
    var hoverX by remember { mutableStateOf<Float?>(null) }
    val axisStyle = TextStyle(fontSize = 10.sp, color = viz.axisText)
    val inkStyle = TextStyle(fontSize = 10.sp, color = viz.ink)
    val pinStyle = TextStyle(fontSize = 9.sp, color = viz.ink, fontWeight = FontWeight.Bold)
    val tipStyle = TextStyle(fontSize = 12.sp, color = viz.ink)
    val tipBold = TextStyle(fontSize = 13.sp, color = viz.ink, fontWeight = FontWeight.Bold)

    Canvas(
        modifier
            .pointerInput(markers, xRange) {
                val ml = 34.dp.toPx(); val mr = (if (endLabels) 64.dp else 10.dp).toPx()
                val top = (if (markers.isNotEmpty()) 24.dp else 8.dp).toPx()
                detectTapGestures(
                    onPress = { o ->
                        val iw = size.width - ml - mr
                        val hit = markers.firstOrNull { mk ->
                            val mx = ml + ((mk.x - xRange.first) / (xRange.second - xRange.first) * iw).toFloat()
                            mk.onClick != null && abs(o.x - mx) < 16.dp.toPx() && o.y < top + 4.dp.toPx()
                        }
                        if (hit != null) { hoverX = null; hit.onClick?.invoke() } else {
                            hoverX = o.x
                            tryAwaitRelease()
                        }
                    },
                )
            }
            .pointerInput(xRange) {
                detectDragGestures(
                    onDragStart = { hoverX = it.x },
                    onDrag = { c, _ -> hoverX = c.position.x },
                    onDragEnd = {},
                )
            },
    ) {
        val ml = 34.dp.toPx(); val mr = (if (endLabels) 64.dp else 10.dp).toPx()
        val mt = (if (markers.isNotEmpty()) 24.dp else 8.dp).toPx(); val mb = 20.dp.toPx()
        val iw = (size.width - ml - mr).coerceAtLeast(10f)
        val ih = (size.height - mt - mb).coerceAtLeast(10f)
        val (x0, x1) = xRange
        val (y0, y1) = yRange
        fun sx(v: Double) = ml + ((v - x0) / (x1 - x0) * iw).toFloat()
        fun sy(v: Double) = mt + ((1 - (v - y0) / (y1 - y0)) * ih).toFloat()
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

        band?.let { (lo, hi) ->
            drawRect(viz.band, Offset(ml, sy(hi)), Size(iw, sy(lo) - sy(hi)))
            for (v in listOf(lo, hi)) drawLine(viz.bandEdge, Offset(ml, sy(v)), Offset(ml + iw, sy(v)), 1.dp.toPx(), pathEffect = dash)
        }
        for (v in yTicks) {
            if (band == null || (v != band.first && v != band.second)) drawLine(viz.grid, Offset(ml, sy(v)), Offset(ml + iw, sy(v)), 1f)
            val t = tm.measure(fmt0(v), axisStyle)
            drawText(t, topLeft = Offset(ml - 6.dp.toPx() - t.size.width, sy(v) - t.size.height / 2f))
        }
        val maxLabel = xTicks.maxOfOrNull { tm.measure(it.label, axisStyle).size.width } ?: 0
        val crowded = iw / (xTicks.size - 1).coerceAtLeast(1) < maxLabel + 8.dp.toPx()
        for (tk in xTicks) {
            if (crowded && tk.minor) continue
            val t = tm.measure(tk.label, axisStyle)
            drawText(t, topLeft = Offset(sx(tk.x) - t.size.width / 2f, size.height - t.size.height))
        }
        for (h in hlines) {
            drawLine(viz.ink, Offset(ml, sy(h.y)), Offset(ml + iw, sy(h.y)), 1.dp.toPx(), pathEffect = if (h.dashed) dash else null)
            h.label?.let {
                val t = tm.measure(it, inkStyle)
                drawText(t, topLeft = Offset(ml + iw - t.size.width - 2.dp.toPx(), sy(h.y) - t.size.height - 1.dp.toPx()))
            }
        }

        clipRect(ml, mt - 4.dp.toPx(), ml + iw, mt + ih + 4.dp.toPx()) {
            for (s in series) {
                val path = Path()
                var prev: ChartPoint? = null
                s.points.forEachIndexed { i, p ->
                    if (prev == null || p.x - prev!!.x > gap) path.moveTo(sx(p.x), sy(p.y)) else path.lineTo(sx(p.x), sy(p.y))
                    val a = s.points.getOrNull(i - 1)
                    val b = s.points.getOrNull(i + 1)
                    if ((a == null || p.x - a.x > gap) && (b == null || b.x - p.x > gap)) drawCircle(s.color, 2.5.dp.toPx(), Offset(sx(p.x), sy(p.y)))
                    prev = p
                }
                drawPath(path, s.color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }

        if (endLabels && series.size <= 4) {
            val placed = mutableListOf<Float>()
            for (s in series) {
                val last = s.points.lastOrNull() ?: continue
                val label = if (s.label.length > 6) s.label.take(6) + "…" else s.label
                val t = tm.measure(label, inkStyle)
                var y = sy(last.y) - t.size.height / 2f
                for (py in placed) if (abs(py - y) < t.size.height) y = py + t.size.height
                placed += y
                drawText(t, topLeft = Offset(sx(last.x) + 5.dp.toPx(), y))
            }
        }

        for (mk in markers) {
            val x = sx(mk.x)
            if (x < ml - 1 || x > ml + iw + 1) continue
            drawLine(viz.meal.copy(alpha = 0.6f), Offset(x, mt), Offset(x, mt + ih), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 6f)))
            val c = Offset(x, mt - 11.dp.toPx())
            drawCircle(viz.surface, 9.dp.toPx(), c)
            drawCircle(viz.meal, 9.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
            if (mk.label.isNotEmpty()) {
                val t = tm.measure(mk.label, pinStyle)
                drawText(t, topLeft = Offset(x - t.size.width / 2f, c.y - t.size.height / 2f))
            }
        }

        // Crosshair + tooltip.
        val hx = hoverX
        if (hx != null && hx >= ml && hx <= ml + iw) {
            val xv = x0 + (hx - ml) / iw * (x1 - x0)
            val hits = series.mapNotNull { s ->
                val best = s.points.minByOrNull { abs(it.x - xv) } ?: return@mapNotNull null
                if (abs(best.x - xv) <= gap / 2) Hit(s, best) else null
            }
            if (hits.isNotEmpty()) {
                val snap = sx(hits.first().point.x)
                drawLine(viz.ink, Offset(snap, mt), Offset(snap, mt + ih), 1.dp.toPx())
                for (h in hits) {
                    val o = Offset(sx(h.point.x), sy(h.point.y))
                    drawCircle(viz.surface, 5.dp.toPx(), o)
                    drawCircle(h.series.color, 3.5.dp.toPx(), o)
                }
                val lines = tooltip(hits.first().point.x, hits)
                val measured = lines.mapIndexed { i, s -> tm.measure(s, if (i == 0) tipStyle else tipBold) }
                val pad = 8.dp.toPx()
                val w = measured.maxOf { it.size.width } + pad * 2
                val h = measured.sumOf { it.size.height } + pad * 2
                var tx = snap + 10.dp.toPx()
                if (tx + w > size.width) tx = snap - w - 10.dp.toPx()
                val ty = mt
                drawRoundRect(viz.surface, Offset(tx, ty), Size(w, h), CornerRadius(8.dp.toPx()))
                drawRoundRect(viz.grid, Offset(tx, ty), Size(w, h), CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
                var yy = ty + pad
                for (m in measured) { drawText(m, topLeft = Offset(tx + pad, yy)); yy += m.size.height }
            }
        }
    }
}

fun fmt0(v: Double): String = if (v == Math.rint(v)) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.0f", v)
