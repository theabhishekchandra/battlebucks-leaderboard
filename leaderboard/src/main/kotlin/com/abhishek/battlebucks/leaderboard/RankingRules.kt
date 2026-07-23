package com.abhishek.battlebucks.leaderboard

/**
 * The ranking rules, as pure functions. This is where ranking lives: not in the UI, not in a
 * ViewModel, not spread across both.
 *
 * The rules:
 *  1. Order by score, descending.
 *  2. Equal scores share a rank.
 *  3. The rank after a tie skips by the size of the tie group. `100, 100, 90` ranks `1, 1, 3`,
 *     never `1, 1, 2`. (Standard competition ranking.)
 *  4. Ties break by `playerId` ascending, for display order only. It doesn't affect rank, and it
 *     isn't cosmetic: without a deterministic tiebreak, two players on the same score swap places
 *     on every emission and the list jitters. That's the flickering the brief rules out, and you
 *     can't fix it with animation tuning. It has to be fixed in the ordering.
 */
object RankingRules {

    /** Display order: score descending, then id ascending so ties can't jitter. */
    val Order: Comparator<LeaderboardEntry> =
        compareByDescending<LeaderboardEntry> { it.score }.thenBy { it.playerId }

    /**
     * The rank of the row at [index], given the row directly above it.
     *
     * This one expression covers rules 2 and 3. A row's rank is its 1-based position, unless it
     * ties with the row above, in which case it inherits that rank. Positions swallowed by a tie
     * group get skipped on their own.
     *
     * @param previousScore score of the row above, or `null` for the first row.
     * @param previousRank rank of the row above. Ignored when [previousScore] is `null`.
     */
    fun rankAt(index: Int, score: Long, previousScore: Long?, previousRank: Int): Int =
        if (previousScore != null && previousScore == score) previousRank else index + 1

    /**
     * Ranks a whole set of standings from scratch, in `O(n log n)`.
     *
     * [LeaderboardReducer] is the incremental path used at runtime. This is the obvious version of
     * the same rules, and the tests check the two agree. Keeping a simple implementation next to
     * the optimised one is what makes the optimisation reviewable. Without it, "it's faster" is
     * just a claim nobody can check.
     */
    fun rank(standings: Collection<ScoreUpdate>): List<LeaderboardEntry> {
        require(standings.distinctBy(ScoreUpdate::playerId).size == standings.size) {
            "Standings must contain at most one entry per player"
        }
        val sorted = standings
            .map {
                LeaderboardEntry(
                    playerId = it.playerId,
                    displayName = it.displayName,
                    score = it.totalScore,
                    rank = 0,
                    previousRank = 0,
                )
            }
            .sortedWith(Order)

        val ranked = ArrayList<LeaderboardEntry>(sorted.size)
        sorted.forEachIndexed { index, entry ->
            val above = ranked.lastOrNull()
            val rank = rankAt(index, entry.score, above?.score, above?.rank ?: 0)
            ranked += entry.copy(rank = rank, previousRank = rank)
        }
        return ranked
    }
}
