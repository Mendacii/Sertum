package com.sertum.player.ui.screens.nowplaying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The full player, presented by hand rather than by `ModalBottomSheet`.
 *
 * The reason is timing. Material settles a drag with a spring whose duration follows the release
 * velocity, while a back gesture runs a fixed animation; measured on the device, the sheet took
 * 1048ms to leave after a slow drag and 502ms after a back gesture. The bottom chrome sits behind
 * the sheet, so those numbers were exactly the delay the reader saw before the bar came back, and
 * no public API can set them - this module's `SheetState.hide()` takes no animation spec.
 *
 * Here every way out runs the same fixed [PLAYER_EXIT_MS] slide, so the bar returns at the same
 * moment whether the player was dismissed by dragging or by going back. The entering slide and the
 * snap back when a drag does not reach the threshold are fixed too, for the same reason.
 */
private const val PLAYER_ENTER_MS = 320
private const val PLAYER_EXIT_MS = 220
private const val PLAYER_RETURN_MS = 180

/** How far down the player has to be released before letting go counts as dismissing it. */
private const val DISMISS_FRACTION = 0.25f

/** px/s. A flick this fast dismisses even if it has not travelled far. */
private const val DISMISS_VELOCITY_PX_S = 2200f

// The drag handle Material drew for us, reproduced so the player looks unchanged. Its bar is a
// 32x4dp pill sitting 22dp down inside a 48dp strip, which is what the sheet reserved above the
// content - the cover's position depends on that strip staying the same height.
private const val HANDLE_STRIP_HEIGHT_DP = 48
private const val HANDLE_PILL_TOP_DP = 22
private const val HANDLE_PILL_WIDTH_DP = 32
private const val HANDLE_PILL_HEIGHT_DP = 4

@Composable
fun PlayerSheet(
    showQueue: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val screenHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        configuration.screenHeightDp.dp.toPx()
    }
    // How far the player is pushed down, in pixels. Animatable by hand rather than an Animatable
    // instance so that the drag, the enter slide, the exit slide and the snap back all write the
    // same value without fighting over ownership of an animation.
    var offsetPx by remember { mutableFloatStateOf(screenHeightPx) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        animate(
            initialValue = offsetPx,
            targetValue = 0f,
            animationSpec = tween(PLAYER_ENTER_MS, easing = LinearOutSlowInEasing),
        ) { value, _ -> offsetPx = value }
    }

    val close: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                animate(
                    initialValue = offsetPx,
                    targetValue = screenHeightPx,
                    animationSpec = tween(PLAYER_EXIT_MS, easing = FastOutLinearInEasing),
                ) { value, _ -> offsetPx = value }
                onDismissRequest()
            }
        }
    }

    // A child BackHandler in NowPlayingHost takes over while the queue layer is open, so this one
    // only sees the press when the player itself is the top layer.
    BackHandler(onBack = close)

    // A Surface, not a Box with a background modifier.
    //
    // `Surface` also publishes `LocalContentColor`, and the player depends on it: its title and
    // transport icons take no explicit colour and inherit it. Outside a Surface that value is
    // black - the colour of this player's background - so those pieces were laid out, measured
    // and announced to accessibility exactly as before and simply painted invisibly. Everything
    // with an explicit `onSurfaceVariant` or the gold accent stayed readable, which is what made
    // the fault look like a partial breakage rather than a colour one.
    // ModalBottomSheet wrapped its content in a Surface too; copying the background alone was not
    // the same thing.
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, offsetPx.roundToInt()) }
            .draggable(
                orientation = Orientation.Vertical,
                // The queue layer owns vertical gestures while it is open, the same way the sheet
                // used to refuse to dismiss itself then.
                enabled = !closing && !showQueue,
                state = rememberDraggableState { delta ->
                    offsetPx = (offsetPx + delta).coerceIn(0f, screenHeightPx)
                },
                onDragStopped = { velocity ->
                    scope.launch {
                        val dismiss = offsetPx > screenHeightPx * DISMISS_FRACTION ||
                            velocity > DISMISS_VELOCITY_PX_S
                        if (dismiss) {
                            close()
                        } else {
                            animate(
                                initialValue = offsetPx,
                                targetValue = 0f,
                                animationSpec = tween(PLAYER_RETURN_MS),
                            ) { value, _ -> offsetPx = value }
                        }
                    }
                },
            ),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Box(Modifier.fillMaxWidth().height(HANDLE_STRIP_HEIGHT_DP.dp)) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = HANDLE_PILL_TOP_DP.dp)
                        .width(HANDLE_PILL_WIDTH_DP.dp)
                        .height(HANDLE_PILL_HEIGHT_DP.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }
            content()
        }
    }
}
