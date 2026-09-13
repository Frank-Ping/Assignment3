package com.example.mobile_wearableapplication

import android.content.Context
import android.util.AtomicFile
import com.example.mobile_wearableapplication.processing.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

internal data class StoredHour(val time: Long, val source: String, val sum: Double, val count: Long, val zones: List<Double>)
internal data class StoredSummary(val ended: Long, val baseline: Double?, val recovery: Double?, val recoveryQuality: String = "UNKNOWN")
internal data class StoredHistory(val hours: List<StoredHour> = emptyList(), val summary: StoredSummary? = null,
    val error: String? = null, val ready: Boolean = false)

/** One process-wide serial writer. ADB previews never enter this store. */
internal object HistoryFileStore {
    private val worker = Executors.newSingleThreadExecutor()
    private var file: AtomicFile? = null
    private var root = JSONObject().put("version", 1).put("sessions", JSONObject())
    @Volatile var snapshot = StoredHistory()
        private set
    private var lastSave = 0L
    private var writable = true
    @Synchronized fun open(context: Context) {
        if (file != null) return
        file = AtomicFile(File(context.applicationContext.filesDir, "sensor_history.json"))
        worker.execute {
            try {
                if (file!!.baseFile.exists() || File(file!!.baseFile.path + ".bak").exists()) root = JSONObject(file!!.openRead().bufferedReader().use { it.readText() })
                require(root.getInt("version") == 1)
                publish()
            } catch (e: Exception) {
                writable = false // Preserve an unreadable file rather than overwrite it.
                snapshot = StoredHistory(error = "History could not be loaded: ${e.message}", ready = true)
            }
        }
    }
    fun update(processing: ProcessingSnapshot, offset: Long?, force: Boolean = false) {
        worker.execute {
            if (!writable) return@execute
            try {
                val now = System.currentTimeMillis()
                val sessions = root.getJSONObject("sessions")
                var completed = false
                val session = processing.session
                if (session != null && offset != null) {
                    val key = "${session.nodeId}/${session.sessionId}"
                    val data = sessions.optJSONObject(key) ?: JSONObject().put("offset", offset)
                        .put("hours", JSONObject()).put("lastHr", -1L).also { sessions.put(key, it) }
                    val anchor = data.getLong("offset")
                    val hours = data.getJSONObject("hours")
                    fun hour(time: Long, source: String): JSONObject {
                        val wall = time / 1_000_000L + anchor
                        val start = Math.floorDiv(wall, 3_600_000L) * 3_600_000L
                        val id = "$start/$source"
                        return hours.optJSONObject(id) ?: JSONObject().put("time", start).put("source", source)
                            .put("sum", 0.0).put("count", 0L).put("zones", JSONArray(List(5) { 0.0 }))
                            .also { hours.put(id, it) }
                    }
                    var last = data.getLong("lastHr")
                    processing.chartOutput.heartRate.forEach { point ->
                        if (point.timestampNanos > last) {
                            point.value?.takeIf { it.isFinite() && it > 0 }?.let { bpm ->
                                val row = hour(point.timestampNanos, point.source.name)
                                row.put("sum", row.getDouble("sum") + bpm).put("count", row.getLong("count") + 1)
                            }
                            last = point.timestampNanos
                        }
                    }
                    data.put("lastHr", last)
                    // Rebuild only complete retained hours; session totals can change after delayed phase confirmation.
                    val intervals = processing.chartOutput.zoneIntervals
                    val first = intervals.firstOrNull()?.startNanos
                    if (first != null) {
                        val source = processing.heartRate.sources.singleOrNull()?.name ?: "MIXED"
                        val begin = Math.floorDiv(first / 1_000_000L + anchor, 3_600_000L) * 3_600_000L
                        val earliest = if (first == processing.exerciseStartedAt) begin else begin + 3_600_000L
                        hours.keys().asSequence().toList().forEach { id ->
                            val row = hours.getJSONObject(id)
                            if (row.getLong("time") >= earliest) row.put("zones", JSONArray(List(5) { 0.0 }))
                        }
                        intervals.forEach { interval ->
                            var from = maxOf(interval.startNanos / 1_000_000L + anchor, earliest)
                            val end = interval.endNanos / 1_000_000L + anchor
                            while (from < end) {
                                val to = minOf(end, (Math.floorDiv(from, 3_600_000L) + 1) * 3_600_000L)
                                val row = hour((from - anchor) * 1_000_000L, source)
                                val zones = row.getJSONArray("zones")
                                val index = interval.zone.ordinal
                                zones.put(index, zones.getDouble(index) + (to - from) / 1000.0)
                                from = to
                            }
                        }
                    }
                    processing.finalSummary?.let { summary ->
                        completed = !data.has("ended")
                        fun metric(result: MetricResult<*>): JSONObject = when (result) {
                            is MetricResult.Available -> JSONObject().put("valid", true)
                                .put("sources", JSONArray(result.evidence.sources.map { it.name }))
                            is MetricResult.Unavailable -> JSONObject().put("valid", false).put("reason", result.reason.name)
                        }
                        val baseline = metric(summary.restingHeartRate)
                        (summary.restingHeartRate as? MetricResult.Available)?.let { baseline.put("bpm", it.value) }
                        val recovery = metric(summary.recovery)
                        (summary.recovery as? MetricResult.Available)?.value?.let {
                            recovery.put("startBpm", it.startBpm).put("endBpm", it.endBpm)
                                .put("quality", it.quality.name).put("declineBpm", it.declineBpm).put("bpmPerMinute", it.bpmPerMinute)
                        }
                        data.put("ended", summary.endedAtNanos / 1_000_000L + anchor)
                        data.put("summary", JSONObject().put("baseline", baseline).put("recovery", recovery))
                    }
                    data.put("updated", now)
                }
                val cutoff = now - 12 * 3_600_000L
                sessions.keys().asSequence().toList().forEach { key ->
                    val data = sessions.getJSONObject(key)
                    val hours = data.getJSONObject("hours")
                    hours.keys().asSequence().toList().forEach { id -> if (hours.getJSONObject(id).getLong("time") < cutoff) hours.remove(id) }
                    if (data.has("ended") && data.getLong("ended") < cutoff) {
                        data.remove("summary")
                    }
                    if (data.optLong("updated") < cutoff) sessions.remove(key)
                }
                if (force || completed || now - lastSave >= 10_000L) {
                    val stream = file!!.startWrite()
                    try { stream.write(root.toString().toByteArray(Charsets.UTF_8)); file!!.finishWrite(stream); lastSave = now }
                    catch (e: Exception) { file!!.failWrite(stream); throw e }
                }
                publish()
            } catch (e: Exception) { snapshot = snapshot.copy(error = "History save failed: ${e.message}") }
        }
    }
    private fun publish() {
        val rows = mutableListOf<StoredHour>()
        var summary: StoredSummary? = null
        var ended = 0L
        val sessions = root.getJSONObject("sessions")
        val cutoff = System.currentTimeMillis() - 12 * 3_600_000L
        sessions.keys().forEach { key ->
            val data = sessions.getJSONObject(key)
            val hours = data.getJSONObject("hours")
            hours.keys().forEach { id ->
                val row = hours.getJSONObject(id)
                if (row.getLong("time") >= cutoff) rows.add(StoredHour(row.getLong("time"), row.getString("source"), row.getDouble("sum"), row.getLong("count"),
                    List(5) { row.getJSONArray("zones").getDouble(it) }))
            }
            if (data.optLong("ended") >= cutoff && data.optLong("ended") > ended && data.has("summary")) {
                ended = data.getLong("ended")
                val saved = data.getJSONObject("summary")
                fun value(name: String, field: String): Double? {
                    val metric = saved.getJSONObject(name)
                    return if (metric.getBoolean("valid")) metric.getDouble(field) else null
                }
                summary = StoredSummary(ended, value("baseline", "bpm"), value("recovery", "declineBpm"), saved.getJSONObject("recovery").optString("quality", "UNKNOWN"))
            }
        }
        snapshot = StoredHistory(rows, summary, ready = true)
    }
}
