package com.abhishek.battlebucks.leaderboard

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaderboardReducerTest {

    private fun update(id: String, score: Long) = ScoreUpdate(id, "Name $id", score)

    private fun LeaderboardReducer.applyAll(vararg updates: Pair<String, Long>) {
        updates.forEach { (id, score) -> apply(update(id, score)) }
    }

    private fun LeaderboardState.ranks() = entries.map { it.playerId to it.rank }

    // --- agreement with the reference implementation ------------------------------------------

    /**
     * The test that makes the incremental reducer defensible rather than merely clever: thousands
     * of randomised updates, and after *every single one* the incrementally maintained state must
     * equal a from-scratch [RankingRules.rank] of the same totals.
     *
     * The delta range is tiny so players collide constantly. Ties and tie-group boundaries are
     * where an incremental re-rank goes wrong, so the test aims straight at them.
     */
    @Test
    fun `incremental state matches a full re-sort after every update`() {
        val random = Random(20260722)
        val playerIds = (1..20).map { "p%02d".format(it) }
        val reducer = LeaderboardReducer()
        val totals = HashMap<String, Long>()

        repeat(5_000) { step ->
            val id = playerIds[random.nextInt(playerIds.size)]
            val newTotal = (totals[id] ?: 0L) + random.nextInt(1, 4)
            totals[id] = newTotal

            assertTrue("update $step should be accepted", reducer.apply(update(id, newTotal)))

            val expected = RankingRules.rank(totals.map { (pid, score) -> update(pid, score) })
            val actual = reducer.snapshot().entries

            assertEquals(
                "diverged at step $step",
                expected.map { Triple(it.playerId, it.score, it.rank) },
                actual.map { Triple(it.playerId, it.score, it.rank) },
            )
        }
    }

    @Test
    fun `agrees with a full re-sort when scores can also fall`() {
        // The reducer does not rely on monotonic input even though the engine guarantees it.
        val random = Random(99)
        val playerIds = (1..12).map { "p$it" }
        val reducer = LeaderboardReducer()
        val totals = HashMap<String, Long>()

        repeat(2_000) { step ->
            val id = playerIds[random.nextInt(playerIds.size)]
            val newTotal = (totals[id] ?: 50L) + random.nextInt(-5, 6)
            if (newTotal == totals[id]) return@repeat
            totals[id] = newTotal
            reducer.apply(update(id, newTotal))

            assertEquals(
                "diverged at step $step",
                RankingRules.rank(totals.map { (pid, score) -> update(pid, score) })
                    .map { Triple(it.playerId, it.score, it.rank) },
                reducer.snapshot().entries.map { Triple(it.playerId, it.score, it.rank) },
            )
        }
    }

    // --- behaviour --------------------------------------------------------------------------

    @Test
    fun `a repeated total is rejected so nothing downstream emits`() {
        val reducer = LeaderboardReducer()

        assertTrue(reducer.apply(update("a", 10L)))
        assertFalse(reducer.apply(update("a", 10L)))
        assertFalse(reducer.apply(update("a", 10L)))
        assertTrue(reducer.apply(update("a", 11L)))
    }

    @Test
    fun `version only advances on accepted updates`() {
        val reducer = LeaderboardReducer()
        reducer.applyAll("a" to 10L, "b" to 20L)
        val before = reducer.snapshot().version

        reducer.apply(update("a", 10L))

        assertEquals(before, reducer.snapshot().version)
    }

    @Test
    fun `ties share a rank and the next rank skips`() {
        val reducer = LeaderboardReducer()
        reducer.applyAll("a" to 100L, "b" to 100L, "c" to 90L)

        assertEquals(listOf("a" to 1, "b" to 1, "c" to 3), reducer.snapshot().ranks())
    }

    @Test
    fun `overtaking reorders the board`() {
        val reducer = LeaderboardReducer()
        reducer.applyAll("a" to 10L, "b" to 20L, "c" to 30L)
        assertEquals(listOf("c" to 1, "b" to 2, "a" to 3), reducer.snapshot().ranks())

        reducer.apply(update("a", 40L))

        assertEquals(listOf("a" to 1, "c" to 2, "b" to 3), reducer.snapshot().ranks())
    }

    @Test
    fun `the last updated player is reported`() {
        val reducer = LeaderboardReducer()
        reducer.applyAll("a" to 10L, "b" to 20L)

        assertEquals("b", reducer.snapshot().lastUpdatedPlayerId)
    }

    // --- movement ----------------------------------------------------------------------------

    @Test
    fun `a newcomer never reports movement`() {
        val reducer = LeaderboardReducer()
        reducer.applyAll("a" to 10L, "b" to 20L, "c" to 5L)

        reducer.snapshot().entries.forEach {
            assertEquals("${it.playerId} should not have moved", RankMovement.NONE, it.movement)
        }
    }

    @Test
    fun `overtaking reports UP for the climber and DOWN for the overtaken`() {
        val reducer = LeaderboardReducer()
        reducer.applyAll("a" to 10L, "b" to 20L)

        reducer.apply(update("a", 30L))
        val entries = reducer.snapshot().entries.associateBy(LeaderboardEntry::playerId)

        assertEquals(RankMovement.UP, entries.getValue("a").movement)
        assertEquals(RankMovement.DOWN, entries.getValue("b").movement)
    }

    @Test
    fun `movement lasts exactly one snapshot`() {
        val reducer = LeaderboardReducer()
        reducer.applyAll("a" to 10L, "b" to 20L, "c" to 1L)
        reducer.apply(update("a", 30L))

        // An unrelated player scores; last tick's arrows must not linger.
        reducer.apply(update("c", 2L))

        val entries = reducer.snapshot().entries.associateBy(LeaderboardEntry::playerId)
        assertEquals(RankMovement.NONE, entries.getValue("a").movement)
        assertEquals(RankMovement.NONE, entries.getValue("b").movement)
    }

    // --- the property the UI depends on ------------------------------------------------------

    @Test
    fun `untouched rows keep their exact instances so Compose can skip them`() {
        val reducer = LeaderboardReducer()
        reducer.applyAll("a" to 100L, "b" to 90L, "c" to 80L, "d" to 70L, "e" to 60L)
        val before = reducer.snapshot().entries.associateBy(LeaderboardEntry::playerId)

        // 'e' climbs one place over 'd'. 'a', 'b' and 'c' are untouched.
        reducer.apply(update("e", 75L))
        val after = reducer.snapshot().entries.associateBy(LeaderboardEntry::playerId)

        listOf("a", "b", "c").forEach { id ->
            assertSame(
                "$id was reallocated and will needlessly recompose",
                before.getValue(id),
                after.getValue(id),
            )
        }
        assertNotSame(before.getValue("e"), after.getValue("e"))
    }

    // --- the viewing player -------------------------------------------------------------------

    @Test
    fun `self is null until the viewing player scores`() {
        val reducer = LeaderboardReducer(currentPlayerId = "me")
        reducer.applyAll("a" to 10L, "b" to 20L)

        assertNull(reducer.snapshot().self)
    }

    @Test
    fun `self is the very same instance that appears in the list`() {
        // The pinned row and the in-list row must never be able to disagree.
        val reducer = LeaderboardReducer(currentPlayerId = "me")
        reducer.applyAll("a" to 30L, "me" to 20L, "b" to 10L)

        val snapshot = reducer.snapshot()
        assertSame(snapshot.entries.single { it.playerId == "me" }, snapshot.self)
    }

    @Test
    fun `self is ranked by the same rules as everyone else`() {
        val reducer = LeaderboardReducer(currentPlayerId = "me")
        reducer.applyAll("a" to 50L, "me" to 50L, "b" to 10L)

        val self = reducer.snapshot().self!!
        // Tied with 'a' on 50, so shares rank 1. No special treatment in either direction.
        assertEquals(1, self.rank)
    }

    @Test
    fun `self tracks the viewing player as they move`() {
        val reducer = LeaderboardReducer(currentPlayerId = "me")
        reducer.applyAll("a" to 30L, "b" to 20L, "me" to 10L)
        assertEquals(3, reducer.snapshot().self?.rank)

        reducer.apply(update("me", 40L))

        val self = reducer.snapshot().self!!
        assertEquals(1, self.rank)
        assertEquals(RankMovement.UP, self.movement)
    }

    @Test
    fun `a spectator view has no self`() {
        val reducer = LeaderboardReducer()
        reducer.applyAll("a" to 10L, "b" to 20L)

        assertNull(reducer.snapshot().self)
    }

    @Test
    fun `snapshots are immutable views that do not change underneath the caller`() {
        val reducer = LeaderboardReducer()
        reducer.applyAll("a" to 10L, "b" to 20L)
        val held = reducer.snapshot()

        reducer.apply(update("a", 999L))

        assertEquals(listOf("b" to 1, "a" to 2), held.ranks())
        assertEquals(listOf("a" to 1, "b" to 2), reducer.snapshot().ranks())
    }
}
