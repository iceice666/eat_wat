package app.eatwat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.eatwat.core.HOUR_MS
import app.eatwat.core.MIN_MS
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(s: UiState, vm: AppViewModel, padding: PaddingValues, onOpenMeal: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    var picking by remember { mutableStateOf(false) }
    val from = s.day.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = s.day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val inDay = s.dayReadings.filter { it.ts in from until to }.map { it.mgdl }
    val meals = s.dayMeals.sortedBy { it.meal.ts }
    val viz = LocalViz.current

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { vm.setDay(s.day.minusDays(1)) }) { Text("‹", fontSize = 20.sp) }
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (s.day == today) "今天 · ${fmtDate(s.day)}" else fmtDate(s.day, s.day.year != today.year), fontWeight = FontWeight.SemiBold)
                }
                FilledTonalIconButton(onClick = { vm.setDay(s.day.plusDays(1)) }, enabled = s.day < today) { Text("›", fontSize = 20.sp) }
            }
        }
        if (inDay.isNotEmpty()) item {
            TileGrid(
                listOf(
                    Triple("平均", fmtN(inDay.average()), "mg/dL"),
                    Triple("目標範圍內", fmtN(inDay.count { it in 70.0..180.0 } * 100.0 / inDay.size), "%"),
                    Triple("最高", fmtN(inDay.max()), "mg/dL"),
                    Triple("最低", fmtN(inDay.min()), "mg/dL"),
                ),
                columns = 2,
            )
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("血糖與飲食", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text("mg/dL · 目標 70–180", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val ys = s.dayReadings.map { it.mgdl }
                val yLo = minOf(50.0, (ys.minOrNull() ?: 60.0) - 10)
                val yHi = maxOf(220.0, (ys.maxOrNull() ?: 0.0) + 20)
                LineChart(
                    modifier = Modifier.fillMaxWidth().height(230.dp).padding(top = 6.dp),
                    xRange = from.toDouble() to to.toDouble(),
                    yRange = yLo to yHi,
                    xTicks = (0..24 step 3).map { h -> XTick((from + h * HOUR_MS).toDouble(), if (h == 24) "" else "%02d:00".format(h), minor = h % 6 != 0) },
                    yTicks = Y_TICKS.filter { it in yLo..yHi },
                    band = 70.0 to 180.0,
                    gap = 31.0 * MIN_MS,
                    series = listOf(ChartSeries(viz.series[0], "血糖", s.dayReadings.map { ChartPoint(it.ts.toDouble(), it.mgdl) })),
                    markers = meals.mapIndexed { i, m -> ChartMarker(m.meal.ts.toDouble(), "${i + 1}") { onOpenMeal(m.meal.id) } },
                    tooltip = { x, hits -> listOf(fmtTime(x.toLong()), "${fmtN(hits.first().point.y)} mg/dL") },
                )
                if (s.dayReadings.isEmpty()) {
                    Text(
                        s.status.lastError?.let { "這天沒有血糖資料。同步錯誤：$it" }
                            ?: "這天沒有血糖資料。同步後會出現；更早的日子可以到「設定」匯入 LibreView CSV。",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Text("這天吃了", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, start = 2.dp)) }
        if (meals.isEmpty()) item {
            Text("還沒有紀錄。按右下角「＋」記錄。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }
        itemsIndexed(meals, key = { _, m -> m.meal.id }) { i, m -> MealListItem(i + 1, m) { onOpenMeal(m.meal.id) } }
    }

    if (picking) {
        val st = rememberDatePickerState(
            initialSelectedDateMillis = s.day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { vm.setDay(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text("確定") }
            },
            dismissButton = { TextButton(onClick = { vm.setDay(today); picking = false }) { Text("今天") } },
        ) { DatePicker(st) }
    }
}

@Composable
fun MealListItem(n: Int, m: MealRow, onClick: () -> Unit) {
    val r = m.response
    val viz = LocalViz.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Pin("$n")
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(m.meal.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (m.meal.kind.isNotEmpty()) { Chip(m.meal.kind); Spacer(Modifier.width(6.dp)) }
                    Text(
                        fmtTime(m.meal.ts) + (m.meal.carbs?.let { " · 醣類 ${fmtN(it)}g" } ?: ""),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            m.meal.photo?.let { PhotoThumb(it, 44.dp, Modifier.padding(end = 10.dp)) }
            Column(horizontalAlignment = Alignment.End) {
                when {
                    r.delta != null -> {
                        Text(signed(r.delta), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        val sub = if (m.inProgress) "進行中 · 餐後 ${(System.currentTimeMillis() - m.meal.ts) / MIN_MS} 分"
                        else "${r.minutesToPeak} 分到高峰 ${fmtN(r.peak)}"
                        Row {
                            Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (r.lowConfidence && !m.inProgress) Text(" ⚠", fontSize = 11.sp, color = viz.warn)
                        }
                    }
                    m.inProgress -> Text("等待資料…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Text("無血糖資料", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
