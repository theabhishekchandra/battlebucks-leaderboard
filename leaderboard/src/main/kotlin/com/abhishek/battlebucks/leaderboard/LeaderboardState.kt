package com.abhishek.battlebucks.leaderboard

/** Which way a player moved since the previous emission. */
enum class RankMovement { UP, DOWN, NONE }

/**
 * One row of the leaderboard.
 *
 * [previousRank] sits in the domain model, not the UI. The alternative is letting the UI keep the
 * last list and diff it to work out what moved. That puts state and logic in the view layer, gets
 * the animation wrong after any recreation such as rotation or process death, and can't be tested.
 * Computing it where the ranks are computed means the UI just gets handed the answer.
 */
data class LeaderboardEntry(
    val playerId: String,
    val displayName: String,
    val score: Long,
    val rank: Int,
    val previousRank: Int,
) {
    val movement: RankMovement
        get() = when {
            rank < previousRank -> RankMovement.UP
            rank > previousRank -> RankMovement.DOWN
            else -> RankMovement.NONE
        }
}

/**
 * An immutable snapshot of the leaderboard.
 *
 * @param entries ordered best-to-worst; safe to render directly.
 * @param selfIndex where the viewing player sits in [entries], or `-1` if they haven't scored yet
 *   (or nobody is "self", as in a spectator view).
 *
 *   A position rather than just the row, because callers usually need both. Handing over only the
 *   row would push every caller that wants to pin it, scroll to it or render around it into an
 *   `indexOf` scan of the whole board on every update. The reducer knows the index in O(1).
 *   Throwing that away and making callers rediscover it is the expensive kind of tidy.
 * @param lastUpdatedPlayerId who caused this snapshot, so a consumer can react to that player
 *   without diffing anything. `null` only for [Empty].
 * @param version goes up by one per accepted update. Gives the UI a cheap "did anything actually
 *   change" check, and makes snapshots tellable apart in tests and logs.
 */
data class LeaderboardState(
    val entries: List<LeaderboardEntry>,
    val selfIndex: Int,
    val lastUpdatedPlayerId: String?,
    val version: Long,
) {
    /**
     * The viewing player's row, or `null` if they haven't scored yet.
     *
     * Derived from [selfIndex] rather than stored next to it, so the two can't disagree. It's the
     * same instance that appears in [entries]. You're ranked by the same rules as everyone else,
     * and the domain only says which row is yours.
     */
    val self: LeaderboardEntry? get() = entries.getOrNull(selfIndex)

    companion object {
        val Empty = LeaderboardState(
            entries = emptyList(),
            selfIndex = -1,
            lastUpdatedPlayerId = null,
            version = 0L,
        )
    }
}
