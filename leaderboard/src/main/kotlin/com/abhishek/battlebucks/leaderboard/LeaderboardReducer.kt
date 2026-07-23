package com.abhishek.battlebucks.leaderboard

/**
 * Keeps ranked leaderboard state up to date incrementally, one [ScoreUpdate] at a time.
 *
 * ### Why not just re-sort every event?
 *
 * A full `sortedWith` per event is `O(n log n)`, and more expensively it allocates `n` new row
 * objects every time. New objects aren't referentially equal to the old ones, so Compose can't skip
 * a single `LazyColumn` item and the whole visible list recomposes on every tick. Right answer,
 * wasted frames, and the "unnecessary recomputation" the brief warns about.
 *
 * ### What it does instead
 *
 * One update moves one player, so only the rows between that player's old and new position can be
 * affected:
 *
 *  - reposition: remove the player and binary-insert at the new spot. `O(n)` for the array shift,
 *    `O(log n)` comparisons, no sort.
 *  - re-rank the window only, from the lowest affected index forward, with an early exit that's
 *    provable rather than a guess (see [recomputeRanksFrom]).
 *  - leave rows outside the window as the same [LeaderboardEntry] instances, so they stay
 *    referentially equal and Compose skips them.
 *
 * ### Threading
 *
 * Not thread-safe, and it doesn't need to be. [LeaderboardEngine] builds one reducer per
 * collection, so an instance never leaves a single coroutine. Confinement beats locking here: no
 * synchronisation on a hot path, and no way to accidentally share one.
 *
 * ### Where this stops being the right shape
 *
 * Up to a few thousand rows. Past that the array shift dominates and the answer changes in kind, to
 * server-side ranking with a windowed top-N. See the Scaling section of the README.
 */
class LeaderboardReducer(
    /** The viewing player, surfaced as [LeaderboardState.self]. `null` for a spectator view. */
    private val currentPlayerId: String? = null,
) {

    /** Sorted by [RankingRules.Order]. Entries are immutable, so slots get replaced. */
    private val entries = ArrayList<LeaderboardEntry>()

    /** playerId to its index in [entries]. Updated on every structural change. */
    private val indexOf = HashMap<String, Int>()

    private var version = 0L
    private var lastUpdatedPlayerId: String? = null

    /**
     * Indices whose `previousRank` was set by the last [apply], so they still advertise a rank
     * movement that's now stale. Cleared at the start of the next accepted update, so a movement
     * arrow shows for exactly one snapshot instead of sticking around.
     */
    private var movementWindow: IntRange? = null

    /**
     * Folds [update] into the current state.
     *
     * @return `true` if anything observable changed. `false` means don't emit: a repeated total is
     *   a no-op and emitting it would wake the UI for nothing. That's the cheapest kind of
     *   recomposition to avoid, the one that never leaves the domain.
     */
    fun apply(update: ScoreUpdate): Boolean {
        val existingIndex = indexOf[update.playerId]
        if (existingIndex != null) {
            val current = entries[existingIndex]
            val unchanged = current.score == update.totalScore &&
                current.displayName == update.displayName
            if (unchanged) return false
        }

        // Has to happen before any reposition, while the recorded indices still point at the rows
        // they were recorded for.
        clearStaleMovement()

        version++
        lastUpdatedPlayerId = update.playerId
        movementWindow = if (existingIndex == null) insert(update) else move(existingIndex, update)
        return true
    }

    /**
     * An immutable snapshot of the current standings.
     *
     * The list is a new container holding the same [LeaderboardEntry] instances for untouched rows.
     * A reference copy, not a rebuild. `LazyColumn` can then skip unchanged items while
     * still receiving a properly immutable list.
     */
    fun snapshot(): LeaderboardState = LeaderboardState(
        entries = entries.toList(),
        // O(1) via the index map. Callers get the position, so nothing downstream has to scan the
        // board looking for "you". See LeaderboardState.selfIndex.
        selfIndex = currentPlayerId?.let(indexOf::get) ?: -1,
        lastUpdatedPlayerId = lastUpdatedPlayerId,
        version = version,
    )

    // --- internals ---------------------------------------------------------------------------

    /** A player's first appearance. Happens once per player, not once per event. */
    private fun insert(update: ScoreUpdate): IntRange {
        val entry = LeaderboardEntry(
            playerId = update.playerId,
            displayName = update.displayName,
            score = update.totalScore,
            rank = 0,
            previousRank = 0,
        )
        val at = insertionPoint(entry)
        entries.add(at, entry)
        // An insert shifts every later index by one, so unlike a move it can't be windowed.
        for (i in at until entries.size) indexOf[entries[i].playerId] = i

        val lastTouched = recomputeRanksFrom(low = at, movedHigh = entries.size - 1)
        // A newcomer has no history, so it mustn't render as having moved.
        entries[at] = entries[at].let { it.copy(previousRank = it.rank) }
        return at..lastTouched
    }

    /** An existing player's total changed, so move them and re-rank the affected window. */
    private fun move(oldIndex: Int, update: ScoreUpdate): IntRange {
        val updated = entries[oldIndex].copy(
            displayName = update.displayName,
            score = update.totalScore,
        )
        entries.removeAt(oldIndex)
        val newIndex = insertionPoint(updated)
        entries.add(newIndex, updated)

        val low = minOf(oldIndex, newIndex)
        val high = maxOf(oldIndex, newIndex)
        // Only [low, high] shifted. Indices outside it are still correct.
        for (i in low..high) indexOf[entries[i].playerId] = i

        return low..recomputeRanksFrom(low = low, movedHigh = high)
    }

    /**
     * Recomputes ranks from [low] forward, returning the highest index it actually rewrote.
     *
     * About the early exit: `rank[i]` depends only on `i`, `score[i]`, `score[i-1]` and `rank[i-1]`
     * (see [RankingRules.rankAt]). Past [movedHigh] nothing changed, so once a recomputed rank
     * matches what's already stored there, `rank[i-1]` and every later score are unchanged too, and
     * by induction no rank after that point can differ. So stopping is safe, not a heuristic.
     */
    private fun recomputeRanksFrom(low: Int, movedHigh: Int): Int {
        var lastTouched = movedHigh
        var i = low
        while (i < entries.size) {
            val above = if (i == 0) null else entries[i - 1]
            val current = entries[i]
            val rank = RankingRules.rankAt(
                index = i,
                score = current.score,
                previousScore = above?.score,
                previousRank = above?.rank ?: 0,
            )
            if (rank != current.rank) {
                entries[i] = current.copy(rank = rank, previousRank = current.rank)
                if (i > lastTouched) lastTouched = i
            } else if (i > movedHigh) {
                break
            }
            i++
        }
        return lastTouched
    }

    /** Where [entry] belongs in the sorted list. It must not currently be in [entries]. */
    private fun insertionPoint(entry: LeaderboardEntry): Int {
        val result = entries.binarySearch(entry, RankingRules.Order)
        // (score, playerId) is unique per player so a hit is impossible, but handle it anyway.
        return if (result >= 0) result else -result - 1
    }

    /** Retire the previous update's rank arrows so each one lasts exactly one snapshot. */
    private fun clearStaleMovement() {
        val window = movementWindow ?: return
        for (i in window) {
            val entry = entries[i]
            if (entry.previousRank != entry.rank) {
                entries[i] = entry.copy(previousRank = entry.rank)
            }
        }
        movementWindow = null
    }
}
