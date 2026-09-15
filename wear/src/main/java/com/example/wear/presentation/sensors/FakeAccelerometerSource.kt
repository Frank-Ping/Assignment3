package com.example.wear.presentation.sensors

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.wear.presentation.DemoControl
import com.example.wear.presentation.data.AccelerometerRecord
import com.example.wear.presentation.data.SensorStatus
import kotlin.math.sin

/** Movement follows the selected input timeline, independently of detected phases. */
class FakeAccelerometerSource : AccelerometerSource {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var status: ((SensorStatus) -> Unit)? = null
    override fun start(onRecord: (AccelerometerRecord) -> Unit, onStatusChanged: (SensorStatus) -> Unit) {
        if (running) return
        running = true
        status = onStatusChanged
        val intervalDemo = DemoControl.intervalDemoEnabled
        val started = SystemClock.elapsedRealtimeNanos()
        if (intervalDemo) DemoControl.intervalStartedAtNanos = started
        var sequence = 0L
        val tick = object : Runnable {
            override fun run() {
                if (!running) return
                com.example.wear.presentation.DemoControl.accelerationFault?.let {
                    onStatusChanged(it)
                    handler.postDelayed(this, 40L)
                    return
                }
                val now = SystemClock.elapsedRealtimeNanos()
                val seconds = (now - started) / 1e9
                val moving = if (intervalDemo)
                    IntervalDemoTimeline.isMovingAt(seconds) else seconds >= 35 && seconds < 95
                val wave = sin(seconds * 2 * Math.PI * 1.5)
                onStatusChanged(SensorStatus.ACTIVE)
                onRecord(AccelerometerRecord(
                    if (moving) (1.5 * wave).toFloat() else 0f,
                    if (moving) (0.8 * wave).toFloat() else 0f,
                    (9.81 + (if (moving) 2.0 else 0.03) * wave).toFloat(), now, ++sequence))
                if (running) handler.postDelayed(this, 40L)
            }
        }
        handler.post(tick)
    }
    override fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        status?.invoke(SensorStatus.STOPPED)
        status = null
    }
}
