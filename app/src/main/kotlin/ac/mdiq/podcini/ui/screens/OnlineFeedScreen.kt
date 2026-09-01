package ac.mdiq.podcini.ui.screens

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.sourcing.download.EpisodeAdrDLManager
import ac.mdiq.podcini.sourcing.searcher.CombinedSearcher
import ac.mdiq.podcini.sourcing.feed.FeedBuilder
import ac.mdiq.podcini.sourcing.searcher.FeedUrlNotFoundException
import ac.mdiq.podcini.sourcing.searcher.PodcastSearcherRegistry
import ac.mdiq.podcini.sourcing.feed.subscribe
import ac.mdiq.podcini.utils.NetworkUtils.getFinalRedirectedUrl
import ac.mdiq.podcini.playback.base.actQueueFlow
import ac.mdiq.podcini.shared.EpisodeIPC
import ac.mdiq.podcini.shared.FeedSearchResult
import ac.mdiq.podcini.shared.getEntityId
import ac.mdiq.podcini.shared.prepareUrl
import ac.mdiq.podcini.sourcing.EPISODE_BATCH_SIZE
import ac.mdiq.podcini.sourcing.SourceGatewayClient
import ac.mdiq.podcini.sourcing.clientBySearcher
import ac.mdiq.podcini.sourcing.isExtFeed
import ac.mdiq.podcini.sourcing.sourceClients
import ac.mdiq.podcini.storage.database.allFeeds
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.getEpisodesCount
import ac.mdiq.podcini.storage.database.getFeed
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.storage.model.Feed.Companion.EPISODES_LIMIT
import ac.mdiq.podcini.storage.model.ShareLog
import ac.mdiq.podcini.storage.model.SubscriptionLog
import ac.mdiq.podcini.storage.model.SubscriptionLog.Companion.feedLogsMap
import ac.mdiq.podcini.storage.model.SubscriptionLog.Companion.takeCodePoints
import ac.mdiq.podcini.storage.model.tmpQueue
import ac.mdiq.podcini.storage.model.toFeed
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder.Companion.reorderWith
import ac.mdiq.podcini.storage.specs.FeedType
import ac.mdiq.podcini.storage.specs.Rating.Companion.fromCode
import ac.mdiq.podcini.ui.actions.ButtonTypes
import ac.mdiq.podcini.ui.actions.SwipeActions
import ac.mdiq.podcini.ui.compose.CustomTextStyles
import ac.mdiq.podcini.ui.compose.EpisodeLazyColumn
import ac.mdiq.podcini.ui.compose.EpisodeScreen
import ac.mdiq.podcini.ui.compose.EpisodeSortDialog
import ac.mdiq.podcini.ui.compose.InforBar
import ac.mdiq.podcini.ui.compose.NumberEditor
import ac.mdiq.podcini.ui.compose.episodeForInfo
import ac.mdiq.podcini.ui.compose.textColor
import ac.mdiq.podcini.ui.utils.HtmlToPlainText
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.formatAbbrev
import ac.mdiq.podcini.utils.timeIt
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
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
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

class OnlineFeedVM(url: String = "", source: String = "", shared: Boolean = false): ViewModel() {
    var feedSource: String = ""
    internal var feedUrl: String = ""
    internal var isShared: Boolean = false

    internal var urlToLog: String = ""
    internal var showTabsDialog by mutableStateOf(false)

    internal var showEpisodes by mutableStateOf(false)
    internal var showFeedDisplay by mutableStateOf(false)
    internal var showProgress by mutableStateOf(true)
    internal var autoDownloadChecked by mutableStateOf(false)
    internal var limitEpisodesCount by mutableIntStateOf(0)
    internal var enableSubscribe by mutableStateOf(true)
    internal var enableEpisodes by mutableStateOf(true)
    internal var subButTextRes by mutableIntStateOf(R.string.subscribe_label)

    var numEpisodes by mutableIntStateOf(0)

