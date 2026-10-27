package ac.mdiq.podcini.ui.screens

import ac.mdiq.podcini.R
import ac.mdiq.podcini.playback.PlaybackService.Companion.playbackService
import ac.mdiq.podcini.playback.activeTheatresCount
import ac.mdiq.podcini.storage.database.feedCountFlow
import ac.mdiq.podcini.storage.database.getEpisodesCount
import ac.mdiq.podcini.storage.database.queuesLive
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.ui.compose.CustomTextStyles
import ac.mdiq.podcini.ui.compose.borderColor
import ac.mdiq.podcini.ui.compose.buttonColor
import ac.mdiq.podcini.ui.compose.textColor
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Logm
import ac.mdiq.podcini.utils.NetworkUtils.imageLoader
import ac.mdiq.podcini.utils.sessionLogsFlow
import ac.mdiq.podcini.utils.showMemoryUsage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.xilinjia.krdb.query.Sort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "NavDrawerScreen"

data class FeedBrief(val id: Long, val title: String?, val imageUrl: String?)

val LocalDrawerController = staticCompositionLocalOf<DrawerController?> { null }
val LocalDrawerState = staticCompositionLocalOf<DrawerState> { error("DrawerState not provided") }

@Composable
fun NavDrawerScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val drawerCtrl = LocalDrawerController.current
    val drawerState = LocalDrawerState.current

    val feedBriefs = remember { mutableStateListOf<FeedBrief>() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> { }
                Lifecycle.Event.ON_START -> { }
                Lifecycle.Event.ON_RESUME -> {}
                Lifecycle.Event.ON_STOP -> {}
                Lifecycle.Event.ON_DESTROY -> {}
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val theatresCount by activeTheatresCount.collectAsStateWithLifecycle()
    LaunchedEffect(drawerState.isOpen) {
        Logd(TAG) { "LaunchedEffect(drawerState.currentValue): ${drawerState.isOpen}" }
        if (drawerState.isOpen) withContext(Dispatchers.IO) {
            navMap[Screens.Library.name]?.count = "${feedCountFlow.value}: ${getEpisodesCount(null)}"
            navMap[Screens.Queues.name]?.count = "${queuesLive.size}: ${queuesLive.sumOf { it.size()}}"
            val feeds_ = realm.query(Feed::class).sort("lastPlayed", sortOrder = Sort.DESCENDING).limit(8).find()
            withContext(Dispatchers.Main) {
                feedBriefs.clear()
                for (f in feeds_) feedBriefs.add(FeedBrief(f.id, f.title, f.images.firstOrNull()?.href))
            }
        }
    }

    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val windowWidthDp = with(density) { windowSize.width.toDp() }
    val drawerWidth = min(350.dp,windowWidthDp * 0.7f)
    val myShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)

    ModalDrawerSheet(modifier = Modifier.width(drawerWidth).border(1.dp, MaterialTheme.colorScheme.tertiary, myShape),
        drawerContainerColor = MaterialTheme.colorScheme.surface, drawerTonalElevation = 0.dp, drawerShape = myShape, windowInsets = WindowInsets.systemBars) {
        Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface).fillMaxSize().padding(start = 10.dp, end = 5.dp, top = 10.dp, bottom = 10.dp)) {
            Box(modifier = Modifier.fillMaxWidth().padding( bottom = 10.dp)) {
                AsyncImage(model = R.drawable.teaser, imageLoader = imageLoader, contentDescription = "bgImage", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxWidth().height(30.dp))
                Box(modifier = Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.2f)))
                Text("Podcini.A", color = borderColor, style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Center))
            }
