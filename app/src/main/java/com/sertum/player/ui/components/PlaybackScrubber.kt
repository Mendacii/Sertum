package com.sertum.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.sertum.player.ui.theme.WarmGold
import com.sertum.player.ui.theme.WarmGoldDim

/** Vertical size of the seek target; the visible track stays thin inside it. */
private val ScrubberTouchHeight = 48.dp
private val ScrubberTrackHeight = 2.dp
private val ScrubberThumbSize = 12.dp

/**
 * Playback position control for the now-playing screen: a thin track with a
 * round draggable thumb, replacing the Material slider (user feedback
 * 2026-10-02). The thumb is a dot, but the gesture target stays 48dp tall so
 * the control remains reachable.
 *
 * [progress] is a 0..1 fraction of the track; [onProgressChange] streams drag
 * updates and [onProgressChangeFinished] commits the final position.
 */
@Composable
fun PlaybackScrubber(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    onProgressChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    val safeProgress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f

    // In RTL the linear progress runs right-to-left, so the thumb has to be
    // positioned from the opposite edge.
    val fractionFromStart =
        if (layoutDirection == LayoutDirection.Rtl) 1f - safeProgress else safeProgress

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(ScrubberTouchHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidth: Dp = maxWidth
        val thumbOffset = (trackWidth - ScrubberThumbSize) * fractionFromStart

        // Remembered so the pointer handler always reads the latest committed
        // fraction without restarting the gesture when it changes.
        var committed by remember { mutableFloatStateOf(safeProgress) }
        committed = safeProgress

        Box(
            Modifier
                .fillMaxWidth()
                .height(ScrubberTouchHeight)
                .pointerInput(trackWidth, layoutDirection) {
                    if (trackWidth.value <= 0f) return@pointerInput
                    val widthPx = trackWidth.toPx()
                    detectDragGestures(
                        onDragStart = { offset ->
                            committed = fractionAt(offset.x, widthPx, layoutDirection)
                            onProgressChange(committed)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            committed = fractionAt(change.position.x, widthPx, layoutDirection)
                            onProgressChange(committed)
                        },
                        onDragEnd = { onProgressChangeFinished(committed) },
                        onDragCancel = { onProgressChangeFinished(committed) },
                    )
                },
        )

        Box(
            Modifier
                .fillMaxWidth()
                .height(ScrubberTrackHeight)
                .clip(CircleShape)
                .background(WarmGoldDim),
        )
        Box(
            Modifier
                .fillMaxWidth(safeProgress)
                .height(ScrubberTrackHeight)
                .clip(CircleShape)
                .background(WarmGold),
        )
        Box(
            Modifier
                .offset(x = thumbOffset)
                .size(ScrubberThumbSize)
                .clip(CircleShape)
                .background(WarmGold),
        )
    }
}

private fun fractionAt(x: Float, widthPx: Float, layoutDirection: LayoutDirection): Float {
    if (widthPx <= 0f) return 0f
    val raw = (x / widthPx).coerceIn(0f, 1f)
    return if (layoutDirection == LayoutDirection.Rtl) 1f - raw else raw
}

/**
 * `m:ss`, or `h:mm:ss` once the track passes an hour. Negative and unknown
 * durations collapse to `0:00` rather than rendering a negative clock.
 */
fun formatDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
