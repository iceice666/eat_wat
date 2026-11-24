package app.eatwat.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.eatwat.BuildConfig
import app.eatwat.core.HttpResult
import app.eatwat.core.LluClient
import app.eatwat.core.LluErrorKind
import app.eatwat.core.MIN_MS
import app.eatwat.core.MemorySessionStore
import app.eatwat.core.MockTransport
import app.eatwat.core.Transport
import app.eatwat.core.mockHostFor
import app.eatwat.core.runSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** HttpURLConnection transport. gzip is handled by the platform; 4xx bodies come from errorStream. */
object HttpTransport : Transport {
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return HttpResult(status, text)
        } finally {
            conn.disconnect()
        }
    }
}

/** Non-secret sync status, readable by the UI. */
class SyncState(ctx: Context) {
    val prefs: SharedPreferences = ctx.applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)

    var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(v) = prefs.edit().putBoolean("paused", v).apply()
    var notBefore: Long
        get() = prefs.getLong("notBefore", 0)
        set(v) = prefs.edit().putLong("notBefore", v).apply()
    var mock: Boolean
        get() = BuildConfig.DEBUG && prefs.getBoolean("mock", false)
        set(v) = prefs.edit().putBoolean("mock", v).apply()

    val lastAttempt get() = prefs.getLong("lastAttempt", 0).takeIf { it > 0 }
    val lastSuccess get() = prefs.getLong("lastSuccess", 0).takeIf { it > 0 }
    val lastError get() = prefs.getString("lastError", null)
    val errorKind get() = prefs.getString("errorKind", null)
    val region get() = prefs.getString("region", "").orEmpty()
    val patientName get() = prefs.getString("patientName", null)
    val timeWarning get() = prefs.getString("timeWarning", null)
    val connections get() = prefs.getString("connections", "").orEmpty()
}

object SyncRunner {
    private const val TAG = "eatwat-sync"
    private val lock = Mutex()
    private const val PERIODIC = "llu-sync"
    private const val PAUSED_RETRY_MS = 6 * 60 * MIN_MS

    /** Schedule the background sync (every 15 minutes, with network). Idempotent. */
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /**
     * Run one sync. A manual run clears the paused / back-off state first.
     * Fatal errors (bad password, terms step, no connections) pause background
     * runs until the user acts, so a wrong password is not retried 96 times a day.
     */
    suspend fun run(ctx: Context, manual: Boolean): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val state = SyncState(ctx)
            val now = System.currentTimeMillis()
            if (manual) {
                state.paused = false
                state.notBefore = 0
            } else if ((state.paused && now - (state.lastAttempt ?: 0) < PAUSED_RETRY_MS) || now < state.notBefore) {
                // Paused runs still retry every few hours, so a transient error
                // misread as fatal cannot stop collection for good.
                Log.i(TAG, "skipped (paused=${state.paused}, notBefore=${state.notBefore})")
                return@withLock false
            }
            val secure = SecureStore(ctx)
            val client = if (state.mock) {
                LluClient("mock@example.invalid", "mock", MockTransport(), MemorySessionStore(), hostFor = ::mockHostFor)
            } else {
                LluClient(secure.email, secure.password, HttpTransport, secure)
            }
            val r = runSync(client, { Db.get(ctx).upsertReadings(it) })
            val e = state.prefs.edit()
                .putLong("lastAttempt", now)
                .putString("region", r.region)
                .putString("connections", r.connections.joinToString("、") { it.name.ifEmpty { it.patientId } })
            if (r.ok) {
                Log.i(TAG, "ok: ${r.graphCount} readings, ${r.written} written; sample=${r.rawSample}")
                e.putLong("lastSuccess", now).remove("lastError").remove("errorKind")
                    .putString("patientName", r.patientName).putString("timeWarning", r.timeWarning)
            } else {
                Log.w(TAG, "error ${r.errorKind}: ${r.error}")
                e.putString("lastError", r.error).putString("errorKind", r.errorKind?.name)
                when {
                    r.errorKind?.fatal == true -> e.putBoolean("paused", true)
                    r.errorKind == LluErrorKind.RATE_LIMITED -> e.putLong("notBefore", now + 30 * MIN_MS)
                }
            }
            e.commit()
            r.ok
        }
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    // Always success: a periodic request keeps running anyway, and our own
    // paused / notBefore state decides whether the next run does anything.
    override suspend fun doWork(): Result {
        SyncRunner.run(applicationContext, manual = false)
        return Result.success()
    }
}