//            Spacer(Modifier.width(20.dp))
            for ((key, value) in navMap) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 15.dp)) {
                    Icon(imageVector = ImageVector.vectorResource(value.iconRes), tint = textColor, contentDescription = key, modifier = Modifier.padding(start = 10.dp))
                    Text(stringResource(value.nameRes), color = textColor, style = CustomTextStyles.titleCustom, modifier = Modifier.padding(start = 20.dp).clickable {
                        Logd(TAG) { "nav.key: $key" }
                        if (key == Screens.Library.name) feedIdsToUse = listOf()
                        navTo(value.navKey, PopMode.Clear)
                        drawerCtrl?.close()
                    })
                    Spacer(Modifier.weight(1f))
                    when {
                        value.count.isNotBlank() -> Text(value.count, color = textColor, modifier = Modifier.padding(end = 10.dp))
                        key == Screens.Logs.name -> {
                            Icon(imageVector = ImageVector.vectorResource(value.iconRes), tint = buttonColor, contentDescription = "recent", modifier = Modifier.padding(start = 10.dp).clickable {
                                Logm(TAG, "Recent logs:", sessionLogsFlow.value.reversed().take(5).joinToString("\n")); drawerCtrl?.close()
                            })
                            Spacer(Modifier.weight(0.3f))
                            Icon(imageVector = ImageVector.vectorResource(R.drawable.outline_memory_24), tint = buttonColor, contentDescription = "memory", modifier = Modifier.padding(start = 10.dp).clickable {
                                showMemoryUsage(); drawerCtrl?.close()
                            })
                            Spacer(Modifier.weight(0.1f))
                        }
                        key == Screens.Facets.name -> {
                            Icon(imageVector = ImageVector.vectorResource(R.drawable.outline_new_releases_24), tint = buttonColor, contentDescription = "new", modifier = Modifier.padding(start = 10.dp).clickable {
                                navTo(Facets(modeName = QuickAccess.New.name)); drawerCtrl?.close()
                            })
                            Spacer(Modifier.weight(0.3f))
                            Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_history), tint = buttonColor, contentDescription = "history", modifier = Modifier.padding(start = 10.dp).clickable {
                                navTo(Facets(modeName = QuickAccess.History.name)); drawerCtrl?.close()
                            })
                            Spacer(Modifier.weight(0.1f))
                        }
                        key == Screens.Search.name -> {
                            Icon(imageVector = ImageVector.vectorResource(R.drawable.outline_cloud_download_24), tint = buttonColor, contentDescription = "add feed", modifier = Modifier.padding(start = 10.dp).clickable {
                                navTo(FindFeeds, PopMode.Clear); drawerCtrl?.close()
                            })
                            Spacer(Modifier.weight(0.4f))
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth().clickable {
                navTo(Settings, PopMode.Clear)
                drawerCtrl?.close()
            }) {
                Icon(imageVector = Icons.Filled.Settings, tint = textColor, contentDescription = "settings", modifier = Modifier.padding(start = 10.dp))
                Text(stringResource(R.string.settings_label), color = textColor, style = CustomTextStyles.titleCustom, modifier = Modifier.padding(start = 20.dp))
                Spacer(Modifier.weight(1f))
                val playersRes = if (theatresCount == 1) R.drawable.teaser else R.drawable.ic_launcher_foreground
                AsyncImage(model = playersRes, contentDescription = "Players", modifier = Modifier.height(24.dp).clickable {
                    Logd(TAG) { "activeTheatres: $theatresCount" }
                    activeTheatresCount.value = if (theatresCount == 1) 2 else {
                        playbackService?.shutdownPlayer(1)
                        1
                    }
                    playerMinHeight = if (activeTheatresCount.value == 1) 100 else 210
                    playbackService?.switchPlayersMode()
                })
                Spacer(Modifier.width(10.dp))
            }
            HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                for ((id, title, imageUrl) in feedBriefs) {
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).clickable {
                        navTo(FeedDetails(feedId = id))
                        drawerCtrl?.close()
                        psState = PSState.PartiallyExpanded
                    }) {
                        AsyncImage(model = imageUrl, imageLoader = imageLoader, contentDescription = "imgvCover", placeholder = painterResource(R.drawable.ic_launcher_foreground), error = painterResource(R.drawable.ic_launcher_foreground), modifier = Modifier.width(40.dp).height(40.dp))
                        Text(title ?: "No title", color = textColor, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
            if (psState == PSState.Hidden) AsyncImage(model = R.drawable.teaser, contentDescription = "PlayerUI", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxWidth().height(60.dp).clickable { psState = PSState.PartiallyExpanded })
        }
    }
}

data class NavItem(val navKey: NavKey, val iconRes: Int, val nameRes: Int) {
    var count by mutableStateOf("")
}

private val navMap: LinkedHashMap<String, NavItem> = linkedMapOf(
    Screens.Library.name to NavItem(Library,R.drawable.ic_subscriptions, R.string.library),
    Screens.Queues.name to NavItem(Queues(), R.drawable.ic_playlist_play, R.string.queue_label),
    Screens.Facets.name to NavItem(Facets(), R.drawable.baseline_view_in_ar_24, R.string.facets),
    Screens.Search.name to NavItem(Search, R.drawable.ic_search, R.string.search_label),
    Screens.Logs.name to NavItem(Logs, R.drawable.ic_history, R.string.logs_label),
    Screens.Statistics.name to NavItem(Statistics, R.drawable.ic_chart_box, R.string.statistics_label),
)

interface DrawerController {
    fun isOpen(): Boolean
    fun open()
    fun close()
    fun toggle()
}