package com.example.mobile_wearableapplication.processing

/** Pure Kotlin. The caller supplies only newly accepted samples, once per sample.
 * This step establishes the pipeline; filtering and metric algorithms come later.
 */
class SensorProcessingEngine {
    private var state = ProcessingSnapshot()

    @Synchronized
    fun startSession(session: ProcessingSession) {
        if (state.session == session) return
        val pending = MetricResult.Unavailable(UnavailableReason.NOT_IMPLEMENTED)
        state = ProcessingSnapshot(
            session = session,
            restingHeartRate = pending, exerciseHeartRate = pending,
            intensity = pending, recovery = pending, accelerationRms = pending,
            workoutState = MetricResult.Unavailable(UnavailableReason.AWAITING_PHASE_CONFIRMATION)
        )
    }

    @Synchronized
    fun reset() {
        state = ProcessingSnapshot()
    }

    @Synchronized
    fun acceptAcceleration(session: ProcessingSession, samples: List<AccelerationInput>) {
        if (session != state.session || samples.isEmpty()) return
        state = state.copy(acceleration = summarize(
            state.acceleration, samples.map { it.timestampNanos }, samples.map { it.source }
        ))
    }

    @Synchronized
    fun acceptHeartRate(session: ProcessingSession, samples: List<HeartRateInput>) {
        if (session != state.session || samples.isEmpty()) return
        state = state.copy(heartRate = summarize(
            state.heartRate, samples.map { it.timestampNanos }, samples.map { it.source }
        ))
    }

    /** Called only for a watch-confirmed event, never directly for a button click. */
    @Synchronized
    fun acceptConfirmedPhase(event: ConfirmedPhaseEvent) {
        if (event.session != state.session || event.revision < 0 || event.watchElapsedTimeNanos < 0) return
        val previous = when (val result = state.workoutState) {
            is MetricResult.Available -> result.value
            is MetricResult.Unavailable -> null
        }
        if (previous != null && (event.revision <= previous.revision ||
                event.watchElapsedTimeNanos < previous.watchElapsedTimeNanos)) return
        state = state.copy(workoutState = MetricResult.Available(event, CalculationEvidence()),
            phaseHistory = state.phaseHistory + event)
    }

    @Synchronized
    fun endSession(session: ProcessingSession, watchElapsedTimeNanos: Long) {
        if (session == state.session) state = state.copy(endedAtNanos = watchElapsedTimeNanos)
    }

    /** Resolve delayed samples by watch measurement time, not the current UI phase. */
    @Synchronized
    fun phaseAt(watchElapsedTimeNanos: Long): WorkoutPhase? {
        if (state.endedAtNanos?.let { watchElapsedTimeNanos >= it } == true) return null
        return state.phaseHistory.lastOrNull { it.watchElapsedTimeNanos <= watchElapsedTimeNanos }?.phase
    }

    @Synchronized
    fun snapshot(): ProcessingSnapshot = state

    private fun summarize(previous: InputSummary, timestamps: List<Long>, sources: List<SampleSource>) =
        InputSummary(
            acceptedSamples = previous.acceptedSamples + timestamps.size,
            latestTimestampNanos = maxOf(previous.latestTimestampNanos ?: Long.MIN_VALUE, timestamps.max()),
            sources = previous.sources + sources
        )
}