    internal var preparedUrl = ""

    internal var feedOptions: List<String?> = listOf()

    internal var infoBarText = mutableStateOf("")

    var episodeSortOrder by mutableStateOf(EpisodeSortOrder.DATE_DESC)

    internal val episodes = mutableStateListOf<Episode>()

    internal var feedId by mutableLongStateOf(0L)
    var updatedFeedUrl by mutableStateOf("")
    internal var feed by mutableStateOf<Feed?>(null)
    internal var username: String? = null
    internal var password: String? = null

    val subLogs = mutableStateListOf<SubscriptionLog>()

    internal var isPaused = false
    internal var subscribePress = false
    internal var isFeedFoundBySearch = false

    var relatedResults by mutableStateOf<List<FeedSearchResult>>(listOf())

    internal var showNoPodcastFoundDialog by mutableStateOf(false)
    internal var errorMessage by mutableStateOf("")
    internal var errorDetails by mutableStateOf("")

    var gatewayClient: SourceGatewayClient? = null

    init {
        timeIt("$TAG start of init")
        feedUrl = url
        feedSource = source
        isShared = shared
        preparedUrl = prepareUrl(feedUrl)

        Logd(TAG, "OnlineFeedVM init feedUrl: $feedUrl feedSource: $feedSource isShared: $isShared")

        findExisting(preparedUrl)?.apply { feedId = this.id }

        val showError = { message: String?, details: String ->
            errorMessage = message ?: "No message"
            errorDetails = details
        }
        gatewayClient = clientBySearcher(source)

        if (feedUrl.isEmpty()) {
            Loge(TAG, "feedUrl is null.")
            showNoPodcastFoundDialog = true
        } else {
            Logd(TAG, "Activity was started with url $feedUrl")
            showProgress = true
            // Remove subscribeonandroid.com from feed URL in order to subscribe to the actual feed URL
            if (feedUrl.contains("subscribeonandroid.com")) feedUrl = feedUrl.replaceFirst("((www.)?(subscribeonandroid.com/))".toRegex(), "")

            suspend fun handleClientFeeds(): Boolean {
                feedOptions = gatewayClient?.withProvider { it.feedsTitlesAtUrl(url) } ?: listOf()
                val feedOptions_ = feedOptions.filter { it != null && it != "playlists" && it != "shorts" }
                Logd(TAG, "feedOptions_: ${feedOptions_.size}")
                when {
                    feedOptions_.size > 1 -> {
                        showTabsDialog = true
                        feedOptions.forEach { Logd(TAG, "feedOptions: $it") }
                        return true
                    }
                    feedOptions_.size <= 1 -> {
                        val fipc = gatewayClient?.withProvider { it.buildFeed(url, 0) }
                        if (fipc != null) {
                            var exist = findExisting(preparedUrl)
                            if (exist != null) {
                                setExist(exist, R.string.open)
                                return true
                            }
                            exist = findExisting(fipc.toFeed())
                            if (exist != null) {
                                setExist(exist, R.string.update_url)
                                updatedFeedUrl = fipc.downloadUrl ?:""
                                Logd(TAG, "handleClientFeeds updatedFeedUrl: $updatedFeedUrl")
                                return true
                            }
                            Logd(TAG, "handleClientFeeds feed exists: $exist ${fipc.title}")
                            val eList = mutableListOf<EpisodeIPC>()
                            var episodes = gatewayClient?.withProvider { it.getEpisodes(EPISODE_BATCH_SIZE, 0L) } ?: listOf()
                            while (episodes.isNotEmpty()) {
                                eList.addAll(episodes)
                                numEpisodes = eList.size
                                if (limitEpisodesCount in 1..<numEpisodes || numEpisodes > EPISODES_LIMIT || episodes.size < EPISODE_BATCH_SIZE) break
                                Logd(TAG, "handleClientFeeds Subscribing eList: ${eList.size}")
                                episodes = gatewayClient?.withProvider { it.getEpisodes(EPISODE_BATCH_SIZE, 0L) } ?: listOf()
                            }
                            fipc.episodes = eList
                            Logd(TAG, "handleClientFeeds fipc: ${fipc.title} ${fipc.author}")
                            handleFeed(fipc.toFeed())
                            return true
                        }
                    }
                }
                return false
            }
            viewModelScope.launch(Dispatchers.IO) {
                urlToLog = feedUrl
                if (gatewayClient != null) handleClientFeeds()
                else {
                    val client = sourceClients.find { it.withProvider { p-> p.canHandleUrl(feedUrl) == 1 } == true }
                    Logd(TAG, "try positive client: ${client != null}")
                    if (client != null) {
                        gatewayClient = client
                        if (handleClientFeeds()) return@launch
                    }
                    val clients = sourceClients.filter { it.withProvider { p-> p.canHandleUrl(feedUrl) == 0 } == true }
                    Logd(TAG, "try neutral clients: ${clients.size}")
                    for (client in clients) {
                        gatewayClient = client
                        if (handleClientFeeds()) return@launch
                    }
                    try {
                        val urlString = PodcastSearcherRegistry.lookupUrl(feedUrl)
                        Logd(TAG, "lookupUrlAndBuild: urlString: $urlString")
                        val feedBuilder = FeedBuilder(showError)
                        feedBuilder.buildPodcast(getFinalRedirectedUrl(urlString), username, password) { feed_, _ -> handleFeed(feed_) }
                    } catch (error: FeedUrlNotFoundException) {
                        Logd(TAG, "lookupUrlAndBuild in error, trying to Retrieve FeedUrl By Search")
                        var url: String? = null
                        val searcher = CombinedSearcher()
                        val query = "${error.trackName} ${error.artistName}"
                        val results = searcher.search(query)
                        if (results.isEmpty()) return@launch
                        for (result in results) {
                            if (result.feedUrl != null && result.author != null && result.author.equals(error.artistName, ignoreCase = true)
                                && result.title.equals(error.trackName, ignoreCase = true)) {
                                url = result.feedUrl
                                break
                            }
                        }
                        if (url != null) {
                            urlToLog = url
                            Logd(TAG, "Successfully retrieve feed url: $url")
                            isFeedFoundBySearch = true
                            val feedBuilder = FeedBuilder(showError)
                            feedBuilder.buildPodcast(getFinalRedirectedUrl(url), username, password) { feed_, _ -> handleFeed(feed_) }
                        } else withContext(Dispatchers.Main) { showNoPodcastFoundDialog = true }
                    }
                }
            }
            viewModelScope.launch { snapshotFlow { episodeSortOrder }.collectLatest { episodes.reorderWith(episodeSortOrder) } }
        }
        timeIt("$TAG end of init")
    }

