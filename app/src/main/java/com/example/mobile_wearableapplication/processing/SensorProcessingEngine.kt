package com.example.mobile_wearableapplication.processing

/** Pure Kotlin. The caller supplies only newly accepted samples, once per sample.
 * Processing preserves watch-time phase boundaries and continuity.
 */
class SensorProcessingEngine(private val hrMaxBpm: Double = 200.0) {
    private val intensityClassifier = IntensityClassifier(hrMaxBpm)
    private var state = ProcessingSnapshot()
    private var published = state
    private var chartBuffer = ChartBuffer()
    private var finalSummary: SessionSummary? = null
    private var preprocessor = SensorPreprocessor()
    private var motionDetector = MotionDetector()
    private var automaticDetector = AutomaticWorkoutDetector(hrMaxBpm)
    private var restingCalculator = RestingHeartRateCalculator()
    private var recoveryCalculator = RecoveryCalculator()
    private var zoneIntervalCalculator = ZoneIntervalCalculator()

    @Synchronized
    fun startSession(session: ProcessingSession) {
        if (state.session == session) return
        finalSummary = null
        resetCalculators()
        val pending = MetricResult.Unavailable(UnavailableReason.NOT_IMPLEMENTED)
        state = ProcessingSnapshot(
            session = session,
            restingHeartRate = pending,
            intensity = pending, recovery = pending,
            workoutState = MetricResult.Unavailable(UnavailableReason.AWAITING_PHASE_CONFIRMATION)
        )
        publish()
    }

    @Synchronized
    fun reset() {
        finalSummary = null
        state = ProcessingSnapshot()
        resetCalculators()
        publish()
    }

    /** Called by synchronized session operations; does not change session or summary state. */
    private fun resetCalculators() {
        chartBuffer = ChartBuffer()
        intensityClassifier.reset()
        preprocessor = SensorPreprocessor()
        motionDetector = MotionDetector()
        automaticDetector = AutomaticWorkoutDetector(hrMaxBpm)
        restingCalculator = RestingHeartRateCalculator()
        recoveryCalculator = RecoveryCalculator()
        zoneIntervalCalculator = ZoneIntervalCalculator()
    }

    @Synchronized
    fun acceptAcceleration(session: ProcessingSession, samples: List<AccelerationInput>) {
        if (session != state.session || samples.isEmpty()) return
        samples.forEach { preprocessor.accept(it) }
        samples.forEach {
            motionDetector.accept(it)
            restingCalculator.observe(it.timestampNanos, motionDetector.snapshot().stillnessVerified == true)
        }
        state = state.copy(acceleration = summarize(
            state.acceleration, samples.map { it.timestampNanos }, samples.map { it.source }
        ))
        publish()
    }

    @Synchronized
    fun acceptHeartRate(session: ProcessingSession, samples: List<HeartRateInput>) {
        if (session != state.session || samples.isEmpty()) return
        samples.forEach {
            preprocessor.accept(it)
            chartBuffer.heartRate(it)
            val smoothed = preprocessor.snapshot(state.phaseHistory.lastOrNull()?.watchElapsedTimeNanos).heartRate3s?.mean
            val confirmed = intensityClassifier.accept(it.timestampNanos, smoothed)
            if (phaseAt(it.timestampNanos) == WorkoutPhase.EXERCISING)
                zoneIntervalCalculator.record(it.timestampNanos, confirmed.zone)
        }
        samples.forEach { restingCalculator.accept(it); recoveryCalculator.accept(it) }
        state = state.copy(heartRate = summarize(
            state.heartRate, samples.map { it.timestampNanos }, samples.map { it.source }
        ))
        publish()
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
        if (state.endedAtNanos != null) return
        val expectedPhase = when (previous?.phase) {
            null -> WorkoutPhase.RESTING
            WorkoutPhase.RESTING -> WorkoutPhase.EXERCISING
            WorkoutPhase.EXERCISING -> WorkoutPhase.RECOVERING
            WorkoutPhase.RECOVERING -> return
        }
        if (event.phase != expectedPhase) return
        if (event.phase == WorkoutPhase.EXERCISING)
            restingCalculator.freeze(state.restingStartedAt, event.watchElapsedTimeNanos)
        intensityClassifier.reset()
        state = state.copy(workoutState = MetricResult.Available(event, CalculationEvidence()),
            phaseHistory = state.phaseHistory + event)
        publish()
    }

