package com.sertum.player.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.sertum.player.R
import com.sertum.player.SertumApplication
import com.sertum.player.data.db.AlbumEntity
import com.sertum.player.data.db.hasRealCover
import com.sertum.player.ui.theme.WarmGold
import com.sertum.player.ui.theme.sertumMutedText
import kotlinx.coroutines.launch

/** Artist -> albums in a 4-column grid (PRD US-4: the HiBy gap is fixed here). */
@Composable
fun ArtistDetailScreen(artistName: String, onAlbumClick: (String) -> Unit = {}) {
    val context = LocalContext.current
    val dao = (context.applicationContext as SertumApplication).database.libraryDao()
    val albums by dao.albumsForArtist(artistName).collectAsState(initial = emptyList())
    val artists by dao.observeArtists().collectAsState(initial = emptyList())
    val chosenKey = artists.firstOrNull { it.name == artistName }?.imageAlbumKey
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }

    // Only albums that can actually be drawn. Offering one that resolves to the
    // placeholder would let the user pick artwork and see an empty square.
    val pickable = remember(albums) { albums.filter { it.hasRealCover() } }

    if (showPicker) {
        ArtistImagePicker(
            artistName = artistName,
            albums = pickable,
            chosenKey = chosenKey,
            onPick = { key ->
                scope.launch { dao.setArtistImageAlbum(artistName, key) }
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    text = artistName,
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Hidden when the artist has no artwork to choose between, rather than
                // shown disabled: an option that can never do anything is noise.
                if (pickable.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.artist_choose_image),
                        color = WarmGold,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .clipToBounds()
                            .clickable { showPicker = true },
                    )
                }
            }
        }
        items(albums, key = { it.albumKey }) { album: AlbumEntity ->
            AlbumCard(album, onClick = { onAlbumClick(album.albumKey) })
        }
    }
}

/**
 * Picks which album's cover stands for an artist in the list.
 *
 * A grid of covers rather than a list of titles, because the thing being chosen is
 * artwork and the titles are often near-identical across an artist's releases.
 *
 * Only the artist's own albums with real artwork are offered, and "Automatic" clears the
 * choice so the list falls back to the first of those.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistImagePicker(
    artistName: String,
    albums: List<AlbumEntity>,
    chosenKey: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.artist_choose_image),
                style = MaterialTheme.typography.titleLarge,
                color = WarmGold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.artist_choose_image_hint),
                    fontSize = 13.sp,
                    color = sertumMutedText,
                )
                // A plain Grid, not a LazyVerticalGrid: it sits inside a verticalScroll
                // column, and nesting a same-axis lazy container in a scrollable parent
                // gives it an infinite height constraint. An artist's album count is small
                // enough that laying them all out costs nothing.
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 4,
                ) {
                    albums.forEach { album ->
                        val selected = album.albumKey == chosenKey
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .then(
                                    if (selected) {
                                        Modifier.border(2.dp, WarmGold, RoundedCornerShape(10.dp))
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { onPick(album.albumKey) },
                        ) {
                            AsyncImage(
                                model = album.coverRef,
                                contentDescription = album.title,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
                // Clearing the choice is a real option, not an afterthought: without it
                // there is no way back to the automatic behaviour.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPick(null) }
                        .padding(vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.artist_image_auto),
                        fontSize = 14.sp,
                        color = if (chosenKey == null) WarmGold else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.artist_image_auto_desc),
                        fontSize = 12.sp,
                        color = sertumMutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.artist_close), color = WarmGold)
            }
        },
    )
}
