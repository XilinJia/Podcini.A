package ac.mdiq.podcini.ui.compose

import ac.mdiq.podcini.R
import ac.mdiq.podcini.config.settings.OpmlTransporter
import ac.mdiq.podcini.net.feed.FeedBuilder
import ac.mdiq.podcini.net.feed.subscribe
import ac.mdiq.podcini.net.sync.transceive.listenForUDPBroadcasts
import ac.mdiq.podcini.shared.EpisodeIPC
import ac.mdiq.podcini.shared.FeedSearchResult
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.sources.EPISODE_BATCH_SIZE
import ac.mdiq.podcini.sources.clientBySearcher
import ac.mdiq.podcini.storage.database.appAttribsFlow
import ac.mdiq.podcini.storage.database.createSynthetic
import ac.mdiq.podcini.storage.database.deleteFeed
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.updateFeedFull
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.storage.model.Feed.Companion.EPISODES_LIMIT
import ac.mdiq.podcini.storage.model.SubscriptionLog
import ac.mdiq.podcini.storage.model.SubscriptionLog.Companion.feedLogsMap
import ac.mdiq.podcini.storage.model.Volume
import ac.mdiq.podcini.storage.specs.FeedType
import ac.mdiq.podcini.storage.specs.Rating
import ac.mdiq.podcini.storage.specs.VideoMode
import ac.mdiq.podcini.ui.screens.FeedDetails
import ac.mdiq.podcini.ui.screens.OnlineFeed
import ac.mdiq.podcini.ui.screens.navTo
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logs
import ac.mdiq.podcini.utils.formatLargeInteger
import ac.mdiq.podcini.utils.formatWithGrouping
import ac.mdiq.podcini.utils.fullDateTimeString
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChooseRatingDialog(selected: List<Feed>, onDismiss: () -> Unit) {
    CommonPopupCard(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            for (rating in Rating.entries.reversed()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp).clickable {
                    for (item in selected) upsertBlk(item) { it.rating = rating.code }
                    onDismiss()
                }) {
                    Icon(imageVector = ImageVector.vectorResource(id = rating.res), "")
                    Text(rating.name, Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@Composable
fun RemoveFeedDialog(feeds: List<Feed>, onDismiss: () -> Unit, callback: ()->Unit) {
    val message = if (feeds.size == 1) {
        if (feeds[0].isLocal) stringResource(R.string.feed_delete_confirmation_local_msg, feeds[0].title?:"No title")
        else stringResource(R.string.feed_delete_confirmation_msg, feeds[0].title?:"No title")
    } else stringResource(R.string.feed_delete_confirmation_msg_batch)
    
    var textState by remember { mutableStateOf(TextFieldValue("")) }

    CommonDialogSurface(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(message)
            var saveImportant by remember { mutableStateOf(true) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = saveImportant, onCheckedChange = { saveImportant = it })
                Text(text = stringResource(R.string.shelve_important), style = MaterialTheme.typography.bodyMedium, color = textColor, modifier = Modifier.padding(start = 10.dp))
            }
            Text(stringResource(R.string.reason_to_delete_msg))
            BasicTextField(value = textState, onValueChange = { textState = it }, textStyle = TextStyle(fontSize = 16.sp, color = textColor), modifier = Modifier.fillMaxWidth().height(100.dp).padding(start = 10.dp, end = 10.dp, bottom = 10.dp).border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small))
            val reasonText = stringResource(R.string.reason_to_remove)
            Button(onClick = {
                callback()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        for (f in feeds) {
                            if (!f.isSynthetic()) {
                                val sLog = SubscriptionLog(f.id, f.title ?: "", f.downloadUrl ?: "", f.link ?: "", SubscriptionLog.Type.Feed.name)
                                upsert(sLog) {
                                    it.description = f.description?.take(100).orEmpty()
                                    it.rating = f.rating
                                    it.comment = if (f.comment.isBlank()) "" else (f.comment + "\n")
                                    it.comment += fullDateTimeString() + "\n$reasonText:\n" + textState.text
                                    it.cancelDate = nowInMillis()
                                }
                            }
                            val preserve = if (saveImportant) f.worthyEpisodes.isNotEmpty() else false
                            deleteFeed(f.id, preserve)
                        }
                        feedLogsMap = null
                    } catch (e: Throwable) { Logs("RemoveFeedDialog", e) }
                }
                onDismiss()
            }) { Text(stringResource(R.string.confirm_label)) }
        }
    }
}

