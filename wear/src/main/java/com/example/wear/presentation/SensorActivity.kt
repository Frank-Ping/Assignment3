package com.example.wear.presentation

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import com.example.wear.presentation.communication.SensorBatcher
import com.example.wear.presentation.communication.SensorDataSender
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
    private lateinit var batcher: SensorBatcher
    private var skippedText by mutableStateOf("Samples skipped before sending: 0")
    private var accelerationStatus by mutableStateOf(SensorStatus.NOT_STARTED)
    private var heartRateStatus by mutableStateOf(SensorStatus.NOT_STARTED)

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
                startHeartRateCollection()
            } else if (!granted) {
                logHeartRateStatus(SensorStatus.PERMISSION_REQUIRED)
            }
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accelerometerSource = SensorManagerAccelerometerSource(this)

        heartRateSource = HealthServicesHeartRateSource.forPage(this)

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
        batcher = SensorBatcher({ peerNodeId }, sender) { skippedText = it }

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
                    Text("Acceleration: $accelerationStatus", color = Color.LightGray, fontSize = 12.sp)
                    Text("Heart rate: $heartRateStatus", color = Color.LightGray, fontSize = 12.sp)
                    Text(skippedText, color = Color.LightGray, fontSize = 12.sp)
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

        if (pageStarted) return
        pageStarted = true

        heartRateText = "-- bpm"
        accelerationText = "X: --\nY: --\nZ: --"

        sender.start()
        connectionManager.start()
        batcher.start()

        accelerometerSource.start(
            onRecord = { record ->
                if (!pageStarted) return@start
                batcher.add(record)
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
                accelerationStatus = status
                if (lastLoggedStatus != status.name) {
                    Log.d("AccelCheck", "status=$status")
                    lastLoggedStatus = status.name
                }
            }
        )
        requestHeartRateCollection()
    }

    override fun onStop() {
        stopPageResources()
        super.onStop()
    }

    private fun stopPageResources() {
        if (!pageStarted) return
        pageStarted = false

        // Reject new records before stopping the data sources.
        accelerometerSource.stop()
        heartRateSource.stop()

        batcher.stop()
        sender.stop()
        connectionManager.stop()
        peerNodeId = null
    }

    private fun requestHeartRateCollection() {
        val permission =
            HealthServicesHeartRateSource.requiredPermission()

        val granted = ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startHeartRateCollection()
        } else if (!permissionRequested) {
            logHeartRateStatus(SensorStatus.PERMISSION_REQUIRED)
            permissionRequested = true
            heartRatePermissionLauncher.launch(permission)
        } else {
            logHeartRateStatus(SensorStatus.PERMISSION_REQUIRED)
        }
    }

    private fun startHeartRateCollection() {
        if (!pageStarted) return

        heartRateSource.start(
            onRecord = { record ->
                if (!pageStarted) return@start
                batcher.add(record)
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
        heartRateStatus = status
        if (lastHeartRateStatus != status) {
            Log.d("HeartRateCheck", "status=$status")
            lastHeartRateStatus = status
        }
    }

}
