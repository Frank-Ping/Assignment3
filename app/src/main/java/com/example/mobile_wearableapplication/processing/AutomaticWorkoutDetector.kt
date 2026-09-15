package com.example.mobile_wearableapplication.processing

import kotlin.math.abs

enum class AutomaticWorkoutAction { START_WORKOUT, END_WORKOUT, FINISH_SESSION }

/** Engineering defaults: +20 bpm to exercise, +10 bpm to rest, 3/3/10 seconds debounce.
 * Repeated workouts share one session. Watch acknowledgements remain the phase time authority.
 */
class AutomaticWorkoutDetector(private val hrMax: Double) {
    private var baseline: Double? = null
    private var candidate: AutomaticWorkoutAction? = null
    private var since = 0L
    private var lastTime = -1L
    private var phase: WorkoutPhase? = null
    private var suggested: AutomaticWorkoutAction? = null

    fun interrupt() { candidate = null; suggested = null; lastTime = -1L }

    fun accept(snapshot: ProcessingSnapshot): AutomaticWorkoutAction? {
        val current = snapshot.phaseHistory.lastOrNull()?.phase
        if (current != phase) { interrupt(); phase = current }
        (snapshot.restingHeartRate as? MetricResult.Available)?.value?.let { baseline = it }
        val hr = snapshot.preprocessing.heartRate3s
        val accTime = snapshot.acceleration.latestTimestampNanos
        val hrTime = snapshot.heartRate.latestTimestampNanos
        val newest = snapshot.preprocessing.asOfWatchNanos
        if (snapshot.endedAtNanos != null || current == null || hr == null ||
            hr.coverageFraction < 0.8 || accTime == null || hrTime == null || newest == null ||
            newest - hrTime > SensorPreprocessor.HEART_RATE_HOLD_NANOS ||
            abs(accTime - hrTime) > SensorPreprocessor.HEART_RATE_HOLD_NANOS ||
            snapshot.motion.state == MotionState.UNKNOWN) {
            interrupt(); return null
        }
        val time = minOf(accTime, hrTime)
        val bpm = hr.mean?.takeIf { it.isFinite() && it > 0 } ?: run { interrupt(); return null }
        val still = snapshot.motion.state == MotionState.STILL
        val nearRest = bpm <= (baseline?.plus(10.0) ?: (hrMax * 0.5))
        val elevated = baseline?.let { bpm >= it + 20.0 } == true || bpm >= hrMax * 0.5
        val next = when (current) {
            WorkoutPhase.RESTING -> if (!still && elevated) AutomaticWorkoutAction.START_WORKOUT else null
            WorkoutPhase.EXERCISING -> if (still) AutomaticWorkoutAction.END_WORKOUT else null
            WorkoutPhase.RECOVERING -> when {
                !still && elevated -> AutomaticWorkoutAction.START_WORKOUT
                still && nearRest && snapshot.recoveryStartedAt?.let { time - it >= 60_000_000_000L } == true ->
                    AutomaticWorkoutAction.FINISH_SESSION
                else -> null
            }
        }
        if (next == null) { candidate = null; suggested = null; return null }
        if (time <= lastTime) return suggested
        if (lastTime >= 0 && time - lastTime > SensorPreprocessor.HEART_RATE_HOLD_NANOS) interrupt()
        lastTime = time
        if (next != candidate) { candidate = next; since = time; suggested = null }
        // Allow a direct return to baseline, but require the longer resting debounce.
        val wait = if (next == AutomaticWorkoutAction.FINISH_SESSION ||
            (next == AutomaticWorkoutAction.END_WORKOUT && nearRest)) 10_000_000_000L else 3_000_000_000L
        suggested = if (time - since >= wait) next else null
        return suggested
    }
}