@Composable
fun OnlineFeedItem(result: FeedSearchResult, log: SubscriptionLog? = null) {
    val TAG = "OnlineFeedItem"
    val context = LocalContext.current
    val showSubscribeDialog = remember { mutableStateOf(false) }
    suspend fun subscribeFeed(result: FeedSearchResult) {
        val url = result.feedUrl ?: return
        val client = clientBySearcher(result.source)
        if (client != null) {
            val fipc = client.withProvider { it.buildFeed(url, 0) }
            if (fipc != null) {
                val eList = mutableListOf<EpisodeIPC>()
                var episodes = client.withProvider { it.getEpisodes(EPISODE_BATCH_SIZE, 0L) }?: listOf()
                while (episodes.isNotEmpty()) {
                    eList.addAll(episodes)
                    Logd(TAG, "subscribeFeed eList: ${eList.size}")
                    if (eList.size > EPISODES_LIMIT || episodes.size < EPISODE_BATCH_SIZE) break
                    episodes = client.withProvider { it.getEpisodes(EPISODE_BATCH_SIZE, 0L) } ?: listOf()
                }
                fipc.episodes = eList
                subscribe(fipc)
            } else Loge(TAG, "Subscribe feed failed")
        } else {
            val fbb = FeedBuilder { message, details -> Loge("OnineFeedItem", "Subscribe error: $message \n $details") }
            fbb.buildPodcast(url, "", "") { feed, _ -> subscribe(feed) }
        }
    }
    if (showSubscribeDialog.value) CommonPopupCard(onDismiss = { showSubscribeDialog.value = false }) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.Center) {
            Text("Subscribe: \"${result.title}\" ?", color = textColor, modifier = Modifier.padding(bottom = 10.dp))
            Button(onClick = {
                runOnIOScope { subscribeFeed(result) }
                showSubscribeDialog.value = false
            }) { Text(stringResource(R.string.confirm_label)) }
        }
    }

    Column(Modifier.padding(start = 5.dp, end = 5.dp, top = 4.dp, bottom = 4.dp).combinedClickable(
        onClick = {
            if (result.feedUrl != null) {
                Logd(TAG, "feed.feedId: ${result.feedId}")
                if (result.feedId > 0) navTo(FeedDetails(feedId = result.feedId))
                else navTo(OnlineFeed(url = result.feedUrl!!, source = result.source))
            } },
        onLongClick = { showSubscribeDialog.value = true })) {
        
        Row {
            Box(modifier = Modifier.width(80.dp).height(80.dp)) {
                AsyncImage(model = ImageRequest.Builder(context).data(result.imageUrl).memoryCachePolicy(CachePolicy.ENABLED).build(), placeholder = painterResource(R.drawable.ic_launcher_foreground), error = painterResource(R.drawable.ic_launcher_foreground), contentDescription = "imgvCover", modifier = Modifier.fillMaxSize())
                if (result.feedId > 0 || log != null) {
                    Logd("OnlineFeedItem", "${result.feedId} $log")
                    val iRes = remember(result) { if (result.feedId > 0) R.drawable.ic_check else R.drawable.baseline_clear_24 }
                    Icon(imageVector = ImageVector.vectorResource(iRes), tint = textColor, contentDescription = "played_mark", modifier = Modifier.background(Color.Green).alpha(1.0f).align(Alignment.BottomEnd))
                }
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(result.title, color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
                val authorText = remember(result.author) { result.author?.takeIf { it.isNotBlank() }?.trim { it <= ' ' }?: "Anonymous" }
                Text(authorText, color = textColor, style = MaterialTheme.typography.bodyMedium)
                if (result.subscriberCount > 0) Text(formatLargeInteger(result.subscriberCount) + " subscribers", color = textColor, style = MaterialTheme.typography.bodyMedium)
                Row {
                    Text(result.count.toString() + " episodes", color = textColor, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    if (result.update != null) Text(result.update!!, color = textColor, style = MaterialTheme.typography.bodyMedium)
                }
                Text("${result.source}:\u00A0${result.feedUrl ?: "unavailable"}", color = textColor, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun AmendSyntheticFeed(feed_: Feed? = null, name_: String? = null, volume: Volume? = null, onDismiss: () -> Unit, cb: (Feed)->Unit) {
    CommonPopupCard(onDismiss = { onDismiss() }) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.rename_feed_label), color = textColor, style = MaterialTheme.typography.bodyLarge)
            var name by remember { mutableStateOf(feed_?.title ?: name_ ?: "") }
            TextField(value = name,  singleLine = true, onValueChange = { name = it }, label = { Text(stringResource(R.string.new_namee)) })
            var hasVideo by remember { mutableStateOf(true) }
            var feedType by remember { mutableStateOf(FeedType.fromName(feed_?.type)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = hasVideo, onCheckedChange = { hasVideo = it })
                Text(text = stringResource(R.string.has_video), style = MaterialTheme.typography.bodyMedium, color = textColor, modifier = Modifier.padding(start = 10.dp))
            }
            HorizontalDivider(modifier = Modifier.fillMaxWidth(), thickness = DividerDefaults.Thickness, color = MaterialTheme.colorScheme.outlineVariant)
            Text(text = stringResource(R.string.pref_feed_type_sum), style = MaterialTheme.typography.bodyMedium, color = textColor)
            for (type in FeedType.entries + listOf(null)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = type == feedType, onCheckedChange = { feedType = type })
                    Text(text = type?.name?:"null", style = MaterialTheme.typography.bodyMedium, color = textColor, modifier = Modifier.padding(start = 10.dp))
                }
            }
            Row {
                Button({ onDismiss() }) { Text(stringResource(R.string.cancel_label)) }
                Spacer(Modifier.weight(1f))
                Button({
                    var feed = feed_ ?: createSynthetic(0, name, hasVideo)
                    feed.type = feedType?.name
                    if (hasVideo) feed.videoModePolicy = VideoMode.WINDOW
                    if (volume != null) feed.volumeId = volume.id
                    if (feed_ != null) feed.customTitle = if (name == feed.eigenTitle) null else name
                    feed = upsertBlk(feed) { }
                    cb(feed)
                    onDismiss()
                }) { Text(stringResource(R.string.confirm_label)) }
            }
        }
    }
}

