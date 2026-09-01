package ac.mdiq.podcini.ui.screens

import ac.mdiq.podcini.R
import ac.mdiq.podcini.activity.MainActivity
import ac.mdiq.podcini.activity.ShareReceiverActivity.Companion.receiveShared
import ac.mdiq.podcini.sourcing.download.RequestType
import ac.mdiq.podcini.sourcing.feed.FeedUpdater
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.sourcing.sourceClients
import ac.mdiq.podcini.storage.database.addToFeed
import ac.mdiq.podcini.storage.database.feedsMap
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.DownloadResult
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.storage.model.ShareLog
import ac.mdiq.podcini.storage.model.SubscriptionLog
import ac.mdiq.podcini.storage.model.toEpisode
import ac.mdiq.podcini.storage.specs.Rating.Companion.fromCode
import ac.mdiq.podcini.ui.actions.ActionButton
import ac.mdiq.podcini.ui.actions.ButtonTypes
import ac.mdiq.podcini.ui.compose.CommonPopupCard
import ac.mdiq.podcini.ui.compose.ConfirmAddToFeed
import ac.mdiq.podcini.ui.compose.ConfirmDialog
import ac.mdiq.podcini.ui.compose.borderColor
import ac.mdiq.podcini.ui.compose.textColor
import ac.mdiq.podcini.utils.EventFlow
import ac.mdiq.podcini.utils.FlowEvent
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logt
import ac.mdiq.podcini.utils.formatDateTimeFlex
import ac.mdiq.podcini.utils.sessionLogsFlow
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.xilinjia.krdb.query.Sort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.days

enum class LogsModes(val res: Int) {
    Session(R.drawable.baseline_running_with_errors_24),
    Downloads(R.drawable.ic_download),
    Shares(R.drawable.ic_share),
    Deletions(R.drawable.outline_delete_history_24)
}

class LogsVM: ViewModel() {
    internal var shareLogs by mutableStateOf<List<ShareLog>>(listOf())

    internal var deletionLogs by mutableStateOf<List<SubscriptionLog>>(listOf())

    internal var downloadLogs by mutableStateOf<List<DownloadResult>>(listOf())
    internal var mode by mutableStateOf(LogsModes.Session )

    var showSuccessLogs by mutableStateOf(false)

    init {
        viewModelScope.launch {
            val trimTime = nowInMillis() - 30.days.inWholeMilliseconds
            realm.write {
                val items = query(DownloadResult::class).query("completionTime < $trimTime").find()
                if (items.isNotEmpty()) delete(items)
            }
            snapshotFlow { mode }.distinctUntilChanged().collectLatest { m ->
                when (m) {
                    LogsModes.Shares -> realm.query(ShareLog::class).sort("id", Sort.DESCENDING).asFlow().distinctUntilChanged().map { it.list }.collect { v->
                        val logs = withContext(Dispatchers.Default) { v.toList().distinctBy { it.url }.toList() }
                        if (logs.isNotEmpty()) shareLogs = logs
                        else {
                            Logt(TAG, "Share log is empty")
                            mode = LogsModes.Session
                        }
                    }
                    LogsModes.Downloads -> realm.query(DownloadResult::class).sort("completionTime",  Sort.DESCENDING).asFlow().distinctUntilChanged().map { it.list }.collect { v->
                        val logs = withContext(Dispatchers.Default) { v.toList().distinctBy { it.feedfileId } }
                        if (logs.isNotEmpty()) downloadLogs = logs
                        else {
                            Logt(TAG, "Download log is empty")
                            mode = LogsModes.Session
                        }
                    }
                    LogsModes.Deletions -> realm.query(SubscriptionLog::class).sort("cancelDate", Sort.DESCENDING).asFlow().distinctUntilChanged().map { it.list }.collect { v->
                        if (v.isNotEmpty()) deletionLogs = v
                        else {
                            Logt(TAG, "Deletion log is empty")
                            mode = LogsModes.Session
                        }
                    }
                    else -> {}
                }
            }
        }
    }
    internal fun clearAllLogs() {
        deletionLogs = listOf()
        shareLogs = listOf()
        downloadLogs = listOf()
    }
}

