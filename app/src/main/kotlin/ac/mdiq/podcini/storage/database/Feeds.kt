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
import ac.mdiq.podcini.storage.specs.EpisodeState
import ac.mdiq.podcini.storage.specs.FeedType
import ac.mdiq.podcini.storage.specs.Rating
import ac.mdiq.podcini.sync.queue.SynchronizationQueueSink
import ac.mdiq.podcini.utils.EventFlow
import ac.mdiq.podcini.utils.FlowEvent
import ac.mdiq.podcini.utils.Logd
import android.app.backup.BackupManager
import io.github.xilinjia.krdb.ext.isManaged
import io.github.xilinjia.krdb.notifications.ResultsChange
import io.github.xilinjia.krdb.notifications.UpdatedResults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val TAG: String = "Feeds"

var allFeeds = realm.query(Feed::class).find()
var feedsMap: Map<Long, Feed> = allFeeds.associateBy { it.id }

val feedCountFlow = MutableStateFlow(-1)

@Synchronized
fun getFeedList(queryString: String = ""): List<Feed> {
    return if (queryString.isEmpty()) allFeeds
    else realm.query(Feed::class, queryString).find()
}

fun compileLanguages() {
    val langsSet = mutableSetOf<String>()
    for (feed in allFeeds) {
        val langs = feed.langSet
        if (langs.isNotEmpty()) langsSet.addAll(langs)
        else langsSet.add("")
    }
    Logd(TAG, "langsSet: ${langsSet.size} appAttribs.langSet: ${appAttribsFlow!!.value.langSet.size}")
    if (!appAttribsFlow!!.value.langSet.containsAll(langsSet)) upsertBlk(appAttribsFlow!!.value) { it.langSet.addAll(langsSet) }
}

fun compileTags() {
    val tagsSet = mutableSetOf<String>()
    for (feed in allFeeds) tagsSet.addAll(feed.tags.filter { it != TAG_ROOT })
    if (!appAttribsFlow!!.value.feedTagSet.containsAll(tagsSet)) upsertBlk(appAttribsFlow!!.value) { it.feedTagSet.addAll(tagsSet) }
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
            allFeeds = changes.list
            feedsMap = allFeeds.associateBy { it.id }
            Logd(TAG, "monitorFeedList feeds updated size: ${allFeeds.size}")
            when (changes) {
                is UpdatedResults -> {
                    when {
                        changes.insertions.isNotEmpty() -> {
                            compileLanguages()
                            compileTags()
                        }
                        changes.changes.isNotEmpty() -> {
//                            for (i in changes.changes) {
//                                Logd(TAG, "monitorFeedList feed changed: ${feeds[i].title}")
//                            }
                        }
                        changes.deletions.isNotEmpty() -> {
                            Logd(TAG, "monitorFeedList feed deleted: ${changes.deletions.size}")
                            compileTags()
                        }
                        else -> Logd(TAG, "monitorFeedList else $changes")
                    }
                }
                else -> Logd(TAG, "monitorFeedList other $changes")
            }
            feedCountFlow.value = allFeeds.size
        }
    }
}

fun getFeed(feedId: Long, copy: Boolean = false): Feed? {
    val f = feedsMap[feedId]
    return if (f != null) {
        if (copy) realm.copyFromRealm(f) else f
    } else null
}

fun feedByIdentityOrID(feed: Feed, copy: Boolean = false): Feed? {
    Logd(TAG, "feedByIdentityOrID isLocal: ${feed.isLocal} id: ${feed.id}")
    if (feed.id != 0L) return getFeed(feed.id, copy)
    val feedIdv = feed.identifyingValue
    if (feed.isLocal) {
        val f = allFeeds.firstOrNull { it.identifyingValue == feedIdv && it.volumeId == feed.volumeId }
        Logd(TAG, "feedByIdentityOrID local feed: ${f?.title}")
        if (f != null) return if (copy) realm.copyFromRealm(f) else f
    } else {
        val f = allFeeds.firstOrNull { it.identifyingValue == feedIdv }
        Logd(TAG, "feedByIdentityOrID remote feed: ${f?.title}")
        if (f != null) return if (copy) realm.copyFromRealm(f) else f
    }
    return null
}

