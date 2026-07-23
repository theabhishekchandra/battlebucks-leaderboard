package com.abhishek.battlebucks.ui.leaderboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue

/**
 * Artwork drawn with [Canvas] instead of shipped as assets.
 *
 * I don't have the game's real avatar or trophy art, and inventing PNGs would be worse than
 * useless. They'd look like the real thing without being it. Drawing the shapes in code keeps the
 * layout honest about what's a stand-in, costs no APK size, and scales to any density.
 */

/** A player avatar: a coloured tile with a hooded figure, chosen deterministically from the id. */
@Composable
internal fun AvatarTile(
    playerId: String,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    // Deterministic per player: the same person always gets the same tile, across recompositions,
    // rotations and process restarts.
    val hash = playerId.hashCode().absoluteValue
    val tint = LeaderboardPalette.AvatarTiles[hash % LeaderboardPalette.AvatarTiles.size]
    val wearsCap = hash % 3 != 0

    Canvas(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp)),
    ) {
        drawRect(tint)
        drawHoodedFigure(wearsCap)
    }
}

private fun DrawScope.drawHoodedFigure(wearsCap: Boolean) {
    val w = size.width
    val h = size.height
    val body = Color(0xFF14100F)

    // A bell/tent silhouette: flat at the bottom, domed at the top.
    val hood = Path().apply {
        moveTo(w * 0.06f, h)
        lineTo(w * 0.06f, h * 0.70f)
        cubicTo(w * 0.10f, h * 0.20f, w * 0.32f, h * 0.08f, w * 0.50f, h * 0.08f)
        cubicTo(w * 0.68f, h * 0.08f, w * 0.90f, h * 0.20f, w * 0.94f, h * 0.70f)
        lineTo(w * 0.94f, h)
        close()
    }
    drawPath(hood, body)

    if (wearsCap) {
        // A cap band hugging the crown.
        val cap = Path().apply {
            moveTo(w * 0.16f, h * 0.44f)
            cubicTo(w * 0.18f, h * 0.12f, w * 0.82f, h * 0.12f, w * 0.84f, h * 0.44f)
            cubicTo(w * 0.66f, h * 0.30f, w * 0.34f, h * 0.30f, w * 0.16f, h * 0.44f)
            close()
        }
        drawPath(cap, Color(0xFFD93A2B))
    }

    // Angled eye slits, which give the avatars their glare.
    drawPath(eyeSlit(w, h, mirrored = false), Color.White)
    drawPath(eyeSlit(w, h, mirrored = true), Color.White)
}

private fun eyeSlit(w: Float, h: Float, mirrored: Boolean): Path {
    fun x(fraction: Float) = if (mirrored) w * (1f - fraction) else w * fraction
    return Path().apply {
        moveTo(x(0.26f), h * 0.56f)
        lineTo(x(0.45f), h * 0.63f)
        lineTo(x(0.44f), h * 0.72f)
        lineTo(x(0.25f), h * 0.66f)
        close()
    }
}

/** A gold trophy. */
@Composable
internal fun TrophyIcon(size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        // Handles, drawn behind the cup.
        val handleStroke = Stroke(width = w * 0.09f)
        drawArc(
            color = LeaderboardPalette.TrophyShadow,
            startAngle = 90f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.02f, h * 0.10f),
            size = Size(w * 0.30f, h * 0.36f),
            style = handleStroke,
        )
        drawArc(
            color = LeaderboardPalette.TrophyShadow,
            startAngle = 270f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.68f, h * 0.10f),
            size = Size(w * 0.30f, h * 0.36f),
            style = handleStroke,
        )

        // Cup: wide at the rim, tapering to the stem.
        val cup = Path().apply {
            moveTo(w * 0.20f, h * 0.10f)
            lineTo(w * 0.80f, h * 0.10f)
            lineTo(w * 0.72f, h * 0.46f)
            cubicTo(w * 0.68f, h * 0.60f, w * 0.32f, h * 0.60f, w * 0.28f, h * 0.46f)
            close()
        }
        drawPath(cup, LeaderboardPalette.TrophyGold)

        drawRect(
            color = LeaderboardPalette.TrophyGold,
            topLeft = Offset(w * 0.44f, h * 0.58f),
            size = Size(w * 0.12f, h * 0.18f),
        )
        drawRect(
            color = LeaderboardPalette.TrophyGold,
            topLeft = Offset(w * 0.26f, h * 0.76f),
            size = Size(w * 0.48f, h * 0.13f),
        )
    }
}

/** The league emblem: an ember crest with a glaring visor. */
@Composable
internal fun LeagueEmblem(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        // Flame licking above the crest.
        val flame = Path().apply {
            moveTo(w * 0.50f, h * 0.02f)
            cubicTo(w * 0.60f, h * 0.14f, w * 0.66f, h * 0.20f, w * 0.62f, h * 0.30f)
            cubicTo(w * 0.58f, h * 0.22f, w * 0.54f, h * 0.20f, w * 0.50f, h * 0.14f)
            cubicTo(w * 0.46f, h * 0.20f, w * 0.42f, h * 0.22f, w * 0.38f, h * 0.30f)
            cubicTo(w * 0.34f, h * 0.20f, w * 0.40f, h * 0.14f, w * 0.50f, h * 0.02f)
            close()
        }
        drawPath(flame, LeaderboardPalette.EmberGlow)

        // Shield crest.
        val crest = Path().apply {
            moveTo(w * 0.50f, h * 0.22f)
            lineTo(w * 0.88f, h * 0.42f)
            lineTo(w * 0.74f, h * 0.92f)
            lineTo(w * 0.26f, h * 0.92f)
            lineTo(w * 0.12f, h * 0.42f)
            close()
        }
        drawPath(crest, LeaderboardPalette.EmberBright)

        val inner = Path().apply {
            moveTo(w * 0.50f, h * 0.34f)
            lineTo(w * 0.76f, h * 0.48f)
            lineTo(w * 0.66f, h * 0.84f)
            lineTo(w * 0.34f, h * 0.84f)
            lineTo(w * 0.24f, h * 0.48f)
            close()
        }
        drawPath(inner, Color(0xFF9E2B0E))

        drawPath(eyeSlit(w, h * 1.02f, mirrored = false), Color.White)
        drawPath(eyeSlit(w, h * 1.02f, mirrored = true), Color.White)
    }
}

/** The double chevron used for rank movement. */
internal fun DrawScope.drawDoubleChevron(color: Color, pointingUp: Boolean) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.16f)

    repeat(2) { index ->
        val shift = h * (0.30f * index)
        val path = Path().apply {
            if (pointingUp) {
                moveTo(w * 0.15f, h * 0.55f + shift)
                lineTo(w * 0.50f, h * 0.20f + shift)
                lineTo(w * 0.85f, h * 0.55f + shift)
            } else {
                moveTo(w * 0.15f, h * 0.45f - shift)
                lineTo(w * 0.50f, h * 0.80f - shift)
                lineTo(w * 0.85f, h * 0.45f - shift)
            }
        }
        drawPath(path, color, style = stroke)
    }
}
