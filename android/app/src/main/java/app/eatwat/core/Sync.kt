package app.eatwat.core

fun interface ReadingSink {
    /** Store readings; returns how many rows changed. */
    fun upsert(rows: List<Reading>): Int
}

data class SyncResult(
    val ok: Boolean,
    val written: Int = 0,
    val graphCount: Int = 0,
    val patientName: String? = null,
    val connections: List<Connection> = emptyList(),
    val region: String = "",
    val timeWarning: String? = null,
    val errorKind: LluErrorKind? = null,
    val error: String? = null,
    val rawSample: String? = null,
)

/** One sync pass: connections → graph (+ logbook) → sink. Never throws. */
fun runSync(client: LluClient, sink: ReadingSink, patientId: String = "", now: Long = System.currentTimeMillis()): SyncResult {
    var conns = emptyList<Connection>()
    return try {
        conns = client.connections()
        if (conns.isEmpty()) throw LluException(
            LluErrorKind.NO_CONNECTIONS,
            "這個帳號沒有任何分享連線。請在 FreeStyle Libre App 的「連線的應用程式 → LibreLinkUp」邀請這個帳號（可以是你自己的 email），並在 LibreLinkUp App 接受邀請。",
        )
        val conn = if (patientId.isNotEmpty()) conns.firstOrNull { it.patientId == patientId }
            ?: throw LluException(LluErrorKind.CONFIG, "找不到指定的 patientId") else conns.first()
        val graph = client.graph(conn.patientId)
        val items = graph?.optJSONArray("graphData")
        val rows = mutableListOf<Reading>()
        if (items != null) for (i in 0 until items.length()) itemToReading(items.optJSONObject(i), "graph")?.let(rows::add)
        val latest = itemToReading(graph?.optJSONObject("connection")?.optJSONObject("glucoseMeasurement"), "latest")
        latest?.let(rows::add)
        val logRows = runCatching {
            val lb = client.logbook(conn.patientId)
            (0 until lb.length()).mapNotNull { itemToReading(lb.optJSONObject(it), "logbook") }
        }.getOrDefault(emptyList())
        val written = sink.upsert(logRows + rows)
        val newest = rows.maxOfOrNull { it.ts }
        SyncResult(
            ok = true, written = written, graphCount = rows.size, patientName = conn.name.ifEmpty { null },
            connections = conns, region = client.region, timeWarning = checkClockSkew(newest, now),
            rawSample = items?.optJSONObject(0)?.toString(),
        )
    } catch (e: LluException) {
        SyncResult(ok = false, connections = conns, region = client.region, errorKind = e.kind, error = e.message)
    } catch (e: Exception) {
        SyncResult(ok = false, connections = conns, region = client.region, errorKind = LluErrorKind.NETWORK, error = e.message ?: e.toString())
    }
}
