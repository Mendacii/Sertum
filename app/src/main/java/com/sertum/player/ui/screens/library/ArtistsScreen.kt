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
import com.sertum.player.ui.components.SearchScaffold
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
    SearchScaffold(
        query = query,
        onQueryChange = { query = it },
        placeholder = stringResource(R.string.search_artists_placeholder),
    ) {
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
                                // 8dp, which lands the gaps above and below this letter on the same
                                // 44px the rows use: 16px of the row's centred slack, plus the
                                // row's own 6px, plus this 22px, and mirrored below.
                                //
                                // It was px(38) while the covers were 92px and the rows 8dp, a
                                // combination that needed 38 to reach 44; the same 44 now arrives
                                // from a taller cover and a smaller row padding. Worth knowing
                                // before adjusting either: this number is the remainder, not a
                                // value with a meaning of its own.
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(list, key = { it.name }) { artist ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onArtistClick(artist.name) }
                                    // 6px, so the row comes to the same 136px the songs and album
                                // track rows use while still holding the full 124px cover: 124 +
                                // 6 + 6 = 136. It was 8dp (22px), which with a 124px cover made a
                                // 168px row - 32px taller than its neighbours, because the text is
                                // 92px and got centred with 16px of slack above and below it.
                                //
                                // The gap a reader sees between two artists works out at 44px:
                                // 16 of that slack, plus this 6, plus the next row's 6, plus its
                                // 16. Same number as the other two lists, reached differently.
                                .padding(horizontal = 16.dp, vertical = px(6)),
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
 * The cover square in the artist list.
 *
 * 124px = 45dp, the size this row was reviewed at. It was briefly 92px, the height of the two
 * lines of text beside it, which evened out the row spacing but made the artwork visibly smaller
 * than the album page it is meant to echo (user: "the icon is too small", 2026-10-17).
 *
 * Both are now possible, because the row height and the cover height are separable: the row is
 * 124px of cover plus 6px of space above and below, which is the 136px the songs and album track
 * rows also use. Equal heights do not require equal cover sizes - only equal row *totals*.
 */
private val ArtistCoverSize = px(124)

/**
 * Rounding for the artist cover, scaled from the album grid rather than copied from it.
 *
 * The album grid's tiles are 227.4px wide (1080 less the grid's 8dp start and 30dp end padding,
 * less three 8dp gaps, over four columns) and use a 12dp = 33px radius. That is 14.51% of the
 * tile, so the same shape at 124px is 18px.
 *
 * A shared 12dp was the earlier approach and is what made this row look wrong: 33px on a 124px
 * square is 26.6% - nearly twice the album page's roundness - so the artwork read as a pill rather
 * than as a small version of the same tile. Corner radii do not transfer between sizes; the ratio
 * does.
 */
private val ArtistCoverShape = RoundedCornerShape(px(18))

/**
 * Physical pixels to dp, for the sizes on this screen.
 *
 * The reference device reports 440dpi, so 1dp is 2.75px. Values here are written in the
 * pixels they were specified in and converted once, rather than being pre-divided at each
 * call site where the intent would be lost.
 */
private const val SCREEN_DPI = 440f

private fun px(value: Int): Dp = (value * 160f / SCREEN_DPI).dp