    fun setExist(exist: Feed, textRes: Int) {
        feedId = exist.id
        feed = exist
        numEpisodes = getEpisodesCount(null, feedId)
        showProgress = false
        showFeedDisplay = true
        enableSubscribe = true
        subButTextRes = textRes
    }

    fun findExisting(feed_: Feed?): Feed? {
        Logd(TAG, "checkExisting check for ${feed_?.title} ${feed_?.author}")
        fun isSameFeed(f: Feed): Boolean {
            Logd(TAG, "isSameFeed check with feed: ${f.type} ${f.title} ${f.author}")
            fun getDomain(url: String): String? = try { URI(url).host?.removePrefix("www.") } catch (e: Exception) { null }
            val d1 = getDomain(f.downloadUrl?:"")
            val d2 = getDomain(feed_?.downloadUrl?:"")
            val ds1 = f.description?.takeCodePoints(100).orEmpty()
            val ds2 = f.description?.takeCodePoints(100).orEmpty()
            Logd(TAG, "isSameFeed d1: $d1 d2: $d2")
            return  (f.title == feed_?.title && f.author == feed_?.author && d1 == d2 && ds1 == ds2)
        }
        for (f in allFeeds) if (isSameFeed(f)) return f
        return null
    }

    fun findExisting(url: String): Feed? {
        if (url.isNotBlank()) for (f in allFeeds) {
            if (f.downloadUrl == url) {
                Logd(TAG, "checkExisting found existing feed: ${f.title}")
                return f
            }
        }
        return null
    }


