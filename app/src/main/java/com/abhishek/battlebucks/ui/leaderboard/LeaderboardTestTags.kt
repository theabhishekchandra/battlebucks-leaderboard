package com.abhishek.battlebucks.ui.leaderboard

/**
 * Handles for the UI tests to grab.
 *
 * Only for things a test can't reach by what the user actually sees. Rows, ranks and scores get
 * found by their text, since that's what a person reads. A test that asserts on text breaks when
 * the screen stops saying what it should, which is the behaviour I want from it. The scrolling list
 * and the docked row have no text of their own, so they get tags.
 */
internal object LeaderboardTestTags {
    const val LIST = "leaderboard_list"
    const val HERO = "hero_panel"
    const val SELF_DOCK = "self_dock"
}
