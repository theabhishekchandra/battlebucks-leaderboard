package com.abhishek.battlebucks.ui.leaderboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import com.abhishek.battlebucks.leaderboard.LeaderboardEntry
import com.abhishek.battlebucks.leaderboard.RankMovement

private val HeroHeight = 260.dp

/** Below this much vertical room, the hero panel is dropped. Landscape phones sit well under it. */
private val MinHeightForHero = 560.dp
private val RowHeight = 64.dp
private val SelfRowHeight = 72.dp

/**
 * The live leaderboard.
 *
 * Presentation only. It draws whatever ranked list it's handed. No sorting here, and nothing on a
 * timer: the list changes because the engine emitted, not because the UI ticked.
 */
@Composable
fun LeaderboardScreen(
    state: LeaderboardUiState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    // The season bar is opaque and floats above the list, so the list has to start below it.
    // Measured rather than guessed, since its height depends on the status bar inset.
    var chromeHeight by remember { mutableStateOf(0.dp) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(LeaderboardPalette.Background),
    ) {
        // The hero is a 260dp block of branding. On a short viewport it would eat the screen and
        // leave room for about one row, so it's dropped and the compact summary in the season bar
        // takes over. Keyed off available height rather than an orientation flag, because landscape
        // isn't the only way to end up short: split screen and a folded inner display do it too.
        val showHero = maxHeight >= MinHeightForHero
        val heroHeightPx = if (showHero) with(density) { HeroHeight.toPx() } else 0f

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag(LeaderboardTestTags.LIST),
            contentPadding = PaddingValues(
                top = chromeHeight,
                bottom = SelfRowHeight + 24.dp,
            ),
            content = { leaderboardItems(state, showHero) },
        )

        // Sticky chrome. Drawn above the list so the hero scrolls underneath it.
        TopChrome(
            state = state,
            listState = listState,
            heroHeightPx = heroHeightPx,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { chromeHeight = with(density) { it.height.toDp() } },
        )

        SelfDock(
            self = state.self,
            listState = listState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The list itself: an optional hero and heading, then a row per player.
 *
 * On a short viewport both headers are dropped. The hero is 260dp of branding and the heading only
 * labels the list beneath it, so with no hero there is nothing left to label and the row of space
 * is worth more than the words.
 */
private fun LazyListScope.leaderboardItems(state: LeaderboardUiState, showHero: Boolean) {
    if (showHero) {
        item(key = "hero", contentType = "hero") { HeroPanel(state) }
        item(key = "section", contentType = "section") { SectionHeading() }
    }
    if (state.isWaitingForFirstScore) {
        item(key = "waiting", contentType = "waiting") { WaitingForFirstScore() }
    }
    items(
        items = state.entries,
        // A stable key is what lets Compose recognise a row that moved, instead of treating slot N
        // as a different row. Without it there's no item animation and every reorder rebinds
        // every row.
        key = LeaderboardEntry::playerId,
        contentType = { "player" },
    ) { entry ->
        PlayerRow(
            entry = entry,
            isSelf = entry.playerId == state.self?.playerId,
            // The rank-movement animation. Rows slide past one another as they overtake, because
            // Compose knows which row went where. Your own row is one of them; it climbs and falls
            // like everybody else's.
            modifier = Modifier.animateItem(),
        )
    }
}

// --- chrome -----------------------------------------------------------------------------------

/**
 * The season bar, plus a compact rank and trophy summary that fades in as the hero scrolls away.
 *
 * The collapse fraction is read inside `graphicsLayer` lambdas, so scrolling re-runs only the layer
 * block and nothing recomposes per scroll frame. Reading `listState` in the composable body would
 * rebuild this whole subtree on every pixel of every fling.
 */
@Composable
private fun TopChrome(
    state: LeaderboardUiState,
    listState: LazyListState,
    heroHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        LeaderboardPalette.EmberBright,
                        LeaderboardPalette.EmberDeep,
                        LeaderboardPalette.Background,
                    ),
                ),
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        SeasonBar()
        Text(
            // The match really does stop generating when the app is backgrounded, so the screen
            // says so instead of pretending the feed is still live.
            text = if (state.isPaused) "Match paused" else "Season ends in 60 days",
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        CollapsedSummary(state, listState, heroHeightPx)
    }
}