fun addNewFeed(feed: Feed) {
    Logd(TAG, "addNewFeeds called")
    feed.lastUpdateTime = nowInMillis()
    feed.lastFullUpdateTime = nowInMillis()
    realm.writeBlocking {
        feed.id = getEntityId()
        feed.totleDuration = 0
        Logd(TAG, "feed.episodes count: ${feed.episodes.size}")
        for (episode in feed.episodes) {
            episode.id = getEntityId()
//            Logd(TAG, "addNewFeeds episode: ${episode.id} ${episode.downloadUrl}")
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
    Logd(TAG, "deleteFeed called")
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
    upsertBlk(toFeed) {
        it.episodesCount = eps.size
        it.totleDuration = dur.toLong()
    }
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
        upsertBlk(episode) {}
        EventFlow.postStickyEvent(FlowEvent.FeedUpdatingEvent(false))
        ShareLog.Status.SUCCESS.code
    }
    if (log != null) upsert(log) {
        it.title = episode.title
        it.status = status
    }
}

fun addRemoteToMiscSyndicate(episode: Episode) {
    fun getMiscSyndicate(): Feed {
        val feedId: Long = 11
        var feed = getFeed(feedId, true)
        if (feed != null) return feed
        feed = createSynthetic(feedId, "Misc Syndicate")
        feed.type = FeedType.RSS.name
        upsertBlk(feed) {}
        return feed
    }
    val feed = getMiscSyndicate()
    Logd(TAG, "addToMiscSyndicate: feed: ${feed.title}")
    val episodes = getEpisodes(null, null, feedId=feed.id, copy = false)
    if (episodes.firstOrNull { it.identifyingValue == episode.identifyingValue } != null) return
    Logd(TAG, "addToMiscSyndicate adding new episode: ${episode.title}")
    //        if (episode.feedId != null && episode.feedId!! >= MAX_SYNTHETIC_ID) {
    //            episode.origFeedTitle = episode.feed?.title
    //            episode.origFeeddownloadUrl = episode.feed?.downloadUrl
    //            episode.origFeedlink = episode.feed?.link
    //        }
    episode.id = getEntityId()
    episode.feedId = feed.id
    upsertBlk(episode) {}
    upsertBlk(feed) {}
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
            val episodes = realm.query(Episode::class).query("feedId == ${feed_.id} SORT (pubDate ASC)").find()
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
        }
    }
    return n
}

suspend fun sumup(feed_: Feed) {
    var feed = feed_
    val episodes = getEpisodes(null, null, feedId=feed.id, copy = false)
    Logd(TAG, "sumup feed: ${feed.title} episodes: ${episodes.size}")
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
    feed = upsert(feed) {
        it.episodesCount = episodes.size
        it.totleDuration = durTotal
        it.scoreCount = scoreCount
        it.score = if (scoreCount > 0) (100 * sumR / scoreCount / Rating.SUPER.code).toInt() else -1000
        it.scoreUpdated = cTime
    }
    Logd(TAG, "sumup ${feed.id} episodesCount: ${feed.episodesCount} ${feed.totleDuration}")
}

// savedFeedId == 0L means saved feed
class FeedAssistant(val feed: Feed, savedFeedId: Long = 0L, isNew: Boolean = false) {
    val map = mutableMapOf<String, MutableList<Episode>>()
    val tag: String = if (savedFeedId == 0L) "Saved feed" else "New feed"

