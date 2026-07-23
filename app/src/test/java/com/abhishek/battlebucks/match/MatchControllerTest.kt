package com.abhishek.battlebucks.match

import com.abhishek.battlebucks.engine.MatchConfig
import com.abhishek.battlebucks.engine.SimulatedScoreEngine
import com.abhishek.battlebucks.engine.defaultRoster
import com.abhishek.battlebucks.leaderboard.LeaderboardEngine
import com.abhishek.battlebucks.leaderboard.LeaderboardState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The match lifecycle. The state machine, and more importantly that each transition does what it
 * claims to the actual work, not just to a status enum.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MatchControllerTest {

    private fun TestScope.controller(seed: Long = 42L, maxEvents: Int? = null) = MatchController(
        scoreEngine = SimulatedScoreEngine(
            players = defaultRoster(8),
            config = MatchConfig(seed = seed, maxEvents = maxEvents),
        ),
        leaderboardEngine = LeaderboardEngine(),
        scope = this,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    // --- the state machine ----------------------------------------------------------------------

    @Test
    fun `a fresh controller is idle and has run nothing`() = runTest {
        val controller = controller()

        assertEquals(MatchStatus.Idle, controller.status.value)
        assertEquals(LeaderboardState.Empty, controller.leaderboard.value)
    }

    @Test
    fun `start runs a match`() = runTest {
        val controller = controller()

        controller.start()
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(MatchStatus.Running, controller.status.value)
        assertTrue(controller.leaderboard.value.entries.isNotEmpty())
        controller.stop()
    }

    @Test
    fun `starting an already running match is a no-op, not a restart`() = runTest {
        val controller = controller()
        controller.start()
        advanceTimeBy(30_000)
        runCurrent()
        val inProgress = controller.leaderboard.value.version

        controller.start()
        runCurrent()

        assertEquals(
            "start() restarted a running match instead of doing nothing",
            inProgress,
            controller.leaderboard.value.version,
        )
        controller.stop()
    }

    @Test
    fun `pause and resume only apply from the states that allow them`() = runTest {
        val controller = controller()

        controller.pause() // Idle: nothing to pause
        assertEquals(MatchStatus.Idle, controller.status.value)
        controller.resume() // Idle: nothing to resume
        assertEquals(MatchStatus.Idle, controller.status.value)

        controller.start()
        controller.resume() // already Running
        assertEquals(MatchStatus.Running, controller.status.value)

        controller.pause()
        assertEquals(MatchStatus.Paused, controller.status.value)
        controller.pause() // already Paused
        assertEquals(MatchStatus.Paused, controller.status.value)

        controller.stop()
    }

    // --- transitions that must affect the actual work -------------------------------------------

    @Test
    fun `pausing stops generation and keeps the standings`() = runTest {
        val controller = controller()
        controller.start()
        advanceTimeBy(30_000)
        runCurrent()

        controller.pause()
        advanceTimeBy(3_000) // let the in-flight tick land
        runCurrent()
        val atPause = controller.leaderboard.value

        advanceTimeBy(600_000) // ten minutes paused
        runCurrent()

        assertEquals(
            "a paused match kept generating",
            atPause.version,
            controller.leaderboard.value.version,
        )
        assertTrue("a paused match lost its standings", atPause.entries.isNotEmpty())
        controller.stop()
    }

    @Test
    fun `resuming continues the match rather than replaying it`() = runTest {
        val controller = controller()
        controller.start()
        advanceTimeBy(30_000)
        runCurrent()
        controller.pause()
        advanceTimeBy(3_000)
        runCurrent()
        val atPause = controller.leaderboard.value

        controller.resume()
        advanceTimeBy(30_000)
        runCurrent()
        val afterResume = controller.leaderboard.value

        assertTrue("the match did not resume", afterResume.version > atPause.version)
        // Scores carried over rather than restarting from the seed at zero.
        val before = atPause.entries.associate { it.playerId to it.score }
        afterResume.entries.forEach { entry ->
            val previous = before[entry.playerId] ?: 0L
            assertTrue(
                "${entry.displayName} went backwards: $previous -> ${entry.score}",
                entry.score >= previous,
            )
        }
        controller.stop()
    }

    @Test
    fun `stopping ends the match and discards the standings`() = runTest {
        val controller = controller()
        controller.start()
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(controller.leaderboard.value.entries.isNotEmpty())

        controller.stop()
        advanceTimeBy(600_000)
        runCurrent()

        assertEquals(MatchStatus.Idle, controller.status.value)
        assertEquals(LeaderboardState.Empty, controller.leaderboard.value)
    }

    @Test
    fun `stopping twice is safe`() = runTest {
        val controller = controller()
        controller.start()
        advanceTimeBy(10_000)
        runCurrent()

        controller.stop()
        controller.stop() // must not throw
        assertEquals(MatchStatus.Idle, controller.status.value)
    }

    @Test
    fun `a stopped match can be started again`() = runTest {
        val controller = controller()
        controller.start()
        advanceTimeBy(30_000)
        runCurrent()
        controller.stop()

        controller.start()
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(MatchStatus.Running, controller.status.value)
        assertTrue(controller.leaderboard.value.entries.isNotEmpty())
        controller.stop()
    }

    @Test
    fun `restart replays the same seeded match from the beginning`() = runTest {
        val controller = controller(seed = 99L)
        controller.start()
        advanceTimeBy(30_000)
        runCurrent()
        val firstRun = controller.leaderboard.value.entries.map { it.playerId to it.score }

        controller.restart()
        // Immediately after restarting the board is empty again, not carried over.
        assertEquals(LeaderboardState.Empty, controller.leaderboard.value)

        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(
            "a restart of a seeded engine should reproduce the same match",
            firstRun,
            controller.leaderboard.value.entries.map { it.playerId to it.score },
        )
        controller.stop()
    }

    @Test
    fun `a bounded match finishes on its own`() = runTest {
        val controller = controller(maxEvents = 20)
        controller.start()

        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(MatchStatus.Finished, controller.status.value)
        // Finishing keeps the final standings; only stop() discards them.
        assertTrue(controller.leaderboard.value.entries.isNotEmpty())
    }

    @Test
    fun `a finished match can be started again`() = runTest {
        val controller = controller(maxEvents = 20)
        controller.start()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(MatchStatus.Finished, controller.status.value)

        controller.start()
        advanceTimeBy(60_000)
        runCurrent()

        assertNotEquals(MatchStatus.Idle, controller.status.value)
        assertTrue(controller.leaderboard.value.entries.isNotEmpty())
        controller.stop()
    }
}
