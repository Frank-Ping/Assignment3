package com.example.mobile_wearableapplication

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import android.content.ContextWrapper
import com.example.mobile_wearableapplication.processing.*
import java.io.File
import com.example.shared.communication.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiWorkoutProtocolTest {
    @Test fun historyKeepsBothRoundsAndDoesNotReplaceSavedRecoveryWithInterruptedRound() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "multi-workout-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        HistoryFileStore.open(context)
        val second = 1_000_000_000L
        val session = ProcessingSession("test", "history")
        val phases = listOf(WorkoutPhase.RESTING, WorkoutPhase.EXERCISING, WorkoutPhase.RECOVERING,
            WorkoutPhase.EXERCISING, WorkoutPhase.RECOVERING).mapIndexed { index, phase ->
            ConfirmedPhaseEvent(session, index + 1L, phase, listOf(0, 10, 70, 150, 240)[index] * second)
        }
        val intervals = listOf(ZoneInterval(10 * second, 70 * second, IntensityZone.LOW),
            ZoneInterval(150 * second, 240 * second, IntensityZone.HIGH))
        val offset = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L - 3_600_000L
        fun valid(drop: Double, end: Long) = MetricResult.Available(RecoveryRate(150.0, 150.0 - drop, drop),
            CalculationEvidence(CalculationWindow(end - 65 * second, end), sources = setOf(SampleSource.DEMO)))
        val first = ProcessingSnapshot(session = session, phaseHistory = phases.take(3),
            heartRate = InputSummary(sources = setOf(SampleSource.DEMO)),
            recovery = valid(25.0, 130 * second), chartOutput = ChartOutput(zoneIntervals = intervals.take(1)))
        fun awaitSaved(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
            assertNull(HistoryFileStore.snapshot.error)
            assertTrue(condition())
        }
        HistoryFileStore.update(first, offset, force = true)
        awaitSaved { HistoryFileStore.snapshot.recovery?.value == 25.0 }
        val saved = HistoryFileStore.snapshot.recovery
        val interrupted = first.copy(phaseHistory = phases, recovery = MetricResult.Unavailable(UnavailableReason.RECOVERY_INTERRUPTED),
            chartOutput = ChartOutput(zoneIntervals = intervals))
        HistoryFileStore.update(interrupted, offset, force = true)
        awaitSaved { HistoryFileStore.snapshot.hours.sumOf { it.zones.sum() } == 150.0 }
        assertEquals(saved, HistoryFileStore.snapshot.recovery)
        HistoryFileStore.update(interrupted.copy(recovery = valid(40.0, 300 * second)), offset, force = true)
        awaitSaved { HistoryFileStore.snapshot.recovery?.value == 40.0 }
        assertEquals(150.0, HistoryFileStore.snapshot.hours.sumOf { it.zones.sum() }, 0.0)
    }

    private fun state(count: Int) = SessionState("multi-round", count.toLong(), SessionLifecycle.RUNNING,
        (0 until count).map { index -> PhaseTransition(
            if (index == 0) SessionPhase.RESTING else if (index % 2 == 1) SessionPhase.EXERCISING else SessionPhase.RECOVERING,
            index + 1L, index * 10_000_000_000L) })

    @Test fun multipleRoundsRoundTripAndAllowResuming() {
        for (count in listOf(3, 4, 5, SessionProtocol.MAX_TRANSITIONS)) {
            val state = state(count)
            val reply = SessionReply("query", true, null, state)
            val payload = SessionProtocol.encodeReply(reply)
            assertTrue(payload.size <= 32768)
            assertEquals(reply, SessionProtocol.decodeReply(payload))
        }
        assertTrue(state(3).allows(SessionAction.START_WORKOUT))
        assertFalse(state(4).allows(SessionAction.START_WORKOUT))
        assertFalse(state(SessionProtocol.MAX_TRANSITIONS).allows(SessionAction.START_WORKOUT))
        assertTrue(state(SessionProtocol.MAX_TRANSITIONS).allows(SessionAction.FINISH_SESSION))
    }

    @Test fun rejectsSkippedPhasesAndOutOfOrderTimes() {
        val original = state(5)
        for (bad in listOf(
            original.copy(transitions = original.transitions.toMutableList().apply { this[3] = this[3].copy(phase = SessionPhase.RECOVERING) }),
            original.copy(transitions = original.transitions.toMutableList().apply { this[3] = this[3].copy(watchElapsedTimeNanos = 0) }),
            original.copy(transitions = original.transitions.toMutableList().apply { this[3] = this[3].copy(revision = 2) })
        )) {
            assertTrue(runCatching { SessionProtocol.decodeReply(SessionProtocol.encodeReply(SessionReply("query", true, null, bad))) }.isFailure)
        }
    }
}
