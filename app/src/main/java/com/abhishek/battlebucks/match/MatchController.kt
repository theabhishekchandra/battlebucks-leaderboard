package com.abhishek.battlebucks.match

import com.abhishek.battlebucks.engine.ScoreEngine
import com.abhishek.battlebucks.engine.ScoreEvent
import com.abhishek.battlebucks.leaderboard.LeaderboardEngine
import com.abhishek.battlebucks.leaderboard.LeaderboardState
import com.abhishek.battlebucks.leaderboard.ScoreUpdate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What a match is currently doing. */
enum class MatchStatus {
    /** Nothing running: either never started, or [MatchController.stop] was called. */
    Idle,

    /** Live and producing score events. */
    Running,

    /** Still exists and still holds its standings, but isn't generating anything. */
    Paused,

    /** The stream ended by itself, which happens when a bounded match hits its last event. */
    Finished,
}

/**
 * Owns the lifecycle of a live match, and is where the score engine meets the leaderboard.
 *
 * ### Why it exists
 *
 * This replaced a `MatchSession` that ran `stateIn(scope, Eagerly, …)` in its constructor. That
 * shape had exactly one behaviour, "a match starts when this object is built and ends when the
 * process dies", with no way to express anything else. Pausing had to be smuggled in as a
 * `Flow<Boolean>` threaded through the engine's constructor, and there was no way to even ask
 * whether a match was running, let alone stop or restart one.
 *
 * Now every reason a match might not be producing sits behind one object, with a [status] you can
 * observe and commands you can call. A season boundary, a reconnect or a "rematch" button becomes a
 * method call instead of a redesign.
 *
 * ### The seam between the two domain modules
 *
 * `:engine` doesn't know rankings exist, and `:leaderboard` doesn't know where its updates come
 * from. [toScoreUpdate] below is the only place they meet, and it lives here rather than in either
 * module. Swapping in a real WebSocket feed means passing a different [ScoreEngine] and writing a
 * different adapter. Neither domain module gets touched.
 *
 * ### Lifecycle
 *
 * [leaderboard] is a `MutableStateFlow` the controller writes to, not a `stateIn` of the upstream.
 * Without that, stopping isn't possible at all. `stateIn` ties a flow's life to a scope at
 * construction, while a job the controller holds can be cancelled and replaced.
 *
 * The standings survive a [pause]. They don't survive a [stop], which is intended. A stopped match
 * is gone, not minimised.
 *
 * ### Thread safety
 *
 * Every command is serialised by a [ReentrantLock], and so is the job-completion callback. That
 * isn't belt-and-braces. Each command is a check-then-act over several fields at once (status, the
 * running gate, the standings, the job handle), and none of them being individually atomic makes
 * the transition atomic. Two concurrent `start()` calls could both see `Idle`, both launch, and
 * leave one job orphaned, running forever with nothing holding its handle.
 *
 * A lock rather than an actor-style command channel. Commands stay synchronous that way, so
 * `status.value` is already correct by the time `pause()` returns. Posting to a channel would make
 * every command fire-and-forget and turn "pause, then read the status" into a race in the caller.
 *
 * The lock is only ever held for field assignments and a [CoroutineScope.launch], never across a
 * suspension point and never while waiting on the match itself, so it can't deadlock with the
 * coroutine it starts. It's reentrant because [restart] is built out of [stop] and [start], and
 * that composition should be atomic rather than a window a caller can see into.
 */
