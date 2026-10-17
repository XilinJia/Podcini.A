package ac.mdiq.podcini.storage.database

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.shared.getEntityId
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.storage.model.ARCHIVED_VOLUME_ID
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.storage.model.Feed.Companion.MAX_NATURAL_SYNTHETIC_ID
import ac.mdiq.podcini.storage.model.Feed.Companion.MAX_SYNTHETIC_ID
import ac.mdiq.podcini.storage.model.Feed.Companion.TAG_ROOT
import ac.mdiq.podcini.storage.model.QueueEntry
import ac.mdiq.podcini.storage.model.ShareLog
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder.Companion.fromCode
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder.Companion.reorderWith
import ac.mdiq.podcini.storage.specs.EpisodeState
import ac.mdiq.podcini.storage.specs.FeedType
import ac.mdiq.podcini.storage.specs.Rating
import ac.mdiq.podcini.sync.queue.SynchronizationQueueSink
import ac.mdiq.podcini.utils.EventFlow
import ac.mdiq.podcini.utils.FlowEvent
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Logt
import android.app.backup.BackupManager
import io.github.xilinjia.krdb.MutableRealm
import io.github.xilinjia.krdb.ext.isManaged
import io.github.xilinjia.krdb.notifications.ResultsChange
import io.github.xilinjia.krdb.notifications.UpdatedResults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.collections.set

private const val TAG: String = "Feeds"

var feedsMap: Map<Long, Feed> = realm.query(Feed::class).find().associateBy { it.id }

val feedCountFlow = MutableStateFlow(-1)

@Synchronized
fun getFeedList(queryString: String = ""): List<Feed> {
    return if (queryString.isEmpty()) feedsMap.values.toList()
    else realm.query(Feed::class, queryString).find()
}

fun compileLanguages() {
    val langsSet = mutableSetOf<String>()
    for (feed in feedsMap.values) {
        val langs = feed.langSet
        if (langs.isNotEmpty()) langsSet.addAll(langs)
        else langsSet.add("")
    }
    Logd(TAG) { "langsSet: ${langsSet.size} appAttribs.langSet: ${appAttribsFlow!!.value.langSet.size}" }
    runOnIOScope { if (!appAttribsFlow!!.value.langSet.containsAll(langsSet)) upsert(appAttribsFlow!!.value) { it.langSet.addAll(langsSet) } }
}

fun compileTags() {
    val tagsSet = mutableSetOf<String>()
    for (feed in feedsMap.values) tagsSet.addAll(feed.tags.filter { it != TAG_ROOT })
    runOnIOScope { if (!appAttribsFlow!!.value.feedTagSet.containsAll(tagsSet)) upsert(appAttribsFlow!!.value) { it.feedTagSet.addAll(tagsSet) } }
}

private var feedMonitorJob: Job? = null
fun cancelMonitorFeeds() {
    feedMonitorJob?.cancel()
    feedMonitorJob = null
}

fun monitorFeeds() {
    if (feedMonitorJob != null) return
    feedMonitorJob = CoroutineScope(Dispatchers.IO).launch {
        realm.query(Feed::class).asFlow().collect { changes: ResultsChange<Feed> ->
            feedsMap = changes.list.associateBy { it.id }
            Logd(TAG) { "monitorFeedList feeds updated size: ${feedsMap.size}" }
            when (changes) {
                is UpdatedResults -> {
                    when {
                        changes.insertions.isNotEmpty() -> {
                            compileLanguages()
                            compileTags()
                        }
                        changes.changes.isNotEmpty() -> {
//                            for (i in changes.changes) {
//                                Logd(TAG) { "monitorFeedList feed changed: ${feeds[i].title}" }
//                            }
                        }
                        changes.deletions.isNotEmpty() -> {
                            Logd(TAG) { "monitorFeedList feed deleted: ${changes.deletions.size}" }
                            compileTags()
                        }
                        else -> Logd(TAG) { "monitorFeedList else $changes" }
                    }
                }
                else -> Logd(TAG) { "monitorFeedList other $changes" }
            }
            feedCountFlow.value = feedsMap.size
        }
    }
}

fun feedById(feedId: Long, copy: Boolean = false): Feed? {
    val f = feedsMap[feedId]
    return if (f != null) {
        if (copy) realm.copyFromRealm(f) else f
    } else null
}

