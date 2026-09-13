package com.example.wear.presentation

import android.os.SystemClock
import java.util.UUID
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.wear.compose.material3.Button
import com.example.wear.presentation.communication.SensorBatch
import com.example.wear.presentation.communication.SensorDataSender
import com.example.wear.presentation.communication.WireDataType
import com.example.wear.presentation.communication.WireSource
import com.example.wear.presentation.communication.WireSample
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import com.example.wear.presentation.theme.MobileWearableApplicationTheme
import android.util.Log
import com.example.wear.presentation.sensors.SensorManagerAccelerometerSource
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import com.example.wear.presentation.data.SensorStatus
import com.example.wear.presentation.sensors.HeartRateSource
import com.example.wear.presentation.sensors.HealthServicesHeartRateSource
import com.example.wear.presentation.communication.ConnectionStatus
import com.example.wear.presentation.communication.DeviceRole
import com.example.wear.presentation.communication.WearConnectionManager

class SensorActivity : ComponentActivity() {
    private lateinit var sender: SensorDataSender
    private var peerNodeId by mutableStateOf<String?>(null)
    private var transferText by mutableStateOf("Sender stopped")
    private val testSessionId = UUID.randomUUID().toString()
    private var testSequence = 0L

    private fun sendTest(type: WireDataType) {
        val nodeId = peerNodeId ?: return
        testSequence++
        val time = SystemClock.elapsedRealtimeNanos()
        val sample = if (type == WireDataType.ACCELEROMETER) {
            WireSample(testSequence, time, x = 0.12, y = -0.08, z = 9.79)
        } else WireSample(testSequence, time, bpm = 72.0)
        sender.sendBatch(nodeId, SensorBatch(
            sessionId = testSessionId,
            batchId = UUID.randomUUID().toString(),
            dataType = type,
            source = WireSource.DEMO,
            samples = listOf(sample)
        ))
    }


    private lateinit var accelerometerSource: SensorManagerAccelerometerSource

    private var lastLoggedStatus: String? = null

    private lateinit var heartRateSource: HeartRateSource

    private lateinit var connectionManager: WearConnectionManager

    private var connectionText by mutableStateOf("Connection stopped")

    private var pageStarted = false
    private var lastHeartRateStatus: SensorStatus? = null
    private var heartRateText by mutableStateOf("-- bpm")
    private var accelerationText by mutableStateOf("X: --\nY: --\nZ: --")
    private var permissionRequested = false

    private val heartRatePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted && pageStarted) {
                startHeartRateTest()
            } else if (!granted) {
                logHeartRateStatus(SensorStatus.PERMISSION_REQUIRED)
            }
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accelerometerSource = SensorManagerAccelerometerSource(this)

        heartRateSource = HealthServicesHeartRateSource(this)

        connectionManager = WearConnectionManager(
            context = this,
            localRole = DeviceRole.WATCH
        ) { info ->
            peerNodeId = if (info.status == ConnectionStatus.CONNECTED) info.nodeId else null
            connectionText = when (info.status) {
                ConnectionStatus.STOPPED -> "Connection stopped"
                ConnectionStatus.SEARCHING -> "Searching for phone"
                ConnectionStatus.DISCONNECTED -> "Phone disconnected"
                ConnectionStatus.WAITING_FOR_APP -> "Waiting for phone app"
                ConnectionStatus.CONNECTED -> "Phone connected"
                ConnectionStatus.ERROR -> "Connection error"
            }
        }

        sender = SensorDataSender(this) { transferText = it }

        setContent {
            MobileWearableApplicationTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(
                        12.dp,
                        Alignment.CenterVertically
                    )
                ) {
                    Text(
                        text = "Sensor Data",
                        color = Color.White,
                        fontSize = 20.sp
                    )

                    Text(
                        text = "Heart rate: $heartRateText",
                        color = Color.White,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "Acceleration (m/s²)\n$accelerationText",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Button(onClick = { sendTest(WireDataType.ACCELEROMETER) }, enabled = peerNodeId != null) {
                        Text("Test XYZ")
                    }
                    Button(onClick = { sendTest(WireDataType.HEART_RATE) }, enabled = peerNodeId != null) {
                        Text("Test HR")
                    }
                    Text(transferText, color = Color.LightGray, fontSize = 12.sp)
                    Text(
                        text = connectionText,
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        sender.start()
        connectionManager.start()
        pageStarted = true

        accelerometerSource.start(
            onRecord = { record ->
                accelerationText = String.format(
                    Locale.US, "X: %.2f\nY: %.2f\nZ: %.2f",
                    record.x, record.y, record.z
                )
                // Log every 25 samples to avoid flooding Logcat.
                if (record.sequence == 1L || record.sequence % 25L == 0L) {
                    Log.d(
                        "AccelCheck",
                        "seq=${record.sequence}, " +
                                "x=${record.x}, " +
                                "y=${record.y}, " +
                                "z=${record.z}, " +
                                "time=${record.timestampNanos}"
                    )
                }
            },
            onStatusChanged = { status ->
                if (lastLoggedStatus != status.name) {
                    Log.d("AccelCheck", "status=$status")
                    lastLoggedStatus = status.name
                }
            }
        )
        requestHeartRateTest()
    }

    override fun onStop() {
        pageStarted = false
        sender.stop()
        connectionManager.stop()
        peerNodeId = null

        accelerometerSource.stop()

        // Leaving this foreground test interrupts the test session.
        heartRateSource.stop()

        super.onStop()
    }

    private fun requestHeartRateTest() {
        val permission =
            HealthServicesHeartRateSource.requiredPermission()

        val granted = ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startHeartRateTest()
        } else if (!permissionRequested) {
            permissionRequested = true
            heartRatePermissionLauncher.launch(permission)
        }
    }

    private fun startHeartRateTest() {
        heartRateSource.start(
            onRecord = { record ->
                heartRateText = String.format(Locale.US, "%.0f bpm", record.bpm)
                Log.d(
                    "HeartRateCheck",
                    "seq=${record.sequence}, " +
                            "bpm=${record.bpm}, " +
                            "source=${record.source}, " +
                            "time=${record.timestampNanos}"
                )
            },
            onStatusChanged = { status ->
                logHeartRateStatus(status)
            }
        )
    }

    private fun logHeartRateStatus(status: SensorStatus) {
        if (lastHeartRateStatus != status) {
            Log.d("HeartRateCheck", "status=$status")
            lastHeartRateStatus = status
        }
    }

}