    init {
        val iterator = if (isNew) feed.episodes.iterator() else getEpisodes(null, null, feedId=feed.id, copy = true).iterator()
        while (iterator.hasNext()) {
            val e = iterator.next()
//            Logd(TAG, "FeedAssistant init $tag ${e.title}")
            if (!e.identifier.isNullOrEmpty()) {
                Logd(TAG, "FeedAssistant init $tag identifier ${e.identifier}")
                if (map.containsKey(e.identifier!!)) {
                    Logd(TAG, "FeedAssistant init $tag identifier duplicate: ${e.identifier} ${e.title}")
                    map[e.identifier!!]!!.add(e)
                } else map[e.identifier!!] = mutableListOf(e)
            }
            val idv = e.identifyingValue
            if (idv != e.identifier && !idv.isNullOrEmpty()) {
//                Logd(TAG, "FeedAssistant init $tag identifyingValue ${e.identifyingValue}")
                if (map.containsKey(idv)) {
                    Logd(TAG, "FeedAssistant init $tag identifyingValue duplicate: $idv ${e.title}")
                    map[idv]!!.add(e)
                } else map[idv] = mutableListOf(e)
            }
            val url = e.downloadUrl
            if (url != idv && !url.isNullOrEmpty()) {
                if (map.containsKey(url)) {
                    Logd(TAG, "FeedAssistant init $tag url duplicate: $url ${e.title}")
                    map[url]!!.add(e)
                } else map[url] = mutableListOf(e)
            }
            val title = canonicalizeTitle(e.title)
            if (title != idv && title.isNotEmpty()) {
                if (map.containsKey(title)) {
                    Logd(TAG, "FeedAssistant init $tag title duplicate: $title ${e.title}")
                } else map[title] = mutableListOf(e)
            }
        }
        if (savedFeedId == 0L) {
            for ((k, v) in map.entries) {
                if (v.size < 2) continue
                Logd(TAG, "FeedAssistant removing ${v.size-1} duplicates on $k")
                var episode = v[0]
                val ecs = v.sortedByDescending { it.comment.length }
                val comment = if (ecs[0].comment.isBlank()) "" else {
                    var c = ecs[0].comment
                    for (i in 1..<ecs.size) if (ecs[i].comment.isNotBlank()) c += "\n" + ecs[i].comment
                    c
                }
                val ers = v.sortedByDescending { it.rating }
                if (ers[0].rating > Rating.UNRATED.code) {
                    episode = if (ers[0].id != ecs[0].id && comment.isNotEmpty()) upsertBlk(ers[0]) { it.addComment(comment) } else ers[0]
                    runOnIOScope { realm.write { for (i in 1..<ers.size) {
                        val e = query(Episode::class).query("id == ${ers[i].id}").first().find()
                        if (e != null) delete(e)
                    } } }
                } else {
                    val eps = v.sortedByDescending { it.lastPlayedTime }
                    if (eps[0].lastPlayedTime > 0L) {
                        episode = if (eps[0].id != ecs[0].id && comment.isNotEmpty()) upsertBlk(eps[0]) { it.addComment(comment) } else eps[0]
                        runOnIOScope { realm.write { for (i in 1..<eps.size) {
                            val e = query(Episode::class).query("id == ${eps[i].id}").first().find()
                            if (e != null) delete(e)
                        } } }
                    } else {
                        val eps = v.sortedByDescending { it.pubDate }
                        episode = if (eps[0].id != ecs[0].id && comment.isNotEmpty()) upsertBlk(eps[0]) { it.addComment(comment) } else eps[0]
                        runOnIOScope { realm.write { for (i in 1..<eps.size) {
                            val e = query(Episode::class).query("id == ${eps[i].id}").first().find()
                            if (e != null) delete(e)
                        } } }
                    }
                }
                map[k] = mutableListOf(episode)
            }
        }
    }
    //        fun addUrlToMap(episode: Episode) {
    //            val url = episode.downloadUrl
    //            if (url != episode.identifyingValue && !url.isNullOrEmpty() && !map.containsKey(url)) map[url] = episode
    //        }
    fun addidvToMap(episode: Episode) {
        val idv = episode.identifyingValue
        if (idv != episode.identifier && !idv.isNullOrEmpty()) {
            if (map.containsKey(idv)) map[idv]!!.add(episode)
            else map[idv] = mutableListOf(episode)
        }
    }
//    private fun addDownloadStatus(episode: Episode, possibleDuplicate: Episode) {
//        fun duplicateEpisodeDetails(episode: Episode): String {
//            return ("""
//                Title: ${episode.title}
//                ID: ${episode.identifier}
//                """.trimIndent() + """
//
//                URL: ${episode.downloadUrl}
//                """.trimIndent())
//        }
//        addDownloadStatus(DownloadResult(savedFeedId, episode.title ?: "", DownloadError.ERROR_PARSER_EXCEPTION_DUPLICATE, false,
//            """
//                The podcast host appears to have added the same episode twice. Podcini still refreshed the feed and attempted to repair it.
//
//                Original episode:
//                ${duplicateEpisodeDetails(episode)}
//
//                Second episode that is also in the feed:
//                ${duplicateEpisodeDetails(possibleDuplicate)}
//                """.trimIndent()))
//    }
    fun getEpisodeByIdentifyingValue(item: Episode): List<Episode>? = map[item.identifyingValue]
    fun guessDuplicate(item: Episode): List<Episode>? {
        var episodes = map[item.identifier]
        if (!episodes.isNullOrEmpty()) return episodes
        val url = item.downloadUrl
        if (!url.isNullOrEmpty()) {
            episodes = map[url]
            if (!episodes.isNullOrEmpty()) return episodes
        }
        val title = canonicalizeTitle(item.title)
        if (title.isNotEmpty()) {
            episodes = map[title]
            if (!episodes.isNullOrEmpty()) return episodes
            //                if (!episodes.isNullOrEmpty()) {
            //                    val e = episodes[0]
            //                    if (datesLookSimilar(e, item) && durationsLookSimilar(e, item) && mimeTypeLooksSimilar(e, item)) return e
            //                }
        }
        return null
    }
    fun clear() = map.clear()
}
