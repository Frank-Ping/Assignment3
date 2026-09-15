package com.example.mobile_wearableapplication

import com.example.shared.communication.SessionAction

import com.example.shared.communication.WireDataType
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import com.example.mobile_wearableapplication.communication.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush.Companion.verticalGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mobile_wearableapplication.ui.theme.MobileWearableApplicationTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.shared.communication.ConnectionStatus
import com.example.shared.communication.DeviceRole
import com.example.shared.communication.WearConnectionManager
import com.example.mobile_wearableapplication.processing.MetricResult
import com.example.mobile_wearableapplication.processing.ProcessingSnapshot

class SensorActivity : ComponentActivity() {
    private lateinit var sessionClient: PhoneSessionClient
    private var controls by mutableStateOf(SessionControlUi())
    private var pageStarted = false
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            if (!pageStarted) return

            refreshDiagnostics()
            refreshHandler.postDelayed(this, 100L)
        }
    }
    private val timeoutTask = object : Runnable {
        override fun run() {
            if (!pageStarted) return
            ReceivedSensorStore.checkReceptionTimeouts()
            refreshHandler.postDelayed(this, 1_000L)
        }
    }
    private var overview by mutableStateOf<Map<String, String>>(emptyMap())
    private var storedHistory by mutableStateOf(StoredHistory())
    private var lastHistoryWriteRequest = 0L
    private var chartNowMillis by mutableStateOf(System.currentTimeMillis())
    private var historyPreview by mutableStateOf<HistoryPreview?>(null)
    private var chartEpochOffsetMillis by mutableStateOf<Long?>(null)
    private var chartSession: com.example.mobile_wearableapplication.processing.ProcessingSession? = null
    private var chartProcessing by mutableStateOf(ProcessingSnapshot())
    private lateinit var receiver: SensorDataReceiver
    private var peerNodeId: String? = null


    private lateinit var connectionManager: WearConnectionManager

    private var connectionText by mutableStateOf("Connection stopped")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HistoryFileStore.open(this)
        enableEdgeToEdge()

        sessionClient = PhoneSessionClient(this, { node, state ->
            ReceivedSensorStore.confirmSession(node, state)
        }, { controls = it }, { reason ->
            ReceivedSensorStore.suspendReception(reason)
        })

        connectionManager = WearConnectionManager(
            context = this,
            localRole = DeviceRole.PHONE
        ) { info ->
            val nextPeer = if (info.status == ConnectionStatus.CONNECTED) info.nodeId else null
            if (peerNodeId != nextPeer && ::receiver.isInitialized) receiver.peerChanged()
            peerNodeId = nextPeer
            sessionClient.setPeer(peerNodeId, when (info.status) {
                ConnectionStatus.WAITING_FOR_APP -> "Watch app not responding (device may still be reachable)"
                ConnectionStatus.DISCONNECTED -> "Device link unavailable"
                ConnectionStatus.ERROR -> "Connection error"
                else -> "Connection unavailable"
            })
            connectionText = when (info.status) {
                ConnectionStatus.STOPPED -> "Connection stopped"
                ConnectionStatus.SEARCHING -> "Searching for watch"
                ConnectionStatus.DISCONNECTED -> "Watch disconnected"
                ConnectionStatus.WAITING_FOR_APP -> "Waiting for watch app"
                ConnectionStatus.CONNECTED -> "Watch connected"
                ConnectionStatus.ERROR -> "Connection error"
            }
        }

        receiver = SensorDataReceiver(this, { peerNodeId }, { batch ->
            check(pageStarted) { "Phone page not active" }
            val node = checkNotNull(peerNodeId)
            ReceivedSensorStore.accept(node, batch)
        })

        setContent {
            MobileWearableApplicationTheme(darkTheme = true) {
                SensorPage(
                    onBack = { finish() },
                    connectionText = connectionText,
                    overview = overview,
                    storedHistory = storedHistory,
                    chartProcessing = historyPreview?.processing ?: chartProcessing,
                    chartNowMillis = chartNowMillis,
                    chartEpochOffsetMillis = historyPreview?.epochOffsetMillis ?: chartEpochOffsetMillis,
                    showingHistoryPreview = historyPreview != null,
                    controls = controls
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()

        if (pageStarted) return
        pageStarted = true
        HistoryPreviewStore.sessionAction = { name ->
            val action = SessionAction.entries.firstOrNull { it.name == name }
            if (action == null) "Invalid sessionAction" else if (!controls.connected || !controls.synchronized ||
                controls.pending || controls.state?.allows(action) != true) "Not ready or invalid phase; wait for watch confirmation"
            else { sessionClient.command(action); "Requested ${action.label}; wait for watch ACK" }
        }

        // Remove any previous refresh before scheduling a new one.
        refreshHandler.removeCallbacks(refreshTask)
        refreshHandler.removeCallbacks(timeoutTask)
        refreshHandler.post(timeoutTask)
        refreshHandler.post(refreshTask)
        sessionClient.start()
        receiver.start()
        connectionManager.start()
        refreshDiagnostics()
    }

    override fun onStop() {
        stopPageResources()
        super.onStop()
    }

    private fun stopPageResources() {
        if (!pageStarted) return
        pageStarted = false
        HistoryPreviewStore.sessionAction = null

        refreshHandler.removeCallbacksAndMessages(null)
        sessionClient.stop()
        receiver.stop()
        connectionManager.stop()
        peerNodeId = null
        refreshDiagnostics()
        HistoryFileStore.update(chartProcessing, chartEpochOffsetMillis, force = true)
    }
    private fun refreshDiagnostics() {
        val display = SensorDisplayData.read()
        val snapshot = display.session
        val reception = display.reception
        val processing = display.processing
        chartNowMillis = System.currentTimeMillis()
        historyPreview = HistoryPreviewStore.value
        if (chartSession != processing.session) {
            HistoryFileStore.update(chartProcessing, chartEpochOffsetMillis, force = true)
            chartSession = processing.session
            chartEpochOffsetMillis = null
        }
        if (chartEpochOffsetMillis == null) {
            (snapshot?.streams?.get(WireDataType.HEART_RATE)?.latest
                ?: snapshot?.streams?.get(WireDataType.ACCELEROMETER)?.latest)?.let { received ->
                // Approximate wall-clock anchor from phone receipt, not clock synchronization.
                chartEpochOffsetMillis = chartNowMillis -
                    (SystemClock.elapsedRealtime() - received.receivedAtMillis) - received.sample.timestampNanos / 1_000_000L
            }
        }
        if (reception.ready) processing.automaticAction?.let {
            sessionClient.command(SessionAction.valueOf(it.name))
        }
        chartProcessing = processing
        if (chartNowMillis - lastHistoryWriteRequest >= 1_000L) {
            lastHistoryWriteRequest = chartNowMillis
            HistoryFileStore.update(processing, chartEpochOffsetMillis)
        }
        storedHistory = HistoryFileStore.snapshot
        val intensity = (processing.intensity as? MetricResult.Available)?.value
        val exercising = processing.exerciseStartedAt != null && processing.recoveryStartedAt == null &&
            processing.endedAtNanos == null
        fun integerBpm(value: Double) = String.format(Locale.US, "%.0f bpm", value)
        fun savedTime(metric: StoredMetric?) = if (historyPreview == null && metric?.savedAt != null)
            java.text.SimpleDateFormat("MM/dd HH:mm", Locale.US).format(java.util.Date(metric.savedAt)) else "—"
        val injectedHr = HistoryPreviewStore.heartRate
        val injectedFresh = injectedHr != null && SystemClock.elapsedRealtime() - injectedHr.receivedAtMillis <= 3_000L
        overview = mapOf(
            "current" to ((if (injectedHr != null) injectedHr.bpm.takeIf { injectedFresh }
                else display.currentHeartRateBpm)?.let { String.format(Locale.US, "%.0f", it) } ?: "—"),
            "freshness" to if (injectedHr != null) {
                if (injectedFresh) "Recent" else "Stale · send another ADB reading or clear preview"
            } else (display.heartRateUnavailableReason() ?: "Recent"),
            "intensityZone" to when {
                exercising -> intensity?.zone?.name.orEmpty()
                processing.recoveryStartedAt != null && processing.endedAtNanos == null -> "RECOVERING"
                else -> ""
            },
            "baselineTime" to savedTime(storedHistory.baseline),
            "recoveryTime" to savedTime(storedHistory.recovery),
            "baseline" to if (historyPreview == null && storedHistory.baseline != null)
                integerBpm(storedHistory.baseline!!.value) else when (val result = historyPreview?.processing?.restingHeartRate ?: processing.restingHeartRate) {
                is MetricResult.Available -> if (historyPreview != null) integerBpm(result.value) else "— waiting for save"
                is MetricResult.Unavailable -> metricStatus(result)
            },
            "recovery" to if (historyPreview == null && storedHistory.recovery != null)
                integerBpm(storedHistory.recovery!!.value) else when (val result = historyPreview?.processing?.recovery ?: processing.recovery) {
                is MetricResult.Available -> if (historyPreview != null) integerBpm(result.value.declineBpm) else "— waiting for save"
                is MetricResult.Unavailable -> metricStatus(result)
            }
        )

    }

    private fun metricStatus(result: MetricResult.Unavailable): String =
        "— (${result.reason.name.lowercase().replace('_', ' ')})"


}

@Composable
private fun SensorPage(
    onBack: () -> Unit,
    connectionText: String = "Connection stopped",
    overview: Map<String, String> = emptyMap(),
    storedHistory: StoredHistory = StoredHistory(),
    chartProcessing: ProcessingSnapshot = ProcessingSnapshot(),
    chartNowMillis: Long = System.currentTimeMillis(),
    chartEpochOffsetMillis: Long? = null,
    showingHistoryPreview: Boolean = false,
    controls: SessionControlUi = SessionControlUi()
) {
    var intensityTab by rememberSaveable { mutableStateOf(false) }
    val cyan = Color(0xFF00DDE7)
    val coral = Color(0xFFFF7973)
    fun value(key: String) = overview[key] ?: "— (waiting for data)"
    BoxWithConstraints(Modifier.fillMaxSize().background(verticalGradient(listOf(
        Color(0xFF000000), Color(0xFF303030)
    ))).safeDrawingPadding()) {
        val scale = (maxHeight.value / 720f).coerceIn(0.7f, 1.15f)
        val gap = 8.dp * scale
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = gap),
            verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(Modifier.height(42.dp * scale), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←", color = Color.White, fontSize = (24 * scale).sp) }
                Text("Sensor Data", color = Color.White, fontSize = (22 * scale).sp, fontWeight = FontWeight.Medium)
            }
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xCC182125)) {
                Row(Modifier.fillMaxWidth().height(46.dp * scale).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Image(painterResource(if (controls.connected) R.drawable.ic_connected else R.drawable.ic_disconnected),
                        null, Modifier.width(82.dp * scale).height(38.dp * scale).clipToBounds(),
                        contentScale = ContentScale.Crop)
                    Text(displayTitle(connectionText), color = Color.White, fontSize = (13 * scale).sp,
                        modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)

                }
            }
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xCC182125)) {
                Row(Modifier.fillMaxWidth().height(34.dp * scale).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Exercise Intensity", color = Color.White, fontSize = (13 * scale).sp)
                    Text(overview["intensityZone"]?.takeIf { it.isNotBlank() }?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Not Started",
                        color = when (overview["intensityZone"]) { "LOW" -> cyan; "MODERATE" -> Color(0xFFFFD166); "HIGH" -> coral; else -> Color.White },
                        fontSize = (13 * scale).sp)
                }
            }
            Row(Modifier.fillMaxWidth().height(100.dp * scale), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(painterResource(R.drawable.ic_heart_pulse), null, Modifier.size(82.dp * scale))
                Column(Modifier.weight(1f)) {
                    Text("Current Heart Rate", color = Color.White, fontSize = (16 * scale).sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(overview["current"] ?: "—", color = Color.White, fontSize = (46 * scale).sp, fontWeight = FontWeight.SemiBold)
                        Text(" bpm", color = Color.White, fontSize = (20 * scale).sp, modifier = Modifier.padding(bottom = 7.dp * scale))
                    }
                    if (overview["freshness"] != "Recent")
                        Text("Waiting For Data", color = Color.LightGray, fontSize = (9 * scale).sp)

                }
            }
            Row(Modifier.height(112.dp * scale), horizontalArrangement = Arrangement.spacedBy(gap)) {
                listOf(Triple("Resting\nHeart Rate", "baseline", R.drawable.ic_heart_filled),
                    Triple("Heart Rate\nRecovery", "recovery", R.drawable.ic_recovery)).forEach { (title, key, icon) ->
                    Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(14.dp), color = Color(0xFF252323)) {
                        Column(Modifier.padding(10.dp * scale), verticalArrangement = Arrangement.spacedBy(4.dp * scale)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(overview["${key}Time"].orEmpty().ifEmpty { "—" }, color = Color.LightGray, fontSize = (10 * scale).sp)
                            }
                            Row(Modifier.fillMaxWidth().height(34.dp * scale),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp * scale)) {
                                Image(painterResource(icon), null, Modifier.size(26.dp * scale))
                                Text(title, color = coral, fontSize = (14 * scale).sp,
                                    lineHeight = (16 * scale).sp, maxLines = 2, modifier = Modifier.weight(1f))
                            }
                            val unavailable = value(key).startsWith("—")
                            Text(if (unavailable) when {
                                value(key).contains("collecting") || value(key).contains("waiting") || value(key).contains("awaiting") || value(key).contains("no session") -> "Waiting For Data"
                                value(key).contains("interrupted") -> "Recovery Interrupted"
                                value(key).contains("insufficient") -> "Insufficient Data"
                                value(key).contains("movement") -> "Movement Detected"
                                value(key).contains("unknown") -> "Motion Unknown"
                                else -> "Unavailable"
                            } else displayTitle(value(key)), color = if (unavailable) Color.LightGray else Color.White,
                                fontSize = ((if (unavailable) 9 else 24) * scale).sp,
                                lineHeight = ((if (unavailable) 11 else 28) * scale).sp,
                                fontWeight = if (unavailable) FontWeight.Normal else FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(), softWrap = false)
                        }
                    }
                }
            }
            Surface(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(14.dp), color = Color(0xCC182125)) {
                Column(Modifier.fillMaxSize().padding(10.dp * scale), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.height(38.dp * scale), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Heart Rate History", "Intensity History").forEachIndexed { index, title ->
                            val selected = intensityTab == (index == 1)
                            TextButton(onClick = { intensityTab = index == 1 },
                                modifier = Modifier.weight(1f).fillMaxHeight().background(if (selected) cyan else Color.Transparent, RoundedCornerShape(10.dp))) {
                                Text(title, color = if (selected) Color.Black else Color.White, fontSize = (12 * scale).sp, maxLines = 1)
                            }
                        }
                    }
                    if (storedHistory.error != null) Text(storedHistory.error ?: "", color = coral, fontSize = 9.sp)
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        HourlyHistoryChart(chartProcessing, chartNowMillis, chartEpochOffsetMillis,
                            if (intensityTab) "Intensity" else "HR",
                            if (showingHistoryPreview) null else storedHistory.hours)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SensorPagePreview() {
    MobileWearableApplicationTheme(darkTheme = true) {
        SensorPage(onBack = {})
    }
}


private fun displayTitle(text: String): String = Regex("[A-Za-z]+").replace(text) {
    if (it.value in setOf("bpm", "min", "m", "s")) it.value
    else it.value.replaceFirstChar { letter -> letter.uppercase() }
}
