package app.eatwat.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Client for the unofficial LibreLinkUp (follower app) API. Abbott does not
// publish it; behaviour follows community clients and may change without notice.

data class HttpResult(val status: Int, val body: String)

fun interface Transport {
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult
}

/** A login session, persisted so each background run does not log in again. */
data class Session(val token: String, val expiresMs: Long, val accountId: String, val region: String)

interface SessionStore {
    fun load(): Session?
    fun save(s: Session?)
}

class MemorySessionStore(var session: Session? = null) : SessionStore {
    override fun load() = session
    override fun save(s: Session?) { session = s }
}

enum class LluErrorKind(val fatal: Boolean) {
    CONFIG(true), AUTH(true), STEP(true), NO_CONNECTIONS(true),
    RATE_LIMITED(false), HTTP(false), NETWORK(false),
}

class LluException(val kind: LluErrorKind, message: String, val raw: String? = null) : Exception(message)

data class Connection(val patientId: String, val name: String)

fun accountIdFor(userId: String): String =
    MessageDigest.getInstance("SHA-256").digest(userId.toByteArray()).joinToString("") { "%02x".format(it) }

fun defaultHostFor(region: String) = if (region.isEmpty()) "https://api.libreview.io" else "https://api-$region.libreview.io"

class LluClient(
    private val email: String,
    private val password: String,
    private val transport: Transport,
    private val sessions: SessionStore,
    private val version: String = "4.16.0",
    initialRegion: String = "",
    private val hostFor: (String) -> String = ::defaultHostFor,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var region: String = sessions.load()?.region ?: initialRegion
        private set
    var loginCount = 0
        private set

    private fun baseHeaders() = mapOf(
        // No accept-encoding: HttpURLConnection then handles gzip transparently.
        "cache-control" to "no-cache",
        "content-type" to "application/json",
        "product" to "llu.android",
        "version" to version,
    )

    private fun send(method: String, path: String, headers: Map<String, String>, body: String?): Pair<Int, JSONObject?> {
        val res = try {
            transport.request(method, hostFor(region) + path, headers, body)
        } catch (e: Exception) {
            throw LluException(LluErrorKind.NETWORK, "無法連線到 LibreView：${e.message}")
        }
        val json = runCatching { JSONObject(res.body) }.getOrNull()
        if (res.status == 429) throw LluException(LluErrorKind.RATE_LIMITED, "LibreView 回應 429（請求太頻繁），稍後重試", res.body.take(300))
        return res.status to json
    }

    fun login(): Session {
        if (email.isBlank() || password.isEmpty()) throw LluException(LluErrorKind.CONFIG, "尚未設定 LibreLinkUp 帳號密碼")
        repeat(2) {
            loginCount++
            val payload = JSONObject().put("email", email).put("password", password).toString()
            val (status, body) = send("POST", "/llu/auth/login", baseHeaders(), payload)
            val data = body?.optJSONObject("data")
            val apiStatus = body?.opt("status")
            if (data != null && data.optBoolean("redirect") && data.optString("region").isNotEmpty() && data.optString("region") != region) {
                region = data.optString("region")
                return@repeat
            }
            // A terms/privacy step can carry a limited authTicket: never treat it as a login.
            data?.optJSONObject("step")?.let { step ->
                val t = step.optString("type").ifEmpty { step.toString().take(80) }
                throw LluException(LluErrorKind.STEP, "LibreLinkUp 帳號需要先在 App 內完成步驟（step=$t，status=$apiStatus）。請打開 LibreLinkUp App 登入一次並接受條款。", body.toString().take(300))
            }
            val ticket = data?.optJSONObject("authTicket")
            val userId = data?.optJSONObject("user")?.optString("id").orEmpty()
            if (apiStatus == 0 && ticket != null && ticket.optString("token").isNotEmpty() && userId.isNotEmpty()) {
                val exp = ticket.optLong("expires", 0)
                val expMs = when {
                    exp <= 0 -> clock() + HOUR_MS
                    exp < 1_000_000_000_000L -> exp * 1000
                    else -> exp
                }
                val s = Session(ticket.getString("token"), expMs, accountIdFor(userId), region)
                sessions.save(s)
                return s
            }
            if (status >= 500) throw LluException(LluErrorKind.HTTP, "登入失敗：HTTP $status")
            val msg = body?.optJSONObject("error")?.optString("message")?.ifEmpty { null } ?: body?.optString("message").orEmpty()
            throw LluException(
                LluErrorKind.AUTH,
                "登入被拒絕（HTTP $status，status=${apiStatus ?: "?"}${if (msg.isNotEmpty()) "，$msg" else ""}）。請檢查帳號密碼。",
                body?.toString()?.take(300),
            )
        }
        throw LluException(LluErrorKind.HTTP, "登入時區域轉址超過一次")
    }

    private fun validSession(): Session? = sessions.load()?.takeIf { clock() < it.expiresMs - MIN_MS && it.region == region }

    fun get(path: String): Any? {
        var s = validSession() ?: login()
        repeat(2) { attempt ->
            val (status, body) = send("GET", path, baseHeaders() + mapOf("authorization" to "Bearer ${s.token}", "account-id" to s.accountId), null)
            if (status == 401 && attempt == 0) {
                sessions.save(null)
                s = login()
                return@repeat
            }
            if (status >= 400) {
                val msg = body?.optString("message").orEmpty()
                throw LluException(LluErrorKind.HTTP, "GET $path 失敗：HTTP $status${if (msg.isNotEmpty()) "（$msg）" else ""}", body?.toString()?.take(300))
            }
            val st = body?.opt("status")
            if (st != null && st != 0) throw LluException(LluErrorKind.HTTP, "GET $path 回應 status=$st", body.toString().take(300))
            return body?.opt("data")
        }
        throw LluException(LluErrorKind.AUTH, "重新登入後仍回應 401")
    }

    fun connections(): List<Connection> {
        val arr = get("/llu/connections") as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("patientId").ifEmpty { return@mapNotNull null }
            Connection(id, listOf(o.optString("firstName"), o.optString("lastName")).filter { it.isNotBlank() }.joinToString(" "))
        }
    }

    fun graph(patientId: String): JSONObject? = get("/llu/connections/$patientId/graph") as? JSONObject

    fun logbook(patientId: String): JSONArray = get("/llu/connections/$patientId/logbook") as? JSONArray ?: JSONArray()
}

