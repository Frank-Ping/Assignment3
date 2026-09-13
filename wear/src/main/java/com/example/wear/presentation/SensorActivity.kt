package com.example.wear.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import com.example.wear.R
import java.util.Date
import com.example.wear.presentation.communication.SensorBatcher
import com.example.wear.presentation.communication.SensorDataSender
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import com.example.wear.presentation.communication.*
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
import com.example.wear.presentation.sensors.AccelerometerSource
import com.example.wear.presentation.sensors.FakeAccelerometerSource
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
import com.example.wear.presentation.sensors.FakeHeartRateSource
import com.example.wear.presentation.sensors.HeartRateDemoScenario
import com.example.wear.presentation.data.HeartRateSourceType
import com.example.wear.presentation.communication.ConnectionStatus
import com.example.wear.presentation.communication.DeviceRole
import com.example.wear.presentation.communication.WearConnectionManager

class SensorActivity : ComponentActivity() {
    private lateinit var sessionTransport: SessionTransport
    private var phaseText by mutableStateOf("Not Started")
    private var clockText by mutableStateOf("")
    private var sessionMessage by mutableStateOf("Waiting for phone command")
    private var collecting by mutableStateOf(false)
    private var collectionGeneration = 0
    private val pageOwner = Any()
    private val pageHandler = Handler(Looper.getMainLooper())
    private var lastAccelerationAt: Long? = null
    private var lastHeartRateAt: Long? = null
    private val freshnessTask = object : Runnable {
        override fun run() {
            if (!pageStarted || activePageOwner !== pageOwner) return
            clockText = android.text.format.DateFormat.getTimeFormat(this@SensorActivity).format(Date())
            val now = SystemClock.elapsedRealtime()
            if (collecting) {
                if (accelerationStatus == SensorStatus.ACTIVE && lastAccelerationAt?.let { now - it >= 3_000L } == true) {
                    accelerationStatus = SensorStatus.WAITING_FOR_DATA
                    accelerationText = "X: --\nY: --\nZ: -- (stale)"
                }
                if (heartRateStatus == SensorStatus.ACTIVE && lastHeartRateAt?.let { now - it >= 10_000L } == true) {
                    heartRateStatus = SensorStatus.WAITING_FOR_DATA
                    heartRateText = "-- bpm (stale)"
                }
            }
            pageHandler.postDelayed(this, 1_000L)
        }
    }
    private val sessionController get() = foregroundSession

    companion object {
        // Retains interruption state across Activity recreation, not process death.
        private val foregroundSession = WatchSessionController({ SystemClock.elapsedRealtimeNanos() })
        private var activePageOwner: Any? = null
    }

    private fun showSession() {
        val state = sessionController.state
        phaseText = when {
            state.lifecycle != SessionLifecycle.RUNNING -> "Not Started"
            state.phase == SessionPhase.EXERCISING -> "Exercising"
            state.phase == SessionPhase.RECOVERING -> "Recovering"
            else -> "Not Started"
        }
    }

    private fun receiveSession(node: String, path: String, bytes: ByteArray) {
        if (!pageStarted || activePageOwner !== pageOwner) return
        val reply = when (path) {
            CommunicationProtocol.SOURCE_COMMAND_PATH -> {
                require(bytes.size in 1..1024)
                val request = org.json.JSONObject(bytes.toString(Charsets.UTF_8))
                val id = request.getString("requestId")
                require(id.isNotBlank() && id.length <= 128)
                val source = HeartRateSourceType.valueOf(request.getString("source"))
                val state = sessionController.state
                val sessionId = if (request.isNull("sessionId")) null else request.getString("sessionId")
                val accepted = !collecting && state.lifecycle != SessionLifecycle.RUNNING &&
                    state.revision == request.getLong("revision") && state.sessionId == sessionId
                if (accepted) {
                    selectedSource = source
                    heartRateText = "-- bpm"
                    heartRateStatus = SensorStatus.NOT_STARTED
                }
                SessionReply(id, accepted, if (accepted) null else "Finish session and sync before changing HR source", state)
            }
            CommunicationProtocol.SESSION_QUERY_PATH -> SessionReply(SessionProtocol.decodeQuery(bytes), true, null, sessionController.state)
            CommunicationProtocol.SESSION_COMMAND_PATH -> {
                val before = sessionController.state
                val result = sessionController.execute(node, SessionProtocol.decodeCommand(bytes))
                val after = sessionController.state
                if (before != after) {
                    if (after.lifecycle == SessionLifecycle.RUNNING && before.sessionId != after.sessionId) startSessionCollection()
                    else if (after.lifecycle != SessionLifecycle.RUNNING) stopSessionCollection()
                }
                result
            }
            else -> return
        }
        showSession()
        sessionMessage = reply.error ?: "Session confirmed"
        sessionTransport.send(node, CommunicationProtocol.SESSION_STATE_PATH, SessionProtocol.encodeReply(reply.copy(heartRateSource = selectedSource.name)))
    }

