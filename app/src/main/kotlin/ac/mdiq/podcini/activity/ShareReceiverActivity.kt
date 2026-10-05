package ac.mdiq.podcini.activity

import ac.mdiq.podcini.R
import ac.mdiq.podcini.config.AppConfig.initialize
import ac.mdiq.podcini.sourcing.AppGatewayRegistry
import ac.mdiq.podcini.sourcing.handleShared
import ac.mdiq.podcini.storage.database.addToFeed
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.ShareLog
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
        initialize()
        AppGatewayRegistry.ensureSourceClients()

        Logd(TAG) { "intent: $intent" }
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
        Logd(TAG) { "feedUrl: $sharedText" }

        var addAsNew by mutableStateOf(false)
        var failed by mutableStateOf(false)
        var episode by mutableStateOf<Episode?>(null)
        var existing by mutableStateOf<List<Episode>?>(null)
        var log = ShareLog(text)
        setContent { PodciniTheme {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
                when {
                    failed -> AlertDialog(modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.small), onDismissRequest = { }, title = { Text(stringResource(R.string.failed_processing_shared), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.Red) }, confirmButton = { Button(onClick = { finish() }) { Text(stringResource(R.string.OK)) } })
                    addAsNew -> ConfirmAddToFeed(onDismiss = { finish() }) { toFeed ->
                        if (episode != null) addToFeed(episode!!, toFeed, log)
                        else Loge(TAG, "Failed adding episode: null")
                    }
                    existing == null -> AlertDialog(modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.small), onDismissRequest = { }, title = { Text(stringResource(R.string.search_existing_media), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }, confirmButton = {})
                    existing!!.isEmpty() -> ConfirmAddToFeed(onDismiss = { finish() }) { toFeed ->
                        if (episode != null) addToFeed(episode!!, toFeed, log)
                        else Loge(TAG, "Failed adding episode: null")
                    }
                    existing!!.size > 1 -> {
                        Surface(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Box(modifier = Modifier.fillMaxWidth().requiredHeightIn(max = 400.dp).padding(bottom = 50.dp)) {
                                    EpisodeLazyColumn(existing!!, layoutMode = LayoutMode.FeedTitle.code, forceFeedImage = true, showActionButtons = false)
                                }
                                Button(modifier = Modifier.align(Alignment.BottomEnd), onClick = { addAsNew = true }) { Text(stringResource(R.string.add_as_new)) }
                            }
                            episodeForInfo?.let { EpisodeScreen(it) }
                        }
                    }
                    else -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            EpisodeScreen(existing!![0], showClose = false)
                            Button(modifier = Modifier.padding(bottom = 24.dp, end = 24.dp).align(Alignment.BottomEnd), onClick = { addAsNew = true }) { Text(stringResource(R.string.add_as_new)) }
                        }
                    }
                }
            }
        } }

        runOnIOScope {
            log = upsert(log) {}
            handleShared(text, this, true, log) { e, ex ->
                episode = e
                existing = ex
            }
        }
    }

    companion object {
        private val TAG: String = ShareReceiverActivity::class.simpleName ?: "Anonymous"
    }
}