    @Synchronized
    fun endSession(session: ProcessingSession, watchElapsedTimeNanos: Long) {
        if (session == state.session) {
            restingCalculator.freeze(state.restingStartedAt, watchElapsedTimeNanos)
            state = state.copy(endedAtNanos = watchElapsedTimeNanos)
            motionDetector.interrupt()
            publish()
        }
    }

    /** Resolve delayed samples by watch measurement time, not the current UI phase. */
    @Synchronized
    fun phaseAt(watchElapsedTimeNanos: Long): WorkoutPhase? {
        if (state.endedAtNanos?.let { watchElapsedTimeNanos >= it } == true) return null
        return state.phaseHistory.lastOrNull { it.watchElapsedTimeNanos <= watchElapsedTimeNanos }?.phase
    }

    @Synchronized
    fun snapshot(): ProcessingSnapshot = published

    /** Called only on processing events, never from a UI read. */
    private fun publish() {
        val preprocessing = preprocessor.snapshot(state.phaseHistory.lastOrNull()?.watchElapsedTimeNanos)
        val motion = if (state.endedAtNanos != null) MotionResult() else motionDetector.snapshot()
        val active = state.session != null && state.endedAtNanos == null
        if (active && preprocessing.asOfWatchNanos?.let { now ->
                now - (state.heartRate.latestTimestampNanos ?: now) > SensorPreprocessor.HEART_RATE_HOLD_NANOS
            } == true) intensityClassifier.missing()
        val intensity = if (active) MetricResult.Available(intensityClassifier.snapshot(), CalculationEvidence())
            else MetricResult.Unavailable(UnavailableReason.AWAITING_PHASE_CONFIRMATION)
        zoneIntervalCalculator.update(state.exerciseStartedAt,
            state.recoveryStartedAt ?: state.endedAtNanos, preprocessing.asOfWatchNanos)
        published = state.copy(recovery = recoveryCalculator.result(state.recoveryStartedAt, state.exerciseStartedAt,
            preprocessing.asOfWatchNanos, state.endedAtNanos),
            recoveryRemainingSeconds = recoveryCalculator.remaining(state.recoveryStartedAt, preprocessing.asOfWatchNanos, state.endedAtNanos),
            intensity = intensity, restingHeartRate = restingCalculator.result(state.restingStartedAt),
            preprocessing = preprocessing, motion = motion)
        published = published.copy(automaticAction = automaticDetector.accept(published))
        val session = state.session
        val ended = state.endedAtNanos
        if (session != null && ended != null && finalSummary == null) {
            finalSummary = SessionSummary(ended, published.restingHeartRate, published.recovery)
        }
        published = published.copy(chartOutput = chartBuffer.snapshot(zoneIntervalCalculator.intervals()), finalSummary = finalSummary)
    }

    @Synchronized
    fun markHeartRateUnavailable() {
        automaticDetector.interrupt()
        intensityClassifier.missing()
        preprocessor.snapshot().asOfWatchNanos?.let { zoneIntervalCalculator.record(it, IntensityZone.MISSING) }
        publish()
    }

    @Synchronized
    fun breakContinuity() {
        automaticDetector.interrupt()
        markHeartRateUnavailable()
        preprocessor.breakContinuity()
        motionDetector.interrupt()
        restingCalculator.interrupt()
        recoveryCalculator.interrupt()
        publish()
    }

    @Synchronized
    fun markAccelerationUnavailable() {
        automaticDetector.interrupt()
        motionDetector.interrupt()
        restingCalculator.interrupt()
        publish()
    }

    private fun summarize(previous: InputSummary, timestamps: List<Long>, sources: List<SampleSource>) =
        InputSummary(
            latestTimestampNanos = maxOf(previous.latestTimestampNanos ?: Long.MIN_VALUE, timestamps.max()),
            sources = previous.sources + sources
        )
}
