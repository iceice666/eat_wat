package app.eatwat.ui

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.eatwat.core.CurvePoint
import app.eatwat.core.DAY_MS
import app.eatwat.core.MIN_MS
import app.eatwat.core.MealResponse
import app.eatwat.core.Point
import app.eatwat.core.Reading
import app.eatwat.core.analyzeMeal
import app.eatwat.core.parseLibreViewCsv
import app.eatwat.core.relativeCurve
import app.eatwat.data.Db
import app.eatwat.data.Meal
import app.eatwat.data.Photos
import app.eatwat.data.ReadingStats
import app.eatwat.data.SecureStore
import app.eatwat.data.SyncRunner
import app.eatwat.data.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class MealRow(val meal: Meal, val response: MealResponse, val curve: List<CurvePoint>) {
    val inProgress: Boolean get() = System.currentTimeMillis() < meal.ts + 180 * MIN_MS
}

data class SyncStatus(
    val hasCredentials: Boolean = false,
    val email: String = "",
    val mock: Boolean = false,
    val paused: Boolean = false,
    val lastAttempt: Long? = null,
    val lastSuccess: Long? = null,
    val lastError: String? = null,
    val region: String = "",
    val patientName: String? = null,
    val connections: String = "",
    val timeWarning: String? = null,
)

enum class SortKey { TIME, TITLE, CARBS, DELTA, TO_PEAK, DELTA2H, IAUC }

data class UiState(
    val day: LocalDate = LocalDate.now(),
    val dayReadings: List<Reading> = emptyList(),
    val dayMeals: List<MealRow> = emptyList(),
    val rangeDays: Int = 14,
    val analysis: List<MealRow> = emptyList(),
    /** meal id -> color slot; the color follows the meal, not its rank. */
    val slots: Map<Long, Int> = emptyMap(),
    val filter: String = "",
    val sort: SortKey = SortKey.TIME,
    val sortDesc: Boolean = true,
    val latest: Reading? = null,
    val stats: ReadingStats = ReadingStats(0, null, null),
    val status: SyncStatus = SyncStatus(),
    val titles: List<String> = emptyList(),
    val syncing: Boolean = false,
    val message: String? = null,
    val loaded: Boolean = false,
)

