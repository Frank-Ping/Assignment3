package com.example.mobile_wearableapplication

import android.os.Bundle
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

class SensorActivity : ComponentActivity() {

    private lateinit var connectionManager: WearConnectionManager

    private var connectionText by mutableStateOf("Connection stopped")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        connectionManager = WearConnectionManager(
            context = this,
            localRole = DeviceRole.PHONE
        ) { info ->
            connectionText = when (info.status) {
                ConnectionStatus.STOPPED -> "Connection stopped"
                ConnectionStatus.SEARCHING -> "Searching for watch"
                ConnectionStatus.DISCONNECTED -> "Watch disconnected"
                ConnectionStatus.WAITING_FOR_APP -> "Waiting for watch app"
                ConnectionStatus.CONNECTED -> "Watch connected"
                ConnectionStatus.ERROR -> "Connection error"
            }
        }

        setContent {
            MobileWearableApplicationTheme(darkTheme = true) {
                SensorPage(
                    onBack = { finish() },
                    connectionText = connectionText
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connectionManager.start()
    }

    override fun onStop() {
        connectionManager.stop()
        super.onStop()
    }
}

@Composable
private fun SensorPage(
    onBack: () -> Unit,
    connectionText: String = "Connection stopped"
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

            Text(
                text = "No session started",
                modifier = Modifier.padding(bottom = 80.dp),
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