package com.example.mobile_wearableapplication

import com.example.mobile_wearableapplication.communication.SensorDataReceiver
import com.example.mobile_wearableapplication.communication.WireDataType
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import com.example.mobile_wearableapplication.communication.ReceivedSensorStore
import com.example.mobile_wearableapplication.communication.ReceivedSessionSnapshot
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
            refreshDiagnostics()
        }, { controls = it })

        connectionManager = WearConnectionManager(
            context = this,
            localRole = DeviceRole.PHONE
        ) { info ->
            peerNodeId = if (info.status == ConnectionStatus.CONNECTED) info.nodeId else null
            sessionClient.setPeer(peerNodeId)
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
            val node = checkNotNull(peerNodeId)
            ReceivedSensorStore.accept(node, batch)
            refreshDiagnostics()
        }, { transferText = it })

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
                    controls = controls,
                    onAction = { sessionClient.command(it) },
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
        val snapshot = ReceivedSensorStore.snapshot()
        sessionText = snapshot?.let { "Session: ${it.sessionId}\nWatch: ${it.nodeId}" }
            ?: "No session received"
        accelerationPreview = formatStream(snapshot, WireDataType.ACCELEROMETER)
        heartRatePreview = formatStream(snapshot, WireDataType.HEART_RATE)
        val processing = ReceivedSensorStore.processingSnapshot()
        processingText = "Processing input (unique within current store retention)\n" +
            "Acceleration: ${processing.acceleration.acceptedSamples}\n" +
            "Heart rate: ${processing.heartRate.acceptedSamples}\n" +
            "Resting HR: ${metricStatus(processing.restingHeartRate)}\n" +
            "Exercise HR: ${metricStatus(processing.exerciseHeartRate)}\n" +
            "Intensity: ${metricStatus(processing.intensity)}\n" +
            "Recovery: ${metricStatus(processing.recovery)}\n" +
            "Workout state: ${metricStatus(processing.workoutState)}\n" +
            "Acceleration RMS: ${metricStatus(processing.accelerationRms)}"
    }

    private fun metricStatus(result: MetricResult<*>): String = when (result) {
        is MetricResult.Available -> result.value.toString()
        is MetricResult.Unavailable -> "— (${result.reason.name.lowercase().replace('_', ' ')})"
    }

    private fun formatStream(snapshot: ReceivedSessionSnapshot?, type: WireDataType): String {
        val title = if (type == WireDataType.ACCELEROMETER) "Acceleration" else "Heart rate"
        val stream = snapshot?.streams?.get(type) ?: return "$title: waiting for data"
        val received = stream.latest ?: return "$title: waiting for data"
        val sample = received.sample
        val ageMillis = (SystemClock.elapsedRealtime() -
            checkNotNull(stream.lastNewSampleAtMillis)).coerceAtLeast(0L)
        val freshness = if (ageMillis >= ReceivedSensorStore.STALE_AFTER_MILLIS) "Stale" else "Recent"
        val value = if (type == WireDataType.ACCELEROMETER) {
            String.format(Locale.US, "X: %.3f  Y: %.3f  Z: %.3f m/s^2", sample.x, sample.y, sample.z)
        } else String.format(Locale.US, "%.1f bpm", sample.bpm)
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
    controls: SessionControlUi = SessionControlUi(),
    onAction: (SessionAction) -> Unit = {},
    onSync: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                verticalGradient(
                    colors = listOf(
                        Color(0xFF000000),
                        Color(0xFF303030)
                    )
                )
            )
            .safeDrawingPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 50.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(
                space = 24.dp,
                alignment = Alignment.CenterVertically
            )
        ) {
            Text(
                text = "Sensor Data",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Text(
                text = connectionText,
                color = Color.LightGray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Text("${controls.state?.phase?.label ?: "No confirmed phase"} · ${controls.state?.lifecycle ?: "Unknown"}", color = Color.White)
            Text(controls.message, color = Color.LightGray, fontSize = 12.sp)
            controls.state?.let { state ->
                Text("Revision: ${state.revision}\nWatch phase time: ${state.transitions.lastOrNull()?.watchElapsedTimeNanos ?: "—"} ns",
                    color = Color.Gray, fontSize = 12.sp)
            }
            SessionAction.entries.forEach { action ->
                Button(onClick = { onAction(action) }, enabled = controls.synchronized && !controls.pending && controls.state?.allows(action) == true) {
                    Text(action.label)
                }
            }
            Button(onClick = onSync, enabled = !controls.pending) { Text("Sync state") }

            Text(accelerationPreview, color = Color.White, fontSize = 14.sp)
            Text(heartRatePreview, color = Color.White, fontSize = 14.sp)
            Text(processingText, color = Color.LightGray, fontSize = 12.sp)
            Text(transferText, color = Color.LightGray, fontSize = 12.sp)
            Text(
                text = "$sessionText\nRolling history in memory; receiving while this page is active.\nRecent/Stale describes receipt time, not measurement accuracy.",
                modifier = Modifier.padding(bottom = 8.dp),
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

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

