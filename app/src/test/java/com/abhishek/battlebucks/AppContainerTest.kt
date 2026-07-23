package com.abhishek.battlebucks

import com.abhishek.battlebucks.match.MatchStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The container owns a `CoroutineScope` that runs a match, so the thing worth testing is its
 * *lifetime*: that closing it actually stops the work, and that a second container is a second
 * independent match rather than a shared surprise.
 *
 * They're also the proof that pulling `ProcessLifecycleOwner` out of `AppContainer` was worth
 * doing. The container is now exercised end to end on a plain JVM, with no Robolectric and no
 * emulator.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent/advanceTimeBy, to drive virtual time
class AppContainerTest {

    @Test
    fun `an open container runs a match`() = runTest {
        val container = AppContainer(dispatcher = StandardTestDispatcher(testScheduler))

        advanceTimeBy(30_000)
        runCurrent()

        assertTrue(
            "expected the match to have produced standings",
            container.matchController.leaderboard.value.entries.isNotEmpty(),
        )

        container.close()
    }

    @Test
    fun `closing stops the match`() = runTest {
        val container = AppContainer(dispatcher = StandardTestDispatcher(testScheduler))
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(container.matchController.leaderboard.value.version > 0)

        container.close()

        // Closing stops the match, and stopping discards its standings. See MatchController.stop.
        assertEquals(MatchStatus.Idle, container.matchController.status.value)
        val atClose = container.matchController.leaderboard.value

        // Ten minutes of virtual time after closing.
        advanceTimeBy(600_000)
        runCurrent()

        assertEquals(
            "the match kept running after close()",
            atClose,
            container.matchController.leaderboard.value,
        )
        assertEquals(MatchStatus.Idle, container.matchController.status.value)
    }

    @Test
    fun `closing twice is safe`() = runTest {
        val container = AppContainer(dispatcher = StandardTestDispatcher(testScheduler))
        advanceTimeBy(10_000)
        runCurrent()

        container.close()
        container.close() // must not throw
    }

    @Test
    fun `closing one container does not affect another`() = runTest {
        // The regression this guards: a scope shared or leaked between containers. Closing one
        // must not silently stop the other.
        val first = AppContainer(StandardTestDispatcher(testScheduler))
        val second = AppContainer(StandardTestDispatcher(testScheduler))
        advanceTimeBy(30_000)
        runCurrent()

        first.close()
        val secondAtFirstClose = second.matchController.leaderboard.value.version
        advanceTimeBy(30_000)
        runCurrent()

        assertNotEquals(
            "the surviving container should have kept playing",
            secondAtFirstClose,
            second.matchController.leaderboard.value.version,
        )

        second.close()
    }

    @Test
    fun `a container starts its match without being asked twice`() = runTest {
        // Starting is now an explicit act, so the container must actually perform it.
        val container = AppContainer(StandardTestDispatcher(testScheduler))

        assertEquals(MatchStatus.Running, container.matchController.status.value)

        container.close()
        assertEquals(MatchStatus.Idle, container.matchController.status.value)
    }
}