@Composable
fun OpmlImportSelectionDialog(readElements: List<OpmlTransporter.OpmlElement>, onDismiss: () -> Unit) {
    val selectedItems = remember {  mutableStateMapOf<Int, Boolean>() }
    AlertDialog(modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.extraLarge), onDismissRequest = { onDismiss() },
        title = { Text("Import OPML file") },
        text = {
            var isSelectAllChecked by remember { mutableStateOf(false) }
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Select/Deselect All", modifier = Modifier.weight(1f))
                    Checkbox(checked = isSelectAllChecked, onCheckedChange = { isChecked ->
                        isSelectAllChecked = isChecked
                        readElements.forEachIndexed { index, _ -> selectedItems[index] = isChecked }
                    })
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(readElements) { index, item ->
                        Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text = item.text?:"", modifier = Modifier.weight(1f))
                            Checkbox(checked = selectedItems[index] == true, onCheckedChange = { checked -> selectedItems[index] = checked })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                Logd("OpmlImportSelectionDialog", "checked: $selectedItems")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        withContext(Dispatchers.IO) {
                            if (readElements.isNotEmpty()) {
                                for (i in selectedItems.keys) {
                                    if (selectedItems[i] != true) continue
                                    val element = readElements[i]
                                    val feed = Feed(element.xmlUrl, null, if (element.text != null) element.text else "Unknown podcast")
                                    feed.episodes.clear()   // TODO: this doesn't do anything
                                    updateFeedFull(feed, removeUnlistedItems = false)
                                }
                            }
                        }
                    } catch (e: Throwable) { Logs("OpmlImportSelectionDialog", e) }
                }
                onDismiss()
            }) { Text(stringResource(R.string.confirm_label)) }
        },
        dismissButton = { Button(onClick = { onDismiss() }) { Text("Dismiss") } }
    )
}

@Composable
fun VideoModeDialog(initMode: VideoMode?, isDemuxed: Boolean? = null, muxed: Boolean = false, onDismiss: () -> Unit, callback: (VideoMode, Boolean) -> Unit) {
    var selectedOption by remember { mutableStateOf(initMode ?: VideoMode.DEFAULT) }
    var useMuxed by remember { mutableStateOf(muxed) }
    CommonPopupCard(onDismiss = { onDismiss() }) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                VideoMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = (mode == selectedOption), onCheckedChange = { if (mode != selectedOption) selectedOption = mode })
                        Text(text = mode.tag, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                }
                if (isDemuxed != false && selectedOption != VideoMode.AUDIO_ONLY) {
                    HorizontalDivider(modifier = Modifier.fillMaxWidth(), thickness = DividerDefaults.Thickness, color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useMuxed, onCheckedChange = { useMuxed = it })
                        Text(stringResource(R.string.pref_muxed_video), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                    Text(stringResource(R.string.pref_muxed_video_sum), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
                }
            }
            Row {
                Button({ onDismiss() }) { Text(stringResource(R.string.cancel_label)) }
                Spacer(Modifier.weight(1f))
                Button({
                    callback(selectedOption, useMuxed)
                    onDismiss()
                }) { Text(stringResource(R.string.confirm_label)) }
            }
        }
    }
}

