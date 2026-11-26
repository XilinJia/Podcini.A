package ac.mdiq.podcini.sourcing

import ac.mdiq.podcini.PodciniApp.Companion.appIOScope
import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.activity.MainActivity
import ac.mdiq.podcini.activity.MainActivity.Extras
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.realm

import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.ShareLog
import ac.mdiq.podcini.storage.model.toEpisode
import ac.mdiq.podcini.utils.Logd
import android.content.Intent
import androidx.activity.ComponentActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

private const val TAG = "Shared"

suspend fun handleShared(sharedText: String, activity: ComponentActivity, finish: Boolean, log: ShareLog? = null, extMediaCB: (Episode, List<Episode>)->Unit) {
    Logd(TAG) { "receiveShared sharedText: $sharedText" }
    when {
        //            plain text
        sharedText.matches(Regex("^[^<>/]+$")) -> {
            log?.let { l-> appIOScope.launch { upsert(l) {it.type = ShareLog.ShareType.Text.name } } }
            Logd(TAG) { "receiveShared Activity is started with text $sharedText" }
            val intent = Intent(getAppContext(), MainActivity::class.java).apply {
                putExtra(Extras.search_string.name, sharedText)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            activity.startActivity(intent)
            if (finish) activity.finish()
        }
        else -> {
            fun openAsFeed(source: String?) {
                log?.let { l-> appIOScope.launch { upsert(l) { it.type = ShareLog.ShareType.Feed.name } } }
                Logd(TAG) { "openAsFeed Activity is started with url $sharedText" }
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
            var client = sourceClients.find { it.withProvider { p-> p.canHandleUrl(sharedText) == 1 } == true }
            if (client == null) {
                delay(2.seconds)
                client = sourceClients.find { it.withProvider { p-> p.canHandleUrl(sharedText) == 1 } == true }
            }
            Logd(TAG) { "receiveShared canHandleUrl==1 client: ${client!= null}" }
            if (client != null) {
                val episode = client.withProvider { it.buildEpisode(sharedText)?.toEpisode() }
                if (episode == null) openAsFeed(client.feedSearcher?.name)
                else {
                    val existing = realm.query(Episode::class).query("title == $0", episode.title).find()
                    log?.let { l-> appIOScope.launch { upsert(l) { it.type = ShareLog.ShareType.Media.name } } }
                    extMediaCB(episode, existing)
                }
                return
            }
            val clients = sourceClients.filter { it.withProvider { p-> p.canHandleUrl(sharedText) == 0 } == true }
            Logd(TAG) { "receiveShared canHandleUrl==0 clients: ${clients.size}" }
            for (client in clients) {
                val episode = client.withProvider { it.buildEpisode(sharedText)?.toEpisode() } ?: continue
                val existing = realm.query(Episode::class).query("title == $0", episode.title).find()
                log?.let { l-> appIOScope.launch { upsert(l) { it.type = ShareLog.ShareType.Media.name } } }
                extMediaCB(episode, existing)
                return
            }
            openAsFeed(null)
        }
    }
}
