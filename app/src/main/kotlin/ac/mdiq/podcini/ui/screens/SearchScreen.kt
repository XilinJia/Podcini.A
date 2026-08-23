package ac.mdiq.podcini.ui.screens

import ac.mdiq.podcini.R
import ac.mdiq.podcini.net.searcher.AppleMediaSearcher
import ac.mdiq.podcini.playback.base.InTheatre.actQueue
import ac.mdiq.podcini.shared.EpisodeIPC
import ac.mdiq.podcini.shared.MediaSearcher
import ac.mdiq.podcini.shared.getEntityId
import ac.mdiq.podcini.sources.clientBySearcher
import ac.mdiq.podcini.sources.sourceClients
import ac.mdiq.podcini.storage.database.appAttribsFlow
import ac.mdiq.podcini.storage.database.queueToVirtual
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.storage.model.PAFeed
import ac.mdiq.podcini.storage.model.SearchHistorySize
import ac.mdiq.podcini.storage.model.tmpQueue
import ac.mdiq.podcini.storage.model.toEpisode
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder.Companion.reorderWith
import ac.mdiq.podcini.storage.specs.FeedType
import ac.mdiq.podcini.storage.specs.Rating
import ac.mdiq.podcini.storage.utils.durationInHours
import ac.mdiq.podcini.ui.actions.ButtonTypes
import ac.mdiq.podcini.ui.actions.SwipeActions
import ac.mdiq.podcini.ui.compose.AmendSyntheticFeed
import ac.mdiq.podcini.ui.compose.CommonPopupCard
import ac.mdiq.podcini.ui.compose.EpisodeLazyColumn
import ac.mdiq.podcini.ui.compose.EpisodeScreen
import ac.mdiq.podcini.ui.compose.EpisodeSortDialog
import ac.mdiq.podcini.ui.compose.InforBar
import ac.mdiq.podcini.ui.compose.LayoutMode
import ac.mdiq.podcini.ui.compose.PlayRandom
import ac.mdiq.podcini.ui.compose.SearchBarRow
import ac.mdiq.podcini.ui.compose.borderColor
import ac.mdiq.podcini.ui.compose.episodeForInfo
import ac.mdiq.podcini.ui.compose.textColor
import ac.mdiq.podcini.ui.utils.SearchAlgo
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.formatLargeInteger
import ac.mdiq.podcini.utils.formatWithGrouping
import androidx.activity.compose.BackHandler
import androidx.collection.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
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
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.github.xilinjia.krdb.notifications.ResultsChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

private var curSearchString by mutableStateOf("")
fun setSearchTerms(query: String? = null) {
    Logd("setSearchTerms", "query: $query")
    if (query != null) {
        curSearchString = query
        saveToSearchHistory()
    }
}

private fun saveToSearchHistory() {
    runOnIOScope {
        upsert(appAttribsFlow!!.value) {
            if (curSearchString in it.searchHistory) it.searchHistory.remove(curSearchString)
            it.searchHistory.add(0, curSearchString)
            if (it.searchHistory.size > SearchHistorySize + 4) it.searchHistory.apply { subList(SearchHistorySize, size).clear() }
        }
    }
}

private val remoteMediaCache = LruCache<String, List<Episode>>(1)

class SearchVM: ViewModel() {
    val algo = SearchAlgo()

    internal var pafeeds by mutableStateOf<List<PAFeed>>(listOf())
    internal var feeds by mutableStateOf<List<Feed>>(listOf())

    val searchersAll = mutableStateListOf<MediaSearcher>()

    var searchers = mutableStateListOf<MediaSearcher>()

    var searchingRemote by mutableStateOf(false)
    internal var remoteMedia by mutableStateOf<List<Episode>>(listOf())

    var episodeSortOrder by mutableStateOf(EpisodeSortOrder.DATE_DESC)

    val tabTitles = listOf(R.string.episodes_label, R.string.feeds, R.string.remote, R.string.pafeeds)
    var selectedTabIndex by mutableIntStateOf(0)

    var listIdentity by mutableStateOf("")

