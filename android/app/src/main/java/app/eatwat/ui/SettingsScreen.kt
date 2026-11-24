package app.eatwat.ui

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.eatwat.BuildConfig

@Composable
private fun KV(k: String, v: String, warn: Boolean = false) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(k, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(84.dp))
        Text(v, fontSize = 14.sp, color = if (warn) LocalViz.current.warn else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun SettingsScreen(s: UiState, vm: AppViewModel, padding: PaddingValues) {
    val ctx = LocalContext.current
    val st = s.status
    var email by rememberSaveable(st.email) { mutableStateOf(st.email) }
    var password by rememberSaveable { mutableStateOf("") }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importCsv) }
    val pm = remember { ctx.getSystemService(PowerManager::class.java) }
    val batteryExempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard {
                Text("LibreLinkUp 帳號", fontWeight = FontWeight.SemiBold)
                Text(
                    "可以用你自己的 LibreView 帳號，但要先在 FreeStyle Libre App 邀請自己的 email 到 LibreLinkUp 並接受。帳密用 Android Keystore 加密存在這支手機。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp),
                )
                OutlinedTextField(
                    email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                OutlinedTextField(
                    password, { password = it }, label = { Text(if (st.hasCredentials) "密碼（留空 = 不變）" else "密碼") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Button(
                    onClick = { vm.saveCredentials(email, password); password = "" },
                    enabled = email.isNotBlank() && (password.isNotEmpty() || st.hasCredentials),
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("儲存並同步") }
            }
        }
        item {
            SectionCard {
                Text("背景同步", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                val stateText = when {
                    st.mock -> "MOCK 假資料"
                    !st.hasCredentials -> "尚未設定帳號"
                    st.paused -> "已暫停（需要處理下面的錯誤）"
                    st.lastError != null -> "暫時失敗，會自動重試"
                    st.lastSuccess != null -> "正常，每 15 分鐘"
                    else -> "尚未同步"
                }
                KV("狀態", stateText, warn = st.paused)
                KV("上次成功", st.lastSuccess?.let { "${fmtDateTime(it)}（${ago(it)}）" } ?: "—")
                KV("區域", st.region.ifEmpty { "（全球）" })
                KV("追蹤對象", st.patientName ?: "—")
                if (st.connections.contains("、")) KV("所有連線", st.connections)
                st.lastError?.let { KV("錯誤", it, warn = true) }
                st.timeWarning?.let { KV("時間警告", it, warn = true) }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::syncNow, enabled = !s.syncing && (st.hasCredentials || st.mock)) { Text("立即同步") }
                    if (s.syncing) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                }
                Text(
                    "LibreLinkUp 只回傳最近約 12 小時，App 每 15 分鐘在背景抓一次（需要網路）。" +
                        "部分手機品牌會強制關閉背景工作；如果「上次成功」常常停住，請把這個 App 設成不受電池最佳化限制。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
                )
                OutlinedButton(
                    onClick = { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text(if (batteryExempt) "電池最佳化：已不受限制 ✓" else "電池最佳化設定…") }
            }
        }
        item {
            SectionCard {
                Text("匯入 LibreView CSV", fontWeight = FontWeight.SemiBold)
                Text(
                    "到 libreview.com 登入 → 右上角「下載血糖資料」，把 CSV 存到手機後在這裡選取。與已同步重疊的時段會自動略過。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp),
                )
                OutlinedButton(onClick = { csvPicker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }) { Text("選擇 CSV 檔") }
            }
        }
        item {
            SectionCard {
                Text("資料", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                KV("血糖讀值", "${s.stats.count} 筆")
                KV("最早", s.stats.first?.let(::fmtDateTime) ?: "—")
                KV("最新", s.stats.last?.let(::fmtDateTime) ?: "—")
                Text(
                    "使用 Abbott LibreLinkUp 的非官方 API，可能隨時失效。這是個人記錄工具，不是醫療器材。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (BuildConfig.DEBUG) item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("開發用：假資料模式", fontWeight = FontWeight.SemiBold)
                        Text("不連 Abbott，用內建假 API 同步", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = st.mock, onCheckedChange = vm::setMock)
                }
            }
        }
    }
}