@Composable
private fun SeasonBar(modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            text = "‹",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 12.dp),
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(LeaderboardPalette.EmberBright)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                text = "GENESIS SEASON  ▾",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "LEGENDS",
            color = Color.White.copy(alpha = 0.22f),
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
    }
}

/**
 * Your rank and trophies, growing in as the hero scrolls away.
 *
 * The height shrinks in a `layout` block and the fade happens in a `graphicsLayer` block. Both read
 * scroll position in a phase lambda rather than in composition, so a fling re-runs layout and draw
 * but never recomposes this subtree. `graphicsLayer` on its own isn't enough: it scales pixels, not
 * the space the row reserves, and that reserved space is what pushed the hero off screen when I
 * first tried it.
 */
@Composable
private fun CollapsedSummary(
    state: LeaderboardUiState,
    listState: LazyListState,
    heroHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .graphicsLayer { alpha = listState.collapseFraction(heroHeightPx) }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val visible = listState.collapseFraction(heroHeightPx)
                layout(placeable.width, (placeable.height * visible).roundToInt()) {
                    placeable.place(0, 0)
                }
            },
    ) {
        LeagueEmblem(size = 34.dp)
        Spacer(Modifier.width(12.dp))
        state.self?.let { RankPill(it.rank) }
        Spacer(Modifier.width(10.dp))
        state.self?.let { TrophyPill(it.score) }
    }
}

/**
 * 0f while the hero is fully visible, 1f once it's scrolled away.
 *
 * A [heroHeightPx] of zero means there is no hero on this viewport, so the compact summary is the
 * only place your rank appears and it stays fully shown.
 */
private fun LazyListState.collapseFraction(heroHeightPx: Float): Float = when {
    heroHeightPx <= 0f -> 1f
    firstVisibleItemIndex > 0 -> 1f
    else -> (firstVisibleItemScrollOffset / heroHeightPx).coerceIn(0f, 1f)
}

@Composable
private fun HeroPanel(state: LeaderboardUiState, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(HeroHeight)
            .testTag(LeaderboardTestTags.HERO),
    ) {
        LeagueEmblem(size = 108.dp)
        Text(
            text = "LEGENDS",
            color = LeaderboardPalette.EmberBright,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            state.self?.let { RankPill(it.rank) }
            state.self?.let { TrophyPill(it.score) }
        }
    }
}

@Composable
private fun RankPill(rank: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xFF3A0F06))
            .drawBehind { drawCircleBorder(LeaderboardPalette.EmberBright) }
            .padding(horizontal = 18.dp, vertical = 7.dp),
    ) {
        Text(
            text = rank.ordinal(),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TrophyPill(score: Long, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xFF2A1A02))
            .drawBehind { drawCircleBorder(LeaderboardPalette.TrophyGold) }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        TrophyIcon(size = 18.dp)
        Spacer(Modifier.width(8.dp))
        AnimatedScore(score = score, fontSize = 15.sp)
    }
}

/**
 * The empty state, before any score has landed.
 *
 * It's short-lived here, since the first event turns up within a second or two. That's why it went
 * missing during a redesign, and why nobody caught it by looking. A UI test doesn't blink.
 */
@Composable
private fun WaitingForFirstScore(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        Text(
            text = "Waiting for the first score…",
            color = LeaderboardPalette.TextMuted,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun SectionHeading(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 12.dp),
    ) {
        Text(
            text = "Leaderboard",
            color = LeaderboardPalette.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f)),
        ) {
            Text("i", color = LeaderboardPalette.TextMuted, fontSize = 12.sp)
        }
    }
}

// --- rows -------------------------------------------------------------------------------------

/**
 * One leaderboard row.
 *
 * This is the performance-relevant bit. The flash and scale pop come from an [Animatable] that's
 * read only inside the `graphicsLayer` and `drawBehind` lambdas. Those re-run in the layout and
 * draw phases, so a 700 ms highlight costs no recompositions at all.
 *
 * Reading `flash.value` in the function body instead would recompose the whole row on every frame
 * of every animation, across every visible row. Same pixels, an order of magnitude more work.
 */
