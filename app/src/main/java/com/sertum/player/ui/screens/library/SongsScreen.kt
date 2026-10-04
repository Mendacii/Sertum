package com.sertum.player.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sertum.player.R
import com.sertum.player.SertumApplication
import com.sertum.player.audio.PlayableTrack
import com.sertum.player.data.db.TrackEntity
import com.sertum.player.ui.components.ALPHABET_RAIL_LETTERS
import com.sertum.player.ui.components.AlphabetRail
import com.sertum.player.ui.components.firstLetterOf
import com.sertum.player.ui.components.railIndexFor
import com.sertum.player.ui.theme.WarmGold
import kotlinx.coroutines.launch

@Composable
fun SongsScreen() {
    val dao = (LocalContext.current.applicationContext as SertumApplication).database.libraryDao()
    val tracks by dao.observeTracks().collectAsState(initial = emptyList())
    val albums by dao.observeAlbums().collectAsState(initial = emptyList())
    val coverByAlbum = albums.associate { it.albumKey to it.coverRef }
    var query by remember { mutableStateOf("") }
    var selectedLetter by remember { mutableStateOf<Char?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val visible = if (query.isBlank()) tracks else tracks.filter {
        it.title.contains(query, ignoreCase = true) ||
            (it.artist?.contains(query, ignoreCase = true) == true) ||
            (it.albumTitle?.contains(query, ignoreCase = true) == true)
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.search_all_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val context = LocalContext.current
        val granted = hasMediaPermission(context)
        if (!granted) {
            EmptyLibrary(
                label = stringResource(R.string.nav_songs),
                actionText = stringResource(R.string.open_app_settings),
                onAction = {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
            )
        } else if (visible.isEmpty()) {
            EmptyLibrary(
                label = stringResource(R.string.nav_songs),
                actionText = null,
                onAction = null,
            )
        } else {
            val controller = (LocalContext.current.applicationContext as SertumApplication).playbackController
            val letterIndexes = ALPHABET_RAIL_LETTERS.associateWith { letter ->
                railIndexFor(letter, visible.map { firstLetterOf(it.title) })
            }.filterValues { it != null }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(end = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(visible, key = { it.id }) { track ->
                        val index = visible.indexOf(track)
                        TrackRow(
                            track = track,
                            onClick = {
                                controller.playTracks(
                                    visible.map { it.toPlayable().copy(coverRef = coverByAlbum[it.albumKey]) },
                                    startIndex = index,
                                )
                            },
                        )
                    }
                }
                AlphabetRail(
                    selected = selectedLetter,
                    onSelect = { letter ->
                        selectedLetter = letter
                        val index = letterIndexes[letter]
                        if (index != null) {
                            scope.launch { listState.animateScrollToItem(index) }
                        }
                    },
                )
            }
        }
    }
}

fun TrackEntity.toPlayable(): PlayableTrack = PlayableTrack(
    id = id,
    uri = android.net.Uri.parse(uri),
    title = title,
    artist = artist,
    album = albumTitle,
    albumKey = albumKey,
)

@Composable
fun TrackRow(track: TrackEntity, onClick: () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = track.trackNumber?.toString() ?: "•",
                style = MaterialTheme.typography.labelLarge,
                color = WarmGold,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = track.artist ?: stringResource(R.string.unknown_artist),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatDuration(track.durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun hasMediaPermission(context: android.content.Context): Boolean {
    val granted = android.content.pm.PackageManager.PERMISSION_GRANTED
    return if (android.os.Build.VERSION.SDK_INT >= 33) {
        context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) == granted
    } else {
        context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == granted
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
