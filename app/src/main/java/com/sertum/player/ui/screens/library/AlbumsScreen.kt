package com.sertum.player.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sertum.player.R
import com.sertum.player.SertumApplication
import com.sertum.player.data.db.AlbumEntity
import com.sertum.player.ui.components.ALPHABET_RAIL_LETTERS
import com.sertum.player.ui.components.AlphabetRail
import com.sertum.player.ui.components.firstLetterOf
import com.sertum.player.ui.components.railIndexFor
import com.sertum.player.ui.theme.SurfaceBlack
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun AlbumsScreen(onAlbumClick: (String) -> Unit = {}) {
    val dao = (LocalContext.current.applicationContext as SertumApplication).database.libraryDao()
    val loaded by dao.observeAlbums().collectAsState(initial = null as List<AlbumEntity>?)
    // Renders from the last known albums instead of a blank frame.
    //
    // The detail page is a separate destination, so leaving this screen disposes it and
    // the collector restarts from null on the way back. That null used to render a blank
    // frame which then filled in while the return transition was already running, and
    // because it lands under a fade the whole return read as a stutter rather than as a
    // transition. With a cached list the grid is on screen from the first frame and only
    // its contents refresh.
    //
    // Plain remember, not rememberSaveable. Returning here is always the same process, so
    // the cache only has to outlive the navigation, and AlbumEntity is a Room class with
    // no Parcelable or Serializable implementation - putting entities in the saved state
    // would throw at runtime, on a path the compiler cannot check. Nothing here is worth
    // that risk.
    val previousAlbums = remember { mutableStateOf<List<AlbumEntity>?>(null) }
    if (loaded != null) previousAlbums.value = loaded
    val albums = loaded ?: previousAlbums.value
    // rememberSaveable, not remember: the detail page is a separate destination, so
    // leaving this screen disposes it and plain remembers would drop the reader back at
    // the top of a 244-album grid on every return. The scroll position already survives
    // because rememberLazyGridState is itself saveable; the search text and the chosen
    // letter did not, and now do.
    var query by rememberSaveable { mutableStateOf("") }
    var selectedLetter by rememberSaveable { mutableStateOf<Char?>(null) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val visible = if (query.isBlank()) {
        albums.orEmpty()
    } else {
        albums.orEmpty().filter {
            it.title.contains(query, ignoreCase = true) || it.albumArtist.contains(query, ignoreCase = true)
        }
    }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.search_albums_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val context = LocalContext.current
        val granted = hasMediaPermission(context)
        if (albums == null) {
            // First frame after returning to this page: keep it blank instead
            // of flashing the empty-library state before Room replays.
            Box(Modifier.fillMaxSize())
        } else if (!granted) {
            EmptyLibrary(
                label = stringResource(R.string.nav_albums),
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
            EmptyLibrary(stringResource(R.string.nav_albums))
        } else {
            val letterIndexes = ALPHABET_RAIL_LETTERS.associateWith { letter ->
                railIndexFor(letter, visible.map { firstLetterOf(it.title) })
            }.filterValues { it != null }
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 30.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visible, key = { it.albumKey }) { album ->
                        AlbumCard(album, onClick = { onAlbumClick(album.albumKey) })
                    }
                }
                AlphabetRail(
                    selected = selectedLetter,
                    onSelect = { letter ->
                        selectedLetter = letter
                        val index = letterIndexes[letter]
                        if (index != null) {
                            scope.launch { gridState.animateScrollToItem(index) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun AlbumCard(album: AlbumEntity, onClick: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceBlack, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            val coverRef = album.coverRef?.takeUnless { it == com.sertum.player.data.covers.CoverResolver.PLACEHOLDER_REF }
            if (coverRef != null) {
                AsyncImage(
                    model = coverRef,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                Text(
                    text = album.title.take(1).uppercase(),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            text = album.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = album.albumArtist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