fun feedByIdentityOrID(feed: Feed, copy: Boolean = false): Feed? {
    Logd(TAG) { "feedByIdentityOrID isLocal: ${feed.isLocal} id: ${feed.id}" }
    if (feed.id != 0L) return feedById(feed.id, copy)
    val feedIdv = feed.identifyingValue
    if (feed.isLocal) {
        val f = feedsMap.values.firstOrNull { it.identifyingValue == feedIdv && it.volumeId == feed.volumeId }
        Logd(TAG) { "feedByIdentityOrID local feed: ${f?.title}" }
        if (f != null) return if (copy) realm.copyFromRealm(f) else f
    } else {
        val f = feedsMap.values.firstOrNull { it.identifyingValue == feedIdv }
        Logd(TAG) { "feedByIdentityOrID remote feed: ${f?.title}" }
        if (f != null) return if (copy) realm.copyFromRealm(f) else f
    }
    return null
}

suspend fun addNewFeed(feed: Feed) {
    Logd(TAG) { "addNewFeeds called" }
    feed.lastUpdateTime = nowInMillis()
    feed.lastFullUpdateTime = nowInMillis()
    realm.write {
        feed.id = getEntityId()
        feed.totleDuration = 0
        Logd(TAG) { "feed.episodes count: ${feed.episodes.size}" }
        for (episode in feed.episodes) {
            episode.id = getEntityId()
//            Logd(TAG) { "addNewFeeds episode: ${episode.id} ${episode.downloadUrl}" }
            episode.feedId = feed.id
            feed.totleDuration += episode.duration
            copyToRealm(episode)
        }
        feed.episodesCount = feed.episodes.size
        copyToRealm(feed)
    }
    if (!feed.isLocal && feed.downloadUrl != null) SynchronizationQueueSink.enqueueFeedAddedIfSyncActive(feed.downloadUrl!!)
    BackupManager(getAppContext()).dataChanged()
}

suspend fun deleteFeed(feedId: Long, preserve: Boolean = false) {
    Logd(TAG) { "deleteFeed called" }
    val feed = feedsMap[feedId]
    val episodesToErase = if (preserve && feed != null) feed.unworthyEpisodes else getEpisodes(null, null, feedId=feedId, copy = false)
    removeFromAllQueuesQuiet(episodesToErase.map { it.id }, false)
    eraseEpisodes(episodesToErase)

    if (feed != null) {
        realm.write {
            findLatest(feed)?.let {
                if (preserve) {
                    it.volumeId = ARCHIVED_VOLUME_ID
                    it.keepUpdated = false
                    it.autoEnqueue = false
                    it.autoDownload = false
                    it.autoDeleteAction = Feed.AutoDeleteAction.NEVER
                    it.queue = null
                } else  delete(it)
            }
        }
        if (!feed.isLocal && feed.downloadUrl != null) SynchronizationQueueSink.enqueueFeedRemovedIfSyncActive(feed.downloadUrl!!)
        BackupManager(getAppContext()).dataChanged()
    }
}

fun allowForAutoDelete(feed: Feed): Boolean = appPrefsFlow!!.value.autoDelete && (!feed.isLocal || appPrefsFlow!!.value.autoDeleteLocal)

suspend fun shelveToFeed(episodes: List<Episode>, toFeed: Feed, removeChecked: Boolean = false) {
    val toFeedEpisodes = getEpisodes(null, null, feedId=toFeed.id, copy = false)
    for (e in episodes) {
        if (toFeedEpisodes.firstOrNull { it.identifyingValue == e.identifyingValue } != null) continue
        var e_ = e
        if (!removeChecked || (e.feedId != null && e.feedId!! >= MAX_SYNTHETIC_ID)) {
            if (e.isManaged()) e_ = realm.copyFromRealm(e)
            e_.id = getEntityId()
            if (e.feedId != null && e.feedId!! >= MAX_SYNTHETIC_ID) {
                e_.origFeedTitle = e.feed?.title
                e_.origFeeddownloadUrl = e.feed?.downloadUrl
                e_.origFeedlink = e.feed?.link
            }
        }
        upsert(e_) { it.feedId = toFeed.id }
    }
    val eps = realm.query(Episode::class).query("feedId == ${toFeed.id}").find()
    val dur = eps.sumOf { it.duration }
    val feed_ = upsert(toFeed) {
        it.episodesCount = eps.size
        it.totleDuration = dur.toLong()
    }
    sumup(feed_)
}