    private lateinit var sender: SensorDataSender
    private var peerNodeId by mutableStateOf<String?>(null)
    private var transferText by mutableStateOf("Sender stopped")
    private lateinit var batcher: SensorBatcher
    private var skippedText by mutableStateOf("Samples skipped before sending: 0")
    private var accelerationStatus by mutableStateOf(SensorStatus.NOT_STARTED)
    private var heartRateStatus by mutableStateOf(SensorStatus.NOT_STARTED)

    private lateinit var accelerometerSource: AccelerometerSource

    private var lastLoggedStatus: String? = null

    private lateinit var heartRateSource: HeartRateSource
    private var selectedSource by mutableStateOf(HeartRateSourceType.REAL)
    private var demoScenario by mutableStateOf(HeartRateDemoScenario.NORMAL)

    private lateinit var connectionManager: WearConnectionManager

    private var connectionText by mutableStateOf("Not Started")

    private var pageStarted = false
    private var lastHeartRateStatus: SensorStatus? = null
    private var heartRateText by mutableStateOf("-- bpm")
    private var accelerationText by mutableStateOf("X: --\nY: --\nZ: --")
    private var permissionRequested = false
    private var permissionGeneration: Int? = null

    private val heartRatePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val currentRequest = permissionGeneration == collectionGeneration
            permissionRequested = false
            permissionGeneration = null
            if (!currentRequest || !pageStarted || !collecting || activePageOwner !== pageOwner) return@registerForActivityResult
            if (granted) {
                startHeartRateCollection()
            } else {
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
            val nextPeer = if (info.status == ConnectionStatus.CONNECTED) info.nodeId else null
            if (peerNodeId != nextPeer && ::sessionTransport.isInitialized) sessionTransport.peerChanged()
            peerNodeId = nextPeer
            connectionText = when (info.status) {
                ConnectionStatus.STOPPED -> "Not Started"
                ConnectionStatus.SEARCHING -> "Searching for phone"
                ConnectionStatus.DISCONNECTED -> "Phone disconnected"
                ConnectionStatus.WAITING_FOR_APP -> "Waiting for phone app"
                ConnectionStatus.CONNECTED -> "Phone connected"
                ConnectionStatus.ERROR -> "Connection error"
            }
        }

        sender = SensorDataSender(this) { transferText = it }
        batcher = SensorBatcher({ peerNodeId }, sender) { skippedText = it }

        sessionTransport = SessionTransport(this, { peerNodeId }, ::receiveSession,
            { sessionMessage = "Ready for phone commands" }, { sessionMessage = it })
        showSession()

