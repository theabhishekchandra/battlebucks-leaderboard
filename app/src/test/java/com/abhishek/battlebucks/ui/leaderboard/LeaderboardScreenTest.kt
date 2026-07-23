package com.abhishek.battlebucks.ui.leaderboard

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onNodeWithTag
import com.abhishek.battlebucks.leaderboard.LeaderboardEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests on the JVM under Robolectric, so no emulator and they run in the same CI step
 * as everything else rather than a job nobody waits for.
 *
 * They cover the rules a screenshot can't: that your own row docks only when it's off screen, that
 * ties render a shared rank with the next one skipped, and that the paused and waiting states say
 * what they claim. The count-up bug earlier in this project is the kind of fault that slips through
 * when the only UI coverage is a human looking at it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp") // a normal phone, so "is it on screen" means something
class LeaderboardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun entry(
        id: String,
        name: String,
        score: Long,
        rank: Int,
        previousRank: Int = rank,
    ) = LeaderboardEntry(id, name, score, rank, previousRank)

    /** Ranks 1..n with distinct scores; the last-placed player is "you" by default. */
    private fun board(size: Int = BOARD_SIZE) = List(size) { index ->
        entry(
            id = "p${index + 1}",
            name = "Player ${index + 1}",
            score = (size - index) * 100L,
            rank = index + 1,
        )
    }

    private fun state(
        entries: List<LeaderboardEntry> = board(),
        self: LeaderboardEntry? = entries.lastOrNull(),
        isPaused: Boolean = false,
        isWaiting: Boolean = false,
    ) = LeaderboardUiState(
        entries = entries,
        self = self,
        updateCount = entries.size.toLong(),
        isWaitingForFirstScore = isWaiting,
        isPaused = isPaused,
    )

    private fun setScreen(state: LeaderboardUiState) {
        compose.setContent {
            LeaderboardScreen(state = state, modifier = Modifier.fillMaxSize())
        }
    }

    // --- the docking rule -----------------------------------------------------------------------

    @Test
    fun `your row is rendered once, not duplicated, while it is on screen`() {
        val entries = board()
        setScreen(state(entries, self = entries.last()))

        // Scroll the list until your row is visible; the dock must then get out of the way.
        compose.onNodeWithTag(LeaderboardTestTags.LIST).performScrollToIndex(SELF_ITEM_INDEX)
        compose.waitForIdle()

        compose.onNodeWithText(SELF_NAME).assertIsDisplayed()
        compose.onAllNodesWithText(SELF_NAME).assertCountEquals(1)
        compose.onNodeWithTag(LeaderboardTestTags.SELF_DOCK).assertDoesNotExist()
    }

    @Test
    fun `your row docks to the bottom once it is scrolled out of view`() {
        val entries = board()
        setScreen(state(entries, self = entries.last()))

        // At the top of the board, the last-placed row is far below the fold.
        compose.onNodeWithTag(LeaderboardTestTags.LIST).performScrollToIndex(0)
        compose.waitForIdle()

        compose.onNodeWithTag(LeaderboardTestTags.SELF_DOCK).assertIsDisplayed()
        compose.onNodeWithText(SELF_NAME).assertIsDisplayed()
    }

    @Test
    fun `a spectator with no row of their own never gets a dock`() {
        setScreen(state(self = null))

        compose.onNodeWithTag(LeaderboardTestTags.SELF_DOCK).assertDoesNotExist()
    }

    // --- adapting to the viewport ---------------------------------------------------------------

    @Test
    fun `a tall viewport shows the hero panel`() {
        setScreen(state())

        compose.onNodeWithTag(LeaderboardTestTags.HERO).assertExists()
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp") // landscape phone
    fun `a short viewport drops the hero and still lists players`() {
        // The hero is 260dp of branding. On a landscape phone that leaves room for roughly one
        // row, so it goes and the compact summary in the season bar carries your rank instead.
        setScreen(state())

        compose.onNodeWithTag(LeaderboardTestTags.HERO).assertDoesNotExist()
        compose.onNodeWithText("Player 1").assertIsDisplayed()
        compose.onNodeWithText("Season ends in 60 days").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp")
    fun `a short viewport still ranks correctly`() {
        val entries = listOf(
            entry("a", "Alpha", 300, rank = 1),
            entry("b", "Bravo", 200, rank = 2),
            entry("c", "Charlie", 200, rank = 2),
            entry("d", "Delta", 100, rank = 4),
        )
        setScreen(state(entries, self = null))

        compose.onAllNodesWithText("2.").assertCountEquals(2)
        compose.onAllNodesWithText("3.").assertCountEquals(0)
    }

    // --- the ranking rules, as rendered ----------------------------------------------------------

    @Test
    fun `tied players render a shared rank and the next rank skips`() {
        // 1, 2, 2, 4. Easy to get wrong, and invisible in a unit test.
        val entries = listOf(
            entry("a", "Alpha", 300, rank = 1),
            entry("b", "Bravo", 200, rank = 2),
            entry("c", "Charlie", 200, rank = 2),
            entry("d", "Delta", 100, rank = 4),
        )
        setScreen(state(entries, self = null))

        compose.onAllNodesWithText("2.").assertCountEquals(2)
        compose.onNodeWithText("4.").assertIsDisplayed()
        compose.onAllNodesWithText("3.").assertCountEquals(0)
    }

    @Test
    fun `scores render with thousands separators`() {
        val entries = listOf(entry("a", "Alpha", 3_200, rank = 1))
        setScreen(state(entries, self = null))

        compose.onNodeWithText("3,200").assertIsDisplayed()
    }

    // --- states ----------------------------------------------------------------------------------

    @Test
    fun `the waiting state is shown before the first score`() {
        setScreen(state(entries = emptyList(), self = null, isWaiting = true))

        compose.onNodeWithText("Waiting for the first score…").assertIsDisplayed()
    }

    @Test
    fun `a paused match says so instead of pretending the feed is live`() {
        setScreen(state(isPaused = true))

        compose.onNodeWithText("Match paused").assertIsDisplayed()
        compose.onAllNodesWithText("Season ends in 60 days").assertCountEquals(0)
    }

    @Test
    fun `a running match shows the season countdown`() {
        setScreen(state(isPaused = false))

        compose.onNodeWithText("Season ends in 60 days").assertIsDisplayed()
    }

    private companion object {
        const val BOARD_SIZE = 15
        const val SELF_NAME = "Player $BOARD_SIZE"

        /** Two header items sit above the player rows on a tall viewport: hero, heading. */
        const val SELF_ITEM_INDEX = BOARD_SIZE - 1 + 2
    }
}
