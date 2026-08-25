package ac.mdiq.podcini.ui.screens

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.config.OpmlBackupAgent.Companion.performRestore
import ac.mdiq.podcini.config.settings.OpmlTransporter.OpmlElement
import ac.mdiq.podcini.net.searcher.FeedSearchers
import ac.mdiq.podcini.net.searcher.PodcastSearcherRegistry.searcherInfos
import ac.mdiq.podcini.shared.FeedSearchResult
import ac.mdiq.podcini.shared.prepareUrl
import ac.mdiq.podcini.sources.sourceClients
import ac.mdiq.podcini.storage.database.allFeeds
import ac.mdiq.podcini.storage.database.appAttribsFlow
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.feedCountFlow
import ac.mdiq.podcini.storage.database.loadLocalFolder
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.SearchHistorySize
import ac.mdiq.podcini.storage.model.SubscriptionLog.Companion.feedLogsMap
import ac.mdiq.podcini.storage.utils.AddLocalFolder
import ac.mdiq.podcini.storage.utils.persistedTrees
import ac.mdiq.podcini.ui.compose.ConfirmDialog
import ac.mdiq.podcini.ui.compose.CommonPopupCard
import ac.mdiq.podcini.ui.compose.OnlineFeedItem
import ac.mdiq.podcini.ui.compose.OpmlImportSelectionDialog
import ac.mdiq.podcini.ui.compose.SearchBarRow
import ac.mdiq.podcini.ui.compose.textColor
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Logs
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private var searchText by mutableStateOf("")
internal var searchResults by mutableStateOf<List<FeedSearchResult>>(listOf())

var searchProvider by mutableStateOf(searcherInfos.find { it.tag == "Combined" }!!.searcher)

fun searchFeedsOnline(searcherName: String = "", query: String? = null) {
    searchText = query ?: ""
    if (searcherName.isNotBlank()) {
        val searcher_ = searcherInfos.find { it.tag == searcherName }?.searcher
        if (searcher_ != null) searchProvider = searcher_
    } else searchProvider = searcherInfos.find { it.tag == "Combined" }!!.searcher
}

class FindFeedsVM: ViewModel() {

    internal var readElements by mutableStateOf<List<OpmlElement>>(listOf())

    var showOpmlImportSelectionDialog by mutableStateOf(false)
    val showOPMLRestoreDialog = mutableStateOf(false)
    val numberOPMLFeedsToRestore = mutableIntStateOf(0)

    var showProgress by mutableStateOf(false)
    var errorText by mutableStateOf("")
    var retryQerry by mutableStateOf("")

    var searchJob: Job? = null

    init {
        if (appPrefsFlow!!.value.OPMLRestored && feedCountFlow.value == 0) {
            numberOPMLFeedsToRestore.intValue = appPrefsFlow!!.value.OPMLFeedsToRestore
            showOPMLRestoreDialog.value = true
        }
//        search(searchText)
    }

