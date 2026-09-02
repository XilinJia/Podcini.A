package ac.mdiq.podcini.storage.database

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.sourcing.download.EpisodeAdrDLManager
import ac.mdiq.podcini.sync.SynchronizationSettings.isSyncProviderConnected
import ac.mdiq.podcini.sync.model.EpisodeAction
import ac.mdiq.podcini.sync.queue.SynchronizationQueueSink
import ac.mdiq.podcini.playback.base.theatres
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.sourcing.clientByEpisode
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.storage.model.SubscriptionLog
import ac.mdiq.podcini.storage.model.SubscriptionLog.Companion.feedLogsMap
import ac.mdiq.podcini.storage.model.SubscriptionLog.Companion.takeCodePoints
import ac.mdiq.podcini.storage.specs.EpisodeFilter
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder.Companion.reorderWith
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder.Companion.sortPairOf
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder.DATE_DESC
import ac.mdiq.podcini.storage.specs.EpisodeState
import ac.mdiq.podcini.storage.utils.durationStringShort
import ac.mdiq.podcini.storage.utils.toUF
import ac.mdiq.podcini.ui.compose.CommonConfirmAttrib
import ac.mdiq.podcini.ui.compose.commonConfirms
import ac.mdiq.podcini.utils.EventFlow
import ac.mdiq.podcini.utils.FlowEvent
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Logs
import ac.mdiq.podcini.utils.Logt
import ac.mdiq.podcini.utils.fullDateTimeString
import androidx.core.app.NotificationManagerCompat
import io.github.xilinjia.krdb.notifications.ResultsChange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

private const val TAG: String = "Episodes"

// TODO: filters of queued and notqueued don't work in this
fun getEpisodes(filter: EpisodeFilter?, sortOrder: EpisodeSortOrder?, feedId: Long = -1, offset: Int = 0, limit: Int = Int.MAX_VALUE, copy: Boolean = true): List<Episode> {
    var queryString = filter?.queryString()?:"id > 0"
    if (feedId >= 0) queryString += " AND feedId == $feedId "
    Logd(TAG, "getEpisodes called with: offset=$offset, limit=$limit queryString: $queryString")
    if (offset > 0) {
        var episodes = realm.query(Episode::class).query(queryString).sort(sortPairOf(sortOrder)).find().toMutableList()
        val size = episodes.size
        if (offset < size) {
            episodes = episodes.subList(offset, min(size, offset + limit))
            return if (copy) realm.copyFromRealm(episodes) else episodes
        } else return listOf()
    } else {
        val episodes = realm.query(Episode::class).query(queryString).sort(sortPairOf(sortOrder)).limit(limit).find()
        return if (copy) realm.copyFromRealm(episodes) else episodes
    }
}

fun getEpisodesAsFlow(filter: EpisodeFilter?, sortOrder: EpisodeSortOrder?, feedId: Long = -1): Flow<ResultsChange<Episode>> {
    var queryString = filter?.queryString()
    if (queryString.isNullOrBlank()) queryString = "id > 0"
    if (feedId >= 0) queryString += " AND feedId == $feedId "
    Logd(TAG, "getEpisodesAsFlow queryString: $queryString sortOrder: $sortOrder")
    return realm.query(Episode::class).query(queryString).sort(sortPairOf(sortOrder)).asFlow()
}

fun getEpisodesAsListFlow(filter: EpisodeFilter?, sortOrder: EpisodeSortOrder?, feedId: Long = -1): Flow<List<Episode>> {
    var queryString = filter?.queryString()
    if (queryString.isNullOrBlank()) queryString = "id > 0"
    if (feedId >= 0) queryString += " AND feedId == $feedId "
    Logd(TAG, "getEpisodesAsFlow queryString: $queryString sortOrder: $sortOrder")
    if (sortOrder != null && sortOrder != DATE_DESC)
        return realm.query(Episode::class).query(queryString).asFlow().map { result ->
            val list = result.list.toMutableList()
            list.reorderWith(sortOrder)
            list.toMutableList()
        }
    return realm.query(Episode::class).query(queryString).sort(sortPairOf(sortOrder)).asFlow().map { it.list }
}

fun getEpisodesCount(filter: EpisodeFilter?, feedId: Long = -1): Int {
    var queryString = filter?.queryString()?:"id > 0"
    Logd(TAG, "getEpisodesCount called queryString: $queryString $feedId")
    if (feedId >= 0) queryString += " AND feedId == $feedId "
    return realm.query(Episode::class).query(queryString).count().find().toInt()
}

fun episodeByGuidOrUrl(guid: String?, episodeUrl: String, copy: Boolean = true): Episode? {
    Logd(TAG, "episodeByGuidOrUrl called $guid $episodeUrl")
    val episode = if (guid != null) realm.query(Episode::class).query("identifier == $0", guid).first().find()
    else realm.query(Episode::class).query("downloadUrl == $0", episodeUrl).first().find()
    if (!copy || episode == null) return episode
    return realm.copyFromRealm(episode)
}

