package com.abhishek.battlebucks.ui.leaderboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the score counter against narrowing a `Long`.
 *
 * The counter used to animate `score.toInt()`, which wraps to a negative number above
 * `Int.MAX_VALUE`. These tests pin the values that used to be wrong, so the regression cannot come
 * back quietly the next time somebody reaches for `animateIntAsState`.
 */
class AnimatedScoreTest {

    @Test
    fun `the endpoints are exact`() {
        assertEquals(1_000L, interpolateScore(from = 1_000L, to = 5_000L, progress = 0f))
        assertEquals(5_000L, interpolateScore(from = 1_000L, to = 5_000L, progress = 1f))
    }

    @Test
    fun `progress outside 0 to 1 clamps rather than overshooting`() {
        assertEquals(1_000L, interpolateScore(1_000L, 5_000L, progress = -0.5f))
        assertEquals(5_000L, interpolateScore(1_000L, 5_000L, progress = 3f))
    }

    @Test
    fun `it interpolates linearly in between`() {
        assertEquals(3_000L, interpolateScore(1_000L, 5_000L, progress = 0.5f))
        assertEquals(2_000L, interpolateScore(1_000L, 5_000L, progress = 0.25f))
    }

    @Test
    fun `a score beyond Int MAX_VALUE survives intact`() {
        val huge = Int.MAX_VALUE.toLong() + 1_000L // 2,147,484,647

        // The bug this replaces: narrowing to Int wraps this to a large negative number.
        assertEquals(-2_147_482_649, huge.toInt())

        assertEquals(huge, interpolateScore(from = 0L, to = huge, progress = 1f))
        assertTrue(interpolateScore(from = 0L, to = huge, progress = 0.5f) > 0L)
    }

    @Test
    fun `a score far beyond Int range stays exact and monotonic`() {
        val from = 9_000_000_000L
        val to = 9_000_000_500L

        assertEquals(to, interpolateScore(from, to, progress = 1f))
        // Float would have stopped resolving consecutive integers long before this magnitude;
        // interpolating in Double keeps every step distinct and ordered.
        val midpoint = interpolateScore(from, to, progress = 0.5f)
        assertEquals(9_000_000_250L, midpoint)
        assertTrue(midpoint in from..to)
    }

    @Test
    fun `counting never runs backwards for an increasing score`() {
        val from = 1_050L
        val to = 1_600L
        var previous = from

        // 220 ms at 60 fps is ~14 frames; sample far more finely than that.
        repeat(101) { step ->
            val value = interpolateScore(from, to, progress = step / 100f)
            assertTrue("went backwards at step $step: $previous -> $value", value >= previous)
            previous = value
        }
        assertEquals(to, previous)
    }

    @Test
    fun `a decreasing score interpolates downwards without wrapping`() {
        // Scores only increase in this game, but the helper must not blow up if that ever changes.
        assertEquals(750L, interpolateScore(from = 1_000L, to = 500L, progress = 0.5f))
    }
}