fun createSynthetic(feedId: Long, name: String, video: Boolean = false): Feed {
    val feed = Feed()
    var feedId_ = feedId
    if (feedId_ <= 0) {
        var i = MAX_NATURAL_SYNTHETIC_ID
        while (true) {
            if (feedsMap[i++] != null) continue
            feedId_ = --i
            break
        }
    }
    feed.id = feedId_
    feed.title = name
    feed.author = "Yours Truly"
    feed.downloadUrl = null
    feed.hasVideoMedia = video
    feed.keepUpdated = false
    feed.queue = null
    return feed
}

suspend fun addToFeed(episode: Episode, toFeed: Feed, log: ShareLog? = null) {
    val episodes = toFeed.episodes
    val status = if (episodes.firstOrNull { it.identifyingValue == episode.identifyingValue } != null) ShareLog.Status.EXISTING.code
    else {
        episode.id = getEntityId()
        episode.feedId = toFeed.id
        upsert(episode) {}
        sumup(toFeed)
        EventFlow.postStickyEvent(FlowEvent.FeedUpdatingEvent(false))
        ShareLog.Status.SUCCESS.code
    }
    if (log != null) upsert(log) {
        it.title = episode.title
        it.status = status
    }
}

suspend fun addRemoteToMiscSyndicate(episode: Episode) {
    suspend fun getMiscSyndicate(): Feed {
        val feedId: Long = 11
        var feed = feedById(feedId, true)
        if (feed != null) return feed
        feed = createSynthetic(feedId, "Misc Syndicate")
        feed.type = FeedType.RSS.name
        return upsert(feed) {}
    }
    val feed = getMiscSyndicate()
    Logd(TAG) { "addToMiscSyndicate: feed: ${feed.title}" }
    val episodes = getEpisodes(null, null, feedId=feed.id, copy = false)
    if (episodes.firstOrNull { it.identifyingValue == episode.identifyingValue } != null) return
    Logd(TAG) { "addToMiscSyndicate adding new episode: ${episode.title}" }
    episode.origFeedTitle = episode.feed?.title
    episode.origFeeddownloadUrl = episode.feed?.downloadUrl
    episode.origFeedlink = episode.feed?.link
    episode.id = getEntityId()
    episode.feedId = feed.id
    upsert(episode) {}
    Logt(TAG, "Episode added to synthetic feed: 'Misc Syndicate'")
    sumup(feed)
    EventFlow.postStickyEvent(FlowEvent.FeedUpdatingEvent(false))
}

/**
 * Publishers sometimes mess up their feed by adding episodes twice or by changing the ID of existing episodes.
 * This class tries to guess if publishers actually meant another episode,
 * even if their feed explicitly says that the episodes are different.
 */
internal fun canonicalizeTitle(title: String?): String {
    if (title == null) return ""
    return title.trim { it <= ' ' }.replace('“', '"').replace('”', '"').replace('„', '"').replace('—', '-')
}

//internal fun datesLookSimilar(item1: Episode, item2: Episode): Boolean {
//    //            if (item1.getPubDate() == null || item2.getPubDate() == null) return false
//    val dateFormat = DateFormat.getDateInstance(DateFormat.SHORT, Locale.US) // MM/DD/YY
//    val dateOriginal = dateFormat.format(item2.pubDate)
//    val dateNew = dateFormat.format(item1.pubDate)
//    return dateOriginal == dateNew // Same date; time is ignored.
//}
//internal fun durationsLookSimilar(media1: Episode, media2: Episode): Boolean {
//    return abs((media1.duration - media2.duration).toDouble()) < 10 * 60L * 1000L
//}
//internal fun mimeTypeLooksSimilar(media1: Episode, media2: Episode): Boolean {
//    var mimeType1 = media1.mimeType
//    var mimeType2 = media2.mimeType
//    if (mimeType1 == null || mimeType2 == null) return true
//    if (mimeType1.contains("/") && mimeType2.contains("/")) {
//        mimeType1 = mimeType1.substringBefore("/")
//        mimeType2 = mimeType2.substringBefore("/")
//    }
//    return (mimeType1 == mimeType2)
//}
//private fun sameAndNotEmpty(string1: String?, string2: String?): Boolean {
//    if (string1.isNullOrEmpty() || string2.isNullOrEmpty()) return false
//    return string1 == string2
//}
//private fun titlesLookSimilar(item1: Episode, item2: Episode): Boolean {
//    return sameAndNotEmpty(canonicalizeTitle(item1.title), canonicalizeTitle(item2.title))
//}

