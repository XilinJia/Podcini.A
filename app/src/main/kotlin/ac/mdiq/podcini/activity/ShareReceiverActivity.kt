package ac.mdiq.podcini.activity

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.activity.MainActivity.Extras
import ac.mdiq.podcini.sourcing.AppGatewayRegistry
import ac.mdiq.podcini.sourcing.SourceGatewayClient
import ac.mdiq.podcini.sourcing.sourceClients
import ac.mdiq.podcini.storage.database.addToFeed
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.storage.model.ShareLog
import ac.mdiq.podcini.storage.model.toEpisode
import ac.mdiq.podcini.storage.utils.toSafeUri
import ac.mdiq.podcini.ui.compose.ConfirmAddToFeed
import ac.mdiq.podcini.ui.compose.EpisodeLazyColumn
import ac.mdiq.podcini.ui.compose.EpisodeScreen
import ac.mdiq.podcini.ui.compose.LayoutMode
import ac.mdiq.podcini.ui.compose.PodciniTheme
import ac.mdiq.podcini.ui.compose.episodeForInfo
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ktor.http.decodeURLQueryComponent

class ShareReceiverActivity : ComponentActivity() {
    private var sharedText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Logd(TAG, "intent: $intent")
        when (intent.action) {
            Intent.ACTION_SEND -> sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> sharedText = intent.dataString
        }
        if (sharedText.isNullOrBlank()) {
            Loge(TAG, "feedUrl is empty or null.\n" + getString(R.string.null_value_podcast_error))
            return
        }
        val regex = Regex("""https?://[^\s'"<>]+""")
        val rawUrl = regex.find(sharedText!!)?.value
        val text = rawUrl?.toSafeUri()?.getQueryParameter("url")?.decodeURLQueryComponent() ?: rawUrl ?: sharedText!!
        Logd(TAG, "feedUrl: $sharedText")

        var addAsNew by mutableStateOf(false)
        var failed by mutableStateOf(false)
        var client by mutableStateOf<SourceGatewayClient?>(null)
        var existing by mutableStateOf<List<Episode>?>(null)
        suspend fun addEpisode(toFeed: Feed) {
            val log = realm.query(ShareLog::class).query("url == $0", text).first().find()
            if (client != null) {
                val episode = client?.withProvider { it.buildEpisode(text)?.toEpisode() }
                if (episode != null) addToFeed(episode, toFeed, log)
                else {
                    Loge(TAG, "Failed adding episode: client can't handle. url=$text")
                    if (log != null) upsert(log) {
                        it.details = "Can not build episode"
                        it.status = ShareLog.Status.ERROR.code
                    }
                }
            } else {
                Loge(TAG, "Failed adding episode: client is null. url=$text")
                if (log != null) upsert(log) {
                    it.details = "client is null"
                    it.status = ShareLog.Status.ERROR.code
                }
            }
        }
        setContent { PodciniTheme {
            when {
                failed -> AlertDialog(modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.small), onDismissRequest = {  },
                    title = { Text(stringResource(R.string.failed_processing_shared), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.Red) },
                    confirmButton = { Button(onClick = { finish() }) { Text(stringResource(R.string.OK)) } })
                addAsNew -> ConfirmAddToFeed(onDismiss = { finish() }) { toFeed -> addEpisode(toFeed) }
                existing == null -> AlertDialog(modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.small), onDismissRequest = {  },
                    title = { Text(stringResource(R.string.search_existing_media), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }, confirmButton = {})
                existing!!.isEmpty() -> ConfirmAddToFeed(onDismiss = { finish() }) { toFeed -> addEpisode(toFeed) }
                existing!!.size > 1 -> {
                    Surface(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.fillMaxWidth().height(400.dp).padding(bottom = 50.dp)) {
                                EpisodeLazyColumn(existing!!, layoutMode = LayoutMode.FeedTitle.code, forceFeedImage = true, showActionButtons = false)
                            }
                            Button(modifier = Modifier.align(Alignment.BottomEnd) , onClick = { addAsNew =  true }) { Text(stringResource(R.string.add_as_new)) }
                        }
                        if (episodeForInfo != null) EpisodeScreen(episodeForInfo!!)
                    }
                }
                else -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        EpisodeScreen(existing!![0])
                        Button(modifier = Modifier.padding(bottom = 24.dp, end = 24.dp).align(Alignment.BottomEnd) , onClick = { addAsNew =  true }) { Text(stringResource(R.string.add_as_new)) }
                    }
                }
            }
        } }

        runOnIOScope {
            var log = ShareLog(text)
            log = upsertBlk(log) {}
            receiveShared(text, this, true, log) { c, ex ->
                client = c
                existing = ex
            }
        }
    }

    companion object {
        private val TAG: String = ShareReceiverActivity::class.simpleName ?: "Anonymous"

        suspend fun receiveShared(sharedText: String, activity: ComponentActivity, finish: Boolean,  log: ShareLog? = null, extMediaCB: (SourceGatewayClient, List<Episode>)->Unit) {
            Logd(TAG, "receiveShared sharedText: $sharedText")
            when {
//            plain text
                sharedText.matches(Regex("^[^<>/]+$")) -> {
                    if (log != null)  upsertBlk(log) {it.type = ShareLog.ShareType.Text.name }
                    Logd(TAG, "receiveShared Activity is started with text $sharedText")
                    val intent = Intent(getAppContext(), MainActivity::class.java).apply {
                        putExtra(Extras.search_string.name, sharedText)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    activity.startActivity(intent)
                    if (finish) activity.finish()
                }
                else -> {
                    fun openAsFeed(source: String?) {
                        if (log != null) upsertBlk(log) { it.type = ShareLog.ShareType.Feed.name }
                        Logd(TAG, "openAsFeed Activity is started with url $sharedText")
                        val intent = Intent(getAppContext(), MainActivity::class.java).apply {
                            putExtra(Extras.feed_url.name, sharedText)
                            putExtra(Extras.isShared.name, true)
                            if (!source.isNullOrBlank()) putExtra(Extras.source.name, source)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        activity.startActivity(intent)
                        if (finish) activity.finish()
                    }
                    if (appPrefsFlow!!.value.loadExternalApp) AppGatewayRegistry.awaitReady()
                    val client = sourceClients.find { it.withProviderBlocking { p-> p.canHandleUrl(sharedText) == 1 } == true }
                    Logd(TAG, "receiveShared canHandleUrl==1 client: ${client!= null}")
                    if (client != null) {
                        val episode = client.withProviderBlocking { it.buildEpisode(sharedText)?.toEpisode() }
                        if (episode == null) openAsFeed(client.feedSearcher?.name)
                        else {
                            val existing = realm.query(Episode::class).query("title == $0", episode.title).find()
                            if (log != null) upsertBlk(log) { it.type = ShareLog.ShareType.Media.name }
                            extMediaCB(client, existing)
                        }
                        return
                    }
                    val clients = sourceClients.filter { it.withProviderBlocking { p-> p.canHandleUrl(sharedText) == 0 } == true }
                    Logd(TAG, "receiveShared canHandleUrl==0 clients: ${clients.size}")
                    for (client in clients) {
                        val episode = client.withProviderBlocking { it.buildEpisode(sharedText)?.toEpisode() }
                        if (episode != null) {
                            val existing = realm.query(Episode::class).query("title == $0", episode.title).find()
                            if (log != null) upsertBlk(log) { it.type = ShareLog.ShareType.Media.name }
                            extMediaCB(client, existing)
                            return
                        }
                    }
                    openAsFeed(null)
                }
            }
        }
    }
}
