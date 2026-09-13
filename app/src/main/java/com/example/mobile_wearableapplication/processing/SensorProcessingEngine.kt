package com.example.mobile_wearableapplication.processing

/** Pure Kotlin. The caller supplies only newly accepted samples, once per sample.
 * Preprocessing is incremental; physiological metric algorithms are added separately.
 */
class SensorProcessingEngine {
    private var state = ProcessingSnapshot()
    private var preprocessor = SensorPreprocessor()
    private var motionDetector = MotionDetector()

    @Synchronized
    fun startSession(session: ProcessingSession) {
        if (state.session == session) return
        preprocessor = SensorPreprocessor()
        motionDetector = MotionDetector()
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
        preprocessor = SensorPreprocessor()
        motionDetector = MotionDetector()
    }

    @Synchronized
    fun acceptAcceleration(session: ProcessingSession, samples: List<AccelerationInput>) {
        if (session != state.session || samples.isEmpty()) return
        samples.forEach { preprocessor.accept(it) }
        samples.forEach { motionDetector.accept(it) }
        state = state.copy(acceleration = summarize(
            state.acceleration, samples.map { it.timestampNanos }, samples.map { it.source }
        ))
    }

    @Synchronized
    fun acceptHeartRate(session: ProcessingSession, samples: List<HeartRateInput>) {
        if (session != state.session || samples.isEmpty()) return
        samples.forEach { preprocessor.accept(it) }
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
        if (session == state.session) {
            state = state.copy(endedAtNanos = watchElapsedTimeNanos)
            motionDetector.interrupt()
        }
    }

    /** Resolve delayed samples by watch measurement time, not the current UI phase. */
    @Synchronized
    fun phaseAt(watchElapsedTimeNanos: Long): WorkoutPhase? {
        if (state.endedAtNanos?.let { watchElapsedTimeNanos >= it } == true) return null
        return state.phaseHistory.lastOrNull { it.watchElapsedTimeNanos <= watchElapsedTimeNanos }?.phase
    }

    @Synchronized
    fun snapshot(): ProcessingSnapshot {
        val preprocessing = preprocessor.snapshot()
        val motion = if (state.endedAtNanos != null) MotionResult() else motionDetector.snapshot(preprocessing.asOfWatchNanos)
        return state.copy(preprocessing = preprocessing, motion = motion, accelerationRms = motion.rms, quality = state.quality.copy(
            stillnessVerified = motion.stillnessVerified, motionDetected = motion.motionDetected,
            heartRateCoverageFraction = preprocessing.heartRate5s?.coverageFraction,
            accelerationCoverageFraction = preprocessing.acceleration1s?.coverageFraction))
    }

    @Synchronized
    fun breakContinuity() { preprocessor.breakContinuity(); motionDetector.interrupt() }

    @Synchronized
    fun markAccelerationUnavailable() { motionDetector.interrupt() }

    private fun summarize(previous: InputSummary, timestamps: List<Long>, sources: List<SampleSource>) =
        InputSummary(
            acceptedSamples = previous.acceptedSamples + timestamps.size,
            latestTimestampNanos = maxOf(previous.latestTimestampNanos ?: Long.MIN_VALUE, timestamps.max()),
            sources = previous.sources + sources
        )
}
