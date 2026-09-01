package ac.mdiq.podcini.ui.screens

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.sourcing.download.RequestType
import ac.mdiq.podcini.sourcing.feed.FeedUpdateManager.runOnceOrAsk
import ac.mdiq.podcini.sourcing.feed.FeedUpdater
import ac.mdiq.podcini.sourcing.sendFeed
import ac.mdiq.podcini.playback.base.theatres
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.sourcing.isExtFeed
import ac.mdiq.podcini.sourcing.typeClientMap
import ac.mdiq.podcini.storage.database.FeedAssistant
import ac.mdiq.podcini.storage.database.buildListInfo
import ac.mdiq.podcini.ui.compose.feedOperationText
import ac.mdiq.podcini.storage.database.getEpisodes
import ac.mdiq.podcini.storage.database.getEpisodesAsListFlow
import ac.mdiq.podcini.storage.database.getHistoryAsFlow
import ac.mdiq.podcini.storage.database.queueToVirtual
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.sourcing.feed.FeedUpdater.Companion.updateFeedFull
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.DownloadResult
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.storage.model.SubscriptionLog
import ac.mdiq.podcini.storage.model.SubscriptionLog.Companion.feedLogsMap
import ac.mdiq.podcini.storage.model.SubscriptionLog.Companion.takeCodePoints
import ac.mdiq.podcini.storage.model.allVolumes
import ac.mdiq.podcini.storage.specs.EpisodeFilter
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder.Companion.compareToNatural
import ac.mdiq.podcini.storage.specs.FeedFunding
import ac.mdiq.podcini.storage.specs.Rating
import ac.mdiq.podcini.storage.specs.Rating.Companion.fromCode
import ac.mdiq.podcini.storage.utils.AddLocalFolder
import ac.mdiq.podcini.storage.utils.persistedTrees
import ac.mdiq.podcini.storage.utils.toSafeUri
import ac.mdiq.podcini.ui.actions.ButtonTypes
import ac.mdiq.podcini.ui.actions.SwipeActions
import ac.mdiq.podcini.ui.actions.playActions
import ac.mdiq.podcini.ui.actions.streamActions
import ac.mdiq.podcini.ui.compose.ChooseRatingDialog
import ac.mdiq.podcini.ui.compose.CommentEditingDialog
import ac.mdiq.podcini.ui.compose.ConfirmDialog
import ac.mdiq.podcini.ui.compose.CustomTextStyles
import ac.mdiq.podcini.ui.compose.EpisodeLazyColumn
import ac.mdiq.podcini.ui.compose.EpisodeScreen
import ac.mdiq.podcini.ui.compose.EpisodeSortDialog
import ac.mdiq.podcini.ui.compose.EpisodesFilterDialog
import ac.mdiq.podcini.ui.compose.InforBar
import ac.mdiq.podcini.ui.compose.LayoutMode
import ac.mdiq.podcini.ui.compose.PlayRandom
import ac.mdiq.podcini.ui.compose.RemoveFeedDialog
import ac.mdiq.podcini.ui.compose.SendToDevice
import ac.mdiq.podcini.ui.compose.TagSettingDialog
import ac.mdiq.podcini.ui.compose.TagType
import ac.mdiq.podcini.ui.compose.borderColor
import ac.mdiq.podcini.ui.compose.episodeForInfo
import ac.mdiq.podcini.ui.compose.textColor
import ac.mdiq.podcini.ui.utils.HtmlToPlainText
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logs
import ac.mdiq.podcini.utils.Logt
import ac.mdiq.podcini.utils.formatAbbrev
import ac.mdiq.podcini.utils.formatDateTimeFlex
import ac.mdiq.podcini.utils.fullDateTimeString
import ac.mdiq.podcini.utils.isCallable
import ac.mdiq.podcini.utils.openInSystemDefault
import ac.mdiq.podcini.utils.shareLink
import ac.mdiq.podcini.utils.timeIt
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import io.github.xilinjia.krdb.query.Sort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


enum class FeedScreenMode {
    List,
    History,
    Info
}

enum class ADLIncExc {
    INCLUDE,
    EXCLUDE
}

class FeedDetailsVM(feedId: Long = 0L, modeName: String = FeedScreenMode.List.name): ViewModel() {
    val screenModeFlow = MutableStateFlow(FeedScreenMode.valueOf(modeName))

    var enableFilter by  mutableStateOf(true)

