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

class SensorActivity : ComponentActivity() {

    private lateinit var accelerometerSource:
            SensorManagerAccelerometerSource

    private var lastLoggedStatus: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accelerometerSource = SensorManagerAccelerometerSource(this)

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
                        text = "Placeholder for sensor data",
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()

        accelerometerSource.start(
            onRecord = { record ->
                // 每 25 条打印一次，避免高频日志刷屏。
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
    }

    override fun onStop() {
        accelerometerSource.stop()
        super.onStop()
    }

}