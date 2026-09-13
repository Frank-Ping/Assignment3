package com.example.mobile_wearableapplication

import com.example.mobile_wearableapplication.communication.SensorDataReceiver
import com.example.mobile_wearableapplication.communication.WireDataType
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import com.example.mobile_wearableapplication.communication.ReceivedSensorStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import com.example.mobile_wearableapplication.communication.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush.Companion.verticalGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mobile_wearableapplication.ui.theme.MobileWearableApplicationTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.mobile_wearableapplication.communication.ConnectionStatus
import com.example.mobile_wearableapplication.communication.DeviceRole
import com.example.mobile_wearableapplication.communication.WearConnectionManager
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
            refreshHandler.postDelayed(this, 1_000L)
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
    private var chartNowMillis by mutableStateOf(System.currentTimeMillis())
    private var chartEpochOffsetMillis by mutableStateOf<Long?>(null)
    private var chartSession: com.example.mobile_wearableapplication.processing.ProcessingSession? = null
    private var chartProcessing by mutableStateOf(ProcessingSnapshot())
    private var pendingTransferText = "No batch received"
    private var sessionText by mutableStateOf("No session received")
    private var processingText by mutableStateOf("Processing: no session")
    private lateinit var receiver: SensorDataReceiver
    private var peerNodeId: String? = null
    private var transferText by mutableStateOf("No batch received")
    private var accelerationPreview by mutableStateOf("Acceleration: --")
    private var heartRatePreview by mutableStateOf("Heart rate: --")


    private lateinit var connectionManager: WearConnectionManager

    private var connectionText by mutableStateOf("Connection stopped")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        }, { pendingTransferText = it })

        setContent {
            MobileWearableApplicationTheme(darkTheme = true) {
                SensorPage(
                    onBack = { finish() },
                    connectionText = connectionText,
                    transferText = transferText,
                    accelerationPreview = accelerationPreview,
                    heartRatePreview = heartRatePreview,
                    sessionText = sessionText,
                    processingText = processingText,
                    overview = overview,
                    chartProcessing = chartProcessing,
                    chartNowMillis = chartNowMillis,
                    chartEpochOffsetMillis = chartEpochOffsetMillis,
                    controls = controls,
                    onAction = { sessionClient.command(it) },
                    onSourceToggle = { sessionClient.toggleHeartRateSource() },
                    onSync = { sessionClient.sync() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()

        if (pageStarted) return
        pageStarted = true

        // Remove any previous refresh before scheduling a new one.
        refreshHandler.removeCallbacks(refreshTask)
        refreshHandler.removeCallbacks(timeoutTask)
        refreshHandler.post(timeoutTask)
        refreshHandler.post(refreshTask)
        sessionClient.start()
        receiver.start()
        connectionManager.start()
    }

    override fun onStop() {
        stopPageResources()
        super.onStop()
    }

    private fun stopPageResources() {
        if (!pageStarted) return
        pageStarted = false

        refreshHandler.removeCallbacksAndMessages(null)
        sessionClient.stop()
        receiver.stop()
        connectionManager.stop()
        peerNodeId = null
    }
    private fun refreshDiagnostics() {
        transferText = pendingTransferText
        val display = SensorDisplayData.read()
        val snapshot = display.session
        val reception = display.reception
        sessionText = snapshot?.let { "Session: ${it.sessionId}\nWatch: ${it.nodeId}" }
            ?: "No session received"
        val lastInterruption = reception.interruptions.lastOrNull()
        sessionText += "\n${reception.message}\nReception interruptions retained: ${reception.interruptions.size}" +
            "\nRejected while unconfirmed: ${reception.rejectedBatches}" +
            (lastInterruption?.let {
                val duration = ((it.endedAtMillis ?: SystemClock.elapsedRealtime()) - it.startedAtMillis) / 1000
                "\nLast interruption: ${duration}s — ${it.reason}\nContinuity unknown; no offline replay."
            } ?: "")
        accelerationPreview = formatStream(display, WireDataType.ACCELEROMETER)
        heartRatePreview = formatStream(display, WireDataType.HEART_RATE)
        val processing = display.processing
        chartNowMillis = System.currentTimeMillis()
        if (chartSession != processing.session) {
            chartSession = processing.session
            chartEpochOffsetMillis = null
        }
        if (chartEpochOffsetMillis == null) {
            snapshot?.streams?.get(WireDataType.HEART_RATE)?.latest?.let { received ->
                // Approximate wall-clock anchor from phone receipt, not clock synchronization.
                chartEpochOffsetMillis = chartNowMillis -
                    (SystemClock.elapsedRealtime() - received.receivedAtMillis) - received.sample.timestampNanos / 1_000_000L
            }
        }
        chartProcessing = processing
        val preprocessing = processing.preprocessing
        val exercise = (processing.exerciseHeartRate as? MetricResult.Available)?.value
        val intensity = (processing.intensity as? MetricResult.Available)?.value
        val exercising = processing.exerciseStartedAt != null && processing.recoveryStartedAt == null &&
            processing.endedAtNanos == null
        val currentReason = when {
            !exercising -> "Only available during exercise"
            display.unavailableReason(WireDataType.HEART_RATE) != null -> display.unavailableReason(WireDataType.HEART_RATE)
            exercise?.currentBpm == null -> "Insufficient fresh data"
            else -> "3-second smoothed HR"
        }
        fun bpm(value: Double?) = value?.let { String.format(Locale.US, "%.1f bpm", it) } ?: "—"
        overview = mapOf(
            "current" to (display.currentHeartRateBpm?.let { String.format(Locale.US, "%.0f", it) } ?: "—"),
            "source" to (snapshot?.streams?.get(WireDataType.HEART_RATE)?.latest?.source?.name ?: "—"),
            "freshness" to (display.unavailableReason(WireDataType.HEART_RATE) ?: "Recent"),
            "intensity" to intensityText(processing.intensity),
            "exercise" to exerciseHeartRateText(processing.exerciseHeartRate),
            "exerciseCurrent" to bpm(exercise?.currentBpm.takeIf {
                exercising && display.unavailableReason(WireDataType.HEART_RATE) == null
            }),
            "exerciseCurrentReason" to (currentReason ?: "Waiting for data"),
            "exerciseAverage" to bpm(exercise?.timeWeightedAverageBpm),
            "exercisePeak" to bpm(exercise?.smoothedPeakBpm),
            "exerciseStatistics" to when (val result = processing.exerciseHeartRate) {
                is MetricResult.Unavailable -> metricStatus(result)
                is MetricResult.Available -> if (exercising) "Average: time weighted · Peak: smoothed"
                    else "Retained exercise statistics · Peak: smoothed"
            },
            "intensityZone" to (intensity?.zone?.name ?: ""),
            "baseline" to when (val result = processing.restingHeartRate) {
                is MetricResult.Available -> bpm(result.value)
                is MetricResult.Unavailable -> metricStatus(result)
            },
            "baselineState" to when {
                processing.exerciseStartedAt != null || processing.endedAtNanos != null ->
                    if (processing.restingHeartRate is MetricResult.Available) "Frozen session baseline"
                    else "Baseline window closed; no valid baseline"
                processing.restingHeartRate is MetricResult.Available -> "Valid resting baseline"
                else -> "Requires 30 seconds of stillness and sufficient HR data"
            },
            "recovery" to when (val result = processing.recovery) {
                is MetricResult.Available -> String.format(Locale.US, "%.1f bpm/min", result.value.bpmPerMinute)
                is MetricResult.Unavailable -> metricStatus(result)
            },
            "details" to recoveryText(processing.recovery, processing.recoveryRemainingSeconds),
            "summary" to summaryText(display.currentSummary),
            "previous" to summaryText(display.lastSummary),
            "hrChart" to "${display.charts.heartRate.size} raw samples · ${display.charts.gaps.count { it.stream == "HR" }} gaps",
            "zoneChart" to zoneDurationText(display.charts.zoneDurations),
            "rms" to "${metricStatus(processing.accelerationRms)} · ${processing.motion.state}\n${display.charts.rms.size} points"
        )

        fun windowText(window: com.example.mobile_wearableapplication.processing.CoveredWindow?): String {
            if (window == null) return "— (waiting for data)"
            val mean = window.mean?.let { String.format(Locale.US, "%.1f bpm", it) } ?: "—"
            return "$mean; coverage ${String.format(Locale.US, "%.0f%%", window.coverageFraction * 100)}; samples ${window.sampleCount}; smooth support ${String.format(Locale.US, "%.2fs", window.smoothingCoveredSeconds)}"
        }
        processingText = "Processing input (unique within current store retention)\n" +
            "Acceleration: ${processing.acceleration.acceptedSamples}\n" +
            "Heart rate: ${processing.heartRate.acceptedSamples}\n" +
            "Session resting baseline: ${metricStatus(processing.restingHeartRate)}\n" +
            "Exercise HR: ${exerciseHeartRateText(processing.exerciseHeartRate)}\n" +
            "Intensity: ${intensityText(processing.intensity)}\n" +
            "Zone duration: ${zoneDurationText(processing.zoneDurations)}\n" +
            "Workout Recovery: ${recoveryText(processing.recovery, processing.recoveryRemainingSeconds)}\n" +
            "Workout state: ${when (val state = processing.workoutState) {
                is MetricResult.Available -> "${state.value.phase} · ${reception.sessionLifecycle}" +
                    (if (!reception.ready) " (last confirmed)" else "")
                is MetricResult.Unavailable -> metricStatus(state)
            }}\n" +
            "Acceleration RMS: ${metricStatus(processing.accelerationRms)}\n\n" +
            "Motion: ${processing.motion.state}\n" +
            "stillnessVerified: ${processing.quality.stillnessVerified ?: "Unknown"}; motionDetected: ${processing.quality.motionDetected ?: "Unknown"}\n" +
            "Preprocessing (as of watch sample time)\n" +
            "${if (!reception.ready || reception.sessionLifecycle != SessionLifecycle.RUNNING) "Historical / reception paused\n" else ""}" +
            "Last valid raw HR (may be historical): ${preprocessing.rawHeartRateBpm ?: "—"}\n" +
            "HR 3s: ${windowText(preprocessing.heartRate3s)}\n" +
            "HR 5s: ${windowText(preprocessing.heartRate5s)}\n" +
            "Acceleration 1s coverage: ${preprocessing.acceleration1s?.let { String.format(Locale.US, "%.0f%%", it.coverageFraction * 100) } ?: "—"}\n" +
            "HR quality: ${preprocessing.heartRateStats}\n" +
            "Acceleration quality: ${preprocessing.accelerationStats}\n" +
            "Hold limits: HR 3s / acceleration 0.2s. Windows do not advance without new watch data.\n\n" +
            "Chart data: raw HR ${display.charts.heartRate.size}; RMS ${display.charts.rms.size}; " +
            "gaps ${display.charts.gaps.size}; phases ${display.charts.phases.size}\n" +
            "Current summary: ${summaryText(display.currentSummary)}\n" +
            "Last completed summary (this app run): ${summaryText(display.lastSummary)}"
    }

    private fun summaryText(summary: com.example.mobile_wearableapplication.processing.SessionSummary?): String =
        summary?.let {
            "${it.session.sessionId}\nBaseline: ${metricStatus(it.restingHeartRate)}\n" +
                "Exercise HR: ${exerciseHeartRateText(it.exerciseHeartRate)}\n" +
                "Recovery: ${recoveryText(it.recovery, null)}"
        } ?: "— (no completed session)"

    private fun exerciseHeartRateText(result: com.example.mobile_wearableapplication.processing.MetricResult<com.example.mobile_wearableapplication.processing.ExerciseHeartRate>): String =
        when (result) {
            is MetricResult.Unavailable -> metricStatus(result)
            is MetricResult.Available -> {
                fun bpm(value: Double?) = value?.let { "%.1f bpm".format(it) }
                    ?: "— (outside exercise or insufficient fresh data)"
                "\nCurrent (3s): ${bpm(result.value.currentBpm)}\nAverage: ${bpm(result.value.timeWeightedAverageBpm)}\nPeak (smoothed): ${bpm(result.value.smoothedPeakBpm)}"
            }
        }

    private fun intensityText(result: MetricResult<com.example.mobile_wearableapplication.processing.ExerciseIntensity>): String =
        when (result) {
            is MetricResult.Unavailable -> metricStatus(result)
            is MetricResult.Available -> {
                val value = result.value
                val percentage = value.percentage?.let { "%.1f%%".format(it) } ?: "—"
                "${value.zone.name.lowercase().replaceFirstChar { it.uppercase() }} ($percentage; HRmax ${value.hrMaxBpm}, demo reference)"
            }
        }

    private fun recoveryText(result: MetricResult<com.example.mobile_wearableapplication.processing.RecoveryRate>, remaining: Long?): String =
        when (result) {
            is MetricResult.Unavailable -> metricStatus(result) +
                (if (remaining != null && remaining > 0) " ($remaining s remaining; watch sample time)" else "")
            is MetricResult.Available -> {
                val r = result.value
                "\nH0: %.1f bpm; H60: %.1f bpm\nDrop: %.1f bpm\nRate: %.1f bpm/min (1-minute average decline)".format(
                    r.startBpm, r.endBpm, r.declineBpm, r.bpmPerMinute) +
                    (if (r.declineBpm < 0) "\nHeart rate has not declined" else "")
            }
        }

    private fun zoneDurationText(value: com.example.mobile_wearableapplication.processing.ZoneDurations?): String {
        if (value == null) return "— (waiting for exercise)"
        return "\nLow: %.1f s\nModerate: %.1f s\nHigh: %.1f s\nUnclassified: %.1f s\nMissing: %.1f s\nTotal: %.1f s".format(
            value.lowSeconds, value.moderateSeconds, value.highSeconds,
            value.unclassifiedSeconds, value.missingSeconds, value.totalSeconds)
    }

    private fun metricStatus(result: MetricResult<*>): String = when (result) {
        is MetricResult.Available -> result.value.toString()
        is MetricResult.Unavailable -> "— (${result.reason.name.lowercase().replace('_', ' ')})"
    }

    private fun formatStream(display: SensorDisplayData, type: WireDataType): String {
        val title = if (type == WireDataType.ACCELEROMETER) "Acceleration" else "Heart rate"
        val reason = display.unavailableReason(type)
        val stream = display.session?.streams?.get(type) ?: return "$title: — ($reason)"
        val received = stream.latest ?: return "$title: — ($reason)"
        val sample = received.sample
        val ageMillis = (display.readAtMillis -
            checkNotNull(stream.lastNewSampleAtMillis)).coerceAtLeast(0L)
        val freshness = reason ?: "Recent"
        val value = if (reason != null) "— ($reason)" else if (type == WireDataType.ACCELEROMETER) {
            String.format(Locale.US, "X: %.3f  Y: %.3f  Z: %.3f m/s^2", sample.x, sample.y, sample.z)
        } else String.format(Locale.US, "%.1f bpm", display.currentHeartRateBpm)
        val capacity = if (type == WireDataType.ACCELEROMETER) {
            ReceivedSensorStore.ACCELERATION_CAPACITY
        } else ReceivedSensorStore.HEART_RATE_CAPACITY
        return "$title (${received.source})\n$value\n" +
            "Sequence: ${sample.sequence}\nWatch timestamp: ${sample.timestampNanos} ns\n" +
            "$freshness: last new sample received ${ageMillis / 1000}s ago\n" +
            "Batches: ${stream.batches}; incoming samples: ${stream.receivedSamples}\n" +
            "Stored: ${stream.history.size}/$capacity; duplicate batches: ${stream.duplicateBatches}"
    }

}

