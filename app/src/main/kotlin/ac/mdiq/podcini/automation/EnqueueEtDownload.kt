package ac.mdiq.podcini.automation

import ac.mdiq.podcini.net.download.EpisodeAdrDLManager
import ac.mdiq.podcini.playback.base.InTheatre.isCurMedia
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.storage.database.EPISODE_CACHE_SIZE_UNLIMITED
import ac.mdiq.podcini.storage.database.addToAssQueue
import ac.mdiq.podcini.storage.database.allFeeds
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.deleteMedia
import ac.mdiq.podcini.storage.database.getEpisodes
import ac.mdiq.podcini.storage.database.getEpisodesCount
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.removeFromAllQueues
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.storage.model.PlayQueue
import ac.mdiq.podcini.storage.model.QueueEntry
import ac.mdiq.podcini.storage.model.VIRTUAL_QUEUE_ID
import ac.mdiq.podcini.storage.specs.AutoDLEQPolicy
import ac.mdiq.podcini.storage.specs.EpisodeFilter
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder.Companion.reorderWith
import ac.mdiq.podcini.storage.specs.EpisodeState
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.LogeFor
import ac.mdiq.podcini.utils.Logt
import kotlin.random.Random

private const val TAG = "AutoDownloads"

class AutoDownloadAlgorithm {
    suspend fun run(feeds: List<Feed>?, checkQueues: Boolean = true, noRefreshing: Boolean = false) {
        Logd(TAG, "run Performing auto-dl of undownloaded episodes")
        val toReplace: MutableSet<Episode> = mutableSetOf()
        val candidates: MutableSet<Episode> = mutableSetOf()
        if (checkQueues) {
            val queues = realm.query(PlayQueue::class).find().filter { !it.isVirtual() }
            for (q in queues) {
                if (q.autoDownloadEpisodes) {
                    val eids = q.entries.map { it.episodeId }
                    val queueItems = realm.query(Episode::class).query("id IN $0 AND fileUrl == nil", eids).find()
                    Logd(TAG, "run add from queue: ${q.name} ${queueItems.size}")
                    if (queueItems.isNotEmpty()) queueItems.forEach { if (!appPrefsFlow!!.value.streamOverDownload || it.feed?.prefStreamOverDownload != true) candidates.add(it) }
                }
            }
        }
        assembleCandidates(feeds, candidates, toReplace, noRefreshing = noRefreshing)
        Logd(TAG, "run candidates ${candidates.size} for download")
        if (candidates.isNotEmpty()) {
            val autoDownloadableCount = candidates.size
            if (toReplace.isNotEmpty()) {
                for (episode in toReplace) {
                    if (episode.feed != null && !episode.feed!!.isLocal) {
                        EpisodeAdrDLManager.manager.cancel(episode)
                        if (episode.downloaded) deleteMedia(episode)
                    }
                }
                removeFromAllQueues(toReplace)
            }
            val downloadedCount = getEpisodesCount(EpisodeFilter(EpisodeFilter.States.downloaded.name))
            val deletedCount = toReplace.size + cleanupAlgorithm().makeRoomForEpisodes(autoDownloadableCount - toReplace.size)
            val appEpisodeCache = appPrefsFlow!!.value.episodeCacheSize
            val cacheIsUnlimited = appEpisodeCache <= EPISODE_CACHE_SIZE_UNLIMITED
            Logd(TAG, "run cacheIsUnlimited: $cacheIsUnlimited appEpisodeCache: $appEpisodeCache downloadedCount: $downloadedCount autoDownloadableCount: $autoDownloadableCount deletedCount: $deletedCount")
            val allowedCount =
                if (cacheIsUnlimited || appEpisodeCache >= downloadedCount + autoDownloadableCount) autoDownloadableCount
                else appEpisodeCache - (downloadedCount - deletedCount)
            Logd(TAG, "run allowedCount $allowedCount")
            if (allowedCount > 0) {
                var itemsToDownload = candidates.toMutableList()
                if (allowedCount < candidates.size) itemsToDownload = itemsToDownload.subList(0, allowedCount)
                Logt(TAG, "Auto download requesting episodes: ${itemsToDownload.size}")
                EpisodeAdrDLManager.manager.download(itemsToDownload)
                itemsToDownload.clear()
            } else Logt(TAG, "Auto download not performed, allowed count exceeded: candidates: ${candidates.size} allowedCount: $allowedCount")
            candidates.clear()
        }
    }
}

