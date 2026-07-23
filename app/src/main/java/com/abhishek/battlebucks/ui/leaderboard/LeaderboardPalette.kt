package com.abhishek.battlebucks.ui.leaderboard

import androidx.compose.ui.graphics.Color

/**
 * A fixed palette instead of `MaterialTheme.colorScheme`.
 *
 * This screen has a specific look to hit: dark, ember-lit, gold on black. Dynamic colour would
 * repaint it with the user's wallpaper and lose all of that, so the screen owns its colours
 * outright.
 * Anywhere else in an app this would be the wrong call. Here it's the same decision a real game UI
 * makes.
 */
internal object LeaderboardPalette {
    val Background = Color(0xFF0C0705)
    val RowSurface = Color(0xFF1A100D)
    val RowSurfaceAlt = Color(0xFF150D0A)
    val ScorePill = Color(0xFF060303)

    val EmberBright = Color(0xFFF2622A)
    val EmberDeep = Color(0xFF8C2A0C)
    val EmberGlow = Color(0xFFFF8A3D)

    val RankGold = Color(0xFFF5C518)
    val TrophyGold = Color(0xFFFFC43D)
    val TrophyShadow = Color(0xFFD08A12)

    val MovementUp = Color(0xFF57D960)
    val MovementDown = Color(0xFFE05B4B)

    val TextPrimary = Color(0xFFFFFFFF)
    val TextMuted = Color(0xFFB9A69E)

    /** Avatar tile backgrounds, picked per player so a row never changes colour on you. */
    val AvatarTiles = listOf(
        Color(0xFFE8613A),
        Color(0xFFF2A03D),
        Color(0xFFF5D14A),
        Color(0xFF57C98A),
        Color(0xFF5BC7D9),
        Color(0xFFCFC3F5),
        Color(0xFFE87A9B),
        Color(0xFF9BD95F),
    )
}