@Composable
private fun PlayerRow(
    entry: LeaderboardEntry,
    isSelf: Boolean,
    modifier: Modifier = Modifier,
) {
    val flash = remember { Animatable(0f) }

    // Keyed on this row's own score, so a row reacts to its own update and ignores everyone
    // else's. No "which player changed?" comparison anywhere in the UI.
    LaunchedEffect(entry.score) {
        flash.snapTo(1f)
        flash.animateTo(0f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
    }

    val base = LeaderboardPalette.RowSurface

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(RowHeight)
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .graphicsLayer {
                val scale = 1f + 0.02f * flash.value          // deferred read: layout phase
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                // deferred reads: draw phase only
                drawRoundRectFill(if (isSelf) LeaderboardPalette.EmberDeep else base)
                drawRoundRectFill(LeaderboardPalette.EmberGlow.copy(alpha = 0.30f * flash.value))
            }
            .padding(horizontal = 10.dp),
    ) {
        AvatarTile(playerId = entry.playerId, size = 40.dp)
        Text(
            text = "${entry.rank}.",
            color = LeaderboardPalette.RankGold,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 12.dp).width(34.dp),
        )
        Text(
            text = entry.displayName,
            color = LeaderboardPalette.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MovementChevron(entry.movement)
        Spacer(Modifier.width(8.dp))
        ScoreBadge(entry.score)
    }
}

/**
 * "You", docked to the bottom, but only while your real row is scrolled out of view.
 *
 * Your row is an ordinary member of the list. It slides as you overtake people, and when it's on
 * screen this dock gets out of the way so you're never drawn twice at once.
 *
 * Two decisions here are about frames rather than pixels:
 *
 *  - Visibility is tracked by item key, not index, so it doesn't quietly depend on how many header
 *    items sit above the list. Add a banner tomorrow and this still works.
 *  - `derivedStateOf` does the real work. `layoutInfo` changes on every frame of every scroll, but
 *    the boolean derived from it flips maybe twice in a whole journey, and only those flips
 *    recompose anything. Reading `layoutInfo` directly would rebuild this subtree on every pixel of
 *    every fling.
 */
@Composable
private fun SelfDock(
    self: LeaderboardEntry?,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val selfKey = self?.playerId
    val selfRowVisible by remember(listState, selfKey) {
        derivedStateOf {
            selfKey != null && listState.layoutInfo.visibleItemsInfo.any { it.key == selfKey }
        }
    }

    AnimatedVisibility(
        visible = self != null && !selfRowVisible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier.testTag(LeaderboardTestTags.SELF_DOCK),
    ) {
        // Captured so the row still draws while animating out after `self` goes null.
        val docked = remember(self) { self }
        docked?.let { PinnedSelfRow(it) }
    }
}

@Composable
private fun PinnedSelfRow(entry: LeaderboardEntry, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(SelfRowHeight)
            .background(
                Brush.horizontalGradient(
                    listOf(LeaderboardPalette.EmberBright, Color(0xFFD9481B)),
                ),
            )
            .padding(horizontal = 22.dp),
    ) {
        AvatarTile(playerId = entry.playerId, size = 42.dp)
        Text(
            text = "${entry.rank}.",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 12.dp).width(34.dp),
        )
        Text(
            text = entry.displayName,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MovementChevron(entry.movement)
        Spacer(Modifier.width(8.dp))
        ScoreBadge(entry.score)
    }
}

@Composable
private fun ScoreBadge(score: Long, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(LeaderboardPalette.ScorePill)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        TrophyIcon(size = 17.dp)
        Spacer(Modifier.width(7.dp))
        AnimatedScore(score = score, fontSize = 15.sp)
    }
}

/**
 * The score, counting up to its new value.
 *
 * Kept as its own composable. Animating text costs a recomposition per frame, since text can't
 * be drawn in the draw phase the way the row's flash can, so the read is kept in the smallest scope
 * that can hold it. Only this `Text` recomposes while counting, not the row around it.
 *
 * ### Why animate progress instead of the number
 *
 * Score is a `Long`, and every animation primitive Compose gives you is `Float`-backed
 * (`AnimationVector` is a vector of floats). So there's no lossless way to animate a `Long`
 * directly:
 *
 *  - `animateIntAsState(score.toInt())` is wrong past `Int.MAX_VALUE`, and not gracefully. It wraps
 *    negative, so a long tournament would show something like `-1,894,967,296`.
 *  - `animateFloatAsState(score.toFloat())` doesn't wrap, but `Float` has a 24-bit mantissa and
 *    stops representing consecutive integers above about 16.7M, so the counter sticks and skips.
 *
 * Animating a normalised `0f..1f` progress avoids both. A fraction is what `Float` handles well.
 * The interpolation happens in `Long`/`Double` (see [interpolateScore]), which is exact for any
 * score the domain can produce.
 *
 * I kept the duration short. A row's rank updates on the same frame as the data, but the
 * number takes this animation's length to catch up. For that window the row shows a new rank next
 * to a stale score, and looks briefly mis-ranked. It isn't. An integration test checks the
 * invariants on all 5,001 snapshots of a full match. A long count-up looks richer on its own and
 * looks wrong in a list that's reordering, so this trades some of the effect for not undermining
 * what the screen exists to show.
 */