    init {
        Logd(TAG, "init $curSearchString")
        algo.setSearchByAll()
        searchersAll.addAll(sourceClients.mapNotNull { it.mediaSearcher })
        searchersAll.add(AppleMediaSearcher())
        searchers.addAll(searchersAll)
        viewModelScope.launch { snapshotFlow { Pair(curSearchString, searchers.size) }.collectLatest {
            Logd(TAG, "snapshotFlow { Pair(curSearchString, searchers.size)")
            remoteMediaCache.remove(curSearchString)
            remoteMedia = listOf()
        } }
        viewModelScope.launch { snapshotFlow { selectedTabIndex }.collectLatest {
            if (selectedTabIndex == 2) {
                val fromCache = remoteMediaCache[curSearchString]
                if (!fromCache.isNullOrEmpty()) remoteMedia = fromCache
            }
        } }
        viewModelScope.launch { snapshotFlow { episodeSortOrder }.collectLatest {
            if (selectedTabIndex == 2 && remoteMedia.isNotEmpty()) {
                val list = remoteMedia.toMutableList()
                list.reorderWith(episodeSortOrder)
                remoteMedia = list
            }
        } }
    }

    suspend fun searchRemoteMedia() {
        val remoteMediaLimit = 1000
        searchingRemote = true
        val results = mutableListOf<Episode>()
        fun addItems(items: List<EpisodeIPC>, type: String?) {
            val list = items.map { it.toEpisode().apply {
                id = getEntityId()
                feedType = type
            } }
            if (list.isNotEmpty()) results.addAll(list)
        }
        Logd(TAG, "searchRemoteMedia searchers ${searchers.size}")
        for (s in searchers) {
            val type = if (s.name in listOf("Apple")) FeedType.RSS.name else clientBySearcher(s.name)?.attributes?.feedType
            val items = s.searchQuick(curSearchString)
            Logd(TAG, "searchQuick ${s.name} items: ${items.size}")
            addItems(items, type)
        }
        remoteMedia = results.toList()
        var counter = results.size
        while (results.size < remoteMediaLimit) {
            for (s in searchers) {
                val type = if (s.name in listOf("Apple")) FeedType.RSS.name else clientBySearcher(s.name)?.attributes?.feedType
                val items = s.getMoreItems()
                Logd(TAG, "searchRemoteMedia ${s.name} more items: ${items.size}")
                addItems(items, type)
            }
            remoteMedia = results.toList()
            if (counter >= results.size) break
            counter = results.size
        }
        Logd(TAG, "searchRemoteMedia found items: $counter")
        results.reorderWith(episodeSortOrder)
        remoteMedia = results.toList()
        remoteMediaCache.put(curSearchString, remoteMedia)
        searchingRemote = false
    }

    data class Triplet(val episodes: Flow<ResultsChange<Episode>>, val feeds: List<Feed>, val pafeeds: List<PAFeed>)

    val episodesFlow: StateFlow<List<Episode>> = snapshotFlow { Pair(curSearchString, episodeSortOrder) }.flatMapLatest { (queryText, order) ->
        val results_ = withContext(Dispatchers.IO) {
            if (queryText.isEmpty()) Triplet(emptyFlow(), listOf(), listOf())
            else {
                val queryWords = (if (queryText.contains(",")) queryText.split(",").map { it.trim() } else queryText.split("\\s+".toRegex())).dropWhile { it.isEmpty() }
                listIdentity = "Search.${queryWords.joinToString()}"
                try {
                    val items = algo.searchEpisodes(0L, queryWords, sortBY = order)
                    val feeds = algo.searchFeeds(queryWords)
                    val pafeeds = algo.searchPAFeeds(queryWords)
                    Triplet(items, feeds, pafeeds)
                } catch (e: Exception) {
                    Loge(TAG, e, "Search failed")
                    Triplet(emptyFlow(), listOf(), listOf())
                }
            }
        }
        withContext(Dispatchers.Main) {
            feeds = results_.feeds
            pafeeds = results_.pafeeds
            Logd(TAG, "Search found feeds: ${feeds.size}")
            results_.episodes.map { it.list }
        }
    }.distinctUntilChanged().stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5_000), initialValue = emptyList())

    val assFeedsFlow = episodesFlow.map { es -> es.mapNotNull { e -> e.feed }.distinctBy { it.id } }.distinctUntilChanged().stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5_000), initialValue = emptyList())
}

