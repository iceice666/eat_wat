package app.eatwat

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eatwat.core.MIN_MS
import app.eatwat.data.Meal
import app.eatwat.data.SyncRunner
import app.eatwat.ui.AnalysisScreen
import app.eatwat.ui.AppViewModel
import app.eatwat.ui.DayScreen
import app.eatwat.ui.EatWatTheme
import app.eatwat.ui.LocalViz
import app.eatwat.ui.MealDetailSheet
import app.eatwat.ui.MealEditor
import app.eatwat.ui.SettingsScreen
import app.eatwat.ui.UiState
import app.eatwat.ui.ago
import app.eatwat.ui.trendArrow

class EatWatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncRunner.schedule(this)
    }
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { EatWatTheme { AppRoot(vm) } }
    }
}

private enum class Tab(val label: String) { DAY("每日"), ANALYSIS("分析"), SETTINGS("設定") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(vm: AppViewModel) {
    val s by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.DAY) }
    var editing by remember { mutableStateOf<Meal?>(null) }
    var creating by remember { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    val snack = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAll() }
    LaunchedEffect(s.message) {
        s.message?.let { snack.showSnackbar(it); vm.consumeMessage() }
    }
    var routed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.loaded) {
        // First launch with nothing configured: go straight to settings (once, after data loads).
        if (s.loaded && !routed) {
            routed = true
            if (!s.status.hasCredentials && !s.status.mock && s.stats.count == 0) tab = Tab.SETTINGS
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { TopTitle(s) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Text(t.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp)) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab != Tab.SETTINGS) ExtendedFloatingActionButton(onClick = { creating = true }) { Text("＋ 記錄飲食", fontWeight = FontWeight.Bold) }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        when (tab) {
            Tab.DAY -> DayScreen(s, vm, padding) { detailId = it }
            Tab.ANALYSIS -> AnalysisScreen(s, vm, padding) { detailId = it }
            Tab.SETTINGS -> SettingsScreen(s, vm, padding)
        }
    }

    val detail = detailId?.let { id -> (s.dayMeals + s.analysis).firstOrNull { it.meal.id == id } }
    if (detailId != null && detail == null && s.dayMeals.isNotEmpty()) detailId = null
    detail?.let { m ->
        MealDetailSheet(
            m,
            onDismiss = { detailId = null },
            onEdit = { detailId = null; editing = m.meal },
            onDelete = { detailId = null; vm.deleteMeal(m.meal.id) },
        )
    }
    if (creating || editing != null) {
        MealEditor(
            initial = editing,
            defaultDay = s.day,
            titles = s.titles,
            onDismiss = { creating = false; editing = null },
            onSave = { meal, photo, remove -> vm.saveMeal(meal, photo, remove) { creating = false; editing = null } },
        )
    }
}

@Composable
private fun TopTitle(s: UiState) {
    val st = s.status
    val dot = when {
        st.paused -> Color(0xFFD03B3B)
        st.lastError != null || st.timeWarning != null -> Color(0xFFFAB219)
        st.lastSuccess != null -> Color(0xFF0CA30C)
        else -> MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text("吃了什麼", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.width(14.dp))
        val latest = s.latest
        if (latest != null) {
            val stale = System.currentTimeMillis() - latest.ts > 20 * MIN_MS
            Text(
                "${latest.mgdl.toInt()} ${trendArrow(latest.trend)}".trim(), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(" ${ago(latest.ts)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text("—", fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        androidx.compose.foundation.layout.Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
    }
}

@Suppress("unused")
private val viz = LocalViz
