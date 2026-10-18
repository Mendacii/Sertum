package com.sertum.player.ui.screens.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sertum.player.R
import com.sertum.player.SertumApplication
import com.sertum.player.domain.playback.RepeatMode
import com.sertum.player.ui.components.ThinChevronDown
import com.sertum.player.ui.components.ThinChevronUp
import com.sertum.player.ui.playback.PlaybackStateHolder
import com.sertum.player.ui.theme.WarmGold
import com.sertum.player.ui.theme.sertumUnselectedIcon
import com.sertum.player.ui.theme.sertumUnselectedText

/**
 * Queue management layer. When hosted inside the full player it slides up
 * over the player and [onBack] slides it back down; standalone usage keeps
 * [onBack] null.
 */
@Composable
fun QueueScreen(onBack: (() -> Unit)? = null) {
    val state by PlaybackStateHolder.state.collectAsState()
    val controller = (LocalContext.current.applicationContext as SertumApplication).playbackController
    // Mirrored from the player rather than owned here, so these two buttons can never disagree
    // with what Media3 is actually doing.
    val repeatMode by controller.repeatMode.collectAsState()
    val shuffleEnabled by controller.shuffleEnabled.collectAsState()
    val listState = rememberLazyListState()

    Column(
        Modifier
            .fillMaxSize()
            .then(if (onBack != null) Modifier.closeOnTopDownDrag(listState, onBack) else Modifier),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp)
                .then(
                    if (onBack != null) Modifier.dragDownToDismiss(onBack) else Modifier,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.cd_back_to_player),
                    )
                }
            }
            Text(
                text = stringResource(R.string.cd_queue),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            // Repeat and shuffle belong here because this is the only screen that shows the queue
            // they act on. Both read the player's own state; gold means active, the same colour
            // language the settings page uses.
            RepeatButton(repeatMode = repeatMode, onClick = { controller.cycleRepeat() })
            IconButton(onClick = { controller.toggleShuffle() }) {
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = stringResource(
                        if (shuffleEnabled) R.string.cd_shuffle_on else R.string.cd_shuffle_off,
                    ),
                    tint = if (shuffleEnabled) WarmGold else sertumUnselectedIcon,
                )
            }
            if (state.queue.isNotEmpty()) {
                ClearQueueAction(onClick = { controller.clearQueue() })
            }
        }

        if (state.queue.isEmpty()) {
            Text(
                text = stringResource(R.string.queue_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize().padding(24.dp),
            )
            return@Column
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(state.queue) { index, title ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        // Tapping a row starts that track (user feedback
                        // 2026-10-02); the row buttons below consume their own
                        // clicks and do not fall through to this one.
                        .clickable { controller.skipToQueueIndex(index) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    IconButton(
                        onClick = { controller.moveQueueItem(index, index - 1) },
                        enabled = index > 0,
                    ) {
                        Icon(ThinChevronUp, contentDescription = stringResource(R.string.cd_move_up))
                    }
                    IconButton(
                        onClick = { controller.moveQueueItem(index, index + 1) },
                        enabled = index < state.queue.lastIndex,
                    ) {
                        Icon(ThinChevronDown, contentDescription = stringResource(R.string.cd_move_down))
                    }
                    IconButton(onClick = { controller.removeQueueItem(index) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.cd_remove))
                    }
                }
            }
        }
    }
}

/**
 * One button for the three repeat states, because three buttons would be three controls for one
 * choice. The icon changes with the state (repeat / repeat-one) and the description spells it out,
 * so the state never depends on colour alone.
 */
@Composable
private fun RepeatButton(repeatMode: RepeatMode, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            contentDescription = stringResource(
                when (repeatMode) {
                    RepeatMode.OFF -> R.string.cd_repeat_off
                    RepeatMode.ALL -> R.string.cd_repeat_all
                    RepeatMode.ONE -> R.string.cd_repeat_one
                },
            ),
            tint = if (repeatMode == RepeatMode.OFF) sertumUnselectedIcon else WarmGold,
        )
    }
}

/**
 * Clearing the queue, as a plain row rather than the outlined pill it was.
 *
 * The pill was the last bordered control in the app - the settings page, the search bar and the
 * queue rows are all borderless with colour carrying state - so it read as a leftover. This is the
 * settings tile's shape instead: a muted icon and its label, nothing drawn around them.
 *
 * The icon sits to the left of the label, and its weight is deliberately below the gold row
 * numbers: clearing the queue is destructive, but it is not the screen's primary action and should
 * not out-shout the list it acts on.
 */
@Composable
private fun ClearQueueAction(onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = null,
            tint = sertumUnselectedIcon,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.clear_queue),
            style = MaterialTheme.typography.labelLarge,
            color = sertumUnselectedText,
        )
    }
}

private fun Modifier.dragDownToDismiss(onClose: () -> Unit): Modifier = pointerInput(onClose) {
    var totalDrag = 0f
    detectVerticalDragGestures(
        onDragStart = { totalDrag = 0f },
        onVerticalDrag = { _, dragAmount -> totalDrag += dragAmount },
        onDragEnd = { if (totalDrag > 200f) onClose() },
    )
}

/**
 * Closes the queue layer when the list is at the top and the user keeps
 * dragging down. Events are observed in the Initial pass so the outer player
 * sheet cannot steal the gesture and dismiss itself instead.
 */
private fun Modifier.closeOnTopDownDrag(
    listState: androidx.compose.foundation.lazy.LazyListState,
    onClose: () -> Unit,
): Modifier = pointerInput(listState, onClose) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var totalDrag = 0f
        var lastY = down.position.y
        var closing = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            totalDrag += change.position.y - lastY
            lastY = change.position.y
            val atTop = listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= 0
            if (!closing && atTop && totalDrag > 40f) closing = true
            if (closing) change.consume()
        }
        if (closing && totalDrag > 150f) onClose()
    }
}