suspend fun trimEpisodes(feed_: Feed): Int {
    var n = 0
    if (feed_.limitEpisodesCount > 0) {
        val count = realm.query(Episode::class).query("feedId == ${feed_.id} AND !(${feed_.isWorthyQuerryStr})").count().find().toInt()
        if (count > feed_.limitEpisodesCount + 5) {
            val f = feedByIdentityOrID(feed_, true) ?: return n
            val dc = count - f.limitEpisodesCount
            val episodes = realm.query(Episode::class).query("feedId == ${feed_.id} SORT (pubDate ASC)").find().toMutableList()
            if (f.sortForTrim != EpisodeSortOrder.DATE_DESC) episodes.reorderWith(fromCode(f.sortForTrim.reverseCode()))
            realm.write {
                for (e_ in episodes) {
                    val qes = query(QueueEntry::class).query("episodeId == ${e_.id}").find()
                    if (qes.isNotEmpty()) delete(qes)
                    val e = findLatest(e_)
                    if (e != null && !e.isWorthy) {
                        delete(e)
                        if (n++ >= dc) break
                    }
                }
            }
            sumup(f)
        }
    }
    return n
}

fun trimEpisodes(feed_: Feed, episodes: MutableList<Episode>): Int {
    var n = 0
    if (feed_.limitEpisodesCount > 0) {
        val count = realm.query(Episode::class).query("feedId == ${feed_.id} AND !(${feed_.isWorthyQuerryStr})").count().find().toInt()
        if (count > feed_.limitEpisodesCount*1.2) {
            val f = feedByIdentityOrID(feed_, true) ?: return n
            val dc = count - f.limitEpisodesCount*1.2
            if (f.sortForTrim != EpisodeSortOrder.DATE_DESC) episodes.reorderWith(fromCode(f.sortForTrim.reverseCode()))
            val it = episodes.iterator()
            while(it.hasNext()) {
                it.remove()
                if (n++ >= dc) break
            }
        }
    }
    return n
}


suspend fun sumup(feed: Feed) {
    val episodes = getEpisodes(null, null, feedId= feed.id, copy = false)
    Logd(TAG) { "sumup feed: ${feed.title} episodes: ${episodes.size}" }
    var durTotal = 0L
    val cTime = nowInMillis()
    var sumR = 0.0
    var scoreCount = 0
    for (e in episodes) {
        durTotal += e.duration
        if (e.playState >= EpisodeState.PROGRESS.code) {
            scoreCount++
            if (e.rating != Rating.UNRATED.code) sumR += e.rating
            if (e.playState >= EpisodeState.SKIPPED.code) sumR += if (e.rating > Rating.OK.code) 1.0 else - 0.5 + 1.0 * e.playedDuration / e.duration
            else if (e.playState in listOf(EpisodeState.AGAIN.code, EpisodeState.FOREVER.code)) sumR += 0.5
        }
    }
    upsert(feed) {
        it.episodesCount = episodes.size
        it.totleDuration = durTotal
        it.scoreCount = scoreCount
        it.score = if (scoreCount > 0) (100 * sumR / scoreCount / Rating.SUPER.code).toInt() else -1000
        it.scoreUpdated = cTime
    }
}

fun MutableRealm.guessDuplicates(item: Episode, feed: Feed): List<Episode> {
    val rq = query(Episode::class)
    var existings: List<Episode> = listOf()
    if (!item.identifier.isNullOrBlank()) existings = rq.query("feedId == $0 AND identifier == $1", feed.id, item.identifier).find()
    if (existings.isEmpty() && !item.downloadUrl.isNullOrBlank()) existings = rq.query("feedId == $0 AND downloadUrl == $1", feed.id, item.downloadUrl).find()
    if (existings.isEmpty() && !item.title.isNullOrBlank()) existings = rq.query("feedId == $0 AND title == $1", feed.id, item.title).find()
    return existings
}

