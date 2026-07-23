package com.abhishek.battlebucks.engine

/**
 * One score award from the match engine. This is the engine's output contract, and consumers
 * depend on it and nothing else.
 *
 * @param delta how much was just awarded. Always at least 1, so scores can only rise.
 * @param totalScore the player's running total after [delta] was applied. Sending the total beats
 *   making every consumer accumulate deltas. A consumer that joins late, or drops an event under
 *   backpressure, still converges on the right value. Real leaderboard feeds send absolute values
 *   for the same reason.
 * @param sequence increases by one per match, starting at 0, so a consumer can spot gaps and throw
 *   away out-of-order or replayed events.
 * @param elapsedMillis virtual match time, added up from the generated intervals instead of read
 *   off a wall clock. Keeping the clock out of the engine is what makes a run reproducible from its
 *   seed and assertable under `runTest`.
 */
data class ScoreEvent(
    val playerId: String,
    val displayName: String,
    val delta: Int,
    val totalScore: Long,
    val sequence: Long,
    val elapsedMillis: Long,
)
