package com.example.mobile_wearableapplication

import com.example.mobile_wearableapplication.processing.*
import org.junit.Assert.*
import org.junit.Test

class MultiWorkoutTest {
    private val second = 1_000_000_000L
    private val session = ProcessingSession("watch", "interval")
    private fun phase(engine: SensorProcessingEngine, revision: Long, phase: WorkoutPhase, time: Int) =
        engine.acceptConfirmedPhase(ConfirmedPhaseEvent(session, revision, phase, time * second))

    @Test fun repeatedRoundsHaveSeparateBoundariesAndKeepBothIntensityIntervals() {
        val engine = SensorProcessingEngine()
        engine.startSession(session)
        phase(engine, 1, WorkoutPhase.RESTING, 0)
        phase(engine, 2, WorkoutPhase.EXERCISING, 10)
        for (t in 10..20) engine.acceptHeartRate(session, listOf(HeartRateInput(t + 1L, t * second, SampleSource.DEMO, 120.0)))
        phase(engine, 3, WorkoutPhase.RECOVERING, 20)
        phase(engine, 4, WorkoutPhase.EXERCISING, 30)
        for (t in 30..40) engine.acceptHeartRate(session, listOf(HeartRateInput(t + 1L, t * second, SampleSource.DEMO, 150.0)))
        assertEquals(30 * second, engine.snapshot().exerciseStartedAt)
        assertNull(engine.snapshot().recoveryStartedAt)
        phase(engine, 5, WorkoutPhase.RECOVERING, 40)
        assertEquals(40 * second, engine.snapshot().recoveryStartedAt)
        assertEquals(WorkoutPhase.EXERCISING, engine.phaseAt(15 * second))
        assertEquals(WorkoutPhase.RECOVERING, engine.phaseAt(25 * second))
        assertEquals(WorkoutPhase.EXERCISING, engine.phaseAt(35 * second))
        val intervals = engine.snapshot().chartOutput.zoneIntervals
        assertEquals(20 * second, intervals.sumOf { it.endNanos - it.startNanos })
        assertTrue(intervals.any { it.zone == IntensityZone.MODERATE && it.endNanos <= 20 * second })
        assertTrue(intervals.any { it.zone == IntensityZone.HIGH && it.startNanos >= 30 * second })
        assertFalse(intervals.any { it.startNanos < 30 * second && it.endNanos > 20 * second })
        val before = engine.snapshot()
        phase(engine, 4, WorkoutPhase.EXERCISING, 30)
        assertEquals(before, engine.snapshot())
    }

    private fun fillRecovery(calculator: RecoveryCalculator, start: Int, before: Double, after: Double,
                             movementAt: Int? = null, omitMotionEnd: Boolean = false) {
        for (tick in (start - 5) * 25..(start + 60) * 25) {
            val t = tick * 40_000_000L
            if (!omitMotionEnd || t < (start + 60) * second)
                calculator.observe(t, if (movementAt != null && t >= movementAt * second) MotionState.MOTION else MotionState.STILL)
            if (tick % 25 == 0) calculator.accept(HeartRateInput(tick + 1L, t, SampleSource.DEMO,
                if (t < start * second) before else after))
        }
    }

    @Test fun resumedMovementInvalidatesRecoveryEvenBeforePhaseConfirmation() {
        val calculator = RecoveryCalculator()
        fillRecovery(calculator, 10, 150.0, 170.0, movementAt = 40)
        assertEquals(MetricResult.Unavailable(UnavailableReason.RECOVERY_INTERRUPTED),
            calculator.result(10 * second, 0, 70 * second, null))
    }

    @Test fun heartRateCannotFinalizeBeforeDelayedAccelerationArrives() {
        val calculator = RecoveryCalculator()
        fillRecovery(calculator, 10, 150.0, 125.0, omitMotionEnd = true)
        assertTrue(calculator.result(10 * second, 0, 70 * second, null) is MetricResult.Unavailable)
        calculator.observe(70 * second, MotionState.STILL)
        assertEquals(25.0, (calculator.result(10 * second, 0, 70 * second, null) as MetricResult.Available).value.declineBpm, 0.0)
    }

    @Test fun nextRecoveryReplacesCancelledWindowAndCachedResult() {
        val calculator = RecoveryCalculator()
        fillRecovery(calculator, 10, 150.0, 170.0, movementAt = 40)
        assertTrue(calculator.result(10 * second, 0, 70 * second, null) is MetricResult.Unavailable)
        calculator.result(null, 80 * second, 80 * second, null)
        fillRecovery(calculator, 100, 160.0, 120.0)
        val secondRound = calculator.result(100 * second, 80 * second, 160 * second, null) as MetricResult.Available
        assertEquals(40.0, secondRound.value.declineBpm, 0.0)
        calculator.result(null, 170 * second, 170 * second, null)
        fillRecovery(calculator, 190, 140.0, 130.0)
        val thirdRound = calculator.result(190 * second, 170 * second, 250 * second, null) as MetricResult.Available
        assertEquals(10.0, thirdRound.value.declineBpm, 0.0)
        assertEquals(250 * second, thirdRound.evidence.window!!.endNanos)
    }

    @Test fun disconnectionCancelsIncompleteRecoveryButPreservesCompletedResult() {
        val calculator = RecoveryCalculator()
        calculator.result(10 * second, 0, 10 * second, null)
        calculator.interrupt()
        fillRecovery(calculator, 10, 150.0, 125.0)
        assertTrue(calculator.result(10 * second, 0, 70 * second, null) is MetricResult.Unavailable)
        calculator.result(null, 80 * second, 80 * second, null)
        fillRecovery(calculator, 100, 150.0, 125.0)
        val complete = calculator.result(100 * second, 80 * second, 160 * second, null)
        assertTrue(complete is MetricResult.Available)
        calculator.interrupt()
        assertEquals(complete, calculator.result(100 * second, 80 * second, 165 * second, null))
    }

    @Test fun genuineHeartRateRiseDuringVerifiedRestIsNotClampedToZero() {
        val calculator = RecoveryCalculator()
        fillRecovery(calculator, 10, 125.0, 150.0)
        assertEquals(-25.0, (calculator.result(10 * second, 0, 70 * second, null) as MetricResult.Available).value.declineBpm, 0.0)
    }

    @Test fun missingMiddleOfRecoveryCannotProduceValidEndpointOnlyResult() {
        val calculator = RecoveryCalculator()
        for (tick in 5 * 25..70 * 25) {
            calculator.observe(tick * 40_000_000L, MotionState.STILL)
            if (tick % 25 == 0 && tick / 25 !in 30..40)
                calculator.accept(HeartRateInput(tick + 1L, tick * 40_000_000L, SampleSource.DEMO, 125.0))
        }
        assertEquals(MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA),
            calculator.result(10 * second, 0, 70 * second, null))
    }
}