    internal fun handleFeed(feed_: Feed) {
        Logd(TAG, "handleFeed feed_.title: ${feed_.title} ${feed_.author}")
        feed = feed_
//        findExisting(preparedUrl, feed)?.apply { feedId = this.id }

        val results = mutableSetOf<SubscriptionLog>()
        if (!feed_.title.isNullOrBlank()) feedLogsMap?.get(feed_.title)?.apply { results.add(this) }
        if (!feed_.downloadUrl.isNullOrBlank()) feedLogsMap?.get(feed_.downloadUrl)?.apply { results.add(this) }
        feed_.description?.takeCodePoints(100).takeIf { !it.isNullOrBlank() }.apply { feedLogsMap?.get(this)?.apply { results.add(this) } }
        if (results.isNotEmpty()) {
            subLogs.clear()
            subLogs.addAll(results)
        }

        numEpisodes = feed_.episodes.size
        if (isShared) {
            val log = realm.query(ShareLog::class).query("url == $0", urlToLog).first().find()
            if (log != null) upsertBlk(log) {
                it.title = feed_.title
                it.author = feed_.author
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val fl = CombinedSearcher::class.java.getDeclaredConstructor().newInstance().search("${feed?.author} podcasts")
            withContext(Dispatchers.Main) { if (fl.isNotEmpty()) relatedResults = fl }
        }
        showProgress = false
        showFeedDisplay = true
        if (isFeedFoundBySearch) Loge(TAG, getAppContext().getString(R.string.no_feed_url_podcast_found_by_search))
        handleSubscribeStatus()
    }

    internal fun showEpisodes() {
        if (feed == null) return
        if (episodes.isEmpty()) {
            episodes.addAll(feed!!.episodes)
            infoBarText.value = "${episodes.size} episodes"

            Logd(TAG, "showEpisodes ${episodes.size}")
            if (episodes.isEmpty()) return
//            episodes.sortByDescending { it.pubDate }
            for (episode in episodes) {
                episode.id = getEntityId()
                episode.origFeedlink = feed!!.link
                episode.origFeeddownloadUrl = feed!!.downloadUrl
                episode.origFeedTitle = feed!!.title
            }
            episodes.reorderWith(episodeSortOrder)
        }
        showEpisodes = true
    }

    internal fun handleSubscribeStatus() {
        if (preparedUrl.isBlank()) return

        when {
            EpisodeAdrDLManager.manager.isDownloading(preparedUrl) -> {
                Logd(TAG, "handleUpdatedFeedStatus isDownloading")
                enableSubscribe = false
                subButTextRes = R.string.subscribe_label
            }
            feedId != 0L -> {
                Logd(TAG, "handleUpdatedFeedStatus feedId != 0L")
                enableSubscribe = true
                subButTextRes = R.string.open
                if (subscribePress) {
                    subscribePress = false
                    runOnIOScope {
                        val feedExisting = getFeed(feedId, true)?: return@runOnIOScope
                        Logd(TAG, "handleUpdatedFeedStatus ${feedExisting.title} ${feedExisting.author}")
                        if (appPrefsFlow!!.value.enableAutoDl && !isExtFeed(feedExisting)) feedExisting.autoDownload = autoDownloadChecked
                        if (!username.isNullOrBlank()) {
                            feedExisting.username = username
                            feedExisting.password = password
                        }
                        upsert(feedExisting) {}
                    }
                }
            }
            else -> {
                Logd(TAG, "handleUpdatedFeedStatus else")
                enableSubscribe = true
                subButTextRes = R.string.subscribe_label
            }
        }
    }

    override fun onCleared() {
        Logd(TAG, "VM onCleared")
        episodes.clear()
    }
}

@ExperimentalMaterial3Api
@Composable
fun OnlineFeedScreen(url: String = "", source: String = "", shared: Boolean = false) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val drawerController = LocalDrawerController.current
    val context by rememberUpdatedState(LocalContext.current)
    val appPrefs by appPrefsFlow!!.collectAsStateWithLifecycle()

