package com.abhishek.battlebucks.match

import com.abhishek.battlebucks.engine.MatchConfig
import com.abhishek.battlebucks.engine.ScoreEvent
import com.abhishek.battlebucks.engine.SimulatedScoreEngine
import com.abhishek.battlebucks.engine.defaultRoster
import com.abhishek.battlebucks.leaderboard.LeaderboardEngine
import com.abhishek.battlebucks.leaderboard.LeaderboardState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam the two domain modules meet at.
 *
 * `:engine` and `:leaderboard` are each well covered on their own, but nothing tested them wired
 * together through [toScoreUpdate], which is where a mapping mistake would hide. This drives a full
 * 5,000-event match through the real engine and the real leaderboard, checking the ranking
 * invariants on every emitted snapshot.
 */
class MatchIntegrationTest {

    private val roster = defaultRoster(15)

    @Test
    fun `every snapshot of a real match satisfies the ranking invariants`() = runTest {
        val engine = SimulatedScoreEngine(
            players = roster,
            config = MatchConfig(seed = 4_242L, maxEvents = 5_000),
        )
        val leaderboard = LeaderboardEngine(currentPlayerId = "p7")

        val states = leaderboard
            .rank(engine.scoreEvents().map(ScoreEvent::toScoreUpdate))
            .toList()

        assertEquals(5_001, states.size) // the initial empty board, plus one per accepted event
        states.forEachIndexed { index, state -> state.assertInvariants(at = index) }
    }

    @Test
    fun `the same seed produces the same final standings`() = runTest {
        suspend fun finalStandings(): List<Pair<String, Int>> {
            val engine = SimulatedScoreEngine(roster, MatchConfig(seed = 7L, maxEvents = 500))
            return LeaderboardEngine()
                .rank(engine.scoreEvents().map(ScoreEvent::toScoreUpdate))
                .toList()
                .last()
                .entries
                .map { it.playerId to it.rank }
        }

        assertEquals(finalStandings(), finalStandings())
    }

    private fun LeaderboardState.assertInvariants(at: Int) {
        entries.forEachIndexed { i, entry ->
            val above = entries.getOrNull(i - 1)

            if (above != null) {
                // Sorted by score descending, so no row may outscore the row above it.
                assertTrue(
                    "snapshot $at, position $i: ${entry.score} > ${above.score} above it",
                    entry.score <= above.score,
                )
                // Same score shares a rank; otherwise rank is the 1-based position, so ranks
                // consumed by a tie group are skipped.
                val expected = if (entry.score == above.score) above.rank else i + 1
                assertEquals(
                    "snapshot $at, position $i (${entry.displayName})",
                    expected,
                    entry.rank,
                )
            } else {
                assertEquals("snapshot $at: the leader must be rank 1", 1, entry.rank)
            }
        }

        // The pinned row must be the very same object as the one in the list.
        self?.let { assertSame(entries.single { it.playerId == "p7" }, it) }
    }
}
