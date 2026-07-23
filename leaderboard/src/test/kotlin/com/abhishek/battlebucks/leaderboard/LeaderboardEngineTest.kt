package com.abhishek.battlebucks.leaderboard

import app.cash.turbine.test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LeaderboardEngineTest {

    private val engine = LeaderboardEngine()

    private fun update(id: String, score: Long) = ScoreUpdate(id, "Name $id", score)

    @Test
    fun `starts from an empty board`() = runTest {
        engine.rank(flowOf()).test {
            assertEquals(LeaderboardState.Empty, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `emits a ranked snapshot per accepted update`() = runTest {
        val updates = flowOf(update("a", 10L), update("b", 30L), update("a", 40L))

        engine.rank(updates).test {
            assertEquals(emptyList<LeaderboardEntry>(), awaitItem().entries)
            assertEquals(listOf("a"), awaitItem().entries.map { it.playerId })
            assertEquals(listOf("b", "a"), awaitItem().entries.map { it.playerId })
            assertEquals(listOf("a", "b"), awaitItem().entries.map { it.playerId })
            awaitComplete()
        }
    }

    @Test
    fun `a no-op update produces no emission`() = runTest {
        val updates = flowOf(update("a", 10L), update("a", 10L), update("a", 10L))

        // Empty + one accepted update. The two repeats never reach the UI, so they can never
        // cost a recomposition.
        assertEquals(2, engine.rank(updates).toList().size)
    }

    @Test
    fun `each collection gets independent state`() = runTest {
        val updates = flowOf(update("a", 10L), update("b", 20L))

        assertEquals(
            engine.rank(updates).toList().map { it.entries.map(LeaderboardEntry::playerId) },
            engine.rank(updates).toList().map { it.entries.map(LeaderboardEntry::playerId) },
        )
    }

    @Test
    fun `ranking is applied to the stream, not just the final state`() = runTest {
        val updates = flowOf(
            update("a", 50L),
            update("b", 50L),
            update("c", 10L),
        )

        val ranks = engine.rank(updates).toList().last().entries.map { it.playerId to it.rank }

        assertEquals(listOf("a" to 1, "b" to 1, "c" to 3), ranks)
    }
}
