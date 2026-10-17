package com.sertum.player.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
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
    val focusManager = LocalFocusManager.current

    LaunchedEffect(selectedTab) {
        // `isScrollInProgress` is the guard that keeps this from fighting a swipe. The highlight
        // now follows `currentPage` (see below), so a swipe updates `selectedTab` mid-gesture;
        // without this check that change would arrive here, find `settledPage` still behind it, and
        // call `scrollToPage` - snapping the pager out from under the finger.
        if (!pagerState.isScrollInProgress && pagerState.settledPage != selectedTab) {
            // Instant on tab taps (short presses must register); swiping
            // between pages keeps its natural gesture animation.
            pagerState.scrollToPage(selectedTab)
        }
    }
    LaunchedEffect(pagerState) {
        // `currentPage`, not `settledPage`. The highlight is the reader's feedback that a swipe is
        // taking effect, and waiting for the pager to come to rest held it back by the whole settle
        // animation - reported as the tab changing "about a fifth of a second after the page had
        // already changed". It now moves when the page nearest the snap position changes, which is
        // the halfway point of the gesture.
        snapshotFlow { pagerState.currentPage }.drop(1).collect { onTabChange(it) }
    }
    LaunchedEffect(pagerState) {
        // Focus still clears only once the pager has settled: doing it mid-swipe hid the keyboard
        // while the pages were still moving, which shifted the layout underneath the animation.
        // Leaving a tab must not leave its search lit. The pager keeps neighbouring pages
        // composed, so a focused search field survived the switch and came back still gold with
        // the keyboard's state half-remembered - which is one of the ways "leaving search" had no
        // reliable meaning (user report, 2026-10-17).
        snapshotFlow { pagerState.settledPage }.drop(1).collect { focusManager.clearFocus() }
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
