package com.abhishek.battlebucks.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Where score events come from. Stands in for a game backend.
 *
 * The app depends on this interface rather than on [SimulatedScoreEngine], so swapping the
 * simulation for a real WebSocket feed later means changing one line of wiring.
 */
interface ScoreEngine {

    /** The match roster. Fixed for the lifetime of the engine. */
    val players: List<Player>

    /**
     * A cold stream of score events.
     *
     * Cold by design. Every collection starts its own independent match from the seed. That buys
     * three things. Collectors share no mutable state, so the engine is safe to reuse. A test can
     * collect it under `runTest` and get virtual-time delays for free. And restarting a match is
     * just collecting again.
     *
     * Making it hot (one shared live match) depends on the app's scope, so that decision belongs to
     * the composition root instead. See `MatchController` in `:app`.
     *
     * @param runWhile gates generation. While this emits `false` the match is paused: the producer
     *   parks on a suspension point and schedules nothing at all, so no timers and no wake-ups.
     *
     *   It's a parameter of the collection rather than of the engine. Whether a match may run right
     *   now is a property of this run, not of the thing producing it. It also puts every reason a
     *   match might be paused in one place, with the caller driving the collection, instead of some
     *   in a constructor and others at the call site.
     */
    fun scoreEvents(runWhile: Flow<Boolean> = flowOf(true)): Flow<ScoreEvent>
}
