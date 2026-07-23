package com.abhishek.battlebucks.leaderboard

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Turns a stream of score updates into a stream of ranked snapshots.
 *
 * This module consumes scores and never generates them. No timers, no randomness, no clock. Feed it
 * the same updates and you get the same states out, every time.
 */
class LeaderboardEngine(
    /** The viewing player, surfaced as [LeaderboardState.self]. `null` for a spectator view. */
    private val currentPlayerId: String? = null,
) {

    /**
     * Ranks [updates] into a live stream of snapshots.
     *
     * The reducer is created inside the [flow] builder, so every collector gets its own state. Two
     * subscribers can't corrupt each other and the operator is safe to reuse. Same confinement
     * trick the score engine uses.
     *
     * Only accepted updates emit. A repeated total gets dropped by [LeaderboardReducer.apply] and
     * never reaches the UI.
     */
    fun rank(updates: Flow<ScoreUpdate>): Flow<LeaderboardState> = flow {
        val reducer = LeaderboardReducer(currentPlayerId)
        emit(LeaderboardState.Empty)
        updates.collect { update ->
            if (reducer.apply(update)) {
                emit(reducer.snapshot())
            }
        }
    }
}
