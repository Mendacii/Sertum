package com.sertum.player.ui.screens.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sertum.player.R
import com.sertum.player.SertumApplication
import com.sertum.player.ui.playback.PlaybackStateHolder

/**
 * Queue management layer. When hosted inside the full player it slides up
 * over the player and [onBack] slides it back down; standalone usage keeps
 * [onBack] null.
 */
@Composable
fun QueueScreen(onBack: (() -> Unit)? = null) {
    val state by PlaybackStateHolder.state.collectAsState()
    val controller = (LocalContext.current.applicationContext as SertumApplication).playbackController
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
            if (state.queue.isNotEmpty()) {
                OutlinedButton(onClick = { controller.clearQueue() }) {
                    Text(stringResource(R.string.clear_queue))
                }
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
                        Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.cd_move_up))
                    }
                    IconButton(
                        onClick = { controller.moveQueueItem(index, index + 1) },
                        enabled = index < state.queue.lastIndex,
                    ) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.cd_move_down))
                    }
                    IconButton(onClick = { controller.removeQueueItem(index) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_remove))
                    }
                }
            }
        }
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
