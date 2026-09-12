package com.example.wear.presentation

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

class SensorActivity : ComponentActivity() {

    private lateinit var accelerometerSource: SensorManagerAccelerometerSource

    private var lastLoggedStatus: String? = null

    private lateinit var heartRateSource: HeartRateSource

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

        setContent {
            MobileWearableApplicationTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
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
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
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
