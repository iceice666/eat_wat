package app.eatwat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val RANGES = listOf(7, 14, 30, 90)
private val SORTS = listOf(SortKey.TIME to "時間", SortKey.DELTA to "上升", SortKey.IAUC to "iAUC", SortKey.TO_PEAK to "到高峰", SortKey.DELTA2H to "2h", SortKey.CARBS to "醣類", SortKey.TITLE to "名稱")

private fun sortValue(m: MealRow, k: SortKey): Comparable<*>? = when (k) {
    SortKey.TIME -> m.meal.ts
    SortKey.TITLE -> m.meal.title
    SortKey.CARBS -> m.meal.carbs
    SortKey.DELTA -> m.response.delta
    SortKey.TO_PEAK -> m.response.minutesToPeak
    SortKey.DELTA2H -> m.response.delta2h
    SortKey.IAUC -> m.response.iauc
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisScreen(s: UiState, vm: AppViewModel, padding: PaddingValues, onOpenMeal: (Long) -> Unit) {
    val viz = LocalViz.current
    val q = s.filter.trim().lowercase()
    val rows = s.analysis.filter { q.isEmpty() || "${it.meal.title} ${it.meal.note} ${it.meal.kind}".lowercase().contains(q) }
        .sortedWith { a, b ->
            @Suppress("UNCHECKED_CAST")
            val va = sortValue(a, s.sort) as Comparable<Any>?
            @Suppress("UNCHECKED_CAST")
            val vb = sortValue(b, s.sort) as Comparable<Any>?
            when {
                va == null && vb == null -> 0
                va == null -> 1
                vb == null -> -1
                else -> if (s.sortDesc) vb.compareTo(va) else va.compareTo(vb)
            }
        }
    val selected = s.analysis.filter { it.meal.id in s.slots }.sortedBy { s.slots[it.meal.id] }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RANGES.forEachIndexed { i, d ->
                    SegmentedButton(
                        selected = s.rangeDays == d, onClick = { vm.setRange(d) },
                        shape = SegmentedButtonDefaults.itemShape(i, RANGES.size),
                    ) { Text("$d 天") }
                }
            }
        }
        item {
            OutlinedTextField(
                value = s.filter, onValueChange = vm::setFilter, singleLine = true,
                placeholder = { Text("搜尋餐點，例如：拉麵") }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
            )
        }
        item {
            SectionCard {
                Text("餐後血糖對照", fontWeight = FontWeight.SemiBold)
                Text("相對餐前的變化（mg/dL）· 勾選下方餐點，最多 8 筆", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (selected.isEmpty()) {
                    Text("勾選下方的餐點來疊圖比較。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
                } else {
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        selected.forEach { m ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(14.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(viz.series[s.slots[m.meal.id]!!]))
                                Text(" ${m.meal.title}", fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Text(" ${fmtDate(m.meal.ts)}${if (m.response.baseline == null) " · 無餐前值" else ""}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    val series = selected.map { m ->
                        ChartSeries(viz.series[s.slots[m.meal.id]!!], m.meal.title, m.curve.filter { it.delta != null }.map { ChartPoint(it.min.toDouble(), it.delta!!) })
                    }
                    val ys = series.flatMap { it.points.map { p -> p.y } }
                    val yLo = minOf(-20.0, ys.minOrNull() ?: 0.0) - 5
                    val yHi = maxOf(60.0, ys.maxOrNull() ?: 0.0) + 10
                    val step = if (yHi - yLo > 160) 50 else 20
                    val yTicks = generateSequence(Math.ceil(yLo / step) * step) { it + step }.takeWhile { it <= yHi }.toList()
                    LineChart(
                        modifier = Modifier.fillMaxWidth().height(240.dp).padding(top = 6.dp),
                        xRange = -30.0 to 180.0,
                        yRange = yLo to yHi,
                        xTicks = listOf(-30, 0, 30, 60, 90, 120, 150, 180).map { XTick(it.toDouble(), if (it == 0) "用餐" else "${if (it > 0) "+" else ""}$it", minor = it % 60 != 0) },
                        yTicks = yTicks,
                        hlines = listOf(HLine(0.0, dashed = false)),
                        gap = 31.0,
                        series = series,
                        endLabels = series.size <= 4,
                        tooltip = { x, hits ->
                            listOf("${if (x >= 0) "餐後" else "餐前"} ${Math.abs(x).toInt()} 分") +
                                hits.sortedByDescending { it.point.y }.map { "${it.series.label.take(10)}  ${signed(it.point.y)}" }
                        },
                    )
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("排序", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SORTS.forEach { (k, label) ->
                    FilterChip(selected = s.sort == k, onClick = { vm.setSort(k) }, label = { Text(if (s.sort == k) "$label ${if (s.sortDesc) "▾" else "▴"}" else label) })
                }
            }
        }
        if (rows.isEmpty()) item {
            Text("這段期間沒有飲食紀錄。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }
        items(rows, key = { it.meal.id }) { m -> AnalysisRow(m, s.slots[m.meal.id], { vm.toggleSlot(m.meal.id) }, { onOpenMeal(m.meal.id) }) }
        item {
            Text(
                "⚠ 表示資料不完整（缺資料、餐前沒有讀值、前後 3 小時內有其他餐），數字僅供參考。iAUC 是餐後 3 小時內高於餐前值的面積，單位 mg/dL·小時。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp),
            )
        }
    }
}

@Composable
private fun AnalysisRow(m: MealRow, slot: Int?, onToggle: () -> Unit, onOpen: () -> Unit) {
    val r = m.response
    val viz = LocalViz.current
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        color = if (slot != null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(end = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Checkbox(checked = slot != null, onCheckedChange = { onToggle() })
                if (slot != null) Box(Modifier.padding(end = 8.dp, bottom = 8.dp).size(8.dp).clip(RoundedCornerShape(2.dp)).background(viz.series[slot]))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.meal.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    when {
                        m.inProgress -> { Spacer(Modifier.width(6.dp)); Chip("進行中") }
                        r.lowConfidence -> Text(" ⚠", color = viz.warn, fontSize = 13.sp)
                    }
                }
                Text(
                    "${fmtDateTime(m.meal.ts)}${m.meal.carbs?.let { " · 醣類 ${fmtN(it)}g" } ?: ""}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Metric("上升", signed(r.delta), bold = true)
                    Metric("到高峰", r.minutesToPeak?.let { "$it 分" } ?: "—")
                    Metric("2h", signed(r.delta2h))
                    Metric("iAUC", fmtN(r.iauc))
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, bold: Boolean = false) {
    Column {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}
