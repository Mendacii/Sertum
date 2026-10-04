package com.sertum.player.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.drop
import com.sertum.player.ui.screens.library.AlbumsScreen
import com.sertum.player.ui.screens.library.ArtistsScreen
import com.sertum.player.ui.screens.library.SongsScreen
import com.sertum.player.ui.screens.settings.SettingsScreen

/**
 * The four top-level tabs as one swipeable pager (user preference, 2026-08-16):
 * 姝屾洸 -> 涓撹緫 -> 鑹烘湳瀹?-> 璁剧疆. Tab selection and the pager position are
 * kept in sync by [selectedTab]/[onTabChange].
 */
@Composable
fun MainTabs(
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    /** Launch-only: opens one section's explanation dialog. Null on normal launches. */
    initialInfoSection: String? = null,

) {
    val pagerState = rememberPagerState(initialPage = selectedTab) { 4 }

    LaunchedEffect(selectedTab) {
        if (pagerState.settledPage != selectedTab) {
            // Instant on tab taps (short presses must register); swiping
            // between pages keeps its natural gesture animation.
            pagerState.scrollToPage(selectedTab)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.drop(1).collect { onTabChange(it) }
    }

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            0 -> SongsScreen()
            1 -> AlbumsScreen(onAlbumClick = onAlbumClick)
            2 -> ArtistsScreen(onArtistClick = onArtistClick)
            else -> SettingsScreen(initialInfoSection = initialInfoSection)
        }
    }
}
