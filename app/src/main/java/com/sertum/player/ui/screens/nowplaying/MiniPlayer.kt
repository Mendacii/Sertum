package com.sertum.player.ui.screens.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sertum.player.R
import com.sertum.player.SertumApplication
import com.sertum.player.ui.playback.PlaybackStateHolder

@Composable
fun MiniPlayer(onExpand: () -> Unit) {
    val state by PlaybackStateHolder.state.collectAsState()
    val controller = (LocalContext.current.applicationContext as SertumApplication).playbackController
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.trackTitle.ifBlank { stringResource(R.string.no_track_playing) },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = listOf(state.artist, state.album).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Same rule as the full player: with an empty playlist the coordinator ignores the
            // press, so the button must not invite one. The row still opens the player.
            IconButton(
                onClick = { controller.togglePlayPause() },
                enabled = state.queue.isNotEmpty(),
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (state.isPlaying) R.string.cd_pause else R.string.cd_play),
                )
            }
        }
    }
}