@Composable
private fun SensorPage(
    onBack: () -> Unit,
    connectionText: String = "Connection stopped",
    transferText: String = "No batch received",
    accelerationPreview: String = "Acceleration: --",
    heartRatePreview: String = "Heart rate: --",
    sessionText: String = "No session received",
    processingText: String = "Processing: no session",
    overview: Map<String, String> = emptyMap(),
    chartProcessing: ProcessingSnapshot = ProcessingSnapshot(),
    chartNowMillis: Long = System.currentTimeMillis(),
    chartEpochOffsetMillis: Long? = null,
    controls: SessionControlUi = SessionControlUi(),
    onAction: (SessionAction) -> Unit = {},
    onSourceToggle: () -> Unit = {},
    onSync: () -> Unit = {}
) {
    var controlsExpanded by rememberSaveable { mutableStateOf(false) }
    var intensityTab by rememberSaveable { mutableStateOf(false) }
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    val cyan = Color(0xFF00DDE7)
    val coral = Color(0xFFFF7973)
    val session = controls.state
    val workoutLabel = when (session?.lifecycle) {
        null -> "Unknown"
        SessionLifecycle.IDLE -> "Not Started"
        SessionLifecycle.ENDED -> "Ended"
        SessionLifecycle.INTERRUPTED -> "Interrupted"
        SessionLifecycle.RUNNING -> session.phase?.label ?: "Unknown"
    }
    val primaryAction = when {
        session == null -> null
        session.lifecycle != SessionLifecycle.RUNNING -> SessionAction.START_SESSION
        session.phase == SessionPhase.RESTING -> SessionAction.START_WORKOUT
        session.phase == SessionPhase.EXERCISING -> SessionAction.END_WORKOUT
        session.phase == SessionPhase.RECOVERING -> SessionAction.FINISH_SESSION
        else -> null
    }
    fun value(key: String) = overview[key] ?: "— (waiting for data)"
    Box(Modifier.fillMaxSize().background(verticalGradient(listOf(
        Color(0xFF000000), Color(0xFF303030)
    ))).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹", color = Color.White, fontSize = 30.sp) }
                Text("Sensor Data", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
            DisplayCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(connectionText, color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = onSourceToggle,
                        enabled = controls.connected && controls.synchronized && !controls.pending &&
                            session != null && session.lifecycle != SessionLifecycle.RUNNING && controls.heartRateSource != null) {
                        Text("⇄ HR · ${controls.heartRateSource ?: "—"}", color = if (controls.pending || session?.lifecycle == SessionLifecycle.RUNNING) Color.Gray else cyan)
                    }
                }
                if (controls.pending || !controls.synchronized)
                    Text(controls.message, color = Color.LightGray, fontSize = 12.sp)
            }
            DisplayCard {
                Text("Exercise intensity · ${value("intensity")}", color = when (overview["intensityZone"]) {
                    "LOW" -> cyan
                    "MODERATE" -> Color(0xFFFFD166)
                    "HIGH" -> coral
                    else -> Color.LightGray
                }, fontSize = 13.sp)
            }
            DisplayCard {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("♥", color = coral, fontSize = 48.sp)
                Column {
                    Text("Current heart rate", color = Color.White, fontSize = 16.sp)
                    Text("Raw HR · ${value("freshness")}", color = Color.LightGray, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(overview["current"] ?: "—", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.SemiBold)
                        Text(" bpm", color = Color.LightGray, fontSize = 20.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DisplayCard(Modifier.weight(1f)) {
                    Text("Session resting heart rate", color = coral, fontSize = 14.sp)
                    Text(value("baseline"), color = Color.White, fontSize = 18.sp)
                    Text(value("baselineState"), color = Color.LightGray, fontSize = 12.sp)
                }
                DisplayCard(Modifier.weight(1f)) {
                    Text("Workout Recovery Rate", color = coral, fontSize = 14.sp)
                    Text(value("recovery"), color = Color.White, fontSize = 18.sp)
                }
            }
            DisplayCard {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Heart rate history", "Intensity history").forEachIndexed { index, title ->
                        val selected = intensityTab == (index == 1)
                        TextButton(onClick = { intensityTab = index == 1 },
                            modifier = Modifier.weight(1f).background(if (selected) cyan else Color.Transparent, RoundedCornerShape(10.dp))) {
                            Text(title, color = if (selected) Color.Black else Color.White, fontSize = 12.sp)
                        }
                    }
                }
                Text(if (intensityTab) "Exercise intensity history" else "Raw heart rate history", color = Color.White)
                if (intensityTab) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 160.dp), verticalArrangement = Arrangement.Center) {
                        Text(value("zoneChart"), color = Color.LightGray)
                        Text("Chart preview unavailable", color = Color.Gray, fontSize = 12.sp)
                    }
                } else HeartRateChart(chartProcessing, chartNowMillis, chartEpochOffsetMillis)
            }
            TextButton(onClick = { controlsExpanded = !controlsExpanded }) {
                Text(if (controlsExpanded) "Hide session controls" else "Session controls", color = cyan)
            }
            if (controlsExpanded) {
            DisplayCard {
                Text("Workout state · ${if (!controls.synchronized) "Last confirmed: " else ""}$workoutLabel", color = Color.White)
                Text(controls.message, color = Color.LightGray, fontSize = 12.sp)
                Button(onClick = { primaryAction?.let(onAction) }, modifier = Modifier.fillMaxWidth(),
                    enabled = controls.connected && controls.synchronized && !controls.pending &&
                        primaryAction != null && session?.allows(primaryAction) == true) {
                    Text(if (controls.pending) "Waiting for confirmation…"
                        else primaryAction?.label ?: "Waiting for watch state")
                }
                if (primaryAction == SessionAction.END_WORKOUT)
                    Text("Starts recovery; sensor collection continues.", color = Color.LightGray, fontSize = 12.sp)
                if (primaryAction == SessionAction.FINISH_SESSION)
                    Text("Ends the session and stops sensor collection.", color = Color.LightGray, fontSize = 12.sp)
                TextButton(onClick = onSync, enabled = controls.connected && !controls.pending) { Text("Sync state") }
            }
            }
            DisplayCard {
                TextButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded }) {
                    Text(if (diagnosticsExpanded) "Hide diagnostics" else "Show diagnostics", color = cyan)
                }
                if (diagnosticsExpanded) {
            DisplayCard {
                Text("Exercise heart rate", color = Color.White, fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Current" to "exerciseCurrent", "Average" to "exerciseAverage", "Peak" to "exercisePeak").forEach { (label, key) ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(label, color = Color.LightGray, fontSize = 12.sp)
                            Text(overview[key] ?: "—", color = Color.White, fontSize = 16.sp)
                        }
                    }
                }
                Text("Current: ${value("exerciseCurrentReason")}", color = Color.LightGray, fontSize = 12.sp)
                Text(value("exerciseStatistics"), color = Color.LightGray, fontSize = 12.sp)
            }
            DisplayCard {
                Text("Movement RMS · m/s²", color = Color.White)
                Column(Modifier.heightIn(min = 100.dp), verticalArrangement = Arrangement.Center) {
                    Text(value("rms"), color = Color.LightGray)
                    Text("Chart preview unavailable", color = Color.Gray, fontSize = 12.sp)
                }
            }
            DisplayCard {
                Text("Recovery details / Session summary", color = Color.White)
                Text(value("details"), color = Color.LightGray, fontSize = 13.sp)
                Text("Current summary\n${value("summary")}\n\nLast completed (this app run)\n${value("previous")}", color = Color.LightGray, fontSize = 13.sp)
            }

                    Text("$accelerationPreview\n\n$heartRatePreview\n\n$processingText\n\n$transferText\n\n$sessionText",
                        color = Color.LightGray, fontSize = 12.sp)
                    Text("Rolling history in memory; receiving while this page is active. Recent/Stale describes receipt time, not measurement accuracy.", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DisplayCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color(0xD91B252B)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Preview(showBackground = true)
@Composable
private fun SensorPagePreview() {
    MobileWearableApplicationTheme(darkTheme = true) {
        SensorPage(onBack = {})
    }
}