    val vm: OnlineFeedVM = viewModel(key = url, factory = viewModelFactory { initializer { OnlineFeedVM(url, source, shared) } })

    var swipeActions by remember { mutableStateOf(SwipeActions(TAG, false)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> Logd(TAG, "feedUrl: ${vm.feedUrl}")
                Lifecycle.Event.ON_START -> {
                    vm.isPaused = false
                    vm.infoBarText.value = "${vm.episodes.size} episodes"
                }
                Lifecycle.Event.ON_STOP -> vm.isPaused = true
                Lifecycle.Event.ON_DESTROY -> {}
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(vm.showEpisodes, episodeForInfo) {
        if (vm.showEpisodes || episodeForInfo != null) handleBackSubScreens.add(TAG)
        else handleBackSubScreens.remove(TAG)
        onDispose { handleBackSubScreens.remove(TAG) }
    }

    BackHandler(enabled = handleBackSubScreens.contains(TAG)) {
        when {
            episodeForInfo != null -> episodeForInfo = null
            else -> vm.showEpisodes = false
        }
    }

    var showSortDialog by remember { mutableStateOf(false) }
    if (showSortDialog) EpisodeSortDialog(initOrder = vm.episodeSortOrder, feed = vm.feed, onDismiss = { showSortDialog = false }) { order -> vm.episodeSortOrder = order ?: EpisodeSortOrder.DATE_DESC }

    @Composable
    fun ShowTabsDialog(onDismiss: () -> Unit) {
        val ytTabsMap = remember { mutableStateMapOf<Int, String>() }
        AlertDialog(modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.extraLarge), onDismissRequest = { onDismiss() },
            title = { Text(stringResource(R.string.choose_tab), style = CustomTextStyles.titleCustom) },
            text = {
                Column {
                    val selectedId = remember { mutableStateOf<Int?>(null) }
                    for (i in vm.feedOptions.indices) {
                        val urlEnd = vm.feedOptions[i]
                        if (!urlEnd.isNullOrBlank() && urlEnd != "playlists" && urlEnd != "shorts") Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 30.dp)) {
                                var checked by remember { mutableStateOf(false) }
                                val isChecked = ytTabsMap.contains(i)   // TODO: better enable multi-select
                                Checkbox(checked = selectedId.value == i, onCheckedChange = {
                                    selectedId.value = if (selectedId.value == i) null else i
                                    checked = it
                                    if (checked) ytTabsMap[i] = urlEnd else ytTabsMap.remove(i)
                                })
                                Text(text = urlEnd, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    CoroutineScope(Dispatchers.IO).launch {
                        // TODO: ytTabsMap doesn't handle multiple keys
                        for (i in ytTabsMap.keys) {
                            Logd(TAG, "Subscribing $i ${vm.feedOptions[i]} ${ytTabsMap[i]}")
                            val endUrl = ytTabsMap[i] ?: continue
                            val fipc = vm.gatewayClient?.withProvider { it.buildFeed(url, i) }
                            if (fipc != null) {
                                fipc.title = "${fipc.title}: $endUrl"
                                Logd(TAG, "url: $url")
                                Logd(TAG, "preparedUrl: ${vm.preparedUrl}")
                                Logd(TAG, "fipc.title: ${fipc.title} ${fipc.downloadUrl}")
                                var exist = vm.findExisting(url)
                                if (exist != null) {
                                    vm.setExist(exist, R.string.open)
                                    return@launch
                                }
                                exist = vm.findExisting(fipc.toFeed())
                                if (exist != null) {
                                    vm.setExist(exist, R.string.update_url)
                                    vm.updatedFeedUrl = fipc.downloadUrl ?:""
                                    Logd(TAG, "updatedFeedUrl: ${vm.updatedFeedUrl}")
                                    return@launch
                                }
                                val eList = mutableListOf<EpisodeIPC>()
                                var episodes = vm.gatewayClient?.withProvider { it.getEpisodes(EPISODE_BATCH_SIZE, 0L) }?: listOf()
                                while (episodes.isNotEmpty()) {
                                    eList.addAll(episodes)
                                    vm.numEpisodes = eList.size
                                    if (vm.limitEpisodesCount in 1..vm.numEpisodes || vm.numEpisodes > EPISODES_LIMIT || episodes.size < EPISODE_BATCH_SIZE) break
                                    Logd(TAG, "Subscribing eList: ${eList.size}")
                                    episodes = vm.gatewayClient?.withProvider { it.getEpisodes(EPISODE_BATCH_SIZE, 0L) }?: listOf()
                                }
                                fipc.episodes = eList
                                vm.handleFeed(fipc.toFeed())
                            } else Loge(TAG, "Subscribe feed failed")
                        }
                    }
                    onDismiss()
                }) { Text(text = stringResource(R.string.confirm_label)) }
            },
            dismissButton = { TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.cancel_label)) } }
        )
    }
    if (vm.showTabsDialog) ShowTabsDialog(onDismiss = { vm.showTabsDialog = false })

    if (vm.showNoPodcastFoundDialog) AlertDialog(modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.extraLarge), onDismissRequest = { vm.showNoPodcastFoundDialog = false },
        title = { Text(stringResource(R.string.error_label)) },
        text = { Text(stringResource(R.string.null_value_podcast_error)) },
        confirmButton = { TextButton(onClick = { vm.showNoPodcastFoundDialog = false }) { Text("OK") } })

    if (vm.errorMessage.isNotBlank()) Loge(TAG, "${vm.errorMessage}\n${vm.errorDetails}")

    swipeActions.ActionOptionsDialog()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(topBar = {
            Box {
                TopAppBar(title = {  Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Online feed", modifier = Modifier.weight(1f))
                    if (vm.showEpisodes) Icon(imageVector = ImageVector.vectorResource(R.drawable.arrows_sort), contentDescription = "butSort", modifier = Modifier.padding(start = 7.dp).clickable { showSortDialog = true })
                } },
                    navigationIcon = {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back or drawer", modifier = Modifier.padding(7.dp).clickable {
                            if (vm.showEpisodes) vm.showEpisodes = false
                            else if (!navBack()) drawerController?.open()
                        })
                    })
                HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(), thickness = DividerDefaults.Thickness, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }) { innerPadding ->
            if (vm.showEpisodes) Column(modifier = Modifier.padding(innerPadding).fillMaxSize().padding(start = 5.dp, end = 5.dp).background(MaterialTheme.colorScheme.surface)) {
                InforBar(swipeActions) { Text(vm.infoBarText.value, style = MaterialTheme.typography.bodyMedium) }
                EpisodeLazyColumn(vm.episodes, isExternal = true, swipeActions = swipeActions, actionButtonCB = { _, type -> if (type in listOf(ButtonTypes.PLAY, ButtonTypes.PLAY_LOCAL, ButtonTypes.STREAM)) actQueueFlow.value = tmpQueue() })
            } else Column(modifier = Modifier.padding(innerPadding).fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp).background(MaterialTheme.colorScheme.surface)) {
                ConstraintLayout(modifier = Modifier.fillMaxWidth().height(110.dp).background(MaterialTheme.colorScheme.surface)) {
                    val (coverImage, taColumn, buttons) = createRefs()
                    AsyncImage(model = vm.feed?.imageUrl ?: "", contentDescription = "coverImage", error = painterResource(R.drawable.ic_launcher_foreground), modifier = Modifier.width(80.dp).height(80.dp).constrainAs(coverImage) {
                        centerVerticallyTo(parent)
                        start.linkTo(parent.start)
                    })
                    Column(Modifier.padding(start = 5.dp).constrainAs(taColumn) {
                        top.linkTo(parent.top)
                        start.linkTo(coverImage.end)
                    }) {
                        Text(vm.feed?.title ?: "No title", color = textColor, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(vm.feed?.author ?: "", color = textColor, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.constrainAs(buttons) {
                        start.linkTo(coverImage.end)
                        bottom.linkTo(parent.bottom)
                        end.linkTo(parent.end)
                    }) {
                        Spacer(modifier = Modifier.weight(0.2f))
                        if (vm.showFeedDisplay && vm.enableSubscribe) Button(onClick = {
                            if (vm.feedId != 0L) {
                                if (vm.isShared) {
                                    val log = realm.query(ShareLog::class).query("url == $0", vm.feedUrl).first().find()
                                    if (log != null) upsertBlk(log) { it.status = ShareLog.Status.EXISTING.code }
                                }
                                if (vm.updatedFeedUrl.isNotBlank() && vm.feed != null) upsertBlk(vm.feed!!) { it.downloadUrl = vm.updatedFeedUrl }
                                navTo(FeedDetails(feedId = vm.feedId, modeName = FeedScreenMode.Info.name))
                            } else {
                                if (vm.feed == null) return@Button
                                vm.enableSubscribe = false
                                vm.enableEpisodes = false
                                CoroutineScope(Dispatchers.IO).launch {
                                    if (vm.limitEpisodesCount > 0) vm.feed?.limitEpisodesCount = vm.limitEpisodesCount
                                    subscribe(vm.feed!!)
                                    if (vm.isShared) {
                                        val log = realm.query(ShareLog::class).query("url == $0", vm.feedUrl).first().find()
                                        if (log != null) upsertBlk(log) { it.status = ShareLog.Status.SUCCESS.code }
                                    }
                                    withContext(Dispatchers.Main) {
                                        runCatching {
                                            vm.subscribePress = true
                                            vm.feedId = vm.feed?.id ?: 0L
                                            vm.enableSubscribe = true
                                            vm.subButTextRes = R.string.open
                                            vm.handleSubscribeStatus()
                                        }
                                    }
                                }
                            }
                        }) { Text(stringResource(vm.subButTextRes)) }
                        Spacer(modifier = Modifier.weight(0.1f))
                        when {
                            vm.showEpisodes -> Button(onClick = { vm.showEpisodes = false }) { Text(stringResource(R.string.feed)) }
                            vm.enableEpisodes && vm.feed != null && vm.numEpisodes > 0 -> Button(onClick = { vm.showEpisodes() }) { Text(stringResource(R.string.episodes_label)) }
                            else -> {}
                        }
                        Spacer(modifier = Modifier.weight(0.2f))
                    }
                }
                Column(Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary)) {
                    //                    TODO: add alternate_urls_spinner
                    if (vm.feedId == 0L) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.limit_episodes_to), modifier = Modifier.weight(0.5f))
                        NumberEditor(vm.limitEpisodesCount, label = "0 = unlimited", nz = false, instant = false, modifier = Modifier.weight(0.5f)) {
                            Logd(TAG, "limitEpisodesCount: $it")
                            vm.limitEpisodesCount = it
                        }
                    }
                    val isAudoDL = remember(vm.feed) { vm.feed?.type in listOf(FeedType.RSS.name, FeedType.ATOM.name) }
                    if (appPrefs.enableAutoDl && isAudoDL) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = vm.autoDownloadChecked, onCheckedChange = { vm.autoDownloadChecked = it })
                        Text(text = stringResource(R.string.auto_download_label), style = MaterialTheme.typography.bodyMedium, color = textColor, modifier = Modifier.padding(start = 16.dp))
                    }
                }
                SelectionContainer {
                    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp)) {
                        if (vm.subLogs.isNotEmpty()) {
                            Text(stringResource(R.string.feed_likely_removed), color = MaterialTheme.colorScheme.primary, style = CustomTextStyles.titleCustom, modifier = Modifier.padding(start = 5.dp))
                            for (sLog in vm.subLogs) {
                                Text(sLog.comment, color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 15.dp, bottom = 5.dp))
                                val ratingRes = remember(sLog.id) { fromCode(sLog.rating).res }
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 15.dp, bottom = 5.dp)) {
                                    Text(stringResource(R.string.rating_label), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 5.dp))
                                    Icon(imageVector = ImageVector.vectorResource(ratingRes), tint = MaterialTheme.colorScheme.tertiary, contentDescription = null)
                                }
                                if (!sLog.description.isNullOrBlank()) Text(sLog.description ?: "", color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 15.dp, bottom = 5.dp))
                                Text(sLog.url ?: "no url", color = textColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 15.dp, bottom = 5.dp))
                                val cancelDate = remember(sLog.id) { formatAbbrev(sLog.cancelDate) }
                                Text(stringResource(R.string.removed_on) + ": " + cancelDate, color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 15.dp, bottom = 10.dp))
                            }
                        }
                        Text("${vm.numEpisodes} episodes", color = textColor, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 5.dp, bottom = 10.dp))
                        Text(stringResource(R.string.description_label), color = textColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp, bottom = 4.dp))
                        Text(HtmlToPlainText.getPlainText(vm.feed?.description ?: ""), color = textColor, style = MaterialTheme.typography.bodyMedium)
                        if (!vm.feed?.episodes.isNullOrEmpty()) {
                            Text(stringResource(R.string.recent_episode), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp, bottom = 4.dp))
                            Text(vm.feed?.episodes[0]?.title ?: "", color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 5.dp, bottom = 4.dp))
                        }
                        Text(stringResource(R.string.feeds_related_to_author), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp).clickable {
                            searchFeedsOnline(query = "${vm.feed?.author} podcasts")
                            navTo(FindFeeds)
                        })
                        LazyRow(state = rememberLazyListState(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            items(vm.relatedResults) { result ->
                                AsyncImage(model = ImageRequest.Builder(context).data(result.imageUrl).memoryCachePolicy(CachePolicy.ENABLED).build(), placeholder = painterResource(R.drawable.ic_launcher_foreground), error = painterResource(R.drawable.ic_launcher_foreground), contentDescription = "imgvCover", modifier = Modifier.width(100.dp).height(100.dp).clickable {
                                    navTo(OnlineFeed(url = result.feedUrl ?: "", source = result.source))
                                })
                            }
                        }
                        val info = remember(vm.feed) { if (vm.feed == null) "" else "${vm.feed!!.langSet.joinToString(" ")} ${vm.feed!!.type.orEmpty()} ${vm.feed!!.lastUpdate.orEmpty()}" }
                        Text(info, color = textColor, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        Text(vm.feed?.link ?: "", color = textColor, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 5.dp, bottom = 4.dp))
                        Text(vm.feed?.downloadUrl ?: "", color = textColor, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 5.dp, bottom = 4.dp))
                    }
                }
            }
            if (vm.showProgress) Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                CircularProgressIndicator(strokeWidth = 10.dp, color = textColor, modifier = Modifier.size(50.dp).align(Alignment.Center))
            }
        }
        if (episodeForInfo != null) EpisodeScreen(episodeForInfo!!)
    }
}

private val TAG: String = Screens.OnlineFeed.name