class AutoEnqueueAlgorithm {
    suspend fun run(feeds: List<Feed>?, noRefreshing: Boolean = false) {
        Logd(TAG, "Performing auto-enqueue of undownloaded episodes")
//        showStackTrace()
        val toReplace: MutableSet<Episode> = mutableSetOf()
        val candidates: MutableSet<Episode> = mutableSetOf()

        assembleCandidates(feeds, candidates, toReplace, noRefreshing = noRefreshing, dl = false)
        if (candidates.isNotEmpty()) {
            if (toReplace.isNotEmpty()) removeFromAllQueues(toReplace, EpisodeState.UNPLAYED)
            Logd(TAG, "Enqueueing ${candidates.size} items")
            realm.write { for (e in candidates) findLatest(e)?.isAutoDownloadEnabled = false }
            addToAssQueue(candidates.toList())
            Logt(TAG, "Auto enqueued episodes: ${candidates.size}")
            candidates.clear()
        }
    }
}

private suspend fun assembleCandidates(feeds_: List<Feed>?, candidates: MutableSet<Episode>, toReplace: MutableSet<Episode>, noRefreshing: Boolean, dl: Boolean = true) {
    val NM = 3
    val feeds = (feeds_ ?: allFeeds).filter { it.inNormalVolume }
    val eIdsAllQueues = realm.query(QueueEntry::class).query("queueId != $VIRTUAL_QUEUE_ID").find().map { it.episodeId }.toSet()
    for (f in feeds) {
        Logd(TAG, "assembleFeedsCandidates: autoDL: ${f.autoDownload} autoEQ: ${f.autoEnqueue} isLocal: ${f.isLocal} ${f.title}")
        if (((dl && f.autoDownload) || (!dl && f.autoEnqueue)) && !f.isLocal) {
            val dlFilter = if (dl) {
                if (f.countingPlayed) EpisodeFilter(EpisodeFilter.States.downloaded.name)
                else EpisodeFilter(EpisodeFilter.States.downloaded.name,
                    EpisodeFilter.States.UNPLAYED.name, EpisodeFilter.States.QUEUE.name,
                    EpisodeFilter.States.PROGRESS.name, EpisodeFilter.States.SKIPPED.name)
            } else EpisodeFilter(EpisodeFilter.States.QUEUE.name)
            val downloadedCount = if (dl) getEpisodesCount(dlFilter, f.id) else {
                if (f.queue == null) 0
                else {
                    val eids = f.queue!!.entries.map { it.episodeId }
                    realm.query(Episode::class).query("feedId == ${f.id} AND id IN $0",eids).count().find().toInt()
                }
            }
            var allowedDLCount = if (f.autoDLMaxEpisodes == EPISODE_CACHE_SIZE_UNLIMITED) Int.MAX_VALUE else f.autoDLMaxEpisodes - downloadedCount
            Logd(TAG, "assembleFeedsCandidates ${f.autoDLMaxEpisodes} downloadedCount: $downloadedCount allowedDLCount: $allowedDLCount")
            val episodes = mutableListOf<Episode>()
            run {
                val cTime = nowInMillis()
                val queryStringAgain = "feedId == ${f.id} AND playState == ${EpisodeState.LATER.code} AND repeatTime <= $cTime SORT(repeatTime ASC)"
                val es = realm.query(Episode::class).query(queryStringAgain).find().filter { it.id !in eIdsAllQueues }
                Logd(TAG, "assembleFeedsCandidates queryStringAgain: [${es.size}] $queryStringAgain")
                if (es.isNotEmpty()) {
                    episodes.addAll(es)
                    allowedDLCount -= es.size
                }
            }
            if (allowedDLCount > 0 && f.autoDLSoon) {
                val queryStringSoon = "feedId == ${f.id} AND playState == ${EpisodeState.SOON.code} SORT(pubDate DESC) LIMIT($allowedDLCount)"
                val es = realm.query(Episode::class).query(queryStringSoon).find().filter { it.id !in eIdsAllQueues }
                Logd(TAG, "assembleFeedsCandidates queryStringSoon: [${es.size}] $queryStringSoon")
                if (es.isNotEmpty()) {
                    episodes.addAll(es)
                    allowedDLCount -= es.size
                }
            }
            var episodes0 = listOf<Episode>()
            for (dleq in f.autoDLEQs) {
                var queryString = "feedId == ${f.id} AND isAutoDownloadEnabled == true AND fileUrl == nil"
                Logd(TAG, "assembleFeedsCandidates autoDLPolicy: ${dleq.autoDLPolicy.name}")
                val policy = dleq.autoDLPolicy
                if (allowedDLCount > 0 || policy.replace) {
                    val episodes1 = mutableListOf<Episode>()
                    dleq.autoDownloadFilter?.queryString()?.let { if (it.isNotBlank()) queryString += " AND $it " }
                    when (policy) {
                        AutoDLEQPolicy.DISCRETION -> {}
                        AutoDLEQPolicy.ONLY_NEW -> {
                            if (!noRefreshing) {
                                if (policy.replace) {
                                    allowedDLCount = if (f.autoDLMaxEpisodes == EPISODE_CACHE_SIZE_UNLIMITED) Int.MAX_VALUE else f.autoDLMaxEpisodes
                                    queryString += " AND playState == ${EpisodeState.NEW.code} SORT(pubDate DESC) LIMIT(${allowedDLCount})"
                                    val es = realm.query(Episode::class).query(queryString).find()
                                    Logd(TAG, "assembleFeedsCandidates Replace queryString: [${es.size}] $queryString")
                                    if (es.isNotEmpty()) {
                                        val numToDelete = es.size + downloadedCount - allowedDLCount
                                        Logd(TAG, "assembleFeedsCandidates numToDelete: $numToDelete")
                                        if (numToDelete > 0) {
                                            val toDelete_ = getEpisodes(dlFilter, EpisodeSortOrder.DATE_ASC, feedId = f.id, limit = numToDelete)
                                            if (toDelete_.isNotEmpty()) toReplace.addAll(toDelete_)
                                            Logd(TAG, "assembleFeedsCandidates toDelete_: ${toDelete_.size}")
                                        }
                                        episodes1.addAll(es)
                                        Logd(TAG, "assembleFeedsCandidates episodes: ${episodes1.size}")
                                    } else Logd(TAG, "No New episodes found for feed: ${f.title}")
                                } else {
                                    queryString += " AND playState == ${EpisodeState.NEW.code} SORT(pubDate DESC) LIMIT(${NM * allowedDLCount})"
                                    val es = realm.query(Episode::class).query(queryString).find()
                                    Logd(TAG, "assembleFeedsCandidates Non-Replace queryString: [${es.size}] $queryString")
                                    if (es.isNotEmpty()) episodes1.addAll(es)
                                    else Logd(TAG, "No New episodes found for feed: ${f.title}")
                                }
                            }
                        }
                        AutoDLEQPolicy.NEWER -> {
                            queryString += " AND playState <= ${EpisodeState.SOON.code} SORT(pubDate DESC) LIMIT(${NM * allowedDLCount})"
                            val es = realm.query(Episode::class).query(queryString).find()
                            Logd(TAG, "assembleFeedsCandidates Newer queryString: [${es.size}] $queryString")
                            if (es.isNotEmpty()) episodes1.addAll(es)
                        }
                        AutoDLEQPolicy.OLDER -> {
                            queryString += " AND playState <= ${EpisodeState.SOON.code} SORT(pubDate ASC) LIMIT(${NM * allowedDLCount})"
                            val es = realm.query(Episode::class).query(queryString).find()
                            Logd(TAG, "assembleFeedsCandidates Older queryString: [${es.size}] $queryString")
                            if (es.isNotEmpty()) episodes1.addAll(es)
                        }
                        AutoDLEQPolicy.FILTER_SORT -> {
                            Logd(TAG, "FILTER_SORT queryString: $queryString")
                            val q = realm.query(Episode::class).query(queryString)
                            val filterADL = dleq.episodeFilterADL.queryString()
                            Logd(TAG, "FILTER_SORT filterADL: $filterADL")
                            if (filterADL.isNotBlank()) q.query(filterADL)
                            val es = q.find().toMutableList()
                            Logd(TAG, "assembleFeedsCandidates Filter-sort queryString: [${es.size}] $queryString")
                            if (es.isNotEmpty()) {
                                val sortOrder = dleq.episodesSortOrderADL ?: EpisodeSortOrder.DATE_DESC
                                Logd(TAG, "FILTER_SORT sortOrder: $sortOrder")
                                es.reorderWith(sortOrder)
                                episodes1.addAll(if (es.size > allowedDLCount) es.subList(0, allowedDLCount) else es)
                                Logd(TAG, "FILTER_SORT episodes: ${episodes1.size}")
                            }
                        }
                    }
                    val maxIndices = 0 until maxOf(episodes0.size, episodes1.size)
                    episodes0 = maxIndices.flatMap { i ->
                        val pair = listOfNotNull(episodes0.getOrNull(i), episodes1.getOrNull(i))
                        if (Random.nextBoolean()) pair else pair.reversed()
                    }
                }
            }
            if (episodes0.isNotEmpty()) episodes.addAll(episodes0)
            if (episodes.isNotEmpty()) {
                var count = 0
                for (e in episodes) {
                    if (isCurMedia(e)) continue
                    if (e.downloadUrl.isNullOrBlank()) {
                        LogeFor(TAG, e.id, "episode downloadUrl is null or blank, skipped from auto-download")
                        upsertBlk(e) { it.isAutoDownloadEnabled = false }
                        continue
                    }
                    candidates.add(e)
                    if (++count >= allowedDLCount) break
                }
            }
            episodes.clear()
            Logd(TAG, "assembleFeedsCandidates ${f.title} candidate size: ${candidates.size}")

            runOnIOScope {
                val eInQ = realm.query(Episode::class, "feedId == ${f.id} AND playState == ${EpisodeState.QUEUE.code}").find()
                val q = f.queue
                if (q != null) {
                    val toAdd = eInQ.filter { it.id !in eIdsAllQueues }
                    if (toAdd.isNotEmpty()) addToAssQueue(toAdd)
                }
            }

            realm.write {
                if (!noRefreshing) {
                    for (dleq in f.autoDLEQs) {
                        if (dleq.autoDownloadFilter?.markExcludedPlayed == true) {
                            val qStr = dleq.autoDownloadFilter!!.queryExcludeString()
                            if (qStr.isNotBlank()) {
                                while (true) {
                                    val eExc = query(Episode::class, "feedId == ${f.id} AND playState == ${EpisodeState.NEW.code} LIMIT(20)").find().toList()
                                    if (eExc.isEmpty()) break
                                    eExc.forEach { it.setPlayState(EpisodeState.PLAYED) }
                                }
                            }
                        }
                    }
                    while (true) {
                        val episodesNew = query(Episode::class, "feedId == ${f.id} AND playState == ${EpisodeState.NEW.code} LIMIT(20)").find().toList()
                        if (episodesNew.isEmpty()) break
                        Logd(TAG, "run episodesNew: ${episodesNew.size}")
                        episodesNew.forEach { e-> e.setPlayState(EpisodeState.UNPLAYED) }
                    }
                }
            }
        }
    }
}