@Composable
private fun AnimatedScore(
    score: Long,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(1f) }
    var from by remember { mutableLongStateOf(score) }
    var to by remember { mutableLongStateOf(score) }

    LaunchedEffect(score) {
        // Restart from whatever is currently on screen, not from the previous target, so a score
        // that changes again mid-count continues smoothly instead of snapping backwards.
        from = interpolateScore(from, to, progress.value)
        to = score
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = SCORE_COUNT_MILLIS))
    }

    Text(
        text = "%,d".format(interpolateScore(from, to, progress.value)),
        color = Color.White,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Default,
        modifier = modifier,
    )
}

/**
 * Interpolates between two scores without ever narrowing a `Long`.
 *
 * The difference widens to `Double`, whose 53-bit mantissa is exact for every `Long` up to 2^53,
 * far past any reachable score, and only the result comes back to `Long`. `roundToLong` saturates
 * at the `Long` bounds instead of wrapping, so even a daft input clamps rather than flipping sign.
 */
internal fun interpolateScore(from: Long, to: Long, progress: Float): Long = when {
    progress <= 0f -> from
    progress >= 1f -> to
    else -> from + ((to - from).toDouble() * progress).roundToLong()
}

private const val SCORE_COUNT_MILLIS = 220

@Composable
private fun MovementChevron(movement: RankMovement, modifier: Modifier = Modifier) {
    // Movement comes from the domain, via previousRank. The UI doesn't diff lists to work out who
    // overtook whom.
    if (movement == RankMovement.NONE) {
        Spacer(modifier.width(14.dp))
        return
    }
    val color = when (movement) {
        RankMovement.UP -> LeaderboardPalette.MovementUp
        else -> LeaderboardPalette.MovementDown
    }
    Canvas(modifier = modifier.size(width = 14.dp, height = 16.dp)) {
        drawDoubleChevron(color, pointingUp = movement == RankMovement.UP)
    }
}

// --- small helpers ----------------------------------------------------------------------------

private fun DrawScope.drawRoundRectFill(color: Color) {
    drawRoundRect(color = color, cornerRadius = CornerRadius(20f, 20f))
}

private fun DrawScope.drawCircleBorder(color: Color) {
    val radius = CornerRadius(size.height / 2f, size.height / 2f)
    drawRoundRect(color = color, cornerRadius = radius, style = Stroke(width = 3f))
}

/** 1 -> "1st", 2 -> "2nd", 11 -> "11th", 21 -> "21st". */
private fun Int.ordinal(): String {
    val suffix = if (this % 100 in 11..13) {
        "th"
    } else {
        when (this % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
    }
    return "$this$suffix"
}

// --- previews ---------------------------------------------------------------------------------

private fun previewEntries() = listOf(
    LeaderboardEntry("p1", "Deepender", 3_200, rank = 1, previousRank = 1),
    LeaderboardEntry("p2", "Predekin_Singh", 3_100, rank = 2, previousRank = 3),
    LeaderboardEntry("p3", "Himanshu", 3_000, rank = 3, previousRank = 2),
    LeaderboardEntry("p4", "Manya Aggarwal", 2_900, rank = 4, previousRank = 4),
    LeaderboardEntry("p5", "Vishal", 1_450, rank = 5, previousRank = 5),
    LeaderboardEntry("p6", "Shreyas", 1_200, rank = 6, previousRank = 8),
    LeaderboardEntry("p7", "Abhishek Chandra", 1_100, rank = 7, previousRank = 9),
    LeaderboardEntry("p8", "Anwesha", 1_000, rank = 8, previousRank = 10),
)

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun LeaderboardScreenPreview() {
    val entries = previewEntries()
    LeaderboardScreen(
        LeaderboardUiState(
            entries = entries,
            self = entries[6],
            updateCount = 137,
            isWaitingForFirstScore = false,
            isPaused = false,
        ),
    )
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun WaitingPreview() {
    LeaderboardScreen(LeaderboardUiState.Initial)
}