fun episodeById(id: Long): Episode? = realm.query(Episode::class).query("id == $0", id).first().find()

fun getHistoryAsFlow(feedId: Long = 0L, start: Long = 0L, end: Long = nowInMillis(), filter: EpisodeFilter? = null, sortOrder: EpisodeSortOrder = EpisodeSortOrder.PLAYED_DATE_DESC): Flow<ResultsChange<Episode>> {
    Logd(TAG, "getHistory() called")
    var qStr = "((playbackCompletionTime > 0) OR (lastPlayedTime > $start AND lastPlayedTime <= $end))"
    if (feedId > 0L) qStr += " AND feedId == $feedId "
    val fqstr = filter?.queryString()
    if (!fqstr.isNullOrBlank()) qStr += " AND $fqstr "
    val episodes = realm.query(Episode::class).query(qStr).sort(sortPairOf(sortOrder)).asFlow()
    return episodes
}

suspend fun deleteEpisodesWarnLocalRepeat(items: Iterable<Episode>) {
    val context = getAppContext()
    val localItems: MutableList<Episode> = mutableListOf()
    val repeatItems: MutableList<Episode> = mutableListOf()
    suspend fun deleteItems(items_: List<Episode>) {
        for (episode in items_) {
            if (episode.feed != null && !episode.feed!!.isLocal) {
                EpisodeAdrDLManager.manager.cancel(episode)
                if (episode.downloaded) deleteMedia(episode)
            }
        }
        if (appPrefsFlow!!.value.deleteRemovesFromQueue) removeFromAllQueues(items_)
    }
    for (item in items) {
        var toConfirm = false
        if (item.feed?.isLocal == true) {
            localItems.add(item)
            toConfirm = true
        }
        if (item.playState == EpisodeState.AGAIN.code || item.playState == EpisodeState.FOREVER.code) {
            repeatItems.add(item)
            toConfirm = true
        }
        if (!toConfirm) deleteItems(listOf(item))
    }

    val userDone = CompletableDeferred<Unit>()
    if (localItems.isNotEmpty()) {
        withContext(Dispatchers.Main) {
            commonConfirms.add(CommonConfirmAttrib(
                title = context.getString(R.string.delete_episode_label),
                message = context.getString(R.string.delete_local_feed_warning_body),
                confirmRes = R.string.delete_label,
                cancelRes = R.string.cancel_label,
                onConfirm = {
                   runOnIOScope {
                       deleteItems(localItems)
                       userDone.complete(Unit)
                   }
                },
                onNeutral = { userDone.complete(Unit)},
                onCancel = { userDone.complete(Unit)}))
        }
        userDone.await()
    }
    if (repeatItems.isNotEmpty()) {
        withContext(Dispatchers.Main) {
            commonConfirms.add(CommonConfirmAttrib(
                title = context.getString(R.string.delete_episode_label),
                message = context.getString(R.string.delete_repeat_warning_msg),
                confirmRes = R.string.delete_label,
                cancelRes = R.string.cancel_label,
                onConfirm = {
                    runOnIOScope { deleteItems(repeatItems) }
                }))
        }
    }
}

suspend fun eraseIfLoose(episode: Episode) {
    if (episode.feed == null) eraseEpisodes(listOf(episode), "")
}

suspend fun eraseEpisodes(episodes: List<Episode>, msg: String = "") {
    val reasonText = getAppContext().getString(R.string.reason_to_remove)
    if (msg.isNotEmpty()) realm.write {
        for (e in episodes) {
            val sLog = SubscriptionLog(e.id, e.title ?: "", e.downloadUrl ?: "", e.link ?: "", SubscriptionLog.Type.Media.name)
            sLog.let {
                it.description = e.description?.takeCodePoints(100).orEmpty()
                it.rating = e.rating
                it.comment = if (e.comment.isBlank()) "" else (e.comment + "\n")
                it.comment += fullDateTimeString() + "\n$reasonText:\n" + msg
                it.cancelDate = nowInMillis()
            }
            copyToRealm(sLog)
        }
        feedLogsMap = null
    }
    for (e in episodes) if (e.feed?.isLocal != true) deleteMedia(e)
    removeFromAllQueues(episodes)
    Logd(TAG, "eraseEpisodes deleting episodes: ${episodes.size}")
    val feeds = allFeeds.filter { it.id in episodes.map { e-> e.feedId } }
    realm.write { for (e in episodes) findLatest(e)?.let { delete(it) } }
    for (f in feeds) sumup(f)
    EventFlow.postStickyEvent(FlowEvent.FeedUpdatingEvent(false))
}

