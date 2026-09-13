package com.example.wear.presentation.sensors

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.wear.presentation.communication.SessionPhase
import com.example.wear.presentation.data.HeartRateRecord
import com.example.wear.presentation.data.HeartRateSourceType
import com.example.wear.presentation.data.SensorStatus

enum class HeartRateDemoScenario(val label: String) {
    NORMAL("Normal"), MISSING("Missing data"),
    BOUNDARY("Boundary 139/141"), NO_RECOVERY("No recovery")
}

/** One reading per second; fixed phase-relative sequences, no catch-up or random noise. */
class FakeHeartRateSource(
    private val phase: () -> SessionPhase?,
    private val scenario: HeartRateDemoScenario
) : HeartRateSource {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var sequence = 0L
    private var phaseTick = 0
    private var previousPhase: SessionPhase? = null
    private var lastBpm = 72.0
    private var phaseStartBpm = 72.0
    private var recordCallback: ((HeartRateRecord) -> Unit)? = null
    private var statusCallback: ((SensorStatus) -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val current = phase()
            if (current == null) {
                statusCallback?.invoke(SensorStatus.WAITING_FOR_DATA)
            } else {
                if (current != previousPhase) {
                    previousPhase = current
                    phaseTick = 0
                    phaseStartBpm = lastBpm
                }
                // Bound the index: long demos remain at their plateau without overflow.
                phaseTick = (phaseTick + 1).coerceAtMost(3600)
                val noise = doubleArrayOf(0.0, 1.0, 0.0, -1.0)[(phaseTick - 1) % 4]
                lastBpm = when (current) {
                    SessionPhase.RESTING -> 72.0 + noise
                    SessionPhase.EXERCISING -> if (scenario == HeartRateDemoScenario.BOUNDARY) {
                        if (phaseTick % 2 == 1) 139.0 else 141.0
                    } else (phaseStartBpm + phaseTick * 2.0).coerceAtMost(150.0) + noise
                    SessionPhase.RECOVERING -> if (scenario == HeartRateDemoScenario.NO_RECOVERY) {
                        phaseStartBpm + 5.0 * phaseTick.coerceAtMost(60) / 60.0
                    } else (phaseStartBpm - 25.0 * phaseTick / 60.0).coerceAtLeast(72.0)
                }
                sequence++
                if (scenario == HeartRateDemoScenario.MISSING && phaseTick in 20..34) {
                    statusCallback?.invoke(SensorStatus.WAITING_FOR_DATA)
                } else {
                    statusCallback?.invoke(SensorStatus.ACTIVE)
                    recordCallback?.invoke(HeartRateRecord(lastBpm, SystemClock.elapsedRealtimeNanos(),
                        sequence, HeartRateSourceType.DEMO))
                }
            }
            if (running) handler.postDelayed(this, 1_000L)
        }
    }

    override fun start(onRecord: (HeartRateRecord) -> Unit, onStatusChanged: (SensorStatus) -> Unit) {
        if (running) return
        sequence = 0L
        phaseTick = 0
        previousPhase = null
        lastBpm = 72.0
        phaseStartBpm = 72.0
        recordCallback = onRecord
        statusCallback = onStatusChanged
        running = true
        onStatusChanged(SensorStatus.WAITING_FOR_DATA)
        handler.postDelayed(tick, 1_000L)
    }

    override fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        statusCallback?.invoke(SensorStatus.STOPPED)
        recordCallback = null
        statusCallback = null
    }
}