    val feedFlow: StateFlow<Feed?> = realm.query(Feed::class).query("id == $0", feedId).asFlow().map { change -> change.list.firstOrNull() }
    .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = null)

    val episodesFlow: StateFlow<List<Episode>> = combine(feedFlow.filterNotNull(), screenModeFlow, snapshotFlow { enableFilter })
        { feed, mode, enableFilter -> Triple(feed, mode, enableFilter) }.distinctUntilChanged().flatMapLatest { (feed, mode, enableFilter) ->
            Logd(TAG, "episodesFlow rebuilding flow")
            listIdentity = "FeedDetails.${feed.id}"
            when {
                mode == FeedScreenMode.Info -> emptyFlow()
                mode == FeedScreenMode.History -> {
                    listIdentity += ".History"
                    getHistoryAsFlow(feed.id).map { it.list }
                }
                enableFilter && feed.filterString.isNotBlank() -> {
                    listIdentity += ".${feed.filterString}.${feed.episodeSortOrder.name}"
                    try {
                        getEpisodesAsListFlow(feed.episodeFilter, feed.episodeSortOrder, feed.id)
                    } catch (e: Throwable) {
                        Loge(TAG, e, "getEpisodesAsFlow error, retry")
                        val feed_ = upsert(feed) {
                            it.episodeFilter = EpisodeFilter("")
                            it.episodeSortOrder = EpisodeSortOrder.DATE_DESC
                        }
                        getEpisodesAsListFlow(feed_.episodeFilter, feed_.episodeSortOrder, feed_.id)
                    }
                }
                else -> {
                    listIdentity += "..${feed.episodeSortOrder.name}"
                    getEpisodesAsListFlow(EpisodeFilter(""), feed.episodeSortOrder, feed.id)
                }
            }.map {
                when (feed.episodeSortOrder) {
                    EpisodeSortOrder.EPISODE_TITLE_ASC -> it.sortedWith { episode, episode1 -> episode.title?.compareToNatural(episode1.title?:"") ?: -1 }
                    EpisodeSortOrder.EPISODE_TITLE_DESC -> it.sortedWith { episode, episode1 -> episode1.title?.compareToNatural(episode.title?:"") ?: -1 }
                    else -> it
                }

            }
        }.distinctUntilChanged().stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5_000), initialValue = emptyList())

    var listIdentity by  mutableStateOf("")
    var showHeader by mutableStateOf(true)
    var listInfoText by mutableStateOf("")

    var feedEpisodesSize by mutableIntStateOf(0)

    val logs: List<DownloadResult>

    init {
        Logd(TAG, "FeedDetailsVM init feedId: $feedId")
        timeIt("$TAG start of init")
        feedEpisodesSize = realm.query(Episode::class).query("feedId == $feedId").count().find().toInt()
        logs = realm.query(DownloadResult::class).query("feedfileId == $feedId AND feedfileType == ${RequestType.FEED.code}").sort("completionTime",  Sort.DESCENDING).find()
        timeIt("$TAG end of init")
    }

    override fun onCleared() {
        Logd(TAG, "FeedDetailsVM onCleared")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedDetailsScreen(feedId: Long = 0L, modeName: String = FeedScreenMode.List.name) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val context by rememberUpdatedState(LocalContext.current)
    val drawerController = LocalDrawerController.current

    val vm: FeedDetailsVM = viewModel(key = feedId.toString(), factory = viewModelFactory { initializer { FeedDetailsVM(feedId = feedId, modeName = modeName) } })

    val feed by vm.feedFlow.collectAsStateWithLifecycle()
    val screenMode by vm.screenModeFlow.collectAsStateWithLifecycle()

    val deletionLogs = remember { mutableStateSetOf<SubscriptionLog>() }
    LaunchedEffect(feed?.id) {
        deletionLogs.clear()
        if (feed != null) {
            val results = mutableSetOf<SubscriptionLog>()
            feedLogsMap?.get(feed!!.id.toString())?.apply { results.add(this) }
            if (!feed?.title.isNullOrBlank()) feedLogsMap?.get(feed!!.title)?.apply { results.add(this) }
            if (!feed?.downloadUrl.isNullOrBlank()) feedLogsMap?.get(feed!!.downloadUrl)?.apply { results.add(this) }
            feed?.description?.takeCodePoints(100).takeIf { !it.isNullOrBlank() }.apply { feedLogsMap?.get(this)?.apply { results.add(this) } }
            if (results.isNotEmpty()) deletionLogs.addAll(results)
        }
    }

    val swipeActions = remember { SwipeActions(TAG) }

    val connectLocalFolderLauncher: ActivityResultLauncher<Uri?> = rememberLauncherForActivityResult(contract = AddLocalFolder()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val context = getAppContext()
        persistedTrees.add(uri)
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runOnIOScope {
            try {
                if (feed != null) {
                    val feed_ = upsert(feed!!) {
                        it.downloadUrl = uri.toString()
                        it.isLocal = true
                    }
                    updateFeedFull(feed_, removeUnlistedItems = true)
                }
                Logt(TAG, "Folder $uri connected " + context.getString(R.string.OK))
            } catch (e: Throwable) { Loge(TAG, e.localizedMessage ?: "No message") }
        }
    }

    DisposableEffect(lifecycleOwner) {
        Logd(TAG, "in DisposableEffect")
        val observer = LifecycleEventObserver { _, event ->
            Logd(TAG, "DisposableEffect LifecycleEventObserver: $event")
            when (event) {
                Lifecycle.Event.ON_CREATE -> {
                    Logd(TAG, "ON_CREATE feedId: $feedId")
                    //                    val testNum = 1
                    //                    val eList = realm.query(Episode::class).query("feedId == ${vm.feedID} AND playState == ${PlayState.SOON.code} SORT(pubDate DESC) LIMIT($testNum)").find()
                    //                    Logd(TAG, "test eList: ${eList.size}")
                }
                Lifecycle.Event.ON_START -> {}
                Lifecycle.Event.ON_RESUME -> {}
                Lifecycle.Event.ON_STOP -> {}
                Lifecycle.Event.ON_DESTROY -> {}
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            Logd(TAG, "DisposableEffect onDispose")
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val showConnectLocalFolderConfirm = remember { mutableStateOf(false) }
    var showChooseRatingDialog by remember { mutableStateOf(false) }
    var showRemoveFeedDialog by remember { mutableStateOf(false) }
    var showFilterDialog by remember {  mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showTagsSettingDialog by remember { mutableStateOf(false) }
    var showToDeviceDialog by remember { mutableStateOf(false) }

    val episodes by vm.episodesFlow.collectAsStateWithLifecycle()
    LaunchedEffect(episodes.size, feed?.id) {
        Logd(TAG, "LaunchedEffect(episodes.size)")
        vm.listInfoText = buildListInfo(episodes, vm.feedEpisodesSize, feed)
    }

    LaunchedEffect(feedOperationText) {
        if (feedOperationText.isEmpty()) vm.feedEpisodesSize = realm.query(Episode::class).query("feedId == $feedId").count().find().toInt()
    }

    LaunchedEffect(modeName) { vm.screenModeFlow.value = (FeedScreenMode.valueOf(modeName)) }

    @Composable
    fun OpenDialogs() {
        ConfirmDialog(0, stringResource(R.string.reconnect_local_folder_warning), showConnectLocalFolderConfirm) {
            try { connectLocalFolderLauncher.launch(null) } catch (e: ActivityNotFoundException) { Logs(TAG, e, "No activity found. Should never happen...") }
        }

        if (showChooseRatingDialog) ChooseRatingDialog(listOf(feed!!)) { showChooseRatingDialog = false }

        if (showRemoveFeedDialog) RemoveFeedDialog(listOf(feed!!), onDismiss = { showRemoveFeedDialog = false }) { navBack() }

        if (feed != null && showFilterDialog) {
            vm.showHeader = false
            EpisodesFilterDialog(filter_ = feed!!.episodeFilter, onDismiss = {
                vm.showHeader = true
                showFilterDialog = false
            }) { filter ->
                Logd(TAG, "persist Episode Filter(): feedId = [${feed?.id}], andOr = ${filter.andOr}, ${filter.propertySet.size} filterValues = ${filter.propertySet}")
                runOnIOScope { upsert(feed!!) { it.episodeFilter = filter } }
            }
        }

        if (feed != null && showSortDialog) {
            vm.showHeader = false
            EpisodeSortDialog(initOrder = feed!!.episodeSortOrder, feed = feed, onDismiss = {
                vm.showHeader = true
                showSortDialog = false
            }) { order ->
                Logd(TAG, "persist Episode SortOrder_")
                runOnIOScope { upsert(feed!!) { it.episodeSortOrder = order ?: EpisodeSortOrder.DATE_DESC } }
            }
        }

        swipeActions.ActionOptionsDialog()

        if (showTagsSettingDialog && feed != null) TagSettingDialog(TagType.Feed, feed!!.tags, onDismiss = { showTagsSettingDialog = false }) { tags ->
            runOnIOScope {
                upsert(feed!!) {
                    it.tags.clear()
                    it.tags.addAll(tags)
                }
            }
        }

        if (showToDeviceDialog) SendToDevice(onDismiss = { showToDeviceDialog = false}) { host, port ->
            runOnIOScope { sendFeed(host, port, feed!!.id) { showToDeviceDialog =  false } }
        }
    }

    val lazyListState = rememberLazyListState()
    fun onImgLongClick() {
        for (i in 0..1) {
            if (theatres[i].mPlayerFlow.value?.curMediaFlow?.value?.feedId == feedId) {
                if (screenMode == FeedScreenMode.List) {
                    if (episodes.size > 5) {
                        val index = episodes.indexOfFirst { it.id == theatres[i].mPlayerFlow.value?.curMediaFlow?.value?.id }
                        if (index >= 0) scope.launch { lazyListState.scrollToItem(index) }
                        else Logt(TAG, "can not find curMediaFlow.value to scroll to")
                    } else Logt(TAG, "only scroll when episodes number is larger than 5")
                } else vm.screenModeFlow.value = (FeedScreenMode.List)
            } else if (theatres[i].mPlayerFlow.value?.curMediaFlow?.value?.feedId != null) navTo(FeedDetails(feedId = theatres[i].mPlayerFlow.value?.curMediaFlow?.value!!.feedId!!))
        }
    }

    val maxHeaderHeight = 60.dp
    val density = LocalDensity.current
    val maxHeaderPx = with(density) { maxHeaderHeight.toPx() }
    val minHeaderPx = with(density) { 0.dp.toPx() }
    val headerHeightPx = remember { mutableFloatStateOf(with(density) { maxHeaderHeight.toPx() }) }
    val currentHeaderDp = with(density) { headerHeightPx.floatValue.toDp() }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val newHeight = headerHeightPx.floatValue + delta
                headerHeightPx.floatValue = newHeight.coerceIn(minHeaderPx, maxHeaderPx)
                return if (headerHeightPx.floatValue > minHeaderPx && headerHeightPx.floatValue < maxHeaderPx) Offset(x = 0f, y = delta) else { Offset.Zero }
            }
        }
    }

    @Composable
    fun TopHeader() {
        var expanded by remember { mutableStateOf(false) }
        val buttonAltColor = lerp(MaterialTheme.colorScheme.tertiary, Color.Green, 0.5f)

        Box(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
            AsyncImage(model = feed?.imageUrl?:"", contentDescription = "bgImage", contentScale = ContentScale.FillBounds, error = painterResource(R.drawable.teaser), modifier = Modifier.matchParentSize().blur(radiusX = 5.dp, radiusY = 5.dp))
            Box(modifier = Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)))
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ImageVector.vectorResource(R.drawable.outline_square_dot_24), contentDescription = "Open Drawer", modifier = Modifier.padding(end = 10.dp).clickable { drawerController?.open() } )
                    AsyncImage(model = feed?.imageUrl ?: "", alignment = Alignment.TopStart, contentDescription = "imgvCover", error = painterResource(R.drawable.ic_launcher_foreground), modifier = Modifier.width(24.dp).height(24.dp).border(2.dp, MaterialTheme.colorScheme.tertiary).combinedClickable(
                        onClick = { if (feed != null) vm.screenModeFlow.value = if (screenMode == FeedScreenMode.List) FeedScreenMode.Info else FeedScreenMode.List },
                        onLongClick = { onImgLongClick() }))
                    Spacer(Modifier.weight(1f))
                    if (screenMode == FeedScreenMode.List) {
                        val isFiltered = remember(feed?.filterString, feed?.episodeFilter?.propertySet) { !feed?.filterString.isNullOrBlank() && !feed?.episodeFilter?.propertySet.isNullOrEmpty() }
                        IconButton(onClick = { showSortDialog = true }) { Icon(imageVector = ImageVector.vectorResource(R.drawable.arrows_sort), contentDescription = "butSort") }
                        val filterButtonColor = if (vm.enableFilter) if (isFiltered) buttonAltColor else textColor else Color.Red
                        if (feed != null) Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_filter_white), tint = filterButtonColor, contentDescription = "butFilter", modifier = Modifier.padding(horizontal = 5.dp).combinedClickable(
                            onClick = { if (vm.enableFilter) showFilterDialog = true },
                            onLongClick = { if (isFiltered) vm.enableFilter = !vm.enableFilter })
                        )
                    }
                    val histColor = if (screenMode != FeedScreenMode.History) textColor else buttonAltColor
                    if (feed != null) IconButton(onClick = {
                        vm.screenModeFlow.value = when(screenMode) {
                            FeedScreenMode.List -> FeedScreenMode.History
                            FeedScreenMode.History -> FeedScreenMode.List
                            else -> FeedScreenMode.History
                        }
                    }) { Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_history), tint = histColor, contentDescription = "history") }
                    if (feed?.queue != null) IconButton(onClick = {
                        navTo(Queues(id=feed?.queue?.id ?: -1L))
                        psState = PSState.PartiallyExpanded
                    }) { Icon(imageVector = ImageVector.vectorResource(R.drawable.playlist_play), contentDescription = "queue") }
                    IconButton(onClick = { navTo(Search) }) { Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_search), contentDescription = "search") }
                    if (feed != null) {
                        Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                            IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                            DropdownMenu(expanded = expanded, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, borderColor), onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.settings_label)) }, onClick = {
                                    feedsToSet = listOf(feed!!)
                                    navTo(FeedsSettings)
                                    expanded = false
                                })
                                if (!feed?.downloadUrl.isNullOrBlank()) DropdownMenuItem(text = { Text(stringResource(R.string.share_label)) }, onClick = {
                                    shareLink(context, feed?.downloadUrl ?: "")
                                    expanded = false
                                })
                                if (!feed?.link.isNullOrBlank()) DropdownMenuItem(text = { Text(stringResource(R.string.visit_website_label)) }, onClick = {
                                    val isCallable = if (!feed?.link.isNullOrEmpty()) isCallable(Intent(Intent.ACTION_VIEW, feed!!.link!!.toSafeUri())) else false
                                    if (isCallable) openInSystemDefault(feed!!.link!!)
                                    else Loge(TAG, "feed link is not valid: ${feed?.link}")
                                    expanded = false
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.transfer_to_device)) }, onClick = {
                                    showToDeviceDialog = true
                                    expanded = false
                                })
                                if (feed?.isLocal == true) DropdownMenuItem(text = { Text(stringResource(R.string.reconnect_local_folder)) }, onClick = {
                                    showConnectLocalFolderConfirm.value = true
                                    expanded = false
                                })
                                val isExtFeed = remember(feed?.id) { isExtFeed(feed) }
                                if (vm.feedEpisodesSize > 0 && !isExtFeed) DropdownMenuItem(text = { Text(stringResource(R.string.fetch_size)) }, onClick = {
                                    feedOperationText = context.getString(R.string.fetch_size)
                                    scope.launch(Dispatchers.IO) {
                                        val feedEpisodes = getEpisodes(null, null, feedId, copy = false)
                                        for (e in feedEpisodes) e.fetchMediaSize(force = true)
                                        withContext(Dispatchers.Main) { feedOperationText = "" }
                                    }
                                    expanded = false
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.clean_up)) }, onClick = {
                                    feedOperationText = context.getString(R.string.clean_up)
                                    runOnIOScope {
                                        val f = realm.copyFromRealm(feed!!)
                                        FeedAssistant(f).clear()
                                        upsert(f) {}
                                        withContext(Dispatchers.Main) { feedOperationText = "" }
                                    }
                                    expanded = false
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.refresh_label)) }, onClick = {
                                    runOnIOScope { FeedUpdater(listOf(feed!!), doItAnyway = true).start() }
                                    expanded = false
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.refresh_complete_feed)) }, onClick = {
                                    runOnIOScope { FeedUpdater(listOf(feed!!), fullUpdate = true, doItAnyway = true, removeUnlisted = true).start() }
                                    expanded = false
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.remove_feed_label)) }, onClick = {
                                    showRemoveFeedDialog = true
                                    expanded = false
                                })
                            }
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().height(currentHeaderDp)) {
                    Text(feed?.title ?: "No title", color = textColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (feed != null) {
                        val ratingIconRes = remember(feed?.rating) {  Rating.fromCode(feed?.rating?:0).res }
                        Icon(imageVector = ImageVector.vectorResource(ratingIconRes), tint = MaterialTheme.colorScheme.tertiary, contentDescription = "rating", modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 5.dp).background(MaterialTheme.colorScheme.tertiaryContainer).clickable { showChooseRatingDialog = true })
                    }
                }
                if (screenMode != FeedScreenMode.Info) InforBar(swipeActions) {
                    if (feedOperationText.isNotBlank()) Text(feedOperationText, style = MaterialTheme.typography.bodyMedium)
                    else {
                        val scoreText = remember(feed?.score, feed?.scoreCount) { if (feed != null) (feed!!.score).toString() + " (" + feed!!.scoreCount + ") " else "" }
                        if (scoreText.isNotBlank()) {
                            Text(scoreText, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.weight(0.1f))
                        }
                        Text(vm.listInfoText, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.weight(0.1f))
                        PlayRandom(episodes)
                    }
                }
            }
        }
    }

    @Composable
    fun InfoUI() {
        var showEditComment by remember { mutableStateOf(false) }
        val localTime = remember { nowInMillis() }
        var editCommentText by remember { mutableStateOf(TextFieldValue(feed?.comment ?: "")) }
        if (feed != null && showEditComment) CommentEditingDialog(textState = editCommentText, onTextChange = { editCommentText = it }, onDismiss = {showEditComment = false},
            onSave = {
                runOnIOScope {
                    upsert(feed!!) {
                        it.comment = editCommentText.text
                        it.commentTime = localTime
                    }
                }
            })
        var showFeedStats by remember { mutableStateOf(false) }
        if (showFeedStats) FeedStatisticsDialog(feed?.title?: "No title", feed?.id?:0, 0, Long.MAX_VALUE) { showFeedStats = false }

        Column(modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp)) {
            SelectionContainer {
                Column {
                    Text(feed?.title ?: "No title", color = textColor, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
                    Text(stringResource(R.string.by) + ": " + (feed?.author?.ifBlank { "Anonymous" } ?: "Anonymous"), color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) {
                        Text(stringResource(R.string.score) + ": " + (feed?.score).toString() + " (" + feed?.scoreCount + ")", textAlign = TextAlign.End, color = textColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.weight(0.2f))
                        Text(stringResource(R.string.episodes_label) + ": " + (vm.feedEpisodesSize).toString(), textAlign = TextAlign.End, color = textColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (feed?.inNormalVolume != true) Text(stringResource(R.string.archived_feed), color = textColor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp, bottom = 4.dp))
                    if (deletionLogs.isNotEmpty()) {
                        Text(stringResource(R.string.feed_likely_removed), color = MaterialTheme.colorScheme.primary, style = CustomTextStyles.titleCustom, modifier = Modifier.padding(start = 5.dp))
                        for (sLog in deletionLogs) {
                            Text(sLog.comment, color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 15.dp, bottom = 5.dp))
                            val ratingRes = remember(sLog.id) { fromCode(sLog.rating).res }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 15.dp, bottom = 5.dp)) {
                                Text(stringResource(R.string.rating_label), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 5.dp))
                                Icon(imageVector = ImageVector.vectorResource(ratingRes), tint = MaterialTheme.colorScheme.tertiary, contentDescription = null)
                            }
                            if (!sLog.description.isNullOrBlank()) Text(sLog.description ?: "", color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 15.dp, bottom = 5.dp))
                            val cancelDate = remember(sLog.id) { formatAbbrev(sLog.cancelDate) }
                            Text(stringResource(R.string.removed_on) + ": " + cancelDate, color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 15.dp, bottom = 10.dp))
                        }
                    }
                    Text(stringResource(R.string.description_label), color = textColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    Text(HtmlToPlainText.getPlainText(feed?.description ?: ""), color = textColor, style = MaterialTheme.typography.bodyMedium)
                }
            }

            val curVolumeName = remember(feed?.volumeId) { if (feed?.volumeId == -1L) "None" else allVolumes.find { it.id == feed?.volumeId }?.name ?: "None" }
            Text("Parent volume: $curVolumeName", color = textColor, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp, bottom = 5.dp))

            Text("Associated queue: ${feed?.queue?.name?:"None"}", color = textColor, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp, bottom = 5.dp))

            Text("Tags: ${feed?.tagsAsString?:""}", color = MaterialTheme.colorScheme.primary, style = CustomTextStyles.titleCustom, modifier = Modifier.padding(top = 10.dp, bottom = 5.dp).clickable { showTagsSettingDialog = true })
            Text(stringResource(R.string.comments) + if (feed?.comment.isNullOrBlank()) " (Add)" else "", color = MaterialTheme.colorScheme.primary, style = CustomTextStyles.titleCustom,
                modifier = Modifier.padding(top = 10.dp, bottom = 5.dp).clickable {
                    editCommentText = TextFieldValue((if (feed?.comment.isNullOrBlank()) "" else feed!!.comment + "\n") + fullDateTimeString(localTime) + ":\n")
                    showEditComment = true
                })
            if (!feed?.comment.isNullOrBlank()) SelectionContainer { Text(feed?.comment ?: "", color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 15.dp, bottom = 10.dp)) }

            Text(stringResource(R.string.statistics_label), color = textColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            Row {
                TextButton({ showFeedStats = true }) { Text(stringResource(R.string.this_podcast)) }
                Spacer(Modifier.width(20.dp))
                TextButton({ navTo(Statistics) }) { Text(stringResource(R.string.all_podcasts)) }
            }
            if (feed?.isSynthetic() == false) {
                Text(stringResource(R.string.feeds_related_to_author), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp).clickable {
                        searchFeedsOnline(query = "${feed?.author} podcasts")
                        navTo(FindFeeds)
                    })
                Text(stringResource(R.string.last_full_update) + ": ${formatDateTimeFlex(feed?.lastFullUpdateTime?:0L)}", modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                if (vm.logs.isNotEmpty()) {
                    var showLogs by remember { mutableStateOf(false) }
                    Text(stringResource(R.string.logs), color = MaterialTheme.colorScheme.primary, style = CustomTextStyles.titleCustom, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).clickable { showLogs = !showLogs})
                    if (showLogs) Column(modifier = Modifier.padding(10.dp)) {
                        for (log in vm.logs) {
                            val message = stringResource(if (!log.isSuccessful) R.string.failed else R.string.download_successful)
                            Row {
                                Text(formatDateTimeFlex(log.completionTime))
                                Text(": $message", color = textColor)
                            }
                            Column(modifier = Modifier.padding(start = 10.dp)) {
                                if (log.reasonDetailed.isNotBlank()) Text(log.reasonDetailed)
                                if (!log.isSuccessful) Text(stringResource(log.reason?.res ?: R.string.download_error_error_unknown))
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
                AsyncImage(model = feed?.imageUrl ?: "", contentDescription = "imgvCover", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().padding(10.dp))
                Text(text = feed?.downloadUrl ?: "", color = textColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 15.dp).combinedClickable(
                    onClick = { if (!feed?.downloadUrl.isNullOrBlank()) openInSystemDefault(feed!!.downloadUrl!!) },
                    onLongClick = {
                        if (!feed?.downloadUrl.isNullOrBlank()) {
                            val url: String = feed!!.downloadUrl!!
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText(url, url))
                            Logt(TAG, context.getString(R.string.copied_to_clipboard))
                        }
                    }
                ))
                if (!feed?.paymentLinkList.isNullOrEmpty()) {
                    Text(stringResource(R.string.support_funding_label), color = textColor, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    fun fundingText(): String {
                        val fundingList: MutableList<FeedFunding> = feed!!.paymentLinkList
                        val i: MutableIterator<FeedFunding> = fundingList.iterator()
                        while (i.hasNext()) {
                            val funding: FeedFunding = i.next()
                            for (other in fundingList) {
                                if (other.url == funding.url) {
                                    if (other.content != null && funding.content != null && other.content!!.length > funding.content!!.length) {
                                        i.remove()
                                        break
                                    }
                                }
                            }
                        }
                        val sb = StringBuilder()
                        val supportPodcast = getAppContext().resources.getString(R.string.support_podcast)
                        for (funding in fundingList) {
                            sb.append(if (funding.content == null || funding.content!!.isEmpty())  supportPodcast else funding.content).append(" ").append(funding.url)
                            sb.append("\n")
                        }
                        return StringBuilder(sb.toString().trim()).toString()
                    }
                    val fundText = remember { fundingText() }
                    Text(fundText, color = textColor)
                }
            }
        }
    }

    DisposableEffect(screenMode, vm.enableFilter, episodeForInfo) {
        Logd(TAG, "DisposableEffect feedScreenMode: $screenMode")
        if (screenMode !in listOf(FeedScreenMode.Info, FeedScreenMode.List) || !vm.enableFilter || episodeForInfo != null) handleBackSubScreens.add(TAG)
        else handleBackSubScreens.remove(TAG)
        onDispose { handleBackSubScreens.remove(TAG) }
    }

    BackHandler(enabled = handleBackSubScreens.contains(TAG)) {
        Logd(TAG, "BackHandler")
        when {
            episodeForInfo != null -> episodeForInfo = null
            !vm.enableFilter -> vm.enableFilter = true
            vm.screenModeFlow.value != FeedScreenMode.List -> vm.screenModeFlow.value = FeedScreenMode.List
        }
    }

    OpenDialogs()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(topBar = { TopHeader() }) { innerPadding ->
            if (screenMode in listOf(FeedScreenMode.List, FeedScreenMode.History)) {
                Column(modifier = Modifier.padding(innerPadding).fillMaxSize().background(MaterialTheme.colorScheme.surface).nestedScroll(nestedScrollConnection)) {
                    val player0 by theatres[0].mPlayerFlow.collectAsStateWithLifecycle()
                    val player1 by theatres[1].mPlayerFlow.collectAsStateWithLifecycle()
                    val curMedia0 by player0?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
                    val curMedia1 by player1?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
                    val scrollToOnStart = remember(episodes.size, curMedia0?.id, curMedia1?.id, screenMode) {
                        when {
                            screenMode == FeedScreenMode.History || screenMode == FeedScreenMode.Info -> -1
                            curMedia0?.feedId == feedId -> episodes.indexOfFirst { it.id == curMedia0?.id }
                            curMedia1?.feedId == feedId -> episodes.indexOfFirst { it.id == curMedia1?.id }
                            else -> -1
                        }
                    } //                Logd(TAG, "feed?.prefActionType: ${feed?.prefActionType}")
                    val actionButtonName = remember(feed?.prefActionType, feed?.downloadUrl) {
                        when {
                            feed == null -> null
                            feed?.isLocal == true -> ButtonTypes.PLAY.name
                            feed?.prefActionType != null -> feed!!.prefActionType!!
                            feed?.downloadUrl == null -> null
                            else -> {
                                val client = if (feed?.type != null) typeClientMap[feed!!.type!!] else null
                                if (client != null) ButtonTypes.STREAM.name else null
                            }
                        }
                    }
                    EpisodeLazyColumn(
                        episodes, feed = feed, layoutMode = if (feed?.useWideLayout == true) LayoutMode.WideImage.code else LayoutMode.Normal.code,
                        swipeActions = swipeActions, lazyListState = lazyListState, scrollToOnStart = scrollToOnStart,
                        refreshCB = {
                            when {
                                feed == null -> Logt(TAG, "feed is null, can not refresh")
                                feed!!.isSynthetic() -> {
                                    val eps = realm.query(Episode::class).query("feedId == ${feed!!.id}").find()
                                    val count = eps.size
                                    val dur = eps.sumOf { it.duration }
                                    upsertBlk(feed!!) {
                                        it.episodesCount = count
                                        it.totleDuration = dur.toLong()
                                    }
                                    Logt(TAG, "episode count updated for synthetic feed: $count")
                                }
                                feed!!.inNormalVolume -> runOnceOrAsk(feeds = listOf(feed!!))
                                else -> Logt(TAG, "feed is archived, can not refresh")
                            }
                        },
                        selectModeCB = { vm.showHeader = !it },
                        preferSingleAction = screenMode == FeedScreenMode.History,
                        actionButtonType = if (screenMode == FeedScreenMode.List && actionButtonName != null) ButtonTypes.valueOf(actionButtonName) else null,
                        actionButtonCB = { e, type ->
                            Logd(TAG, "actionButtonCB type: $type ${e.feed?.id} ${feed?.id}")
                            if (e.feed?.id == feed?.id) {
                                if (type in streamActions + playActions + listOf(ButtonTypes.PLAY_LOCAL)) runOnIOScope { upsert(feed!!) { it.lastPlayed = nowInMillis() } }
                                if (type in listOf(ButtonTypes.PLAY, ButtonTypes.PLAY_LOCAL, ButtonTypes.STREAM)) runOnIOScope { queueToVirtual(e, episodes, vm.listIdentity, feed!!.episodeSortOrder, true) }
                            }
                        },
                    )
                }
            } else Column(modifier = Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surface)) { InfoUI() }
        }
        if (episodeForInfo != null) EpisodeScreen(episodeForInfo!!, listFlow = vm.episodesFlow)
    }
}

private val TAG = Screens.FeedDetails.name
