package com.abhishek.battlebucks.leaderboard

import org.junit.Assert.assertEquals
import org.junit.Test

class RankingRulesTest {

    private fun standings(vararg pairs: Pair<String, Long>) =
        pairs.map { (id, score) -> ScoreUpdate(id, "Name $id", score) }

    private fun ranksOf(vararg pairs: Pair<String, Long>) =
        RankingRules.rank(standings(*pairs)).map(LeaderboardEntry::rank)

    private fun orderOf(vararg pairs: Pair<String, Long>) =
        RankingRules.rank(standings(*pairs)).map(LeaderboardEntry::playerId)

    @Test
    fun `sorted by score descending`() {
        assertEquals(listOf("b", "c", "a"), orderOf("a" to 10L, "b" to 30L, "c" to 20L))
        assertEquals(listOf(1, 2, 3), ranksOf("a" to 10L, "b" to 30L, "c" to 20L))
    }

    @Test
    fun `tied scores share a rank and the next rank skips`() {
        // 100, 100, 90 -> 1, 1, 3. Not 1, 1, 2.
        assertEquals(listOf(1, 1, 3), ranksOf("a" to 100L, "b" to 100L, "c" to 90L))
    }

    @Test
    fun `a tie in the middle skips by the size of the tie group`() {
        assertEquals(listOf(1, 2, 2, 4), ranksOf("a" to 50L, "b" to 40L, "c" to 40L, "d" to 30L))
    }

    @Test
    fun `a three-way tie skips three places`() {
        assertEquals(
            listOf(1, 2, 2, 2, 5),
            ranksOf("a" to 90L, "b" to 80L, "c" to 80L, "d" to 80L, "e" to 10L),
        )
    }

    @Test
    fun `everyone tied is all rank one`() {
        assertEquals(listOf(1, 1, 1, 1), ranksOf("a" to 5L, "b" to 5L, "c" to 5L, "d" to 5L))
    }

    @Test
    fun `a trailing tie still shares a rank`() {
        assertEquals(listOf(1, 2, 2), ranksOf("a" to 9L, "b" to 3L, "c" to 3L))
    }

    @Test
    fun `tied rows order deterministically by player id`() {
        // Without this, equal-score rows swap on every emission and the list visibly jitters.
        val forwards = orderOf("zed" to 10L, "amy" to 10L, "mia" to 10L)
        val backwards = orderOf("mia" to 10L, "zed" to 10L, "amy" to 10L)

        assertEquals(listOf("amy", "mia", "zed"), forwards)
        assertEquals(forwards, backwards)
    }

    @Test
    fun `empty standings rank to an empty list`() {
        assertEquals(emptyList<LeaderboardEntry>(), RankingRules.rank(emptyList()))
    }

    @Test
    fun `a single player is rank one`() {
        assertEquals(listOf(1), ranksOf("solo" to 0L))
    }

    @Test
    fun `a freshly ranked list reports no movement`() {
        RankingRules.rank(standings("a" to 3L, "b" to 2L)).forEach {
            assertEquals(RankMovement.NONE, it.movement)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate players are rejected`() {
        RankingRules.rank(standings("a" to 1L, "a" to 2L))
    }
}