        setContent {
            MobileWearableApplicationTheme {
                BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                    val cardHeight = ((maxHeight - 48.dp - 40.dp * LocalDensity.current.fontScale) / 2).coerceAtLeast(48.dp)
                    Column(
                        Modifier.fillMaxSize()
                            .padding(top = (maxHeight * 0.025f).coerceAtLeast(4.dp), bottom = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(clockText, modifier = Modifier.fillMaxWidth(0.45f),
                            color = Color.White, fontSize = 10.sp, lineHeight = 12.sp,
                            fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center)
                        Row(
                            Modifier.border(1.dp, Color.Cyan, RoundedCornerShape(50))
                                .padding(horizontal = 9.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            val phaseIcon = when (phaseText) {
                                "Not Started" -> R.drawable.ic_not_started
                                "Exercising" -> R.drawable.ic_exercising
                                "Recovering" -> R.drawable.ic_recovering
                                else -> null
                            }
                            phaseIcon?.let { Image(painterResource(it), null, Modifier.size(18.dp)) }
                            Text(phaseText, color = Color.Cyan, fontSize = 9.sp, lineHeight = 11.sp,
                                fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium)
                        }
                        SensorCard("Heart rate", R.drawable.ic_heart_filled, heartRateStatus,
                            cardHeight, true) {
                            Text(
                                if (heartRateStatus == SensorStatus.ACTIVE) heartRateText else "— bpm",
                                color = Color.White, fontSize = 18.sp, lineHeight = 22.sp,
                                fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold
                            )
                        }
                        SensorCard("Acceleration · m/s²", R.drawable.ic_acceleration, accelerationStatus,
                            cardHeight, false) {
                            Row(Modifier.fillMaxWidth(0.9f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                val values = accelerationText.lines()
                                listOf("X", "Y", "Z").forEachIndexed { index, axis ->
                                    Column(
                                        Modifier.weight(1f).border(0.5.dp, Color.DarkGray, RoundedCornerShape(4.dp))
                                            .padding(vertical = 2.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(axis, color = Color.LightGray, fontSize = 8.sp, lineHeight = 10.sp)
                                        Text(if (accelerationStatus == SensorStatus.ACTIVE)
                                            values.getOrNull(index)?.substringAfter(": ") ?: "—" else "—",
                                            color = Color.White, fontSize = 10.sp, lineHeight = 12.sp,
                                            fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }

                    }
                        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.66f).padding(bottom = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center) {
                            if (peerNodeId != null) {
                                Box(Modifier.size(6.dp).background(Color(0xFF00DD88), RoundedCornerShape(50)))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(connectionText, color = Color.Gray, fontSize = 9.sp, lineHeight = 11.sp,
                                textAlign = TextAlign.Center)
                        }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()

        if (pageStarted) return
        pageStarted = true
        if (activePageOwner != null && activePageOwner !== pageOwner) sessionController.interrupt()
        activePageOwner = pageOwner
        DemoControl.configure = { name ->
            val scenario = HeartRateDemoScenario.entries.firstOrNull { it.name == name }
            if (collecting) "Finish session before changing scenario"
            else if (scenario == null) "Use NORMAL, AUTO, MISSING, BOUNDARY or NO_RECOVERY"
            else {
                selectedSource = HeartRateSourceType.DEMO
                demoScenario = scenario
                heartRateText = "-- bpm"
                heartRateStatus = SensorStatus.NOT_STARTED
                "DEMO configured: ${scenario.name}"
            }
        }

        heartRateText = "-- bpm"
        accelerationText = "X: --\nY: --\nZ: --"
        if (sessionController.state.lifecycle != SessionLifecycle.IDLE) {
            accelerationStatus = SensorStatus.STOPPED
            heartRateStatus = SensorStatus.STOPPED
        }

        sender.start()
        sessionTransport.start()
        connectionManager.start()
        pageHandler.removeCallbacksAndMessages(null)
        pageHandler.post(freshnessTask)
        showSession()
    }

    private fun startSessionCollection() {
        if (!pageStarted || collecting || activePageOwner !== pageOwner) return
        collecting = true
        val token = ++collectionGeneration
        heartRateSource = if (selectedSource == HeartRateSourceType.DEMO) {
            FakeHeartRateSource({ sessionController.state.phase }, demoScenario)
        } else HealthServicesHeartRateSource.forPage(this)
        lastAccelerationAt = null
        lastHeartRateAt = null
        heartRateText = "-- bpm"
        accelerationText = "X: --\nY: --\nZ: --"
        val fakeAcceleration = selectedSource == HeartRateSourceType.DEMO && demoScenario == HeartRateDemoScenario.AUTO
        accelerometerSource = if (fakeAcceleration) FakeAccelerometerSource() else SensorManagerAccelerometerSource(this)
        batcher.start(checkNotNull(sessionController.state.sessionId),
            if (fakeAcceleration) WireSource.DEMO else WireSource.REAL)

        accelerometerSource.start(
            onRecord = { record ->
                if (!pageStarted || !collecting || token != collectionGeneration || activePageOwner !== pageOwner) return@start
                if (record.timestampNanos < sessionController.state.transitions.first().watchElapsedTimeNanos) return@start
                batcher.add(record)
                lastAccelerationAt = SystemClock.elapsedRealtime()
                accelerationStatus = SensorStatus.ACTIVE
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
                if (token != collectionGeneration || activePageOwner !== pageOwner) return@start
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

        pageHandler.removeCallbacksAndMessages(null)
        if (activePageOwner === pageOwner) {
            DemoControl.configure = null
            sessionController.interrupt()
            // Best effort only; reconnect/query remains the authority if this is lost.
            if (sessionController.state.lifecycle == SessionLifecycle.INTERRUPTED && sessionTransport.ready) {
                peerNodeId?.let { node ->
                    sessionTransport.send(node, CommunicationProtocol.SESSION_STATE_PATH,
                        SessionProtocol.encodeReply(SessionReply("session-interrupted", true, null, sessionController.state)))
                }
            }
            activePageOwner = null
        }
        stopSessionCollection()
        sessionTransport.stop()
        sender.stop()
        connectionManager.stop()
        peerNodeId = null
        showSession()
    }

    private fun stopSessionCollection() {
        collecting = false
        collectionGeneration++
        // Reject new records before stopping the data sources.
        accelerometerSource.stop()
        heartRateSource.stop()

        batcher.stop()
        accelerationStatus = SensorStatus.STOPPED
        heartRateStatus = SensorStatus.STOPPED
        heartRateText = "-- bpm"
        accelerationText = "X: --\nY: --\nZ: --"
    }

    private fun requestHeartRateCollection() {
        if (selectedSource == HeartRateSourceType.DEMO) {
            startHeartRateCollection()
            return
        }
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
            permissionGeneration = collectionGeneration
            heartRatePermissionLauncher.launch(permission)
        } else {
            logHeartRateStatus(SensorStatus.PERMISSION_REQUIRED)
        }
    }

    private fun startHeartRateCollection() {
        if (!pageStarted || !collecting || activePageOwner !== pageOwner) return
        val token = collectionGeneration

        heartRateSource.start(
            onRecord = { record ->
                if (!pageStarted || !collecting || token != collectionGeneration || activePageOwner !== pageOwner) return@start
                if (record.timestampNanos < sessionController.state.transitions.first().watchElapsedTimeNanos) return@start
                batcher.add(record)
                lastHeartRateAt = SystemClock.elapsedRealtime()
                heartRateStatus = SensorStatus.ACTIVE
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
                if (token == collectionGeneration && collecting && activePageOwner === pageOwner) {
                    logHeartRateStatus(status)
                    if (status == SensorStatus.WAITING_FOR_DATA) heartRateText = "-- bpm (waiting)"
                }
            }
        )
    }

    @Composable
    private fun SensorCard(
        title: String, icon: Int, status: SensorStatus, height: Dp,
        upper: Boolean, content: @Composable ColumnScope.() -> Unit
    ) {
        val outerCorner = height / 2
        val shape = RoundedCornerShape(
            topStart = if (upper) outerCorner else 10.dp,
            topEnd = if (upper) outerCorner else 10.dp,
            bottomStart = if (upper) 10.dp else outerCorner,
            bottomEnd = if (upper) 10.dp else outerCorner
        )
        Column(
            Modifier.fillMaxWidth(0.84f).height(height)
                .background(Color(0xFF0C1217), shape).border(1.dp, Color(0xFF485761), shape)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Image(painterResource(icon), null, Modifier.size(14.dp))
                Text(title, color = Color.White, fontSize = 11.sp, lineHeight = 13.sp,
                    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (status == SensorStatus.ACTIVE) {
                    Box(Modifier.size(6.dp).background(Color(0xFF00DD88), RoundedCornerShape(50)))
                }
                Text(if (status == SensorStatus.STOPPED || status == SensorStatus.NOT_STARTED) "Not Started"
                    else status.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                    color = Color.LightGray,
                    fontSize = 9.sp, lineHeight = 11.sp, textAlign = TextAlign.Center)
            }
        }
    }

    private fun logHeartRateStatus(status: SensorStatus) {
        heartRateStatus = status
        if (lastHeartRateStatus != status) {
            Log.d("HeartRateCheck", "status=$status")
            lastHeartRateStatus = status
        }
    }

}