const val MAX_SLOTS = 8

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val db = Db.get(app)
    private val secure = SecureStore(app)
    private val syncState = SyncState(app)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshAll() }

    init {
        syncState.prefs.registerOnSharedPreferenceChangeListener(prefListener)
        refreshAll()
    }

    override fun onCleared() {
        syncState.prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    private fun dayStart(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun analyze(m: Meal, now: Long): MealRow {
        val pts = db.readings(m.ts - 40 * MIN_MS, m.ts + 185 * MIN_MS).map { Point(it.ts, it.mgdl) }
        val others = db.meals(m.ts - 180 * MIN_MS, m.ts + 180 * MIN_MS).filter { it.id != m.id }.map { it.ts }
        val r = analyzeMeal(m.ts, pts, others, now)
        return MealRow(m, r, relativeCurve(m.ts, pts, r.baseline))
    }

    fun refreshAll() {
        viewModelScope.launch {
            val s = _ui.value
            val now = System.currentTimeMillis()
            val next = withContext(Dispatchers.IO) {
                val from = dayStart(s.day)
                val to = dayStart(s.day.plusDays(1)) - 1
                val rangeFrom = dayStart(LocalDate.now().minusDays((s.rangeDays - 1).toLong()))
                val analysis = db.meals(rangeFrom, now + DAY_MS).map { analyze(it, now) }
                val ids = analysis.map { it.meal.id }.toSet()
                var slots = s.slots.filterKeys { it in ids }
                if (slots.isEmpty() && s.slots.isEmpty()) {
                    slots = analysis.filter { it.response.delta != null && it.response.baseline != null && !it.inProgress }
                        .sortedByDescending { it.response.delta }.take(3).mapIndexed { i, r -> r.meal.id to i }.toMap()
                }
                s.copy(
                    loaded = true,
                    dayReadings = db.readings(from - 30 * MIN_MS, to + 30 * MIN_MS),
                    dayMeals = db.meals(from, to).map { analyze(it, now) },
                    analysis = analysis,
                    slots = slots,
                    latest = db.latestReading(),
                    stats = db.readingStats(),
                    titles = db.recentTitles(),
                    status = SyncStatus(
                        hasCredentials = secure.hasCredentials, email = secure.email, mock = syncState.mock,
                        paused = syncState.paused, lastAttempt = syncState.lastAttempt, lastSuccess = syncState.lastSuccess,
                        lastError = syncState.lastError, region = syncState.region, patientName = syncState.patientName,
                        connections = syncState.connections, timeWarning = syncState.timeWarning,
                    ),
                )
            }
            _ui.update { cur -> next.copy(day = cur.day, rangeDays = cur.rangeDays, filter = cur.filter, sort = cur.sort, sortDesc = cur.sortDesc, syncing = cur.syncing, message = cur.message) }
        }
    }

    fun setDay(d: LocalDate) {
        _ui.update { it.copy(day = d) }
        refreshAll()
    }

    fun setRange(days: Int) {
        _ui.update { it.copy(rangeDays = days) }
        refreshAll()
    }

    fun setFilter(q: String) = _ui.update { it.copy(filter = q) }

    fun setSort(k: SortKey) = _ui.update {
        if (it.sort == k) it.copy(sortDesc = !it.sortDesc) else it.copy(sort = k, sortDesc = k != SortKey.TITLE)
    }

    fun toggleSlot(id: Long) = _ui.update { s ->
        if (id in s.slots) s.copy(slots = s.slots - id)
        else {
            val free = (0 until MAX_SLOTS).firstOrNull { it !in s.slots.values }
            if (free == null) s.copy(message = "最多同時比較 $MAX_SLOTS 筆，請先取消一些") else s.copy(slots = s.slots + (id to free))
        }
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    private fun toast(msg: String) = _ui.update { it.copy(message = msg) }

    fun saveMeal(meal: Meal, newPhoto: Uri?, removePhoto: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val old = if (meal.id != 0L) db.meal(meal.id) else null
                var photo = old?.photo
                if (newPhoto != null) {
                    val name = Photos.importFrom(ctx, newPhoto)
                    if (name != null) { Photos.delete(ctx, old?.photo); photo = name }
                } else if (removePhoto) {
                    Photos.delete(ctx, old?.photo); photo = null
                }
                db.saveMeal(meal.copy(photo = photo))
            }
            _ui.update { it.copy(day = java.time.Instant.ofEpochMilli(meal.ts).atZone(zone).toLocalDate()) }
            refreshAll()
            onDone()
        }
    }

    fun deleteMeal(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.meal(id)?.let { Photos.delete(ctx, it.photo) }
                db.deleteMeal(id)
            }
            _ui.update { it.copy(slots = it.slots - id) }
            refreshAll()
        }
    }

    fun syncNow() {
        if (_ui.value.syncing) return
        _ui.update { it.copy(syncing = true) }
        viewModelScope.launch {
            val ok = SyncRunner.run(ctx, manual = true)
            _ui.update { it.copy(syncing = false) }
            toast(if (ok) "同步完成" else "同步失敗：${syncState.lastError ?: "未知錯誤"}")
            refreshAll()
        }
    }

    fun saveCredentials(email: String, password: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                secure.email = email.trim()
                if (password.isNotEmpty()) secure.password = password
                secure.save(null) // new account → new session and region
                syncState.prefs.edit().remove("region").apply()
            }
            syncNow()
        }
    }

    fun setMock(on: Boolean) {
        syncState.mock = on
        refreshAll()
    }

    fun importCsv(uri: Uri) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val text = ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: error("讀不到檔案")
                    val parsed = parseLibreViewCsv(text, zone)
                    if (parsed.readings.isEmpty()) error("檔案裡沒有找到血糖資料，請確認是 LibreView 匯出的 CSV")
                    val (ins, skip) = db.insertCsvReadings(parsed.readings)
                    "匯入完成：新增 $ins 筆，略過 $skip 筆（${fmtDateTime(parsed.readings.first().ts)} – ${fmtDateTime(parsed.readings.last().ts)}，${parsed.unit}，${parsed.dateOrder}）"
                }.getOrElse { "匯入失敗：${it.message}" }
            }
            toast(msg)
            refreshAll()
        }
    }
}