suspend fun deleteMedia(episode: Episode): Episode {
    val context = getAppContext()
    val url = episode.fileUrl
    Logd(TAG, "deleteMedia [id=${episode.id}, title=${episode.getEpisodeTitle()}, downloaded=${episode.downloaded} $url")
    var episode = episode
    if (!url.isNullOrBlank()) {
        try {
            url.toUF().delete()
            episode = upsertBlk(episode) {
                it.fileUrl = null
                it.hasEmbeddedPicture = false
                if (it.playState < EpisodeState.SKIPPED.code && !shouldPreserve(it.playState)) it.setPlayState(EpisodeState.SKIPPED)
            }
            EventFlow.postEvent(FlowEvent.EpisodeMediaEvent.removed(episode))
        } catch (e: Throwable) { Logs(TAG, e, "deleteMedia failed") }
    }
    for (i in 0..1) {
        if (episode.id == theatres[i].mPlayerFlow.value?.curState?.curMediaId) {
            theatres[i].mPlayerFlow.value?.saveCurState()
            val nm = NotificationManagerCompat.from(context)
            nm.cancel(R.id.notification_playing)
        }
    }
    if (isSyncProviderConnected) {
        // Gpodder: queue delete action for synchronization
        val action = EpisodeAction.Builder(episode, EpisodeAction.DELETE).currentTimestamp().build()
        SynchronizationQueueSink.enqueueEpisodeActionIfSyncActive(action)
    }
    return episode
}

fun isMediaDownloadable(media: Episode): Boolean {
    return clientByEpisode(media)?.attributes?.supportDownload != false
}

fun canCheckMediaSize(episode: Episode): Boolean {
    Logd(TAG, "canCheckMediaSize episode.fileUrl: ${episode.fileUrl} episode.downloadUrl: ${episode.downloadUrl}")
    if (episode.feed?.isLocal == true) return true
    if (episode.downloadUrl != null) return clientByEpisode(episode) == null
    return false
}

fun checkAndMarkDuplicates(episode: Episode): Episode {
    var updated = false
    realm.writeBlocking {
        val candidates = query(Episode::class, "title == $0 OR downloadUrl == $1", episode.title, episode.downloadUrl).find()
        if (candidates.size > 1) {
            Logt(TAG, "Found ${candidates.size - 1} duplicate episodes, setting to Ignored")
            val duplicates = mutableListOf<Episode>()
            for (e in candidates) {
                if (e.id == episode.id) continue
                if (e.duration > 0L && episode.duration > 0L && abs(e.duration - episode.duration) < 0.05 * (e.duration + episode.duration)) duplicates.add(e)
            }
//            val ignoredDups = duplicates.filter { it.playState == EpisodeState.IGNORED.code }
            val comment = "duplicate"
            for (e in duplicates) {
                if (e.playState <= EpisodeState.AGAIN.code) {
                    e.setPlayState(EpisodeState.IGNORED)
                    e.addComment(comment)
                }
            }
//            if (ignoredDups.isNotEmpty()) {
//                val m = findLatest(episode)?.let {
//                    it.setPlayState(EpisodeState.IGNORED)
//                    it.addComment(comment)
//                    it
//                }
//                m?.let { updated = true }
////                LogtFor(TAG, e.id,"Duplicate item was previously set to ${fromCode(e.playState).name} ${e.downloadUrl}")
//            }
            for (e in candidates) {
                for (e1 in candidates) {
                    if (e.id != e1.id) e.related.add(e1)
                }
            }
            updated = true
        }
    }
    return if (updated) realm.query(Episode::class, "id == ${episode.id}").first().find() ?: episode else episode
}

fun shouldPreserve(stat: Int): Boolean = stat in listOf(EpisodeState.SOON.code, EpisodeState.LATER.code, EpisodeState.AGAIN.code, EpisodeState.FOREVER.code)

fun buildListInfo(episodes: List<Episode>, total: Int = 0, feed: Feed? = null): String {
    Logd(TAG, "buildListInfo")
    var infoText = episodes.size.toString()
    if (total > 0) infoText += "/$total"
    if (episodes.isNotEmpty()) {
        var speed = feed?.playSpeed?.takeIf { it > 0 } ?: 1f
        var timeLeft: Long = 0
        for (item in episodes) {
            if (feed == null) speed = if (item.feedId != null && feedsMap.containsKey(item.feedId!!)) feedsMap[item.feedId!!]!!.playSpeed.takeIf { it > 0 } ?: 1f else 1f
            timeLeft += ((item.duration - item.position) / speed).toLong()
        }
        infoText += " * " + durationStringShort(timeLeft, true)
    }
    return infoText
}

fun List<Episode>.indexWithId(id: Long): Int = indexOfFirst { it.id == id }

fun List<Episode>.indexWithUrl(downloadUrl: String): Int = indexOfFirst { it.downloadUrl == downloadUrl }
