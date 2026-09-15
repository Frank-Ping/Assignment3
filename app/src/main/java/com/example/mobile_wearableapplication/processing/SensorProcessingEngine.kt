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
    private val zoneCalculators = linkedMapOf<Long, ZoneIntervalCalculator>()
    private val recentIntensity = mutableListOf<Pair<Long, IntensityZone>>()

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
        zoneCalculators.clear()
        recentIntensity.clear()
    }

    @Synchronized
    fun acceptAcceleration(session: ProcessingSession, samples: List<AccelerationInput>) {
        if (session != state.session || samples.isEmpty()) return
        samples.forEach { preprocessor.accept(it) }
        samples.forEach {
            motionDetector.accept(it)
            restingCalculator.observe(it.timestampNanos, motionDetector.snapshot().stillnessVerified == true)
            recoveryCalculator.observe(it.timestampNanos, motionDetector.snapshot().state)
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
            // Intensity depends on recent HR, so a phase change must not restart its smoothing.
            val smoothed = preprocessor.snapshot().heartRate3s?.mean
            val confirmed = intensityClassifier.accept(it.timestampNanos, smoothed)
            recentIntensity.add(it.timestampNanos to confirmed.zone)
            while (recentIntensity.size > 600 || recentIntensity.first().first < it.timestampNanos - 65_000_000_000L)
                recentIntensity.removeAt(0)
            if (phaseAt(it.timestampNanos) == WorkoutPhase.EXERCISING)
                state.phaseHistory.lastOrNull { phase -> phase.watchElapsedTimeNanos <= it.timestampNanos }
                    ?.let { phase -> zoneCalculators[phase.watchElapsedTimeNanos]?.record(it.timestampNanos, confirmed.zone) }
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
            WorkoutPhase.RECOVERING -> WorkoutPhase.EXERCISING
        }
        if (event.phase != expectedPhase) return
        if (event.phase == WorkoutPhase.EXERCISING)
            restingCalculator.freeze(state.restingStartedAt, event.watchElapsedTimeNanos)
        // Keep confirmed intensity across phases; session resets and missing HR still clear it.
        state = state.copy(workoutState = MetricResult.Available(event, CalculationEvidence()),
            phaseHistory = state.phaseHistory + event)
        if (event.phase == WorkoutPhase.EXERCISING) {
            zoneCalculators[event.watchElapsedTimeNanos] = ZoneIntervalCalculator().also { calculator ->
                recentIntensity.filter { it.first >= event.watchElapsedTimeNanos }.forEach { (time, zone) -> calculator.record(time, zone) }
            }
        }
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
        val intervals = state.phaseHistory.flatMapIndexed { index, phase ->
            val calculator = zoneCalculators[phase.watchElapsedTimeNanos]
            if (phase.phase != WorkoutPhase.EXERCISING || calculator == null) emptyList()
            else {
                calculator.update(phase.watchElapsedTimeNanos,
                    state.phaseHistory.getOrNull(index + 1)?.watchElapsedTimeNanos ?: state.endedAtNanos,
                    preprocessing.asOfWatchNanos)
                calculator.intervals()
            }
        }.filter { it.endNanos > (preprocessing.asOfWatchNanos ?: 0L) - 43_200_000_000_000L }
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
        published = published.copy(chartOutput = chartBuffer.snapshot(intervals), finalSummary = finalSummary)
    }

    @Synchronized
    fun markHeartRateUnavailable() {
        automaticDetector.interrupt()
        intensityClassifier.missing()
        recoveryCalculator.interrupt()
        preprocessor.snapshot().asOfWatchNanos?.let {
            if (phaseAt(it) == WorkoutPhase.EXERCISING) zoneCalculators[state.exerciseStartedAt]?.record(it, IntensityZone.MISSING)
        }
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
        recoveryCalculator.interrupt()
        publish()
    }

    private fun summarize(previous: InputSummary, timestamps: List<Long>, sources: List<SampleSource>) =
        InputSummary(
            latestTimestampNanos = maxOf(previous.latestTimestampNanos ?: Long.MIN_VALUE, timestamps.max()),
            sources = previous.sources + sources
        )
}