class MatchController(
    private val scoreEngine: ScoreEngine,
    private val leaderboardEngine: LeaderboardEngine,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val _status = MutableStateFlow(MatchStatus.Idle)
    val status: StateFlow<MatchStatus> = _status.asStateFlow()

    private val _leaderboard = MutableStateFlow(LeaderboardState.Empty)

    /**
     * The live standings.
     *
     * Application-scoped in practice, because the controller is. So rotation destroys and rebuilds
     * the Activity while this carries on, and a new collector gets the current standings straight
     * from the `StateFlow` cache. No reset, no
     * re-fetch, no flash of an empty list.
     */
    val leaderboard: StateFlow<LeaderboardState> = _leaderboard.asStateFlow()

    /**
     * Gates the engine. Separate from [status] so `Finished` and `Idle` stop the loop too, not just
     * `Paused`.
     */
    private val running = MutableStateFlow(false)

    private var job: Job? = null

    /** Guards every transition. See "Thread safety" above. */
    private val lock = ReentrantLock()

    /**
     * Starts a match from the beginning.
     *
     * Does nothing if one is already [MatchStatus.Running] or [MatchStatus.Paused]. Starting an
     * already-running match is a caller mistake, not a restart; use [restart] if that's what you
     * meant.
     *
     * Since [ScoreEngine.scoreEvents] is cold and seeded, a restarted match replays the same match
     * again. That comes from the simulator rather than from this class. Pass an engine with a
     * different seed if you want a different match.
     */
    fun start() = lock.withLock { startLocked() }

    /**
     * Stops generation while keeping the match and its standings.
     *
     * The engine parks on a suspension point, so a paused match costs nothing at all rather than
     * waking every tick to throw the result away. Compare [stop], which discards the match.
     */
    fun pause() = lock.withLock {
        if (_status.value != MatchStatus.Running) return@withLock
        running.value = false
        _status.value = MatchStatus.Paused
    }

    /** Picks a [MatchStatus.Paused] match up exactly where it left off. */
    fun resume() = lock.withLock {
        if (_status.value != MatchStatus.Paused) return@withLock
        running.value = true
        _status.value = MatchStatus.Running
    }

    /** Ends the match and throws away its standings. Safe to call more than once. */
    fun stop() = lock.withLock { stopLocked() }

    /** Ends the current match and starts a fresh one, as one atomic step. */
    fun restart() = lock.withLock {
        stopLocked()
        startLocked()
    }

    // --- transitions, all called with the lock held ----------------------------------------------

    private fun startLocked() {
        if (_status.value == MatchStatus.Running || _status.value == MatchStatus.Paused) return

        _leaderboard.value = LeaderboardState.Empty
        running.value = true
        _status.value = MatchStatus.Running

        val started = scope.launch(dispatcher) {
            leaderboardEngine
                .rank(scoreEngine.scoreEvents(runWhile = running).map(ScoreEvent::toScoreUpdate))
                .collect { _leaderboard.value = it }
        }
        job = started
        // Registered here rather than written at the end of the coroutine body, so the cancelled
        // path and the ran-to-completion path settle the state machine in one place.
        started.invokeOnCompletion { cause -> onMatchEnded(started, cause) }
    }

    private fun stopLocked() {
        // This can re-enter onMatchEnded synchronously on this thread. The lock is reentrant, and
        // that path does nothing once `job` has been cleared.
        job?.cancel()
        job = null
        running.value = false
        _leaderboard.value = LeaderboardState.Empty
        _status.value = MatchStatus.Idle
    }

    /**
     * Settles the state machine when a match ends, however it ended.
     *
     * Takes the lock like any command, because it races with them. Without it, a match completing
     * at the same moment as [stop] could write `Finished` over the `Idle` stop just set.
     */
    private fun onMatchEnded(ended: Job, cause: Throwable?) = lock.withLock {
        // A completion from a job we already replaced or discarded says nothing about the match
        // running now. Compare by identity: two matches are never the same job.
        if (job !== ended) return@withLock

        job = null
        running.value = false
        _status.value = if (cause == null) MatchStatus.Finished else MatchStatus.Idle
    }
}

/**
 * The engine-to-leaderboard adapter.
 *
 * Small by design. This is the entire price of keeping the two modules independent, and it's where
 * a real feed's payload would get translated instead.
 */
fun ScoreEvent.toScoreUpdate(): ScoreUpdate = ScoreUpdate(
    playerId = playerId,
    displayName = displayName,
    totalScore = totalScore,
)
