package com.abhishek.battlebucks.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every test here collects a flow containing `delay(500..2000)` calls. They finish in milliseconds
 * because `runTest` advances virtual time. The delays are real suspensions, which is what matters
 * (no UI timers), but nothing actually waits.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent/advanceTimeBy, used to drive the pause gate
class SimulatedScoreEngineTest {

    private val roster = defaultRoster(8)

    private fun engine(seed: Long = 42L, maxEvents: Int? = null) =
        SimulatedScoreEngine(roster, MatchConfig(seed = seed, maxEvents = maxEvents))

    @Test
    fun `same seed produces an identical event sequence`() = runTest {
        val first = engine(seed = 7L).scoreEvents().take(200).toList()
        val second = engine(seed = 7L).scoreEvents().take(200).toList()

        assertEquals(first, second)
    }

    @Test
    fun `a single engine replays identically on every collection`() = runTest {
        // Guards the "cold flow, state confined to the collection" design: collecting twice must
        // not resume mid-match or share totals.
        val engine = engine(seed = 7L)

        assertEquals(
            engine.scoreEvents().take(50).toList(),
            engine.scoreEvents().take(50).toList(),
        )
    }

    @Test
    fun `different seeds produce different sequences`() = runTest {
        val first = engine(seed = 1L).scoreEvents().take(200).toList()
        val second = engine(seed = 2L).scoreEvents().take(200).toList()

        assertNotEquals(first, second)
    }

    @Test
    fun `a player's score only ever increases`() = runTest {
        val seen = mutableMapOf<String, Long>()

        engine().scoreEvents().take(1_000).toList().forEach { event ->
            val previous = seen[event.playerId] ?: 0L
            assertTrue(
                "score for ${event.playerId} went $previous -> ${event.totalScore}",
                event.totalScore > previous,
            )
            assertEquals(previous + event.delta, event.totalScore)
            seen[event.playerId] = event.totalScore
        }
    }

    @Test
    fun `intervals stay within the configured range`() = runTest {
        val config = MatchConfig(seed = 3L, tickInterval = 500L..2_000L)
        val events = SimulatedScoreEngine(roster, config).scoreEvents().take(500).toList()

        var previousElapsed = 0L
        events.forEach { event ->
            val gap = event.elapsedMillis - previousElapsed
            assertTrue("gap of $gap ms out of range", gap in config.tickInterval)
            previousElapsed = event.elapsedMillis
        }
    }

    @Test
    fun `sequence numbers are gapless and start at zero`() = runTest {
        val events = engine().scoreEvents().take(100).toList()

        assertEquals(List(100) { it.toLong() }, events.map(ScoreEvent::sequence))
    }

    @Test
    fun `events are spread across the whole roster`() = runTest {
        val events = engine().scoreEvents().take(500).toList()

        assertEquals(roster.map(Player::id).toSet(), events.map(ScoreEvent::playerId).toSet())
    }

    @Test
    fun `maxEvents terminates the stream`() = runTest {
        val events = engine(maxEvents = 25).scoreEvents().toList()

        assertEquals(25, events.size)
    }

    @Test
    fun `awards stay within bounds and land on the configured step`() {
        val config = MatchConfig(seed = 11L, scoreDelta = 50..500, scoreStep = 50)

        runTest {
            SimulatedScoreEngine(roster, config).scoreEvents().take(1_000).toList()
                .forEach { event ->
                    assertTrue(
                        "delta ${event.delta} out of range",
                        event.delta in config.scoreDelta,
                    )
                    assertEquals(
                        "delta ${event.delta} is not a multiple of ${config.scoreStep}",
                        0,
                        event.delta % config.scoreStep,
                    )
                }
        }
    }

    @Test
    fun `totals stay on the step boundary so scores read as round numbers`() = runTest {
        val config = MatchConfig(seed = 12L, scoreStep = 50)

        SimulatedScoreEngine(roster, config).scoreEvents().take(500).toList().forEach { event ->
            assertEquals(0L, event.totalScore % config.scoreStep)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a score delta that is not a multiple of the step is rejected`() {
        // Otherwise the drawn award could round outside the range callers rely on.
        MatchConfig(scoreDelta = 30..500, scoreStep = 50)
    }

    // --- pausing ------------------------------------------------------------------------------

    @Test
    fun `a paused match generates nothing`() = runTest {
        val running = MutableStateFlow(true)
        val events = mutableListOf<ScoreEvent>()
        backgroundScope.launch {
            SimulatedScoreEngine(roster, MatchConfig(seed = 5L))
                .scoreEvents(runWhile = running)
                .collect { events += it }
        }

        advanceTimeBy(20_000)
        runCurrent()
        assertTrue("expected events while running", events.isNotEmpty())

        running.value = false
        advanceTimeBy(3_000) // let any already-scheduled tick land
        runCurrent()
        val whilePaused = events.size

        // Five minutes of virtual time with the match paused.
        advanceTimeBy(300_000)
        runCurrent()

        assertEquals("a paused match must not generate", whilePaused, events.size)
    }

    @Test
    fun `resuming continues the match instead of restarting it`() = runTest {
        val running = MutableStateFlow(true)
        val events = mutableListOf<ScoreEvent>()
        backgroundScope.launch {
            SimulatedScoreEngine(roster, MatchConfig(seed = 6L))
                .scoreEvents(runWhile = running)
                .collect { events += it }
        }

        advanceTimeBy(30_000)
        runCurrent()
        running.value = false
        advanceTimeBy(3_000)
        runCurrent()

        val beforePause = events.toList()
        assertTrue(beforePause.isNotEmpty())

        running.value = true
        advanceTimeBy(30_000)
        runCurrent()

        val afterResume = events.drop(beforePause.size)
        assertTrue("expected events after resuming", afterResume.isNotEmpty())

        // The sequence keeps counting rather than restarting at 0.
        assertEquals(beforePause.last().sequence + 1, afterResume.first().sequence)

        // And totals carry over: every player's score is still monotonic across the pause, which
        // is what a naive cancel-and-restart would have destroyed by replaying from the seed.
        val seen = mutableMapOf<String, Long>()
        events.forEach { event ->
            val previous = seen[event.playerId] ?: 0L
            assertTrue(
                "${event.playerId} reset across the pause: $previous -> ${event.totalScore}",
                event.totalScore > previous,
            )
            seen[event.playerId] = event.totalScore
        }
    }

    @Test
    fun `a match paused from the start generates nothing until released`() = runTest {
        val running = MutableStateFlow(false)
        val events = mutableListOf<ScoreEvent>()
        backgroundScope.launch {
            SimulatedScoreEngine(roster, MatchConfig(seed = 8L))
                .scoreEvents(runWhile = running)
                .collect { events += it }
        }

        advanceTimeBy(60_000)
        runCurrent()
        assertTrue("nothing should be generated before the gate opens", events.isEmpty())

        running.value = true
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(events.isNotEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive score delta is rejected at construction`() {
        // The monotonic-score rule is enforced here rather than discovered 10,000 events later.
        MatchConfig(scoreDelta = 0..10)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate player ids are rejected`() {
        SimulatedScoreEngine(listOf(Player("p1", "A"), Player("p1", "B")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty roster is rejected`() {
        SimulatedScoreEngine(emptyList())
    }
}
