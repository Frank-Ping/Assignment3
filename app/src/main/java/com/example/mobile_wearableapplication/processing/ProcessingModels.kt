package com.example.mobile_wearableapplication.processing

data class ProcessingSession(val nodeId: String, val sessionId: String)

enum class SampleSource { REAL, DEMO }
enum class WorkoutPhase { RESTING, EXERCISING, RECOVERING }
enum class UnavailableReason {
    NO_SESSION, AWAITING_PHASE_CONFIRMATION, NOT_IMPLEMENTED,
    COLLECTING_BASELINE, COLLECTING_RECOVERY, INSUFFICIENT_DATA, INTERRUPTED_BY_MOVEMENT,
    INVALID_CONFIGURATION, STALE_DATA
}

/** Measurement time is watch elapsed time, never the phone receipt clock. */
data class CalculationWindow(val startNanos: Long, val endNanos: Long)
data class CalculationEvidence(
    val window: CalculationWindow? = null,
    val sampleCount: Long = 0,
    val coverageFraction: Double? = null,
    val sources: Set<SampleSource> = emptySet()
)

sealed interface MetricResult<out T> {
    data class Available<T>(val value: T, val evidence: CalculationEvidence) : MetricResult<T>
    data class Unavailable(val reason: UnavailableReason) : MetricResult<Nothing>
}

data class AccelerationInput(
    val sequence: Long, val timestampNanos: Long, val source: SampleSource,
    val x: Double, val y: Double, val z: Double
)
data class HeartRateInput(
    val sequence: Long, val timestampNanos: Long, val source: SampleSource, val bpm: Double
)
data class ConfirmedPhaseEvent(
    val session: ProcessingSession, val revision: Long,
    val phase: WorkoutPhase, val watchElapsedTimeNanos: Long
)

data class ExerciseHeartRate(
    val currentBpm: Double?, val timeWeightedAverageBpm: Double?, val smoothedPeakBpm: Double?
)
enum class IntensityZone { LOW, MODERATE, HIGH, UNCLASSIFIED, MISSING }
data class ExerciseIntensity(val percentage: Double?, val zone: IntensityZone, val confirmedAtNanos: Long? = null, val hrMaxBpm: Double = 200.0)
data class RecoveryRate(val startBpm: Double, val endBpm: Double, val declineBpm: Double, val bpmPerMinute: Double,
    val startEvidence: CalculationEvidence = CalculationEvidence(),
    val endEvidence: CalculationEvidence = CalculationEvidence())

/** Counts describe accepted input, not calculated physiological quality. */
data class InputSummary(
    val acceptedSamples: Long = 0,
    val latestTimestampNanos: Long? = null,
    val sources: Set<SampleSource> = emptySet()
)
data class ProcessingQuality(
    val heartRateCoverageFraction: Double? = null,
    val accelerationCoverageFraction: Double? = null,
    val stillnessVerified: Boolean? = null,
    val motionDetected: Boolean? = null,
    val movementDuringRecovery: Boolean? = null
)
data class ProcessingSnapshot(
    val session: ProcessingSession? = null,
    val preprocessing: PreprocessingSnapshot = PreprocessingSnapshot(),
    val motion: MotionResult = MotionResult(),
    val phaseHistory: List<ConfirmedPhaseEvent> = emptyList(),
    val endedAtNanos: Long? = null,
    val recoveryRemainingSeconds: Long? = null,
    val acceleration: InputSummary = InputSummary(),
    val heartRate: InputSummary = InputSummary(),
    val restingHeartRate: MetricResult<Double> = MetricResult.Unavailable(UnavailableReason.NO_SESSION),
    val exerciseHeartRate: MetricResult<ExerciseHeartRate> = MetricResult.Unavailable(UnavailableReason.NO_SESSION),
    val intensity: MetricResult<ExerciseIntensity> = MetricResult.Unavailable(UnavailableReason.NO_SESSION),
    val recovery: MetricResult<RecoveryRate> = MetricResult.Unavailable(UnavailableReason.NO_SESSION),
    val workoutState: MetricResult<ConfirmedPhaseEvent> = MetricResult.Unavailable(UnavailableReason.NO_SESSION),
    val accelerationRms: MetricResult<Double> = MetricResult.Unavailable(UnavailableReason.NO_SESSION),
    val quality: ProcessingQuality = ProcessingQuality()
) {
    // Watch elapsed time in nanoseconds; derived from the confirmed history to avoid duplicate state.
    val restingStartedAt: Long?
        get() = phaseHistory.firstOrNull { it.phase == WorkoutPhase.RESTING }?.watchElapsedTimeNanos
    val exerciseStartedAt: Long?
        get() = phaseHistory.firstOrNull { it.phase == WorkoutPhase.EXERCISING }?.watchElapsedTimeNanos
    val recoveryStartedAt: Long?
        get() = phaseHistory.firstOrNull { it.phase == WorkoutPhase.RECOVERING }?.watchElapsedTimeNanos
}
