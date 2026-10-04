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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sertum.player.R
import com.sertum.player.SertumApplication
import com.sertum.player.data.db.ArtistEntity
import com.sertum.player.ui.components.AlphabetRail
import com.sertum.player.ui.components.startsWithNonLatin
import com.sertum.player.ui.theme.WarmGold
import kotlinx.coroutines.launch

@Composable
fun ArtistsScreen(onArtistClick: (String) -> Unit = {}) {
    val dao = (LocalContext.current.applicationContext as SertumApplication).database.libraryDao()
    val artists by dao.observeArtists().collectAsState(initial = null as List<ArtistEntity>?)
    var query by remember { mutableStateOf("") }
    var selectedLetter by remember { mutableStateOf<Char?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val visible = if (query.isBlank()) {
        artists.orEmpty()
    } else {
        artists.orEmpty().filter { it.name.contains(query, ignoreCase = true) }
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
                                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = artist.name.take(1).uppercase(),
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
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