/** Convert one LLU glucose item to a Reading, or null when unusable. */
fun itemToReading(item: JSONObject?, source: String): Reading? {
    if (item == null) return null
    val ts = parseLluTimestamp(item.opt("FactoryTimestamp")) ?: return null
    var mg = item.optDouble("ValueInMgPerDl", Double.NaN)
    if (mg.isNaN() || mg <= 0) {
        val v = item.optDouble("Value", Double.NaN)
        mg = if (!v.isNaN() && v > 0) (if (v < 35) v * 18.016 else v) else return null
    }
    val trend = if (item.has("TrendArrow") && !item.isNull("TrendArrow")) item.optInt("TrendArrow") else null
    return Reading(toMinute(ts), (mg * 10).roundToLong() / 10.0, source, trend)
}

/**
 * FactoryTimestamp is parsed as UTC. If the newest reading is in the future or
 * exactly N hours old, that assumption is probably wrong for this account.
 */
fun checkClockSkew(latestTs: Long?, now: Long = System.currentTimeMillis()): String? {
    if (latestTs == null) return null
    val skew = now - latestTs
    if (skew < -10 * MIN_MS) return "最新讀值的時間比現在晚 ${"%.1f".format(java.util.Locale.ROOT, -skew / HOUR_MS.toDouble())} 小時，時區解析可能錯誤。"
    val hours = (skew / HOUR_MS.toDouble()).roundToInt()
    if (hours >= 1 && abs(skew - hours * HOUR_MS) < 8 * MIN_MS) return "最新讀值剛好是約 $hours 小時前，可能是時區解析錯誤（也可能是感測器暫時斷線）。"
    return null
}