@Composable
fun AssociatedFeedsGrid(feedsAssociated: List<Feed>) {
    val TAG = "AssociatedFeedsGrid"
    val context = LocalContext.current
    LazyVerticalGrid(state = rememberLazyGridState(), columns = GridCells.Adaptive(80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(start = 12.dp, top = 16.dp, end = 12.dp, bottom = 16.dp)) {
        items(feedsAssociated, key = {it.id}) { feed ->
            ConstraintLayout {
                val (coverImage, episodeCount, rating, _) = createRefs()
                AsyncImage(model = ImageRequest.Builder(context).data(feed.imageUrl).memoryCachePolicy(CachePolicy.ENABLED).build(), placeholder = painterResource(R.drawable.ic_launcher_foreground), error = painterResource(R.drawable.ic_launcher_foreground), contentDescription = "coverImage",
                    colorFilter = if (!feed.inNormalVolume) ColorFilter.tint(color = Color.Gray.copy(alpha = 0.5f), blendMode = BlendMode.SrcAtop) else null,
                    modifier = Modifier.height(100.dp).aspectRatio(1f)
                        .constrainAs(coverImage) {
                            top.linkTo(parent.top)
                            bottom.linkTo(parent.bottom)
                            start.linkTo(parent.start)
                        }.combinedClickable(
                            onClick = { navTo(FeedDetails(feedId = feed.id)) },
                            onLongClick = { Logd(TAG, "long clicked: ${feed.title}") })
                )
                val numEpisodes by remember(feed.episodesCount) { mutableIntStateOf(feed.episodesCount) }
                Text(formatWithGrouping(numEpisodes.toLong()), color = Color.Green,
                    modifier = Modifier.background(Color.Gray).constrainAs(episodeCount) {
                        end.linkTo(parent.end)
                        top.linkTo(coverImage.top)
                    })
                if (feed.rating != Rating.UNRATED.code)
                    Icon(imageVector = ImageVector.vectorResource(Rating.fromCode(feed.rating).res), tint = MaterialTheme.colorScheme.tertiary, contentDescription = "rating",
                        modifier = Modifier.background(MaterialTheme.colorScheme.tertiaryContainer).constrainAs(rating) {
                            start.linkTo(parent.start)
                            centerVerticallyTo(coverImage)
                        })
            }
        }
    }
}

@Composable
fun SendToDevice(onDismiss: ()->Unit, cb: (String, Int)->Job?) {
    val appAttribs by appAttribsFlow!!.collectAsStateWithLifecycle()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var uid by remember { mutableStateOf("") }
    var udpPort by remember(appAttribs.udpPort) { mutableIntStateOf(appAttribs.udpPort) }
    var sendJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) {
        listenForUDPBroadcasts(udpPort) { list ->
            if (list.isNotEmpty()) {
                host = list[0].ip
                port = list[0].port
                name = list[0].name
                uid = list[0].uid
                Logd("SendToDevice", "name: $name host: $host port: $port")
            }
        }
    }
    fun cleanup() {
        sendJob?.cancel()
        onDismiss()
    }
    DisposableEffect(Unit) { onDispose { cleanup() } }

    AlertDialog(modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.extraLarge), onDismissRequest = {  },
        title = { Text(stringResource(R.string.send_to_device), style = CustomTextStyles.titleCustom) },
        text = {
            Column {
                Text(stringResource(R.string.send_to_device_sum))
                TextField(value = udpPort.toString(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(R.string.broadcast_port)) }, singleLine = true, modifier = Modifier.padding(end = 8.dp), onValueChange = { udpPort = it.toIntOrNull() ?: 0 })
                Text(stringResource(R.string.receiver_tag, "$name:$host:$port"))
            }
        },
        confirmButton = {
            if (sendJob == null && host.isNotEmpty()) TextButton(onClick = {
                if (udpPort != appAttribs.udpPort) upsertBlk(appAttribs) { it.udpPort = udpPort }
                sendJob = cb(host, port)
            }) { Text(stringResource(R.string.send)) }
        },
        dismissButton = { TextButton(onClick = { cleanup() }) { Text(stringResource(R.string.cancel_label)) } }
    )
}