@ExperimentalMaterial3Api
@Composable
fun SearchScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val drawerController = LocalDrawerController.current
    val scope = rememberCoroutineScope()

    val vm: SearchVM = viewModel()

    var swipeActions by remember { mutableStateOf(SwipeActions(TAG)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {}
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

    DisposableEffect(episodeForInfo) {
        if (episodeForInfo != null) handleBackSubScreens.add(TAG)
        else handleBackSubScreens.remove(TAG)
        onDispose { handleBackSubScreens.remove(TAG) }
    }

    BackHandler(enabled = handleBackSubScreens.contains(TAG)) { episodeForInfo = null }

    var showSortDialog by remember { mutableStateOf(false) }
    if (showSortDialog) EpisodeSortDialog(initOrder = vm.episodeSortOrder, onDismiss = { showSortDialog = false }) { order -> vm.episodeSortOrder = order ?: EpisodeSortOrder.DATE_DESC }
    var showSearchBy by remember { mutableStateOf(false) }
    if (showSearchBy) CommonPopupCard(onDismiss = { showSearchBy = false} ) { vm.algo.SearchByGrid() }
    var showRemoteSearchers by remember { mutableStateOf(false) }
    if (showRemoteSearchers) CommonPopupCard(onDismiss = { showRemoteSearchers = false} ) {
        if (!vm.searchingRemote) Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            val sNames = remember(vm.searchers.size) { vm.searchers.map { it.name } }
            for (searcher in vm.searchersAll) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = searcher.name in sNames, onCheckedChange = { checked ->
                        scope.launch(Dispatchers.Default) {
                            while (vm.searchingRemote) delay(500.milliseconds)
                            if (checked) vm.searchers.add(searcher) else vm.searchers.remove(searcher)
                        }
                    })
                    Text(searcher.name)
                }
            }
        }
    }
    var showReserveAllDialog by remember { mutableStateOf(false) }
    if (showReserveAllDialog) AmendSyntheticFeed(name_ = "$curSearchString. By ${vm.searchers.joinToString { it.name }}", onDismiss = { showReserveAllDialog = false }) { feed->
        runOnIOScope {
            realm.write {
                for (e in vm.remoteMedia) {
                    e.feedId = feed.id
                    copyToRealm(e)
                }
                val eps = query(Episode::class).query("feedId == ${feed.id}").find()
                val dur = eps.sumOf { it.duration }
                findLatest(feed)?.let {
                    it.episodesCount = eps.size
                    it.totleDuration = dur.toLong()
                }
            }
        }
    }

    @Composable
    fun TopBar() {
        val appAttribs by appAttribsFlow!!.collectAsStateWithLifecycle()
        Box {
            TopAppBar(
                title = { Row(verticalAlignment = Alignment.CenterVertically) {
                    SearchBarRow(R.string.search_hint, defaultText = curSearchString, modifier = Modifier.weight(1f) , history = appAttribs.searchHistory) { str ->
                        if (str.isBlank()) return@SearchBarRow
                        curSearchString = str
                        if (vm.selectedTabIndex == 2) scope.launch(Dispatchers.IO) { vm.searchRemoteMedia() }
                        saveToSearchHistory()
                    }
                    if (vm.selectedTabIndex in listOf(0, 2)) Icon(imageVector = ImageVector.vectorResource(R.drawable.arrows_sort), contentDescription = "butSort", modifier = Modifier.padding(start = 7.dp).clickable { showSortDialog = true })
                } },
                navigationIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back or drawer", modifier = Modifier.padding(horizontal = 7.dp).clickable { if (!navBack()) drawerController?.open()  }) },
                actions = {
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                    DropdownMenu(expanded = expanded, border = BorderStroke(1.dp, borderColor), onDismissRequest = { expanded = false }) {
                        if (vm.selectedTabIndex != 2) DropdownMenuItem(text = { Text(stringResource(R.string.show_criteria)) }, onClick = {
                            showSearchBy = true
                            expanded = false
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.feeds_online)) }, onClick = {
                            val query = curSearchString
                            if (query.matches("http[s]?://.*".toRegex())) {
                                navTo(OnlineFeed(url = query))
                                return@DropdownMenuItem
                            }
                            searchFeedsOnline(query = query)
                            navTo(FindFeeds)
                            expanded = false
                        })
                        if (vm.selectedTabIndex == 2) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_searchers)) }, onClick = {
                                showRemoteSearchers = true
                                expanded = false
                            })
                            if (!vm.searchingRemote) DropdownMenuItem(text = { Text(stringResource(R.string.reserve_all)) }, onClick = {
                                showReserveAllDialog = true
                                expanded = false
                            })
                        }
                    }
                })
            HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(), thickness = DividerDefaults.Thickness, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }

    val episodes by vm.episodesFlow.collectAsStateWithLifecycle()
    val assFeeds by vm.assFeedsFlow.collectAsStateWithLifecycle()

    val infoBarText = remember(episodes.size) { mutableStateOf("${episodes.size} episodes") }
    val tabCounts = remember(episodes.size, assFeeds.size, vm.feeds.size, vm.remoteMedia.size, vm.pafeeds.size) { listOf(episodes.size, vm.feeds.size + assFeeds.size, vm.remoteMedia.size, vm.pafeeds.size) }
    swipeActions.ActionOptionsDialog()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(topBar = { TopBar() }) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding).fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    vm.tabTitles.forEachIndexed { index, titleRes ->
                        if (index != 2 || vm.searchersAll.isNotEmpty()) Tab(modifier = Modifier.wrapContentWidth().padding(horizontal = 2.dp, vertical = 4.dp).background(shape = RoundedCornerShape(8.dp), color = if (vm.selectedTabIndex == index) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else { Color.Transparent }),
                            selected = vm.selectedTabIndex == index, onClick = { vm.selectedTabIndex = index }, text = {
                            Text(text = stringResource(titleRes) + "(${tabCounts[index]})", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = if (vm.selectedTabIndex == index) MaterialTheme.colorScheme.primary else { MaterialTheme.colorScheme.onSurface })
                        })
                    }
                }
                @Composable
                fun FeedsColumn() {
                    val context = LocalContext.current
                    @Composable
                    fun FeedRow(feed: Feed) {
                        Row(Modifier.background(MaterialTheme.colorScheme.surface)) {
                            AsyncImage(model = ImageRequest.Builder(context).data(feed.imageUrl).memoryCachePolicy(CachePolicy.ENABLED).build(), placeholder = painterResource(R.drawable.ic_launcher_foreground), error = painterResource(R.drawable.ic_launcher_foreground), contentDescription = "imgvCover", modifier = Modifier.width(80.dp).height(80.dp).clickable {
                                Logd(TAG, "icon clicked!")
                                if (!feed.isBuilding) navTo(FeedDetails(feedId = feed.id, modeName = FeedScreenMode.Info.name))
                            })
                            Column(Modifier.weight(1f).padding(start = 10.dp).clickable { if (!feed.isBuilding) navTo(FeedDetails(feedId = feed.id)) }) {
                                Row {
                                    if (feed.rating != Rating.UNRATED.code) Icon(imageVector = ImageVector.vectorResource(Rating.fromCode(feed.rating).res), tint = MaterialTheme.colorScheme.tertiary, contentDescription = "rating", modifier = Modifier.width(20.dp).height(20.dp).background(MaterialTheme.colorScheme.tertiaryContainer))
                                    Text(feed.title ?: "No title", color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                }
                                Text(feed.author ?: "No author", color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Row(Modifier.padding(top = 5.dp)) {
                                    val measureString = remember { formatWithGrouping(feed.episodesCount.toLong()) + " : " + durationInHours(feed.totleDuration / 1000) }
                                    Text(measureString, color = textColor, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(modifier = Modifier.weight(1f))
                                    var feedSortInfo by remember { mutableStateOf(feed.sortInfo) }
                                    Text(feedSortInfo, color = textColor, style = MaterialTheme.typography.bodyMedium)
                                }
                            } //                                TODO: need to use state
                            if (feed.lastUpdateFailed) Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_error), tint = Color.Red, contentDescription = "error")
                        }
                    }
                    LazyColumn(modifier = Modifier.padding(horizontal = 10.dp), contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.feeds.isNotEmpty()) {
                            item { Text(text = stringResource(R.string.feeds_from_search), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                            items(items = vm.feeds, key = { "vm_${it.id}" }) { feed -> FeedRow(feed) }
                        }
                        item { HorizontalDivider(modifier = Modifier.fillMaxWidth()) }
                        if (assFeeds.isNotEmpty()) {
                            item { Text(stringResource(R.string.associated_feeds_from_episodes), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                            items(items = assFeeds, key = { "ass_${it.id}" }) { feed -> FeedRow(feed) }
                        }
                    }
                }
                @Composable
                fun PAFeedsColumn() {
                    val context = LocalContext.current
                    val lazyListState = rememberLazyListState()
                    LazyColumn(state = lazyListState, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(vm.pafeeds, key = { _, feed -> feed.id }) { _, feed ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(model = ImageRequest.Builder(context).data(feed.imageUrl).memoryCachePolicy(CachePolicy.ENABLED).build(), placeholder = painterResource(R.drawable.ic_launcher_foreground), error = painterResource(R.drawable.ic_launcher_foreground), contentDescription = "imgvCover", modifier = Modifier.width(60.dp).height(60.dp).clickable { if (feed.feedUrl.isNotBlank()) navTo(OnlineFeed(url = feed.feedUrl)) })
                                Column(Modifier.weight(1f).padding(start = 10.dp).clickable { if (feed.feedUrl.isNotBlank()) navTo(OnlineFeed(url = feed.feedUrl)) }) {
                                    Text(feed.name, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                    Text(feed.author, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    Text(feed.category.joinToString(","), color = textColor, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Episodes: ${feed.episodesNb} Average duration: ${feed.aveDuration} minutes", color = textColor, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(formatLargeInteger(feed.subscribers) + " subscribers", color = textColor, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                when (vm.selectedTabIndex) {
                    0 -> {
                        InforBar(swipeActions) {
                            Text(infoBarText.value, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.weight(0.1f))
                            PlayRandom(episodes)
                        }
                        EpisodeLazyColumn(episodes, swipeActions = swipeActions, actionButtonCB = { e, type -> if (type in listOf(ButtonTypes.PLAY, ButtonTypes.PLAY_LOCAL, ButtonTypes.STREAM)) runOnIOScope { queueToVirtual(e, episodes, vm.listIdentity, EpisodeSortOrder.DATE_DESC) } })
                    }
                    1 -> FeedsColumn()
                    2 -> {
                        InforBar(null) {
                            if (vm.searchingRemote) CircularProgressIndicator(strokeWidth = 4.dp, color = textColor, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.weight(0.1f))
                            Text(vm.remoteMedia.size.toString(), style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.weight(0.1f))
                            PlayRandom(vm.remoteMedia)
                        }
                        EpisodeLazyColumn(vm.remoteMedia, isExternal = true, layoutMode = LayoutMode.WideImage.code, swipeActions = null, actionButtonCB = { e, type -> if (type in listOf(ButtonTypes.PLAY, ButtonTypes.PLAY_LOCAL, ButtonTypes.STREAM)) actQueue = tmpQueue() })
                    }
                    3 -> PAFeedsColumn()
                }
            }
        }
        if (episodeForInfo != null) EpisodeScreen(episodeForInfo!!, listFlow = vm.episodesFlow, allowOpenFeed = true)
    }
}

enum class SearchBy(val nameRes: Int) {
    TITLE(R.string.title),
    DESCRIPTION(R.string.description_label),
    COMMENT(R.string.comments),
    AUTHOR(R.string.author),
}

//    private fun showInputMethod(view: View) {
//        val imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
//        imm.showSoftInput(view, 0)
//    }

private const val TAG: String = "SearchScreen"
