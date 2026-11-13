package io.github.yyan115.opentune.ui

import io.github.yyan115.opentune.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.media3.common.util.UnstableApi
import io.github.yyan115.opentune.core.*

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun OpenTuneRoot(vm: MainViewModel, state: AppState, initialDestination: Destination = Destination.Home, initialPlayer: Boolean = false, initialSettings: Boolean = false, initialLyrics: Boolean = false) {
    // The position changes twice a second. Only the mini player reads it, so the rest of the screen does not recompose.
    val position = LocalPlaybackPosition.current.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val pagesVm: PagesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var destinationName by rememberSaveable { mutableStateOf(initialDestination.name) }
    var libraryFilter by rememberSaveable { mutableIntStateOf(0) }
    var settings by rememberSaveable { mutableStateOf(initialSettings) }
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    var nowPlaying by rememberSaveable { mutableStateOf(initialPlayer) }
    var queue by rememberSaveable { mutableStateOf(false) }
    var trackMenu by remember { mutableStateOf<Track?>(null) }
    var addToPlaylist by remember { mutableStateOf<Track?>(null) }
    var newPlaylist by remember { mutableStateOf(false) }
    var addSongs by remember { mutableStateOf(false) }
    var newPlaylistTrack by remember { mutableStateOf<Track?>(null) }
    var playlistTitle by remember { mutableStateOf("") }
    var playlistPosition by remember { mutableStateOf<Int?>(null) }
    var editPlaylist by remember { mutableStateOf(false) }
    var deletePlaylist by remember { mutableStateOf(false) }
    var removeTrack by remember { mutableStateOf<Track?>(null) }
    var storyTrack by remember { mutableStateOf<Track?>(null) }
    var navigationMenu by rememberSaveable { mutableStateOf(false) }
    var statsInfo by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val largeText = LocalDensity.current.fontScale > 1.3f
    // A screen at least 600 dp wide is a tablet, or an unfolded phone: it gets the rail in any orientation, and the content a limit to its width.
    val tablet = configuration.screenWidthDp >= 600
    val rail = (landscape || tablet) && !largeText
    val destinations = listOf(Destination.Home, Destination.Search, Destination.Library, Destination.Device)
    val destination = Destination.entries.firstOrNull { it.name == destinationName }?.takeIf { it in destinations } ?: destinations.first()
    val settingsPageOpen = SettingsPage.entries.firstOrNull { it.name == settingsPage }
    val closeSettings = { settings = false; settingsPage = null }
    val select: (Destination) -> Unit = { destinationName = it.name; closeSettings(); vm.closeAll() }
    val navigation = destinations.size > 1
    val welcome = !state.signedIn
    val snackbar = remember { SnackbarHostState() }
    val inDetail = state.detail != null || state.routes.isNotEmpty()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    // A download needs the permissions that apply to this version of Android. They are asked for when they are missing.
    val askPermissions = { val missing = Permissions.missing(context); if (missing.isNotEmpty()) permissions.launch(missing) }
    val openLogin = { startLogin(context, vm) }
    val openTrackMenu: (Track) -> Unit = { trackMenu = it; playlistPosition = null }
    // A page opens over everything else, so the player, the settings and the lists behind it make way.
    val openRelease: (Release) -> Unit = { release -> closeSettings(); nowPlaying = false; queue = false; vm.open(release) }
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(state.message) {
        state.message?.let { text ->
            val undo = state.undo
            val result = snackbar.showSnackbar(text, actionLabel = if (undo != null) undoLabel else null)
            vm.messageShown()
            if (result == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }
    LaunchedEffect(state.signedIn) { if (state.signedIn) vm.finishWelcome() }
    BackHandler(enabled = state.detail != null || settings || state.routes.isNotEmpty()) {
        if (settingsPageOpen != null) settingsPage = settingsPageOpen.parent?.name else if (settings) closeSettings() else vm.closeDetail()
    }

    // The permissions page follows the first screen when there is anything to ask. It stays until the listener continues.
    val setupPending = state.settings.setupPending
    val setup by rememberSaveable(setupPending) { mutableStateOf(setupPending && Permissions.missing(context).isNotEmpty()) }
    LaunchedEffect(setupPending, setup) { if (setupPending && !setup) vm.finishSetup() }
    if (welcome) WelcomeScreen(onSignIn = openLogin)
    else if (setup) SetupScreen(onContinue = vm::finishSetup)
    else Row(Modifier.fillMaxSize().then(if (landscape) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)) else Modifier)) {
    if (rail && navigation) OpenTuneRail(destinations, destination, select)
    Scaffold(modifier = Modifier.weight(1f),
        topBar = {
            val title = if (settings) stringResource(settingsPageOpen?.title ?: R.string.nav_settings) else state.detail?.let {
                when (it.type) { ReleaseType.ALBUM -> stringResource(R.string.type_album); ReleaseType.ARTIST -> stringResource(R.string.type_artist); ReleaseType.PLAYLIST -> stringResource(R.string.type_playlist); ReleaseType.MIX -> stringResource(R.string.type_mix); ReleaseType.VIDEO -> stringResource(R.string.type_video); ReleaseType.FOLDER -> stringResource(R.string.type_folder) }
            } ?: stringResource(destination.title)
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = if (settings || inDetail || largeText) 8.dp else 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (settings || inDetail) IconButton(onClick = { if (settingsPageOpen != null) settingsPage = settingsPageOpen.parent?.name else if (settings) closeSettings() else vm.closeDetail() }) { Icon(OpenTuneIcons.ArrowBack, stringResource(R.string.action_back)) }
                    else if (largeText && navigation) IconButton(onClick = { navigationMenu = true }) { Icon(OpenTuneIcons.Menu, stringResource(R.string.nav_open)) }
                    if (!settings && !inDetail && !largeText) {
                        Icon(OpenTuneIcons.Brand, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(9.dp))
                    }
                    Text(stringResource(R.string.brand_name), Modifier.weight(1f), fontFamily = AppFonts.body, fontSize = 11.sp, lineHeight = 16.sp,
                        letterSpacing = 2.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    if (!settings) {
                        if (destination == Destination.Library && !inDetail && state.signedIn && !state.settings.offline) IconButton(onClick = vm::sync, enabled = !state.syncing) { Icon(OpenTuneIcons.Refresh, stringResource(R.string.library_refresh)) }
                        state.detail?.takeIf { state.routes.isEmpty() }?.let { release ->
                            if (vm.canEdit(release)) IconButton(onClick = { playlistTitle = release.title; editPlaylist = true }) { Icon(OpenTuneIcons.Edit, stringResource(R.string.playlist_edit)) }
                            if (release.type == ReleaseType.PLAYLIST && state.signedIn && !release.isDevicePlaylist && !vm.canEdit(release)) IconButton(onClick = { vm.favoritePlaylist(release) }) {
                                val saved = state.playlists.any { it.id == release.id }
                                Icon(if (saved) OpenTuneIcons.Favorite else OpenTuneIcons.FavoriteBorder, if (saved) stringResource(R.string.playlist_unsave) else stringResource(R.string.playlist_save))
                            }
                            if (release.type == ReleaseType.ALBUM && state.signedIn) IconButton(onClick = { vm.favoriteAlbum(release) }) {
                                val saved = state.albums.any { it.id == release.id }
                                Icon(if (saved) OpenTuneIcons.Favorite else OpenTuneIcons.FavoriteBorder, if (saved) stringResource(R.string.album_unsave) else stringResource(R.string.album_save))
                            }
                        }
                        if (state.settings.offline) Icon(OpenTuneIcons.CloudOff, stringResource(R.string.status_offline), Modifier.padding(end = 8.dp), tint = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { settings = true }) { Icon(OpenTuneIcons.Settings, stringResource(R.string.nav_settings), Modifier.size(22.dp)) }
                    }
                }
                // On its side the screen has little height, and the rail already says which page it is: the big title is left out.
                if ((!inDetail || settings) && !(landscape && !settings)) Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = if (settingsPageOpen == SettingsPage.Statistics) 8.dp else 24.dp, top = if (largeText || landscape) 2.dp else 6.dp, bottom = if (landscape) 6.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f),
                        style = if (largeText || landscape || settingsPageOpen != null) AppFonts.title(30, 38) else if (destination == Destination.Home && !settings) AppFonts.title(52, 58) else AppFonts.title(44, 50))
                    // The explanation of the statistics sits on the line of the title.
                    if (settingsPageOpen == SettingsPage.Statistics) IconButton(onClick = { statsInfo = true }) { Icon(OpenTuneIcons.Info, stringResource(R.string.stats_info_label)) }
                }
            }
        },
        bottomBar = {
            Column {
                if (state.playback.track != null) MiniPlayer(state.playback, position, onOpen = { nowPlaying = true }, onPlay = vm::togglePlay, onNext = vm::next, onDismiss = vm::stopPlayback)
                if (!settings && !landscape && !tablet && !largeText && navigation) OpenTuneNavigation(destinations, destination, select)
                else Spacer(Modifier.navigationBarsPadding())
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) { Box(Modifier.fillMaxSize().widthIn(max = 960.dp)) {
            when {
                settings -> when (settingsPageOpen) {
                    SettingsPage.Repeats -> AvoidRepeatsPage(state, vm)
                    SettingsPage.Djai -> DjaiPage(state, vm)
                    SettingsPage.Statistics -> StatisticsPage(vm)
                    SettingsPage.Licenses -> LicencesPage { settingsPage = it.name }
                    SettingsPage.Apache, SettingsPage.DmSans, SettingsPage.Mono -> LicenceDocumentPage(settingsPageOpen)
                    null -> SettingsScreen(state, vm, openLogin) { settingsPage = it.name }
                }
                state.routes.isNotEmpty() -> PageScreen(state.routes.last(), state, vm, pagesVm, openTrackMenu)
                state.detail != null -> DetailScreen(state, vm, { track, index -> trackMenu = track; playlistPosition = index }, onAddSongs = { addSongs = true })
                destination == Destination.Home -> ListenScreen(state, vm, openLogin, { filter -> libraryFilter = filter; destinationName = Destination.Library.name },
                    { destinationName = Destination.Device.name }, openTrackMenu, { nowPlaying = true })
                destination == Destination.Search -> SearchScreen(state, vm, openLogin, openTrackMenu)
                destination == Destination.Library -> LibraryScreen(state, vm, openLogin, { newPlaylistTrack = null; playlistTitle = ""; newPlaylist = true }, openTrackMenu,
                    libraryFilter, { libraryFilter = it })
                else -> DownloadsScreen(state, vm, openLogin, openTrackMenu)
            }
        } }
    }
    }

    if (navigationMenu) ModalBottomSheet(shape = androidx.compose.ui.graphics.RectangleShape, dragHandle = { SheetHandle() }, onDismissRequest = { navigationMenu = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            destinations.forEach { item -> NavigationDrawerItem(selected = destination == item, label = { Text(stringResource(item.title)) },
                icon = { Icon(item.icon, null) }, modifier = Modifier.padding(horizontal = 12.dp),
                onClick = { select(item); navigationMenu = false }) }
        }
    }

    // The sign-in finishes in the browser, so a bar shows while the app exchanges the answer.
    if (state.signingIn) LinearProgressIndicator(Modifier.fillMaxWidth().statusBarsPadding())
    if (nowPlaying && state.playback.track != null) NowPlayingScreen(state, vm, onClose = { nowPlaying = false }, onQueue = { queue = true }, onMenu = openTrackMenu, onCredits = { track -> vm.showCredits("track", track.id, "${track.title} · ${track.artist}") }, onAlbum = { track -> openRelease(Release(track.albumId, track.album, track.artist, track.artwork)) },
        onArtist = { track -> openRelease(Release(track.artistId, track.artist.substringBefore(","), "", type = ReleaseType.ARTIST)) }, initialLyrics = initialLyrics)
    if (queue) QueueSheet(state, vm) { queue = false }
    trackMenu?.let { track ->
        ModalBottomSheet(shape = androidx.compose.ui.graphics.RectangleShape, dragHandle = { SheetHandle() }, onDismissRequest = { trackMenu = null; playlistPosition = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TrackArtwork(track, null, Modifier.size(52.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    IconButton(onClick = { trackMenu = null }) { Icon(OpenTuneIcons.Close, stringResource(R.string.track_menu_close)) }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                val saved = state.favorites.any { it.id == track.id }
                SettingRow(if (saved) OpenTuneIcons.Favorite else OpenTuneIcons.FavoriteBorder, if (saved) stringResource(R.string.track_unsave) else stringResource(R.string.track_save), onClick = { vm.favorite(track); trackMenu = null })
                SettingRow(OpenTuneIcons.PlaylistPlay, stringResource(R.string.track_play_next), onClick = { vm.queue(track, true); trackMenu = null })
                SettingRow(OpenTuneIcons.QueueMusic, stringResource(R.string.track_add_queue), onClick = { vm.queue(track); trackMenu = null })
                SettingRow(OpenTuneIcons.PlaylistAdd, stringResource(R.string.playlist_add), onClick = { addToPlaylist = track; trackMenu = null })
                state.detail?.takeIf { vm.canEdit(it) }?.let { release ->
                    playlistPosition?.let { position ->
                        if (position > 0) SettingRow(OpenTuneIcons.ArrowUpward, stringResource(R.string.track_move_up), onClick = { vm.moveInPlaylist(release, position, -1); trackMenu = null; playlistPosition = null })
                        if (position < state.detailTracks.lastIndex) SettingRow(OpenTuneIcons.KeyboardArrowDown, stringResource(R.string.track_move_down), onClick = { vm.moveInPlaylist(release, position, 1); trackMenu = null; playlistPosition = null })
                        SettingRow(OpenTuneIcons.PlaylistRemove, stringResource(R.string.track_remove_from_playlist), onClick = {
                            vm.removeFromPlaylist(release, track, position); trackMenu = null; playlistPosition = null
                        })
                    }
                }
                if (!track.onDevice) SettingRow(OpenTuneIcons.Download, stringResource(R.string.action_download), onClick = {
                    askPermissions()
                    vm.download(track); trackMenu = null
                })
                if (track.albumId.isNotBlank() && !track.isFile) SettingRow(OpenTuneIcons.Album, stringResource(R.string.track_go_album), onClick = {
                    openRelease(Release(track.albumId, track.album, track.artist, track.artwork)); trackMenu = null
                })
                if (track.artistId.isNotBlank() && !track.isFile) SettingRow(OpenTuneIcons.Person, stringResource(R.string.track_go_artist), onClick = {
                    openRelease(Release(track.artistId, track.artist.substringBefore(","), "", type = ReleaseType.ARTIST)); trackMenu = null
                })
                if (!track.isFile && state.signedIn && !state.settings.offline) {
                    SettingRow(OpenTuneIcons.Radio, stringResource(R.string.track_radio), onClick = { vm.playTrackRadio(track); trackMenu = null })
                    SettingRow(OpenTuneIcons.Info, stringResource(R.string.album_credits), onClick = { vm.showCredits("track", track.id, "${track.title} · ${track.artist}"); trackMenu = null })
                }
                if (!track.isFile) SettingRow(OpenTuneIcons.Share, stringResource(R.string.share_story_button), onClick = {
                    storyTrack = track; trackMenu = null
                })
                if (track.onDevice || (state.signedIn && !state.settings.offline)) SettingRow(OpenTuneIcons.Waves, stringResource(R.string.track_analyze), onClick = { vm.analyzeForDjai(track); trackMenu = null })
                val stored = state.local.firstOrNull { it.id == track.id } ?: track.takeIf(Track::onDevice)
                if (stored != null) SettingRow(OpenTuneIcons.RemoveCircleOutline, stringResource(R.string.track_remove_local), onClick = { removeTrack = stored; trackMenu = null })
            }
        }
    }
    if (statsInfo) AlertDialog(onDismissRequest = { statsInfo = false }, text = { Text(stringResource(R.string.stats_info)) },
        confirmButton = { TextButton(onClick = { statsInfo = false }) { Text(stringResource(R.string.action_close)) } })
    state.update?.let { found -> AlertDialog(onDismissRequest = vm::dismissUpdate, title = { Text(stringResource(R.string.update_title)) },
        text = { Text(stringResource(R.string.update_text, found.version)) },
        confirmButton = { TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(found.url))) }; vm.dismissUpdate() }) { Text(stringResource(R.string.update_download)) } },
        dismissButton = { TextButton(onClick = vm::dismissUpdate) { Text(stringResource(R.string.update_later)) } }) }
    storyTrack?.let { track -> StoryShareSheet(track, track.quality.orEmpty(), vm::storyDetails) { storyTrack = null } }
    state.video?.let { video -> VideoPlayerOverlay(video, state.settings.offline, vm::closeVideo) }
    state.credits?.let { credits -> CreditsSheet(credits, onClose = vm::dismissCredits, onPerson = { person ->
        vm.dismissCredits()
        openRelease(Release(person.id, person.name, "", type = ReleaseType.ARTIST))
    }) }
    addToPlaylist?.let { track ->
        AddToPlaylistSheet(track, state, vm, onNew = { newPlaylistTrack = track; addToPlaylist = null; newPlaylist = true }) { addToPlaylist = null }
    }
    if (newPlaylist) NewPlaylistDialog(accountAvailable = state.signedIn && !state.settings.offline && newPlaylistTrack?.isFile != true,
        onCreate = { name, home -> vm.createPlaylist(name, home, newPlaylistTrack); newPlaylist = false; newPlaylistTrack = null },
        onClose = { newPlaylist = false; newPlaylistTrack = null })
    if (addSongs) state.detail?.let { release -> AddSongsSheet(release, state, vm) { addSongs = false } }
    if (editPlaylist) state.detail?.let { release -> AlertDialog(onDismissRequest = { editPlaylist = false }, title = { Text(stringResource(R.string.playlist_edit)) }, text = {
        Column {
            OutlinedTextField(playlistTitle, { playlistTitle = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true)
            TextButton(onClick = { editPlaylist = false; deletePlaylist = true }) { Text(stringResource(R.string.playlist_delete), color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = playlistTitle.isNotBlank(), onClick = { vm.renamePlaylist(release, playlistTitle); editPlaylist = false; playlistTitle = "" }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = { editPlaylist = false }) { Text(stringResource(R.string.action_cancel)) } }) }
    if (deletePlaylist) state.detail?.let { release -> AlertDialog(onDismissRequest = { deletePlaylist = false }, title = { Text(stringResource(R.string.playlist_delete_title, release.title)) },
        text = { Text(stringResource(R.string.playlist_delete_text)) },
        confirmButton = { TextButton(onClick = { vm.deletePlaylist(release); deletePlaylist = false }) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { deletePlaylist = false }) { Text(stringResource(R.string.action_cancel)) } }) }
    removeTrack?.let { track -> AlertDialog(onDismissRequest = { removeTrack = null }, title = { Text(stringResource(R.string.track_remove_title)) },
        text = { Text(stringResource(R.string.track_remove_download_text)) },
        confirmButton = { TextButton(onClick = { vm.removeLocal(track); removeTrack = null }) { Text(stringResource(R.string.action_remove)) } },
        dismissButton = { TextButton(onClick = { removeTrack = null }) { Text(stringResource(R.string.action_cancel)) } }) }
}
