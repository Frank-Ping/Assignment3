package com.example.mobile_wearableapplication

import com.example.mobile_wearableapplication.processing.*
import org.junit.Assert.*
import org.junit.Test

class ExerciseIntensityTest {
    private val second = 1_000_000_000L
    private val session = ProcessingSession("watch", "intensity-test")
    private fun engine() = SensorProcessingEngine().also {
        it.startSession(session)
        it.acceptConfirmedPhase(ConfirmedPhaseEvent(session, 0, WorkoutPhase.RESTING, 0))
    }
    private fun heartRate(engine: SensorProcessingEngine, time: Int, bpm: Double) {
        engine.acceptHeartRate(session, listOf(HeartRateInput(time + 1L, time * second, SampleSource.DEMO, bpm)))
    }
    private fun zone(engine: SensorProcessingEngine) =
        (engine.snapshot().intensity as MetricResult.Available).value.zone

    @Test fun confirmedLowSurvivesExerciseStartAndAppearsInHistory() {
        val engine = engine()
        for (time in 0..5) heartRate(engine, time, 90.0)
        assertEquals(IntensityZone.LOW, zone(engine))
        engine.acceptConfirmedPhase(ConfirmedPhaseEvent(session, 1, WorkoutPhase.EXERCISING, 5 * second))
        assertEquals(IntensityZone.LOW, zone(engine))
        heartRate(engine, 6, 90.0)
        assertEquals(IntensityZone.LOW, zone(engine))
        heartRate(engine, 7, 90.0)
        assertEquals(ZoneInterval(5 * second, 7 * second, IntensityZone.LOW),
            engine.snapshot().chartOutput.zoneIntervals.last())
    }

    @Test fun phaseChangeDoesNotResetModerateOrHighEither() {
        for ((bpm, expected) in listOf(120.0 to IntensityZone.MODERATE, 160.0 to IntensityZone.HIGH)) {
            val engine = engine()
            for (time in 0..5) heartRate(engine, time, bpm)
            engine.acceptConfirmedPhase(ConfirmedPhaseEvent(session, 1, WorkoutPhase.EXERCISING, 5 * second))
            heartRate(engine, 6, bpm)
            assertEquals(expected, zone(engine))
        }
    }

    @Test fun missingDataStillRequiresFreshConfirmation() {
        val engine = engine()
        for (time in 0..5) heartRate(engine, time, 90.0)
        engine.markHeartRateUnavailable()
        engine.acceptConfirmedPhase(ConfirmedPhaseEvent(session, 1, WorkoutPhase.EXERCISING, 10 * second))
        assertEquals(IntensityZone.MISSING, zone(engine))
        heartRate(engine, 11, 90.0)
        assertEquals(IntensityZone.UNCLASSIFIED, zone(engine))
        for (time in 12..15) heartRate(engine, time, 90.0)
        assertEquals(IntensityZone.LOW, zone(engine))
        assertFalse(engine.snapshot().chartOutput.zoneIntervals.any {
            it.zone == IntensityZone.LOW && it.startNanos < 15 * second
        })
    }

    @Test fun newSessionDoesNotInheritConfirmedIntensity() {
        val engine = engine()
        for (time in 0..5) heartRate(engine, time, 90.0)
        engine.startSession(ProcessingSession("watch", "next"))
        assertEquals(IntensityZone.UNCLASSIFIED, zone(engine))
    }

    @Test fun shortThresholdCrossingDoesNotChangeConfirmedZone() {
        val classifier = IntensityClassifier()
        for (time in 0..3) classifier.accept(time * second, 90.0)
        assertEquals(IntensityZone.LOW, classifier.accept(4 * second, 120.0).zone)
        assertEquals(IntensityZone.LOW, classifier.accept(5 * second, 90.0).zone)
        for (time in 6..8) assertEquals(IntensityZone.LOW, classifier.accept(time * second, 120.0).zone)
        assertEquals(IntensityZone.MODERATE, classifier.accept(9 * second, 120.0).zone)
    }
}