    @SuppressLint("StringFormatMatches")
    fun search(query: String) {
        if (query.isBlank()) return
        if (searchJob != null) {
            searchJob?.cancel()
            searchResults = listOf()
        }
        errorText = ""
        retryQerry = ""
        showProgress = true
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            fun feedId(r: FeedSearchResult): Long {
                for (f in allFeeds) if (f.downloadUrl == r.feedUrl) return f.id
                return 0L
            }
            try {
                val results = searchProvider.search(query)
                for (r in results) r.feedId = feedId(r)
                searchResults = results.sortedBy { it.title }
                withContext(Dispatchers.Main) { showProgress = false }
            } catch (e: Exception) {
                showProgress = false
                errorText = e.toString()
                retryQerry = query
            }
        }.apply { invokeOnCompletion { searchJob = null } }
    }

    override fun onCleared() {
        Logd(TAG, "VM onCleared")
        searchJob?.cancel()
        searchJob = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindFeedsScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context by rememberUpdatedState(LocalContext.current)
    val drawerController = LocalDrawerController.current

    val vm: FindFeedsVM = viewModel()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            Logd(TAG, "DisposableEffect Lifecycle.Event: $event")
            when (event) {
                Lifecycle.Event.ON_CREATE -> {}
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

    val actionColor = MaterialTheme.colorScheme.tertiary
    ConfirmDialog(R.string.restore_subscriptions_label, stringResource(R.string.restore_subscriptions_summary, vm.numberOPMLFeedsToRestore.intValue), vm.showOPMLRestoreDialog) {
        vm.showProgress = true
        performRestore()
        vm.showProgress = false
    }
    val addLocalFolderLauncher = rememberLauncherForActivityResult(AddLocalFolder()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        getAppContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        persistedTrees.add(uri)
        runOnIOScope { loadLocalFolder(uri) }
    }

    if (vm.showOpmlImportSelectionDialog) OpmlImportSelectionDialog(vm.readElements) { vm.showOpmlImportSelectionDialog = false }

    var showAdvanced by remember { mutableStateOf(false) }
    if (showAdvanced) CommonPopupCard({ showAdvanced = false }) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.search_combined_label), color = actionColor, modifier = Modifier.padding(start = 10.dp, top = 10.dp).clickable {
                searchFeedsOnline()
                showAdvanced = false
            })
            for (client in sourceClients) {
                val searchName = remember { client.feedSearcher?.name }
                if (!searchName.isNullOrBlank()) Text(searchName, color = actionColor, modifier = Modifier.padding(start = 10.dp, top = 10.dp).clickable {
                    searchFeedsOnline(searchName)
                    showAdvanced = false
                })
            }
            Text(stringResource(R.string.search_itunes_label), color = actionColor, modifier = Modifier.padding(start = 10.dp, top = 10.dp).clickable {
                searchFeedsOnline(FeedSearchers.Apple.name)
                showAdvanced = false
            })
            Text(stringResource(R.string.deep_search_itunes), color = actionColor, modifier = Modifier.padding(start = 10.dp, top = 10.dp).clickable {
                searchFeedsOnline(FeedSearchers.AppleDeep.name)
                showAdvanced = false
            })
            Text(stringResource(R.string.search_podcastindex_label), color = actionColor, modifier = Modifier.padding(start = 10.dp, top = 10.dp).clickable {
                searchFeedsOnline(FeedSearchers.PodcastIndex.name)
                showAdvanced = false
            })
        }
    }

    @Composable
    fun TopBar() {
        val appAttribs by appAttribsFlow!!.collectAsStateWithLifecycle()
        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_add), contentDescription = "Open Drawer", modifier = Modifier.padding(end = 7.dp).clickable { drawerController?.open() })
                SearchBarRow(R.string.search_podcast_hint, modifier = Modifier.weight(1f), defaultText = searchText, history = appAttribs.onlineSearchHistory) { str ->
                    if (str.isBlank()) return@SearchBarRow
                    searchText = str
                    upsertBlk(appAttribs) {
                        if (str in it.onlineSearchHistory) it.onlineSearchHistory.remove(str)
                        it.onlineSearchHistory.add(0, str)
                        if (it.onlineSearchHistory.size > SearchHistorySize+4) it.onlineSearchHistory.apply { subList(SearchHistorySize, size).clear() }
                    }
                    if (str.matches("http[s]?://.*".toRegex())) navTo(OnlineFeed(url=str))
                    else vm.search(str)
                }
                Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_settings), contentDescription = "Advanced", modifier = Modifier.padding(7.dp).clickable { showAdvanced = true })
            }
            HorizontalDivider(modifier = Modifier.fillMaxWidth(), thickness = DividerDefaults.Thickness, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }

    Scaffold(topBar = { TopBar() }) { innerPadding ->
        ConstraintLayout(modifier = Modifier.padding(innerPadding).fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            val (controlRow, gridView, progressBar, empty, txtvError, butRetry, powered) = createRefs()
            Row(modifier = Modifier.padding(vertical = 8.dp, horizontal = 20.dp).fillMaxWidth().constrainAs(controlRow) { top.linkTo(parent.top) }) {
                Text(stringResource(R.string.top_chart), color = actionColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { navTo(TopChart) })
                Spacer(Modifier.weight(1f))
                Text(searchResults.size.toString(), color = textColor, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.local_folder),color = actionColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                    try { addLocalFolderLauncher.launch(null) } catch (e: ActivityNotFoundException) { Logs(TAG, e, context.getString(R.string.unable_to_start_system_file_manager)) }
                })
            }

            if (vm.showProgress) CircularProgressIndicator(strokeWidth = 10.dp, modifier = Modifier.size(50.dp).constrainAs(progressBar) { centerTo(parent) })
            if (searchResults.isNotEmpty()) LazyColumn(state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 10.dp).constrainAs(gridView) {
                    top.linkTo(controlRow.bottom)
                    bottom.linkTo(parent.bottom)
                    start.linkTo(parent.start)
                }) {
                items(searchResults) { result ->
                    val urlPrepared = remember(result.feedUrl) { prepareUrl(result.feedUrl!!) }
                    val sLog = remember(urlPrepared, result.title, feedLogsMap) { feedLogsMap?.get(urlPrepared) ?: feedLogsMap?.get(result.title) }
                    OnlineFeedItem(result, sLog)
                }
            } else Text(stringResource(R.string.no_results_for_query, searchText), color = textColor, modifier = Modifier.constrainAs(empty) { centerTo(parent) })
            if (vm.errorText.isNotEmpty()) Text(vm.errorText, color = textColor, modifier = Modifier.constrainAs(txtvError) { centerTo(parent) })
            if (vm.retryQerry.isNotEmpty()) Button(modifier = Modifier.padding(16.dp).constrainAs(butRetry) { top.linkTo(txtvError.bottom) }, onClick = { vm.search(vm.retryQerry) }) { Text(stringResource(id = R.string.retry_label)) }
            Text(context.getString(R.string.search_powered_by, searchProvider.name), color = Color.Black, style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(Color.LightGray)
                .constrainAs(powered) {
                    bottom.linkTo(parent.bottom)
                    end.linkTo(parent.end)
                })
        }
    }
}

private val TAG: String = Screens.FindFeeds.name
