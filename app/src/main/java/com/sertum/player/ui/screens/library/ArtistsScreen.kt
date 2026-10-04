package com.sertum.player.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sertum.player.R
import coil3.compose.AsyncImage
import com.sertum.player.data.covers.CoverResolver
import com.sertum.player.SertumApplication
import com.sertum.player.data.db.AlbumEntity
import com.sertum.player.data.db.ArtistEntity
import com.sertum.player.data.db.hasRealCover
import com.sertum.player.ui.components.AlphabetRail
import com.sertum.player.ui.components.startsWithNonLatin
import com.sertum.player.ui.theme.WarmGold
import kotlinx.coroutines.launch

@Composable
fun ArtistsScreen(onArtistClick: (String) -> Unit = {}) {
    val dao = (LocalContext.current.applicationContext as SertumApplication).database.libraryDao()
    val artists by dao.observeArtists().collectAsState(initial = null as List<ArtistEntity>?)
    // Albums are read whole and matched by artist name rather than through a relation,
    // because the list needs one cover per artist and Room would emit a query per row.
    val albums by dao.observeAlbums().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var selectedLetter by remember { mutableStateOf<Char?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val visible = if (query.isBlank()) {
        artists.orEmpty()
    } else {
        artists.orEmpty().filter { it.name.contains(query, ignoreCase = true) }
    }
    // One cover per artist, resolved once here rather than per row.
    //
    // Preference order: the album the user chose for this artist, then the artist's first
    // album that actually carries artwork, then nothing - in which case the row falls back
    // to the artist's initial, which is what the list showed for every artist before.
    //
    // The middle step matters more than it looks: a third of the library's albums resolve
    // to CoverResolver.PLACEHOLDER_REF, so "the artist's first album" is not the same as
    // "an album with a cover", and taking the first blindly would leave most artists
    // showing an empty square.
    val coverByArtist = remember(albums, artists) {
        val albumsByArtist = albums.groupBy { it.albumArtist }
        val albumByKey = albums.associateBy { it.albumKey }
        buildMap {
            for (artist in artists.orEmpty()) {
                val forThisArtist = albumsByArtist[artist.name].orEmpty()
                val chosen = artist.imageAlbumKey?.let { albumByKey[it] }
                val album = chosen ?: forThisArtist.firstOrNull { it.hasRealCover() }
                val ref = album?.coverRef?.takeIf { it != CoverResolver.PLACEHOLDER_REF }
                if (ref != null) put(artist.name, ref)
            }
        }
    }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.search_artists_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val context = LocalContext.current
        val granted = hasMediaPermission(context)
        if (artists == null) {
            // First frame after returning to this page: keep it blank instead
            // of flashing the "no artists" empty state before Room replays.
            Box(Modifier.fillMaxSize())
        } else if (!granted) {
            EmptyLibrary(
                label = stringResource(R.string.nav_artists),
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
            EmptyLibrary(stringResource(R.string.nav_artists))
        } else {
            val grouped = visible.groupBy { it.sortKey.firstOrNull()?.uppercase() ?: "#" }.toSortedMap()
            val headerIndexes = mutableMapOf<Char, Int>()
            // Where the non-Latin run begins, for the rail's '#'. Computed from the
            // generated sort key rather than the display name so it matches how these
            // groups were built.
            var nonLatinStartIndex: Int? = null
            var runningIndex = 0
            grouped.forEach { (letter, list) ->
                headerIndexes[letter.firstOrNull() ?: '#'] = runningIndex
                if (nonLatinStartIndex == null && list.any { startsWithNonLatin(it.sortKey) }) {
                    nonLatinStartIndex = runningIndex
                }
                runningIndex += 1 + list.size
            }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(end = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    grouped.forEach { (letter, list) ->
                        item(key = "header-$letter") {
                            Text(
                                text = letter,
                                style = MaterialTheme.typography.titleMedium,
                                color = WarmGold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(list, key = { it.name }) { artist ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onArtistClick(artist.name) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(ArtistCoverSize), contentAlignment = Alignment.Center) {
                                    val coverRef = coverByArtist[artist.name]
                                    if (coverRef != null) {
                                        AsyncImage(
                                            model = coverRef,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(ArtistCoverShape),
                                            contentScale = ContentScale.Crop,
                                        )
                                    } else {
                                        // No artwork anywhere in this artist's albums: the
                                        // initial is still the honest thing to show.
                                        Text(
                                            text = artist.name.take(1).uppercase(),
                                            style = MaterialTheme.typography.titleLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                // Was flush against the cover. 20px, in physical pixels
                                // like the rest of this file's measured values.
                                Spacer(Modifier.width(px(20)))
                                Column(Modifier.weight(1f)) {
                                    Text(artist.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = pluralStringResource(R.plurals.album_count, artist.albumCount, artist.albumCount),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                AlphabetRail(
                    selected = selectedLetter,
                    onSelect = { letter ->
                        selectedLetter = letter
                        // '#' goes to the start of the non-Latin run, matching the songs
                        // and albums pages. Not to the last group: the run is a range, and
                        // the reader belongs at its beginning. See railIndexFor.
                        val index = if (letter == '#') nonLatinStartIndex else headerIndexes[letter]
                        if (index != null) {
                            scope.launch { listState.animateScrollToItem(index) }
                        }
                    },
                )
            }
        }
    }
}

/**
 * The cover square in the artist list, and the rounding the album grid uses.
 *
 * 45dp, which is 124px at this device's 440dpi - the album grid's tiles are 250px, so this
 * reads as the same square at list scale rather than as a new shape. The row previously
 * carried a bare 40dp letter with no container, so the row grows by 5dp and the text
 * beside it is untouched.
 */
private val ArtistCoverSize = 45.dp
private val ArtistCoverShape = RoundedCornerShape(12.dp)

/**
 * Physical pixels to dp, for the sizes on this screen.
 *
 * The reference device reports 440dpi, so 1dp is 2.75px. Values here are written in the
 * pixels they were specified in and converted once, rather than being pre-divided at each
 * call site where the intent would be lost.
 */
private const val SCREEN_DPI = 440f

private fun px(value: Int): Dp = (value * 160f / SCREEN_DPI).dp