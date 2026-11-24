package app.eatwat.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.eatwat.core.MIN_MS
import app.eatwat.data.Meal
import app.eatwat.data.Photos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MealEditor(initial: Meal?, defaultDay: LocalDate, titles: List<String>, onDismiss: () -> Unit, onSave: (Meal, Uri?, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val startTs = initial?.ts ?: if (defaultDay == LocalDate.now()) now
    else defaultDay.atTime(LocalTime.now()).atZone(zone).toInstant().toEpochMilli()

    var ts by rememberSaveable { mutableStateOf(startTs) }
    var kind by rememberSaveable { mutableStateOf(initial?.kind ?: kindFor(startTs)) }
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var carbs by rememberSaveable { mutableStateOf(initial?.carbs?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(initial?.note.orEmpty()) }
    var newPhoto by rememberSaveable { mutableStateOf<Uri?>(null) }
    var removePhoto by rememberSaveable { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) { newPhoto = uri; removePhoto = false } }
    val cameraUri = remember { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", Photos.cameraTemp(ctx)) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) { newPhoto = cameraUri; removePhoto = false } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Text(if (initial == null) "記錄飲食" else "編輯紀錄", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    Button(
                        enabled = !saving,
                        onClick = {
                            val t = title.trim()
                            val c = carbs.trim().replace(',', '.')
                            val cv = if (c.isEmpty()) null else c.toDoubleOrNull()
                            error = when {
                                t.isEmpty() -> "請填寫吃了什麼"
                                c.isNotEmpty() && (cv == null || cv < 0 || cv > 1000) -> "醣類克數不正確"
                                ts > now + 2 * 86_400_000L -> "時間不能在太遠的未來"
                                else -> null
                            }
                            if (error == null) {
                                saving = true
                                onSave(Meal(initial?.id ?: 0, ts, t.take(200), kind, cv, note.take(2000), initial?.photo), newPhoto, removePhoto)
                            }
                        },
                    ) { Text("儲存") }
                }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickDate = true }) { Text(fmtDate(ts)) }
                        OutlinedButton(onClick = { pickTime = true }) { Text(fmtTime(ts)) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MEAL_KINDS.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k) }) }
                    }
                    OutlinedTextField(title, { title = it }, label = { Text("吃了什麼") }, placeholder = { Text("例如：牛肉麵＋燙青菜") }, modifier = Modifier.fillMaxWidth())
                    val sugg = titles.filter { it != title && (title.isBlank() || it.contains(title.trim(), ignoreCase = true)) }.take(8)
                    if (sugg.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            sugg.forEach { t -> SuggestionChip(onClick = { title = t }, label = { Text(t, maxLines = 1) }) }
                        }
                    }
                    OutlinedTextField(
                        carbs, { carbs = it }, label = { Text("醣類（克，選填）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(note, { note = it }, label = { Text("備註") }, placeholder = { Text("份量、運動、心情…") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    Text("照片", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { camera.launch(cameraUri) }) { Text("拍照") }
                        OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("從相簿") }
                        if (newPhoto != null || (initial?.photo != null && !removePhoto)) {
                            TextButton(onClick = { newPhoto = null; removePhoto = initial?.photo != null }) { Text("移除") }
                        }
                    }
                    when {
                        newPhoto != null -> UriPreview(newPhoto!!)
                        initial?.photo != null && !removePhoto -> PhotoBanner(initial.photo, Modifier.fillMaxWidth().height(200.dp))
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
            }
        }
    }

    if (pickDate) {
        val local = Instant.ofEpochMilli(ts).atZone(zone)
        val st = rememberDatePickerState(initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let {
                        val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        ts = d.atTime(local.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
                        if (initial == null) kind = kindFor(ts)
                    }
                    pickDate = false
                }) { Text("確定") }
            },
        ) { DatePicker(st) }
    }
    if (pickTime) {
        val local = Instant.ofEpochMilli(ts).atZone(zone)
        val st = rememberTimePickerState(local.hour, local.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    ts = local.withHour(st.hour).withMinute(st.minute).withSecond(0).toInstant().toEpochMilli()
                    if (initial == null) kind = kindFor(ts)
                    pickTime = false
                }) { Text("確定") }
            },
            text = { TimePicker(st) },
        )
    }
}

@Composable
private fun UriPreview(uri: Uri) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            }.getOrNull()
        }
    }
    bmp?.let {
        Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealDetailSheet(m: MealRow, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val r = m.response
    val viz = LocalViz.current
    var confirm by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "${if (m.meal.kind.isNotEmpty()) "${m.meal.kind} · " else ""}${fmtDateTime(m.meal.ts)}${m.meal.carbs?.let { " · 醣類 ${fmtN(it)}g" } ?: ""}",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(m.meal.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            m.meal.photo?.let { PhotoBanner(it, Modifier.fillMaxWidth().height(200.dp)) }
            if (m.meal.note.isNotBlank()) Text(m.meal.note, fontSize = 14.sp)
            val pts = m.curve.map { ChartPoint(it.min.toDouble(), it.mgdl) }
            val ys = pts.map { it.y }
            val yLo = minOf(60.0, (ys.minOrNull() ?: 70.0) - 10)
            val yHi = maxOf(200.0, (ys.maxOrNull() ?: 0.0) + 20)
            LineChart(
                modifier = Modifier.fillMaxWidth().height(210.dp),
                xRange = -30.0 to 180.0,
                yRange = yLo to yHi,
                xTicks = listOf(-30, 0, 30, 60, 90, 120, 150, 180).map { XTick(it.toDouble(), if (it == 0) "用餐" else "${if (it > 0) "+" else ""}$it", it % 60 != 0) },
                yTicks = Y_TICKS.filter { it in yLo..yHi },
                band = 70.0 to 180.0,
                hlines = r.baseline?.let { listOf(HLine(it, "餐前 ${fmtN(it)}")) } ?: emptyList(),
                gap = 31.0,
                series = listOf(ChartSeries(viz.series[0], "血糖", pts)),
                markers = listOf(ChartMarker(0.0, "")),
                tooltip = { x, hits -> listOf("${if (x >= 0) "餐後" else "餐前"} ${Math.abs(x).toInt()} 分", "${fmtN(hits.first().point.y)} mg/dL") },
            )
            if (pts.isEmpty()) Text("這段時間沒有血糖資料。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TileGrid(
                listOf(
                    Triple("餐前", fmtN(r.baseline), "mg/dL"),
                    Triple("高峰", fmtN(r.peak), "mg/dL"),
                    Triple("上升", signed(r.delta), "mg/dL"),
                    Triple("到高峰", r.minutesToPeak?.toString() ?: "—", "分"),
                    Triple("2 小時後", signed(r.delta2h), "mg/dL"),
                    Triple("iAUC", fmtN(r.iauc), "mg/dL·h"),
                ),
                columns = 3,
            )
            if (m.inProgress) Text("進行中：餐後 ${(System.currentTimeMillis() - m.meal.ts) / MIN_MS} 分，數字還會變。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (r.flags.isNotEmpty()) Text("⚠ " + r.flags.joinToString("；") { it.text }, fontSize = 13.sp, color = viz.warn)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { confirm = true }) { Text("刪除", color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onEdit) { Text("編輯") }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("刪除這筆紀錄？") },
            text = { Text(m.meal.title) },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("刪除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}
