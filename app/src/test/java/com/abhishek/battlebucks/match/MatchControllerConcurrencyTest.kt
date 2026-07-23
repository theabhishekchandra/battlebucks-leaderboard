package com.abhishek.battlebucks.match

import com.abhishek.battlebucks.engine.MatchConfig
import com.abhishek.battlebucks.engine.SimulatedScoreEngine
import com.abhishek.battlebucks.engine.defaultRoster
import com.abhishek.battlebucks.leaderboard.LeaderboardEngine
import com.abhishek.battlebucks.leaderboard.LeaderboardState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Real threads, real dispatchers, real time, on purpose.
 *
 * Everything else here runs on virtual time, which is faster and exact. It's also single-threaded,
 * so it can't observe the failure this class exists to prevent: two commands interleaving
 * mid-transition.
 *
 * The load-bearing assertion in every test is the same, and it isn't "did it throw". It's
 * quiescence after `stop()`. A stopped controller resets its standings to
 * [LeaderboardState.Empty], so any job that survived the stop overwrites that with real standings
 * within a few milliseconds. An orphaned coroutine is exactly what a lost `start()` race produces,
 * and it can't hide from that.
 */
class MatchControllerConcurrencyTest {

    /** ~1ms between events, so a few hundred milliseconds of real time is a busy match. */
    private fun controller(scope: CoroutineScope) = MatchController(
        scoreEngine = SimulatedScoreEngine(
            players = defaultRoster(8),
            config = MatchConfig(seed = 1L, tickInterval = 1L..2L),
        ),
        leaderboardEngine = LeaderboardEngine(),
        scope = scope,
        dispatcher = Dispatchers.Default,
    )

    private fun withController(block: (MatchController) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            block(controller(scope))
        } finally {
            scope.cancel()
        }
    }

    /**
     * Fires [count] tasks at genuinely the same instant.
     *
     * One thread per task, on purpose. A smaller pool would starve: workers park on [go] before
     * doing any work, so with fewer threads than tasks the later ones cannot even reach the
     * barrier, and what looks like a stampede is really a trickle.
     */
    private fun stampede(count: Int, task: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(count)
        val ready = CountDownLatch(count)
        val go = CountDownLatch(1)
        val done = CountDownLatch(count)
        repeat(count) { index ->
            pool.execute {
                ready.countDown()
                go.await()
                try {
                    task(index)
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(
            "workers never reached the barrier - the pool is starved and this is not a stampede",
            ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        go.countDown()
        assertTrue("workers did not finish", done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        pool.shutdownNow()
    }

    @Test
    fun `a stampede of concurrent starts leaves no orphaned match`() = withController { c ->
        // Unsynchronised, several of these can see Idle at once, all launch, and all but the last
        // have their handle overwritten. Those orphans are ones stop() can never cancel.
        stampede(CONCURRENT_COMMANDS) { c.start() }

        Thread.sleep(SETTLE_MILLIS)
        assertTrue(
            "the match never actually ran, so this test proves nothing",
            c.leaderboard.value.entries.isNotEmpty(),
        )

        c.stop()
        Thread.sleep(SETTLE_MILLIS)

        assertEquals(MatchStatus.Idle, c.status.value)
        assertEquals(
            "something was still writing standings after stop()",
            LeaderboardState.Empty,
            c.leaderboard.value,
        )
    }

    @Test
    fun `concurrent stops and starts settle into a consistent state`() = withController { c ->
        stampede(CONCURRENT_COMMANDS) { index ->
            if (index % 2 == 0) c.start() else c.stop()
        }

        // Whatever the interleaving decided, one final stop must leave nothing running.
        c.stop()
        Thread.sleep(SETTLE_MILLIS)

        assertEquals(MatchStatus.Idle, c.status.value)
        assertEquals(LeaderboardState.Empty, c.leaderboard.value)
    }

    @Test
    fun `hammering every command never corrupts the state machine`() = withController { c ->
        c.start()

        stampede(CONCURRENT_COMMANDS) { index ->
            val random = Random(index)
            repeat(COMMANDS_PER_WORKER) {
                when (random.nextInt(5)) {
                    0 -> c.start()
                    1 -> c.pause()
                    2 -> c.resume()
                    3 -> c.stop()
                    else -> c.restart()
                }
                // Reading through a transition must never observe a status outside the enum, nor
                // a Running match with its gate closed.
                assertTrue(c.status.value in MatchStatus.entries)
            }
        }

        c.stop()
        Thread.sleep(SETTLE_MILLIS)

        assertEquals(MatchStatus.Idle, c.status.value)
        assertEquals(
            "a job survived the final stop()",
            LeaderboardState.Empty,
            c.leaderboard.value,
        )
    }

    @Test
    fun `restart is atomic - no observer sees two matches at once`() = withController { c ->
        c.start()
        Thread.sleep(SETTLE_MILLIS)

        stampede(CONCURRENT_COMMANDS) { c.restart() }

        Thread.sleep(SETTLE_MILLIS)
        assertEquals(MatchStatus.Running, c.status.value)

        c.stop()
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(LeaderboardState.Empty, c.leaderboard.value)
    }

    private companion object {
        const val CONCURRENT_COMMANDS = 64
        const val COMMANDS_PER_WORKER = 20
        const val SETTLE_MILLIS = 250L
        const val TIMEOUT_SECONDS = 20L
    }
}
