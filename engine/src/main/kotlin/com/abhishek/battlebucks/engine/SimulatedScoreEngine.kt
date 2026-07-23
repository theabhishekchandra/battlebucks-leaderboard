package com.abhishek.battlebucks.engine

import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * Simulates a game backend: random players scoring random amounts at random intervals.
 *
 * Can be paused mid-match without losing anything. See [ScoreEngine.scoreEvents].
 *
 * All the generator state (the RNG, the running totals, the sequence counter) is created inside the
 * [flow] builder, so it's confined to a single collection. Two collectors get two separate matches
 * and neither can see or corrupt the other's state. So it's reusable without a
 * single lock or `@Volatile`, and it avoids the usual "shared engine, mysteriously interleaved
 * output" bugs.
 */
class SimulatedScoreEngine(
    override val players: List<Player>,
    private val config: MatchConfig = MatchConfig(),
) : ScoreEngine {

    init {
        require(players.isNotEmpty()) { "A match needs at least one player" }
        require(players.distinctBy(Player::id).size == players.size) {
            "Player ids must be unique"
        }
    }

    override fun scoreEvents(runWhile: Flow<Boolean>): Flow<ScoreEvent> = flow {
        val random = Random(config.seed)
        val totals = LongArray(players.size)
        var sequence = 0L
        var elapsedMillis = 0L

        while (config.maxEvents == null || sequence < config.maxEvents) {
            // Park here while the match is paused. Suspending before scheduling the delay is what
            // makes this cheap. A paused match costs nothing, instead of waking every tick just to
            // throw the result away.
            //
            // This is a pause, not a stop. The RNG, the totals and the sequence counter are locals
            // of this coroutine, so they stay put and the match picks up mid-stride. The obvious
            // alternative, cancelling the collection and restarting it later, would re-run this
            // cold flow from its seed with every total back at zero, and the scores on screen
            // would collapse to nothing.
            runWhile.first { it }

            val wait = random.nextInclusive(config.tickInterval)
            // A real suspension, not a UI timer. `runTest` skips it with virtual time, so a
            // 10,000-event test still finishes in milliseconds.
            delay(wait)
            elapsedMillis += wait

            val index = random.nextInt(players.size)
            val delta = random.nextInclusive(config.deltaSteps) * config.scoreStep
            totals[index] += delta

            val player = players[index]
            emit(
                ScoreEvent(
                    playerId = player.id,
                    displayName = player.displayName,
                    delta = delta,
                    totalScore = totals[index],
                    sequence = sequence,
                    elapsedMillis = elapsedMillis,
                ),
            )
            sequence++
        }
    }
}

/** [Random.nextLong] is half-open, but the config reads better as an inclusive range. */
private fun Random.nextInclusive(range: LongRange): Long =
    if (range.first == range.last) range.first else nextLong(range.first, range.last + 1)

/** Same again for [Random.nextInt]. */
private fun Random.nextInclusive(range: IntRange): Int =
    if (range.first == range.last) range.first else nextInt(range.first, range.last + 1)
