package com.abhishek.battlebucks.leaderboard

/**
 * What this module takes in: "this player's total is now this".
 *
 * It never imports the engine's `ScoreEvent`. Declaring its own input type is what
 * keeps the two modules apart. The leaderboard can be driven by the simulator, a WebSocket feed, a
 * REST poll or a test fixture, without being recompiled or even knowing they exist. The adapter
 * lives in the composition root, in `:app`.
 *
 * [totalScore] is absolute rather than a delta, so a consumer that misses an update still lands on
 * the right answer instead of drifting forever.
 */
data class ScoreUpdate(val playerId: String, val displayName: String, val totalScore: Long)
