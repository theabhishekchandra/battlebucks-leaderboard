package com.abhishek.battlebucks.ui.leaderboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abhishek.battlebucks.BattleBucksApp
import com.abhishek.battlebucks.leaderboard.LeaderboardEntry
import com.abhishek.battlebucks.leaderboard.LeaderboardState
import com.abhishek.battlebucks.match.MatchController
import com.abhishek.battlebucks.match.MatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Everything the leaderboard screen renders, and nothing else. */
data class LeaderboardUiState(
    /**
     * The full ranked board, viewing player included.
     *
     * You're an ordinary row that slides as you overtake people, because you're ranked by the same
     * rules as everyone else. The screen docks a copy to the bottom only while that row is scrolled
     * out of view. See `LeaderboardScreen`.
     */
    val entries: List<LeaderboardEntry>,
    /** The viewing player's row. The same instance that appears in [entries]. */
    val self: LeaderboardEntry?,
    val updateCount: Long,
    val isWaitingForFirstScore: Boolean,
    /** The match exists but isn't generating anything, usually because the app is backgrounded. */
    val isPaused: Boolean,
) {
    companion object {
        val Initial = LeaderboardUiState(
            entries = emptyList(),
            self = null,
            updateCount = 0L,
            isWaitingForFirstScore = true,
            isPaused = false,
        )
    }
}

/**
 * Adapts the live leaderboard for one screen.
 *
 * Worth noticing what isn't here: no sorting, no rank assignment, no tie handling, no timer, no
 * `MutableStateFlow` being poked from a `launch`. Ranking lives in `:leaderboard`, as a pure
 * function with unit tests and no Android on the classpath. Delete this ViewModel and the
 * leaderboard would still be correct and still be tested.
 *
 * What it does own: turning domain state into what this screen needs, and scoping that work to the
 * screen's lifetime.
 */
class LeaderboardViewModel(matchController: MatchController) : ViewModel() {

    val uiState: StateFlow<LeaderboardUiState> = combine(
        matchController.leaderboard,
        matchController.status,
        ::toUiState,
    )
        // viewModelScope is Dispatchers.Main.immediate, so without this the mapping would run on
        // the main thread on every tick. It's cheap today, but moving it off-main by construction
        // means it stays cheap when the roster isn't 15 people.
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            // Keep mapping for a few seconds after the UI stops, so a rotation doesn't tear the
            // subscription down and rebuild it a moment later. The match is application-scoped, so
            // it's unaffected either way.
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            // Seeded from the current standings rather than an empty board, so a screen rebuilt by
            // rotation draws live state on its first frame instead of flashing empty.
            initialValue = toUiState(
                matchController.leaderboard.value,
                matchController.status.value,
            ),
        )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        // A ViewModelProvider.Factory rather than constructor injection, because the framework
        // instantiates ViewModels. `initializer` reaches the Application through CreationExtras, so
        // nothing static and nothing Context-holding is needed.
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as BattleBucksApp
                LeaderboardViewModel(app.container.matchController)
            }
        }
    }
}

private fun toUiState(state: LeaderboardState, status: MatchStatus) = LeaderboardUiState(
    entries = state.entries,
    self = state.self,
    updateCount = state.version,
    isWaitingForFirstScore = state.entries.isEmpty(),
    isPaused = status == MatchStatus.Paused,
)