@ExperimentalMaterial3Api
@Composable
fun LogsScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context by rememberUpdatedState(LocalContext.current)
    val drawerController = LocalDrawerController.current

    val vm: LogsVM = viewModel()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> {}
                Lifecycle.Event.ON_START -> {}
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

    fun copyToClipboard(message: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(context.getString(R.string.download_error_details), message)
        clipboard.setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < 32) EventFlow.postEvent(FlowEvent.MessageEvent(context.getString(R.string.copied_to_clipboard)))
    }

    @Composable
    fun SharedDetailDialog(status: ShareLog, onDismiss: () -> Unit) {
        val message = when (status.status) {
            ShareLog.Status.ERROR.code -> status.details
            ShareLog.Status.SUCCESS.code -> stringResource(R.string.download_successful)
            ShareLog.Status.EXISTING.code -> stringResource(R.string.share_existing)
            else -> ""
        }
        CommonPopupCard(onDismiss = { onDismiss() }) {
            Column(modifier = Modifier.padding(10.dp)) {
                
                Text(stringResource(R.string.download_error_details), color = textColor, modifier = Modifier.padding(bottom = 3.dp))
                Text(message, color = textColor)
                Row(Modifier.padding(top = 10.dp)) {
                    Spacer(Modifier.weight(0.5f))
                    Text(stringResource(R.string.copy_to_clipboard), color = textColor, modifier = Modifier.clickable { copyToClipboard(message) })
                    Spacer(Modifier.weight(0.3f))
                    Text("OK", color = textColor, modifier = Modifier.clickable { onDismiss() })
                    Spacer(Modifier.weight(0.2f))
                }
            }
        }
    }

    @Composable
    fun SharedLogView() {
        val lazyListState = rememberLazyListState()
        val showSharedDialog = remember { mutableStateOf(false) }
        val sharedlogState = remember { mutableStateOf(ShareLog()) }
        if (showSharedDialog.value) SharedDetailDialog(status = sharedlogState.value, onDismiss = { showSharedDialog.value = false })

        var sharedUrl by remember { mutableStateOf("") }
        if (sharedUrl.isNotBlank()) ConfirmAddToFeed(onDismiss = {  }) { toFeed->
            Logd(TAG, "ConfirmAddToFeed cb sharedUrl: $sharedUrl")
            val log = realm.query(ShareLog::class).query("url == $0", sharedUrl).first().find()
            val client = sourceClients.find { it.withProvider { p-> p.canHandleUrl(sharedUrl) == 1 } == true }
            if (client != null) {
                val episode = client.withProvider { it.buildEpisode(sharedUrl)?.toEpisode() }
                if (episode != null) addToFeed(episode, toFeed, log)
                else {
                    Loge(TAG, "Failed adding episode: client can't handle. url=$sharedUrl")
                    if (log != null) upsert(log) {
                        it.details = "client can't handle"
                        it.status = ShareLog.Status.ERROR.code
                    }
                }
            } else {
                val clients = sourceClients.filter { it.withProvider { p-> p.canHandleUrl(sharedUrl) == 0 } == true }
                var success = false
                for (c in clients) {
                    val episode = c.withProvider { it.buildEpisode(sharedUrl)?.toEpisode() }
                    if (episode != null) {
                        addToFeed(episode, toFeed, log)
                        success = true
                        break
                    }
                }
                if (!success) {
                    Loge(TAG, "Failed adding episode: no client can handle. url=$sharedUrl")
                    if (log != null) upsert(log) {
                        it.details = "no client can handle"
                        it.status = ShareLog.Status.ERROR.code
                    }
                }
            }
            sharedUrl = ""
        }

        val logs = remember(vm.shareLogs, vm.showSuccessLogs) { vm.shareLogs.filter { vm.showSuccessLogs == (it.status == ShareLog.Status.SUCCESS.code) } }
        LazyColumn(state = lazyListState, modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(logs) { log ->
                Column(modifier = Modifier.fillMaxWidth().clickable {
                    Logd(TAG, "shared log url: ${log.url}")
                    if (log.status in listOf(ShareLog.Status.ERROR.code, ShareLog.Status.MISSING.code)) {
                        Logt(TAG, "Handling shared url...")
                        runOnIOScope { receiveShared(log.url!!, context as MainActivity, false, log) { _, _ -> sharedUrl = log.url!! } }
                        return@clickable
                    }
                    var hasError = false
                    when (log.type) {
                        ShareLog.ShareType.Media.name -> {
                            val episode = realm.query(Episode::class).query("title == $0", log.title).first().find()
                            if (episode != null) navTo(EpisodeInfo(episodeId = episode.id))
                            else hasError = true
                        }
                        "Podcast", ShareLog.ShareType.Feed.name -> {
                            val feed = realm.query(Feed::class, "eigenTitle == $0 AND author == $1", log.title ?: "", log.author ?: "").first().find()
                            if (feed != null) navTo(FeedDetails(feedId = feed.id, modeName = FeedScreenMode.Info.name))
                            else hasError = true
                        }
                        else -> {
                            showSharedDialog.value = true
                            sharedlogState.value = log
                        }
                    }
                    if (hasError) {
                        runOnIOScope {
                            Logt(TAG, "Handling shared url...")
                            val log_ = upsertBlk(log) { it.status = ShareLog.Status.MISSING.code }
                            vm.shareLogs = listOf()
                            receiveShared(log_.url!!, context as MainActivity, false, log_) { _, _ -> sharedUrl = log_.url!! }
                        }
                    }
                }) {
                    Row {
                        Icon(if (log.status == ShareLog.Status.SUCCESS.code) Icons.Filled.Info else Icons.Filled.Warning, "Info", tint = if (log.status == ShareLog.Status.SUCCESS.code) Color.Green else Color.Yellow, modifier = Modifier.padding(end = 2.dp))
                        Text(formatDateTimeFlex(log.id), color = textColor)
                        Spacer(Modifier.weight(1f))
                        if (log.status < ShareLog.Status.SUCCESS.code) Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_delete), tint = textColor, contentDescription = null, modifier = Modifier.width(25.dp).height(25.dp).clickable {})
                    }
                    Text(log.title ?: "unknown title", color = textColor)
                    Text(log.url ?: "unknown url", color = textColor)
                    Row {
                        val statusText = remember(log.status) { ShareLog.Status.entries.firstOrNull { it.code == log.status }?.name ?: ShareLog.Status.ERROR.name }
                        Text(statusText, color = textColor)
                        Spacer(Modifier.weight(1f))
                        Text(log.type ?: "unknow type", color = textColor)
                    }
                }
            }
        }
    }

    @Composable
    fun DeletionDetailDialog(log: SubscriptionLog, onDismiss: () -> Unit) {
        CommonPopupCard(onDismiss = { onDismiss() }) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(stringResource(R.string.download_error_details), color = textColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 3.dp))
                Text(stringResource(R.string.title), color = textColor,  style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(log.title, color = textColor, modifier = Modifier.padding(bottom = 5.dp))
                Text(stringResource(R.string.comments), color = textColor,  style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(log.comment.ifEmpty { "None" }, color = textColor, modifier = Modifier.padding(bottom = 5.dp))
                Text(stringResource(R.string.description_label), color = textColor,  style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(log.description?:"None", color = textColor, modifier = Modifier.padding(bottom = 5.dp))
                Text("URL:", color = textColor,  style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(log.url ?:"None", color = textColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 5.dp))
                Text("Link:", color = textColor,  style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(log.link ?: "None", color = textColor, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(top = 10.dp)) {
                    Spacer(Modifier.weight(0.3f))
                    Text("OK", color = textColor, modifier = Modifier.clickable { onDismiss() })
                    Spacer(Modifier.weight(0.2f))
                }
            }
        }
    }

    @Composable
     fun DeletionLogView() {
        val lazyListState = rememberLazyListState()
        var dialogParam by remember { mutableStateOf<SubscriptionLog?>(null) }
        if (dialogParam != null) DeletionDetailDialog(log = dialogParam!!, onDismiss = { dialogParam = null })

        LazyColumn(state = lazyListState, modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.deletionLogs) { log ->
                Row (verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, end = 10.dp).clickable { dialogParam = log }) {
                    val iconRes = remember { fromCode(log.rating).res  }
                    Icon(imageVector = ImageVector.vectorResource(iconRes), tint = MaterialTheme.colorScheme.tertiary, contentDescription = "rating", modifier = Modifier.background(MaterialTheme.colorScheme.tertiaryContainer).width(40.dp).height(40.dp).padding(end = 15.dp))
                    Column {
                        Text(log.type + ": " + formatDateTimeFlex(log.id) + " -- " + formatDateTimeFlex(log.cancelDate), color = textColor)
                        Text(log.title, color = textColor)
                    }
                }
            }
        }
    }

    @Composable
    fun SessionLogView() {
        val lazyListState = rememberLazyListState()
        val sessionLogs by sessionLogsFlow.collectAsStateWithLifecycle()
        val logs = remember(sessionLogs, vm.showSuccessLogs) { sessionLogs.reversed().filter { vm.showSuccessLogs == !it.contains("Error", ignoreCase = true) } }
        LazyColumn(state = lazyListState, modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(logs) { log -> Text(log, color = if (log.contains("Error", ignoreCase = true)) Color.Red else textColor) }
        }
    }

    @Composable
    fun DownlaodDetailDialog(status: DownloadResult, onDismiss: () -> Unit) {
        var url by remember { mutableStateOf("unknown") }
        var feed by remember(status.feedfileId) { mutableStateOf<Feed?>(null) }
        var media by remember(status.feedfileId) { mutableStateOf<Episode?>(null) }
        Logd(TAG, "DownlaodDetailDialog ${status.feedfileType} status.feedfileId: ${status.feedfileId}")
        LaunchedEffect(status.feedfileId) {
            when (status.feedfileType) {
                RequestType.FEEDMEDIA.code -> {
                    media = realm.query(Episode::class).query("id == $0", status.feedfileId).first().find()
                    if (media != null) url = media!!.downloadUrl ?: ""
                }
                RequestType.FEED.code -> {
                    feed = feedsMap[status.feedfileId]
                    if (feed != null) url = feed!!.downloadUrl ?: ""
                }
            }
        }
        CommonPopupCard(onDismiss = { onDismiss() }) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(stringResource(R.string.download_error_details), color = textColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 3.dp))
                Text(stringResource(status.reason?.res ?: R.string.download_error_error_unknown), color = textColor, modifier = Modifier.padding(bottom = 5.dp))
                Text(stringResource(R.string.reason), color = textColor, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(status.reasonDetailed, color = textColor, modifier = Modifier.padding(bottom = 5.dp))
                Text("URL:", color = textColor, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(url, color = textColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 5.dp))
                if (feed == null && media == null) Text(stringResource(R.string.content_not_exist))

                Row(Modifier.padding(top = 10.dp)) {
                    Spacer(Modifier.weight(0.2f))
                    val message = stringResource(status.reason?.res ?: R.string.download_error_error_unknown) + "\n" + status.reasonDetailed + "\n" + url
                    Text(stringResource(R.string.copy_to_clipboard), color = textColor, modifier = Modifier.clickable { copyToClipboard(message) })
                    Spacer(Modifier.weight(0.3f))
                    if (!status.isSuccessful) Text(stringResource(R.string.retry), color = textColor, modifier = Modifier.clickable {
                        if (feed != null) runOnIOScope { FeedUpdater(listOf(feed!!)).start() }
                        else if (media != null) {
                            ActionButton(media!!, typeInit = ButtonTypes.DOWNLOAD).onClick()
                            Logt(TAG, context.getString(R.string.status_downloading_label))
                        }
                        onDismiss()
                    })
                    Spacer(Modifier.weight(0.2f))
                    val bynText = if (feed != null || media != null) stringResource(R.string.open) else "OK"
                    Text(bynText, color = textColor, modifier = Modifier.clickable {
                        if (feed != null) navTo(FeedDetails(feedId = feed!!.id, modeName = FeedScreenMode.Info.name))
                        else if (media != null) navTo(EpisodeInfo(episodeId = media!!.id))
                        onDismiss()
                    })
                    Spacer(Modifier.weight(0.2f))
                }
            }
        }
    }

    @Composable
     fun DownloadLogView() {
        val lazyListState = rememberLazyListState()
        var showDialog by remember { mutableStateOf(false) }
        var dialogParam by remember { mutableStateOf(DownloadResult()) }
        if (showDialog) DownlaodDetailDialog(status = dialogParam, onDismiss = { showDialog = false })

        val logs = remember(vm.downloadLogs, vm.showSuccessLogs) { vm.downloadLogs.filter { vm.showSuccessLogs == it.isSuccessful } }
        LazyColumn(state = lazyListState, modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(logs) { status ->
                Column(modifier = Modifier.fillMaxWidth().clickable {
                    showDialog = true
                    dialogParam = status
                }) {
                    Row {
                        Icon(if (status.isSuccessful) Icons.Filled.Info else Icons.Filled.Warning, "Info", tint =  if (status.isSuccessful) Color.Green else Color.Yellow, modifier = Modifier.padding(end = 5.dp))
                        val statusText = remember(status.id) {
                            "" + when (status.feedfileType) {
                                RequestType.FEED.code -> context.getString(R.string.download_type_feed)
                                RequestType.FEEDMEDIA.code -> context.getString(R.string.download_type_media)
                                else -> ""
                            } + " · " + formatDateTimeFlex(status.completionTime)
                        }
                        Text(statusText, color = textColor)
                    }
                    Text(status.title.ifEmpty { stringResource(R.string.download_log_title_unknown) }, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!status.isSuccessful) Text(stringResource(status.reason?.res ?: R.string.download_error_error_unknown), color = Color.Red)
                }
            }
        }
    }

    val showDeleteConfirmDialog = remember { mutableStateOf(false) }

    @Composable
     fun MyTopAppBar() {
        Box {
            TopAppBar(title = {  }, navigationIcon = { Icon(imageVector = ImageVector.vectorResource(vm.mode.res), contentDescription = "Open Drawer", modifier = Modifier.padding(7.dp).clickable { drawerController?.open() }) },
                actions = {
                    if (vm.mode in listOf(LogsModes.Session, LogsModes.Downloads, LogsModes.Shares)) Switch(checked = vm.showSuccessLogs, onCheckedChange = { vm.showSuccessLogs = !vm.showSuccessLogs },
                        thumbContent = { Icon(imageVector = if (vm.showSuccessLogs) Icons.Filled.Info else Icons.Filled.Warning, contentDescription = null, tint = if (vm.showSuccessLogs) Color.Green else Color.Yellow , modifier = Modifier.size(SwitchDefaults.IconSize)) })
                    if (vm.mode != LogsModes.Session) IconButton(onClick = {
                        vm.clearAllLogs()
                        vm.mode = LogsModes.Session
                    }) { Icon(imageVector = ImageVector.vectorResource(LogsModes.Session.res), contentDescription = "session") }
                    if (vm.mode != LogsModes.Downloads) IconButton(onClick = {
                        vm.clearAllLogs()
                        vm.mode = LogsModes.Downloads
                    }) { Icon(imageVector = ImageVector.vectorResource(LogsModes.Downloads.res), contentDescription = "download") }
                    if (vm.mode != LogsModes.Shares) IconButton(onClick = {
                        vm.clearAllLogs()
                        vm.mode = LogsModes.Shares
                    }) { Icon(imageVector = ImageVector.vectorResource(LogsModes.Shares.res), contentDescription = "share") }
                    if (vm.mode != LogsModes.Deletions) IconButton(onClick = {
                        vm.clearAllLogs()
                        vm.mode = LogsModes.Deletions
                    }) { Icon(imageVector = ImageVector.vectorResource(LogsModes.Deletions.res), contentDescription = "Deletions") }
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                    if (vm.mode != LogsModes.Deletions) DropdownMenu(expanded = expanded, border = BorderStroke(1.dp, borderColor), onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.clear_logs)) }, onClick = {
                            showDeleteConfirmDialog.value = true
                            expanded = false
                        })
                    }
            })
            HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(), thickness = DividerDefaults.Thickness, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }

    Scaffold(topBar = { MyTopAppBar() }) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            ConfirmDialog(R.string.confirm_delete_logs_label, stringResource(R.string.confirm_delete_logs_message), showDeleteConfirmDialog) {
                runOnIOScope {
                    when {
                        vm.shareLogs.isNotEmpty() -> {
                            realm.write {
                                val items = query(ShareLog::class).find()
                                delete(items)
                            }
                            vm.shareLogs = listOf()
                        }
                        vm.downloadLogs.isNotEmpty() -> {
                            realm.write {
                                val items = query(DownloadResult::class).find()
                                delete(items)
                            }
                            vm.downloadLogs = listOf()
                        }
                    }
                }
            }
            when {
                vm.downloadLogs.isNotEmpty() -> DownloadLogView()
                vm.shareLogs.isNotEmpty() -> SharedLogView()
                vm.deletionLogs.isNotEmpty() -> DeletionLogView()
                else -> SessionLogView()
            }
        }
    }
}

private const val TAG: String = "LogsScreen"


