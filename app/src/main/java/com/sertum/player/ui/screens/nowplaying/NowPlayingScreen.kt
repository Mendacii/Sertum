package com.sertum.player.ui.screens.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.sertum.player.R
import com.sertum.player.SertumApplication
import com.sertum.player.data.covers.CoverResolver
import com.sertum.player.ui.components.PlaybackScrubber
import com.sertum.player.ui.components.UsbBadge
import com.sertum.player.ui.components.formatDuration
import com.sertum.player.ui.playback.OutputMode
import com.sertum.player.ui.playback.PlaybackStateHolder
import com.sertum.player.ui.settings.SettingsStateHolder
import com.sertum.player.ui.theme.SurfaceBlack
import com.sertum.player.ui.theme.WarmGold

/**
 * Full player. The cover stays in the upper area; title, progress and
 * transport controls cluster near the bottom (user preference 2026-08-16).
 */
@Composable
fun NowPlayingScreen(onOpenQueue: () -> Unit) {
    val state by PlaybackStateHolder.state.collectAsState()
    val settings by SettingsStateHolder.state.collectAsState()
    val controller = (LocalContext.current.applicationContext as SertumApplication).playbackController

    // The cover rotates only while audio is playing, and holds its angle when paused rather
    // than resetting, so resuming looks like the record picking up where it left off.
    //
    // An Animatable rather than an infinite transition: an infinite transition runs on its
    // own timeline and cannot be stopped, so a paused cover kept spinning.
    //
    // The angle is initialised from and written back to a process-wide holder rather than
    // living only in this composition. That is the part a `remember` cannot do: the value
    // it holds dies with the composable, and a player that pauses midway and comes back
    // then restarts from the top. Holding it outside means a disposed and rebuilt player -
    // for any reason - resumes at the angle it had, which is the behaviour that was asked
    // for and is independent of why the composition went away.
    val rotation = remember { Animatable(CoverRotation.angle) }
    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) {
            while (true) {
                rotation.animateTo(
                    targetValue = nextTurnTarget(rotation.value),
                    animationSpec = tween(durationMillis = COVER_ROTATION_MS, easing = LinearEasing),
                )
            }
        } else {
            CoverRotation.angle = rotation.value
        }
    }
    // Also recorded when this screen goes away, so a player dismissed mid-turn - by swiping
    // the sheet down rather than pausing - still hands its angle on. A DisposableEffect
    // rather than keying an effect on the angle, which would restart every frame.
    DisposableEffect(Unit) {
        onDispose { CoverRotation.angle = rotation.value }
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        val coverRef = state.coverRef?.takeUnless { it == CoverResolver.PLACEHOLDER_REF }
        // The artwork is inset by the ring's width when round, so the ring sits outside it
        // rather than on top of it. `border` paints inwards from the edge, so an
        // equal-sized ring would cover the outermost pixels of the cover instead of
        // outlining it.
        val coverInset = if (settings.roundCover) COVER_EDGE_WIDTH else 0.dp
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(if (settings.roundCover) CircleShape else RoundedCornerShape(16.dp))
                .background(SurfaceBlack, MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) {
            if (coverRef != null) {
                AsyncImage(
                    model = coverRef,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(coverInset)
                        .clip(if (settings.roundCover) CircleShape else RoundedCornerShape(16.dp))
                        .then(
                            if (settings.roundCover) {
                                Modifier.graphicsLayer { rotationZ = rotation.value }
                            } else {
                                Modifier
                            },
                        ),
                )
            } else {
                Text(
                    text = state.trackTitle.ifBlank { stringResource(R.string.no_track_playing) }.take(1).uppercase(),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = if (settings.roundCover) {
                        Modifier.graphicsLayer { rotationZ = rotation.value }
                    } else {
                        Modifier
                    },
                )
            }
            if (settings.roundCover) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(COVER_EDGE_WIDTH, WarmGold, CircleShape),
                )
            }
        }

        // The cover-shape control lives outside the artwork (user feedback
        // 2026-10-02): one toggle for both directions, on a labelled row.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    if (settings.roundCover) {
                        R.string.now_playing_cover_round
                    } else {
                        R.string.now_playing_cover_square
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { SettingsStateHolder.update { it.copy(roundCover = !it.roundCover) } },
            ) {
                Icon(
                    Icons.Filled.Album,
                    contentDescription = stringResource(R.string.cd_toggle_cover_shape),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.trackTitle.ifBlank { stringResource(R.string.no_track_playing) },
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        text = state.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenQueue) {
                    Icon(Icons.Filled.QueueMusic, contentDescription = stringResource(R.string.cd_queue))
                }
            }

            // Framed USB badge sits ABOVE the rate/depth info (A-18 amendment).
            if (state.outputMode == OutputMode.USB_EXCLUSIVE) {
                UsbBadge(state.bitPerfectState, modifier = Modifier.padding(top = 8.dp))
            }
            Text(
                text = buildString {
                    if (state.sampleRate > 0) append("${state.sampleRate / 1000f} kHz")
                    if (state.bitDepth > 0) append(" 路 ${state.bitDepth} bit")
                    append(" 路 ")
                    append(
                        when (state.outputMode) {
                            OutputMode.USB_EXCLUSIVE -> stringResource(R.string.output_usb_exclusive)
                            OutputMode.BLUETOOTH -> stringResource(R.string.output_bluetooth)
                            OutputMode.STANDARD -> stringResource(R.string.output_standard)
                        },
                    )
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            val displayedPosition = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            val shownFraction = if (dragging) dragValue else displayedPosition
            PlaybackScrubber(
                progress = shownFraction,
                onProgressChange = {
                    dragging = true
                    dragValue = it
                },
                onProgressChangeFinished = {
                    if (state.durationMs > 0) {
                        controller.seekTo((dragValue * state.durationMs).toLong())
                    }
                    dragging = false
                },
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatDuration((shownFraction.coerceIn(0f, 1f) * state.durationMs).toLong()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatDuration(state.durationMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { controller.skipToPrevious() }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.cd_previous))
                }
                IconButton(
                    onClick = { controller.togglePlayPause() },
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (state.isPlaying) R.string.cd_pause else R.string.cd_play),
                        modifier = Modifier.size(48.dp),
                    )
                }
                IconButton(onClick = { controller.skipToNext() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.cd_next))
                }
            }
        }
    }
}