fun episodesByIdentifyingValue(item: Episode, feed:Feed): List<Episode> {
    val rq = realm.query(Episode::class).query("feedId == ${feed.id}")
    val q = when {
        !item.identifier.isNullOrEmpty() -> rq.query("identifier == $0", item.identifier)
        !item.title.isNullOrEmpty() -> rq.query("title == $0", item.title)
        else -> rq.query("downloadUrl == $0", item.downloadUrl)
    }
    return q.find()
}

suspend fun cleanUp(feed: Feed) {
    val map = mutableMapOf<String, MutableList<Episode>>()
    val iterator = getEpisodes(null, null, feedId=feed.id, copy = true).iterator()
//    val iterator = listOf<Episode>().iterator()
    while (iterator.hasNext()) {
        val e = iterator.next()
        //            Logd(TAG) { "FeedAssistant init $tag ${e.title}" }
        if (!e.identifier.isNullOrEmpty()) {
            Logd(TAG) { "cleanUp identifier ${e.identifier}" }
            if (map.containsKey(e.identifier!!)) {
                Logd(TAG) { "cleanUp identifier duplicate: ${e.identifier} ${e.title}" }
                map[e.identifier!!]!!.add(e)
            } else map[e.identifier!!] = mutableListOf(e)
        }
        val idv = e.identifyingValue
        if (idv != e.identifier && !idv.isNullOrEmpty()) {
            //                Logd(TAG) { "FeedAssistant init $tag identifyingValue ${e.identifyingValue}" }
            if (map.containsKey(idv)) {
                Logd(TAG) { "cleanUp identifyingValue duplicate: $idv ${e.title}" }
                map[idv]!!.add(e)
            } else map[idv] = mutableListOf(e)
        }
        val url = e.downloadUrl
        if (url != idv && !url.isNullOrEmpty()) {
            if (map.containsKey(url)) {
                Logd(TAG) { "cleanUp url duplicate: $url ${e.title}" }
                map[url]!!.add(e)
            } else map[url] = mutableListOf(e)
        }
        val title = canonicalizeTitle(e.title)
        if (title != idv && title.isNotEmpty()) {
            if (map.containsKey(title)) {
                Logd(TAG) { "cleanUp title duplicate: $title ${e.title}" }
            } else map[title] = mutableListOf(e)
        }
    }
    realm.write {
        for ((k, v) in map.entries) {
            if (v.size < 2) continue
            Logd(TAG) { "cleanUp removing ${v.size - 1} duplicates on $k" }
            val ecs = v.sortedByDescending { it.comment.length }
            val comment = if (ecs[0].comment.isBlank()) "" else {
                var c = ecs[0].comment
                for (i in 1..<ecs.size) if (ecs[i].comment.isNotBlank()) c += "\n" + ecs[i].comment
                c
            }
            val ers = v.sortedByDescending { it.rating }
            val episode: Episode? = query(Episode::class).query("id == ${ers[0].id}").first().find()
            if (ers[0].rating > Rating.UNRATED.code) {
                if (ers[0].id != ecs[0].id && comment.isNotEmpty()) episode?.addComment(comment)
                for (i in 1..<ers.size) query(Episode::class).query("id == ${ers[i].id}").first().find()?.let { delete(it) }
            } else {
                val eps = v.sortedByDescending { it.lastPlayedTime }
                if (eps[0].lastPlayedTime > 0L) {
                    if (eps[0].id != ecs[0].id && comment.isNotEmpty()) episode?.addComment(comment)
                    for (i in 1..<eps.size) query(Episode::class).query("id == ${eps[i].id}").first().find()?.let { delete(it) }
                } else {
                    val eps = v.sortedByDescending { it.pubDate }
                    if (eps[0].id != ecs[0].id && comment.isNotEmpty()) episode?.addComment(comment)
                    for (i in 1..<eps.size) query(Episode::class).query("id == ${eps[i].id}").first().find()?.let { delete(it) }
                }
            }
            if (episode != null) map[k] = mutableListOf(episode)
        }
    }
    sumup(feed)
}