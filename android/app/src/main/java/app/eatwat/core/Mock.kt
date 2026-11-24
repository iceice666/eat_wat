package app.eatwat.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

// Fake LibreLinkUp API for tests and the debug "mock" mode. Same shape as the
// live API as observed: region redirect, required headers, 15-minute graph
// points without TrendArrow, UTC FactoryTimestamp "M/D/YYYY h:mm:ss AM".

private const val USER_ID = "00000000-mock-user-0000-000000000000"
const val MOCK_PATIENT_ID = "11111111-mock-patient-0000-000000000000"
private const val TOKEN = "mock-token"
const val MOCK_REGION = "mock"

fun mockHostFor(region: String) = if (region.isEmpty()) "https://api.mock.invalid" else "https://api-$region.mock.invalid"

/** Deterministic curve with meal bumps at 07:30, 12:30 and 19:00 in [zone]. */
fun mockGlucoseAt(t: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
    val local = Instant.ofEpochMilli(t).atZone(zone)
    val minutes = local.hour * 60 + local.minute
    var g = 96 + 6 * sin(t / MIN_MS.toDouble() / 1440 * 2 * PI * 3)
    for ((at, height) in listOf(450 to 55.0, 750 to 80.0, 1140 to 65.0)) {
        for (shift in listOf(0, -1440)) {
            val dt = (minutes - at - shift).toDouble()
            if (dt > 0 && dt < 240) g += height * (dt / 45) * exp(1 - dt / 45)
        }
    }
    return (g + sin(t / 997_000.0) * 3).roundToInt()
}

private fun factoryTs(t: Long): String {
    val d = Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC)
    val h12 = (d.hour % 12).let { if (it == 0) 12 else it }
    return "${d.monthValue}/${d.dayOfMonth}/${d.year} $h12:%02d:%02d ${if (d.hour >= 12) "PM" else "AM"}".format(java.util.Locale.ROOT, d.minute, d.second)
}

private fun item(t: Long, zone: ZoneId, withTrend: Boolean): JSONObject {
    val v = mockGlucoseAt(t, zone)
    return JSONObject().put("FactoryTimestamp", factoryTs(t)).put("type", 0).put("ValueInMgPerDl", v)
        .put("MeasurementColor", 1).put("GlucoseUnits", 1).put("Value", v).put("isHigh", v > 180).put("isLow", v < 70)
        .apply { if (withTrend) put("TrendArrow", 3) }
}

class MockTransport(
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val withConnections: Boolean = true,
) : Transport {
    var requests = 0
        private set

    private fun json(status: Int, body: JSONObject) = HttpResult(status, body.toString())

    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
        requests++
        val u = java.net.URI(url)
        val regional = u.host.startsWith("api-$MOCK_REGION.")
        if (headers["version"].isNullOrEmpty() || headers["product"].isNullOrEmpty())
            return json(403, JSONObject().put("status", 920).put("error", JSONObject().put("message", "RequiredHeaderMissing")))
        val t = now() / MIN_MS * MIN_MS
        if (u.path == "/llu/auth/login") {
            if (!regional) return json(200, JSONObject().put("status", 0).put("data", JSONObject().put("redirect", true).put("region", MOCK_REGION)))
            val pw = JSONObject(body ?: "{}").optString("password")
            if (pw == "wrong") return json(200, JSONObject().put("status", 2).put("error", JSONObject().put("message", "notAuthenticated")))
            val ticket = JSONObject().put("token", if (pw == "needs-tou") "limited" else TOKEN).put("expires", now() / 1000 + 3600).put("duration", 3_600_000)
            val data = JSONObject().put("user", JSONObject().put("id", USER_ID)).put("authTicket", ticket)
            if (pw == "needs-tou") return json(200, JSONObject().put("status", 4).put("data", data.put("step", JSONObject().put("type", "tou"))))
            return json(200, JSONObject().put("status", 0).put("data", data))
        }
        if (headers["authorization"] != "Bearer $TOKEN") return json(401, JSONObject().put("message", "Unauthorized"))
        if (headers["account-id"] != accountIdFor(USER_ID))
            return json(400, JSONObject().put("status", 920).put("error", JSONObject().put("message", "RequiredHeaderMissing")))
        return when (u.path) {
            "/llu/connections" -> {
                val arr = JSONArray()
                if (withConnections) arr.put(JSONObject().put("patientId", MOCK_PATIENT_ID).put("firstName", "測試").put("lastName", "使用者"))
                json(200, JSONObject().put("status", 0).put("data", arr))
            }
            "/llu/connections/$MOCK_PATIENT_ID/graph" -> {
                val g = JSONArray()
                val step = 15 * MIN_MS
                var x = (t - 12 * HOUR_MS) / step * step
                while (x < t) { g.put(item(x, zone, false)); x += step }
                val conn = JSONObject().put("patientId", MOCK_PATIENT_ID).put("glucoseMeasurement", item(t, zone, true))
                json(200, JSONObject().put("status", 0).put("data", JSONObject().put("connection", conn).put("graphData", g)))
            }
            "/llu/connections/$MOCK_PATIENT_ID/logbook" -> json(200, JSONObject().put("status", 0).put("data", JSONArray()))
            else -> json(404, JSONObject().put("message", "not found"))
        }
    }
}
