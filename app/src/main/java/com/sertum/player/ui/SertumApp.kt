package com.sertum.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sertum.player.R
import com.sertum.player.SertumApplication
import com.sertum.player.ui.navigation.MainTabs
import com.sertum.player.ui.navigation.SertumDestinations
import com.sertum.player.ui.screens.library.AlbumDetailScreen
import com.sertum.player.ui.screens.library.ArtistDetailScreen
import com.sertum.player.ui.screens.nowplaying.MiniPlayer
import com.sertum.player.ui.screens.nowplaying.NowPlayingHost
import com.sertum.player.ui.theme.SertumTheme

private data class TopLevelDestination(
    val labelRes: Int,
    val icon: @Composable () -> Unit,
)

private val topLevel = listOf(
    TopLevelDestination(R.string.nav_songs, { Icon(Icons.Filled.LibraryMusic, contentDescription = stringResource(R.string.nav_songs)) }),
    TopLevelDestination(R.string.nav_albums, { Icon(Icons.Filled.Album, contentDescription = stringResource(R.string.nav_albums)) }),
    TopLevelDestination(R.string.nav_artists, { Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.nav_artists)) }),
    TopLevelDestination(R.string.nav_settings, { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_settings)) }),
)

/**
 * Shared timing for the bottom chrome and the content inset it reserves, so
 * the two stop moving on the same frame instead of one outrunning the other.
 */
private const val BOTTOM_CHROME_ANIM_MS = 120

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SertumApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    val settings by com.sertum.player.ui.settings.SettingsStateHolder.state.collectAsState()
    val context = LocalContext.current
    val controller = (context.applicationContext as SertumApplication).playbackController
    val snackbarHostState = remember { SnackbarHostState() }
    val userMessage by controller.userMessage.collectAsState()

    LaunchedEffect(userMessage) {
        val message = userMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            controller.consumeUserMessage()
        }
    }

    val layoutDirection = LocalLayoutDirection.current

    SertumTheme(darkTheme = settings.darkTheme) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                // The mini player stays on every browsing surface; the full
                // player is a full-screen bottom sheet of its own. When the
                // sheet slides away the bottom chrome slides back up quickly.
                AnimatedVisibility(
                    visible = !showNowPlaying,
                    enter = slideInVertically { it } + fadeIn(tween(BOTTOM_CHROME_ANIM_MS)),
                    exit = slideOutVertically { it } + fadeOut(tween(BOTTOM_CHROME_ANIM_MS)),
                ) {
                    Column {
                        MiniPlayer(onExpand = { showNowPlaying = true })
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            topLevel.forEachIndexed { index, dest ->
                                NavigationBarItem(
                                    selected = selectedTab == index,
                                    onClick = {
                                        selectedTab = index
                                        if (currentRoute != SertumDestinations.MAIN) {
                                            navController.navigate(SertumDestinations.MAIN) {
                                                popUpTo(SertumDestinations.MAIN) { inclusive = false }
                                                launchSingleTop = true
                                            }
                                        }
                                    },
                                    icon = dest.icon,
                                    label = { Text(stringResource(dest.labelRes)) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = com.sertum.player.ui.theme.WarmGold,
                                        selectedTextColor = com.sertum.player.ui.theme.WarmGold,
                                        indicatorColor = com.sertum.player.ui.theme.WarmGoldDim,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            // Reserve the bottom chrome's space permanently.
            //
            // The first attempt animated this inset and dropped it to zero
            // while the full player was open, which made the content area
            // taller and stretched the A-Z rail (measured on device: letter
            // pitch 62px -> 77px, rail bottom 1985 -> 2353, i.e. past the
            // bottom bar). The rail is sized by whatever height it is given,
            // so no amount of easing fixes that - the height must not change
            // at all. The bar fades and slides inside space that is always
            // reserved, and the rail therefore never re-lays-out. That also
            // matches what was asked for: keep it fixed at the size it has
            // when the bar is in its normal position.
            //
            // `coerceAtLeast` keeps the reserve even if a frame reports a
            // collapsed bar, and the non-zero guard avoids a one-frame flash
            // of the un-reserved height during the first composition.
            var reservedBottomInset by remember { mutableStateOf(0.dp) }
            val reportedBottomInset = padding.calculateBottomPadding()
            if (reportedBottomInset > 0.dp) reservedBottomInset = reportedBottomInset
            NavHost(
                navController = navController,
                startDestination = SertumDestinations.MAIN,
                modifier = Modifier.padding(
                    PaddingValues(
                        start = padding.calculateStartPadding(layoutDirection),
                        top = padding.calculateTopPadding(),
                        end = padding.calculateEndPadding(layoutDirection),
                        bottom = reservedBottomInset,
                    ),
                ),
                enterTransition = { fadeIn(tween(120)) },
                exitTransition = { fadeOut(tween(90)) },
                popEnterTransition = { fadeIn(tween(120)) },
                popExitTransition = { fadeOut(tween(90)) },
            ) {
                composable(SertumDestinations.MAIN) {
                    MainTabs(
                        selectedTab = selectedTab,
                        onTabChange = { selectedTab = it },
                        onAlbumClick = { key -> navController.navigate(SertumDestinations.albumDetail(key)) },
                        onArtistClick = { name -> navController.navigate(SertumDestinations.artistDetail(name)) },
                    )
                }
                composable(
                    SertumDestinations.ALBUM_DETAIL,
                    arguments = listOf(navArgument("albumKey") { type = NavType.StringType }),
                ) { entry ->
                    AlbumDetailScreen(
                        albumKey = entry.arguments?.getString("albumKey")?.let(android.net.Uri::decode).orEmpty(),
                    )
                }
                composable(
                    SertumDestinations.ARTIST_DETAIL,
                    arguments = listOf(navArgument("artistName") { type = NavType.StringType }),
                ) { entry ->
                    ArtistDetailScreen(
                        artistName = entry.arguments?.getString("artistName")?.let(android.net.Uri::decode).orEmpty(),
                        onAlbumClick = { key -> navController.navigate(SertumDestinations.albumDetail(key)) },
                    )
                }
            }
        }

        if (showNowPlaying) {
            ModalBottomSheet(
                onDismissRequest = { showNowPlaying = false },
                // Material's default sheet colour is the baseline light
                // `surfaceContainerLow`, not the theme background, so the whole
                // player read as grey on a black page (user feedback 2026-10-02).
                containerColor = MaterialTheme.colorScheme.background,
                sheetState = rememberModalBottomSheetState(
                    skipPartiallyExpanded = true,
                    confirmValueChange = { target ->
                        // Dragging the queue layer down must close only the
                        // queue layer, never the whole player sheet.
                        if (showQueue && target == SheetValue.Hidden) false else true
                    },
                ),
            ) {
                Box(Modifier.fillMaxHeight(0.94f)) {
                    NowPlayingHost(
                        showQueue = showQueue,
                        onShowQueueChange = { showQueue = it },
                    )
                }
            }
        }
    }
}
