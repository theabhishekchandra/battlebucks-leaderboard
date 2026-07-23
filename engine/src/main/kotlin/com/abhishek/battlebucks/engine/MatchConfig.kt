package com.abhishek.battlebucks.engine

/**
 * Tuning for a simulated match.
 *
 * Every knob that changes the output lives here rather than being hard-coded. A test can make the
 * engine fast, bounded and predictable without going near production code paths.
 *
 * @param seed this is what "deterministic per session" comes down to. Two engines built with the
 *   same seed emit identical event sequences. Handy for reproducing a bug report from a session id,
 *   and it makes the tests exact instead of statistical.
 * @param tickInterval bounds (inclusive) for the gap between score events.
 * @param scoreDelta bounds (inclusive) for a single award. The lower bound must be `>= 1`, and both
 *   bounds must be whole multiples of [scoreStep].
 * @param scoreStep how coarse an award is. Trophies in this game come in round amounts, so awards
 *   land on multiples of this rather than arbitrary integers, and totals read like `1,450` instead
 *   of `1,437`. Set it to 1 if you don't want that.
 * @param maxEvents stop after this many events; `null` runs until the collector cancels.
 */
data class MatchConfig(
    val seed: Long = DEFAULT_SEED,
    val tickInterval: LongRange = 500L..2_000L,
    val scoreDelta: IntRange = 50..500,
    val scoreStep: Int = 50,
    val maxEvents: Int? = null,
) {
    init {
        require(!tickInterval.isEmpty()) { "tickInterval must not be empty" }
        require(tickInterval.first >= 0) { "tickInterval must be non-negative" }
        require(!scoreDelta.isEmpty()) { "scoreDelta must not be empty" }
        // "Scores only increase" is checked here rather than trusted. A zero or negative delta
        // would otherwise break it quietly, thousands of events later.
        require(scoreDelta.first >= 1) {
            "scoreDelta must be strictly positive to keep scores monotonic"
        }
        require(scoreStep >= 1) { "scoreStep must be at least 1" }
        // Without this, a delta rounded to the step could land outside scoreDelta, breaking a
        // bound the rest of the system is entitled to rely on.
        require(scoreDelta.first % scoreStep == 0 && scoreDelta.last % scoreStep == 0) {
            "scoreDelta bounds (${scoreDelta.first}..${scoreDelta.last}) must be multiples of " +
                "scoreStep ($scoreStep)"
        }
        require(maxEvents == null || maxEvents > 0) { "maxEvents must be positive when set" }
    }

    /** The award range expressed in steps, which is what the generator actually draws from. */
    internal val deltaSteps: IntRange
        get() = (scoreDelta.first / scoreStep)..(scoreDelta.last / scoreStep)

    companion object {
        const val DEFAULT_SEED: Long = 20_260_722L
    }
}
