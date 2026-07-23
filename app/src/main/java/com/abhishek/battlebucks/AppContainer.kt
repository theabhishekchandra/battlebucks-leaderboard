package com.abhishek.battlebucks

import com.abhishek.battlebucks.engine.MatchConfig
import com.abhishek.battlebucks.engine.ScoreEngine
import com.abhishek.battlebucks.engine.SimulatedScoreEngine
import com.abhishek.battlebucks.engine.defaultRoster
import com.abhishek.battlebucks.leaderboard.LeaderboardEngine
import com.abhishek.battlebucks.match.MatchController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Hand-rolled dependency container, and the owner of the running match.
 *
 * Four objects to construct and one place that constructs them. Hilt would add a plugin, an
 * annotation processor and build time to solve a problem this project doesn't have, and the brief
 * warns against over-engineering. As soon as there are several feature modules or scoped bindings
 * this stops being the right answer and Hilt starts being one.
 *
 * ### Ownership
 *
 * The container creates and owns a [CoroutineScope], and that scope keeps a match running for as
 * long as the container is alive. Owning a scope means being responsible for ending it, so the
 * container is [AutoCloseable] and [close] cancels it. Ordinary rule: whoever builds the scope
 * cancels it, nobody else.
 *
 * That matters more than it looks. This scope used to be created and never cancelled, justified by
 * a comment saying there was only ever one. Nothing enforced that. Anything constructing a second
 * container quietly started a second match that ran forever, with no way to stop it. A unit test
 * would do it, and so would some future multi-window path. A comment isn't a lifetime. [close] is.
 *
 * In production there's exactly one, built by [BattleBucksApp], and it lives until the process
 * dies. I deliberately didn't override `Application.onTerminate` to call [close]. It doesn't run on
 * real devices, so it would be code that looks like cleanup while guaranteeing nothing. Process
 * death is the real teardown. [close] exists so every other caller has a correct way to stop. It
 * also lets this class be tested without leaking a coroutine per test.
 *
 * @param dispatcher where the match runs. [Dispatchers.Default], because ranking is CPU work with
 *   no business on the main thread. Overridable so tests can drive it on virtual time.
 */
class AppContainer(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    matchConfig: MatchConfig = MatchConfig(),
) : AutoCloseable {

    /**
     * Outlives every screen, because the match does. [SupervisorJob] so one failing collector can't
     * take the whole match down with it.
     */
    private val appScope = CoroutineScope(SupervisorJob() + dispatcher)

    private val roster = defaultRoster(size = ROSTER_SIZE)

    /**
     * Who "you" are. Hard-coded because there's no sign-in in this exercise; in a real build it
     * would come from the session or auth layer. Everything downstream already treats it as data.
     *
     * Looked up by id and allowed to be `null`, rather than found by display name and asserted. A
     * roster change should fall back to the spectator view, not crash the app on launch.
     */
    private val currentPlayerId: String? = roster.firstOrNull { it.id == CURRENT_PLAYER_ID }?.id

    private val scoreEngine: ScoreEngine = SimulatedScoreEngine(
        players = roster,
        config = matchConfig,
    )

    val matchController: MatchController = MatchController(
        scoreEngine = scoreEngine,
        leaderboardEngine = LeaderboardEngine(currentPlayerId = currentPlayerId),
        scope = appScope,
        dispatcher = dispatcher,
    )

    init {
        // Starting a match is an explicit act now, not a side effect of construction.
        matchController.start()
    }

    /**
     * Ends the match and releases the scope. Safe to call twice, since cancelling an
     * already-cancelled scope does nothing, so callers don't have to track whether they closed it.
     *
     * The container isn't reusable afterwards. Restarting a match is [MatchController]'s job and is
     * supported; restarting a container isn't, because the scope it owned is gone. Keeping those
     * two lifetimes separate is the whole reason the controller exists.
     */
    override fun close() {
        matchController.stop()
        appScope.cancel()
    }

    private companion object {
        const val ROSTER_SIZE = 15

        /** "Abhishek Chandra" in the default roster. */
        const val CURRENT_PLAYER_ID = "p7"
    }
}