/**
 * One full turn of the round cover, in milliseconds.
 *
 * 4,000ms is 15 rpm, chosen by the user. For reference a 12-inch record plays at 33 1/3 rpm,
 * so this is a little under half that.
 */
internal const val COVER_ROTATION_MS = 4_000

/**
 * The angle the cover should animate to next, given where it is now.
 *
 * Always one full turn ahead, so a resume continues from whatever angle it paused at rather
 * than restarting at the top. Extracted from the animation so the arithmetic can be tested
 * on its own - see CoverRotationTest.
 */
internal fun nextTurnTarget(currentAngle: Float): Float = currentAngle + 360f

/**
 * The round cover's current angle, held outside the composition.
 *
 * The player lives in a modal bottom sheet, and the angle has to survive it being disposed
 * and rebuilt for any reason - otherwise pausing midway and resuming snaps the cover back
 * to the top, which is what a plain `remember` produced. Same pattern as
 * [com.sertum.player.ui.playback.PlaybackStateHolder]: process-wide state that the UI reads.
 *
 * Not persisted to disk deliberately. It describes where a record is spinning right now,
 * which is meaningless after the process ends, and a stale angle from a previous session
 * would be a worse answer than starting at the top.
 */
internal object CoverRotation {
    var angle: Float = 0f
}

/**
 * Width of the ring around the round cover.
 *
 * Physical pixels, like the other measured values: 2px at this display's 440dpi is 0.727dp,
 * half the 4dp it replaces. It is opaque [WarmGold] now rather than the 40%-opacity
 * WarmGoldDim it was, so the edge reads as a drawn line instead of a haze.
 */
private val COVER_EDGE_WIDTH = (2 * 160f / 440f).dp
