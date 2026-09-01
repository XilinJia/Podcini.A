package ac.mdiq.podcini.sourcing.feed

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.automation.AutoDownloadAlgorithm
import ac.mdiq.podcini.automation.AutoEnqueueAlgorithm
import ac.mdiq.podcini.config.CHANNEL_ID
import ac.mdiq.podcini.sourcing.download.DownloadError
import ac.mdiq.podcini.sourcing.download.DownloadRequest
import ac.mdiq.podcini.sourcing.download.DownloadRequest.Companion.requestFor
import ac.mdiq.podcini.sourcing.download.Downloader.Companion.downloaderFor
import ac.mdiq.podcini.sourcing.feed.PodcastHandler.FeedHandlerResult
import ac.mdiq.podcini.utils.NetworkUtils.isFeedRefreshAllowed
import ac.mdiq.podcini.utils.NetworkUtils.mobileAllowFeedRefresh
import ac.mdiq.podcini.utils.NetworkUtils.networkMonitor
import ac.mdiq.podcini.shared.EpisodeIPC
import ac.mdiq.podcini.shared.getEntityId
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.sourcing.EPISODE_BATCH_SIZE
import ac.mdiq.podcini.sourcing.typeClientMap
import ac.mdiq.podcini.storage.database.FeedAssistant
import ac.mdiq.podcini.storage.database.addNewFeed
import ac.mdiq.podcini.storage.database.appAttribsFlow
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.compileLanguages
import ac.mdiq.podcini.storage.database.compileTags
import ac.mdiq.podcini.storage.database.eraseEpisodes
import ac.mdiq.podcini.storage.database.feedByIdentityOrID
import ac.mdiq.podcini.storage.database.getEpisodes
import ac.mdiq.podcini.storage.database.getFeedList
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.sumup
import ac.mdiq.podcini.storage.database.trimEpisodes
import ac.mdiq.podcini.storage.database.unmanaged
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.DownloadResult
import ac.mdiq.podcini.storage.model.DownloadResult.Companion.logDownloadResult
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.storage.model.Feed.Companion.EPISODES_LIMIT
import ac.mdiq.podcini.storage.model.toFeed
import ac.mdiq.podcini.storage.specs.EpisodeState
import ac.mdiq.podcini.storage.specs.FeedType
import ac.mdiq.podcini.storage.specs.MediaType
import ac.mdiq.podcini.storage.specs.VolumeAdaptionSetting
import ac.mdiq.podcini.storage.utils.toUF
import ac.mdiq.podcini.sync.SynchronizationSettings.isSyncProviderConnected
import ac.mdiq.podcini.sync.model.EpisodeAction
import ac.mdiq.podcini.sync.queue.SynchronizationQueueSink
import ac.mdiq.podcini.ui.compose.CommonConfirmAttrib
import ac.mdiq.podcini.ui.compose.commonConfirms
import ac.mdiq.podcini.ui.compose.feedOperationText
import ac.mdiq.podcini.utils.EventFlow
import ac.mdiq.podcini.utils.FlowEvent
import ac.mdiq.podcini.utils.LogFor
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logs
import ac.mdiq.podcini.utils.Logt
import android.Manifest
import android.app.Notification
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.xilinjia.krdb.ext.toRealmSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import org.xml.sax.SAXException
import javax.xml.parsers.ParserConfigurationException

class FeedUpdater(val feeds: List<Feed>, val fullUpdate: Boolean = false, val doItAnyway: Boolean = false, val removeUnlisted: Boolean = false) {
    private val context = getAppContext()
    private val notificationManager = NotificationManagerCompat.from(context)

    private var feedsToUpdate: MutableList<Feed> = mutableListOf()
    private val feedsToOnlyDownload: MutableList<Feed> = mutableListOf()
    private val feedsToOnlyEnqueue: MutableList<Feed> = mutableListOf()

    var force = false

    private suspend fun onFail(feed: Feed, details: String, reason: DownloadError = DownloadError.ERROR_MISC) {
        LogFor(TAG, feed, false, details, reason = reason)
        upsert(feed) { it.lastUpdateFailed = true }
    }

    suspend fun start() {
        Logd(TAG, "start doItAnyway: $doItAnyway feeds: ${feeds.size}")
        prepare()
        val allLocalFeeds = run {
            for (f in feeds) {
                if (!f.isLocal) {
                    Logd(TAG, "start feed is not local: ${f.title}")
                    return@run false
                }
            }
            true
        }
        Logd(TAG, "start allLocalFeeds: $allLocalFeeds")
        when {
            allLocalFeeds -> runOnIOScope { refresh() }
            !networkMonitor.isConnected -> EventFlow.postEvent(FlowEvent.MessageEvent(context.getString(R.string.download_error_no_connection)))
            isFeedRefreshAllowed -> runOnIOScope { refresh() }
            else -> {
                commonConfirms.add(CommonConfirmAttrib(
                    title = context.getString(R.string.feed_refresh_title),
                    message = context.getString(if (networkMonitor.isNetworkRestricted && networkMonitor.isVpnOverWifi) R.string.confirm_mobile_feed_refresh_dialog_message_vpn else R.string.confirm_mobile_feed_refresh_dialog_message),
                    confirmRes = R.string.confirm_mobile_streaming_button_once,
                    cancelRes = R.string.no,
                    neutralRes = R.string.confirm_mobile_streaming_button_always,
                    onConfirm = { runOnIOScope { refresh() }  },
                    onNeutral = {
                        mobileAllowFeedRefresh = true
                        runOnIOScope { refresh() }
                    }))
            }
        }
    }

    suspend fun prepare() {
        withContext(Dispatchers.Main) { feedOperationText = context.getString(R.string.preparing) }
        Logd(TAG, "prepare feeds: ${feeds.size}")
        if (feeds.isEmpty()) {
            val feedIds = appAttribsFlow!!.value.feedIdsToRefresh
            if (feedIds.isNotEmpty()) {
                Logt(TAG, "prepare Partial refresh of ${feedIds.size} feeds")
                feedsToUpdate = realm.query(Feed::class, "id IN $0", feedIds).find().filter { it.inNormalVolume }.toMutableList()
            } else feedsToUpdate = getFeedList("keepUpdated == true").filter { it.inNormalVolume }.toMutableList()
        } else {
            feedsToUpdate = feeds.filter { it.inNormalVolume }.toMutableList()
            force = true
        }
        Logd(TAG, "prepare feedsToUpdate: ${feedsToUpdate.size}")
        if (!doItAnyway) {
            val itr = feedsToUpdate.iterator()
            while (itr.hasNext()) {
                val feed = itr.next()
                if (!feed.keepUpdated) {
                    LogFor(TAG, feed, true, "feed set not to update, igored.", toastAnyway = true)
                    if (feed.autoEnqueue) feedsToOnlyEnqueue.add(feed)
                    else if (feed.autoDownload) feedsToOnlyDownload.add(feed)
                    itr.remove()
                }
            }
        }
    }

    suspend fun refresh() {
        Logd(TAG, "refresh feedsToUpdate: ${feedsToUpdate.size}")
        withContext(Dispatchers.Main) { feedOperationText = context.getString(R.string.refreshing_label) }
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Loge(TAG, "refresh: require POST_NOTIFICATIONS permission")
            return
        }
        val titles = feedsToUpdate.map { it.title ?: "No title" }.toMutableList()
        val feedIdsToRefresh = feedsToUpdate.map { it.id }.toMutableList()
        var i = 0
        while (i < feedsToUpdate.size) {
            notificationManager.notify(R.id.notification_updating_feeds, createNotification(titles))
            val feed = unmanaged(feedsToUpdate[i++])
            try {
                Logd(TAG, "refresh updating local feed? ${feed.isLocal} ${feed.title}")
                when {
                    feed.isLocal -> updateLocalFeed(feed, null)
                    else -> refreshFeed(feed)
                }
            } catch (e: Exception) { onFail(feed, "refresh: update failed ${feed.title} ${e.message}") }
            titles.removeAt(0)
            feedIdsToRefresh.removeAt(0)
            upsertBlk(appAttribsFlow!!.value) { it.feedIdsToRefresh = feedIdsToRefresh.toRealmSet() }
        }
        // TODO: not sure these need to be here
        compileLanguages()
        compileTags()

        notificationManager.cancel(R.id.notification_updating_feeds)
        withContext(Dispatchers.Main) { feedOperationText = context.getString(R.string.post_refreshing) }

        try {
            if (feedsToOnlyEnqueue.isNotEmpty()) feedsToUpdate.addAll(feedsToOnlyEnqueue)
            if (feedsToOnlyDownload.isNotEmpty()) feedsToUpdate.addAll(feedsToOnlyDownload)
            AutoEnqueueAlgorithm().run(feedsToUpdate)
            if (appPrefsFlow!!.value.enableAutoDl) AutoDownloadAlgorithm().run(feedsToUpdate)
        } finally {
            feedsToUpdate.clear()
            feedsToOnlyEnqueue.clear()
            feedsToOnlyDownload.clear()
            withContext(Dispatchers.Main + NonCancellable) { feedOperationText = "" }
        }
    }

    private suspend fun downloadFeed(feed: Feed): Feed? {
        var feed_: Feed? = null
        val nextPage = false
        //        val nextPage = (inputData.getBoolean(EXTRA_NEXT_PAGE, false) && feed.nextPageLink != null)
        if (nextPage) feed.pageNr += 1
        val builder = requestFor(feed)
        if (force || feed.lastUpdateFailed) builder.lastModified = null
        if (nextPage) builder.source = feed.nextPageLink
        val request = builder.build()
        val downloader = downloaderFor(request) ?: throw Exception("Unable to create downloader")
        var downloadResult: DownloadResult? = null
        var isSuccessful = true
        var feedRaw = Feed()
        var reason: DownloadError? = null
        var reasonDetailed: String? = null
        var feedHandlerResult: FeedHandlerResult? = null
        downloader.download { source ->
            feedRaw = Feed(request.source, request.lastModified)
            feedRaw.id = request.feedfileId
            feedRaw.limitEpisodesCount = feed.limitEpisodesCount
            feedRaw.fillPreferences(false, Feed.AutoDeleteAction.GLOBAL, VolumeAdaptionSetting.OFF, request.username, request.password)
            if (request.arguments != null) feedRaw.pageNr = request.arguments.getInt(DownloadRequest.REQUEST_ARG_PAGE_NR, 0)

            try {
                feedHandlerResult = PodcastHandler.parseFeed(source, feedRaw)
                Logd(TAG,  "downloadFeed Parsed ${feedRaw.title}")
                if (feedRaw.title.isNullOrBlank()) throw InvalidFeedException("Feed has no title")
                for (item in feedRaw.episodes) if (item.title.isNullOrBlank()) LogFor(TAG, feedRaw, true, "episode ${item.id} title is empty", toastAnyway = true)
                if (feedRaw.imageUrl.isNullOrEmpty()) feedRaw.imageUrl = feedRaw.downloadUrl
                if (feedHandlerResult != null) feed_ = feedHandlerResult!!.feed
                Logd(TAG, "downloadFeed completed feed_: ${feed_?.title}")
            } catch (e: SAXException) {
                isSuccessful = false
                Logs(TAG, e, "SAXException")
                reason = DownloadError.ERROR_PARSER_EXCEPTION
                reasonDetailed = e.message
            } catch (e: IOException) {
                isSuccessful = false
                Logs(TAG, e, "IOException")
                reason = DownloadError.ERROR_IO_ERROR
                reasonDetailed = e.message
            } catch (e: ParserConfigurationException) {
                isSuccessful = false
                Logs(TAG, e, "ParserConfigurationException")
                reason = DownloadError.ERROR_PARSER_EXCEPTION
                reasonDetailed = e.message
            } catch (e: PodcastHandler.UnsupportedFeedtypeException) {
                Logs(TAG, e, "UnsupportedFeedtypeException")
                isSuccessful = false
                reason = DownloadError.ERROR_UNSUPPORTED_TYPE
                if ("html".equals(e.rootElement, ignoreCase = true)) reason = DownloadError.ERROR_UNSUPPORTED_TYPE_HTML
                reasonDetailed = e.message
            } catch (e: InvalidFeedException) {
                Logs(TAG, e, "InvalidFeedException")
                isSuccessful = false
                reason = DownloadError.ERROR_PARSER_EXCEPTION
                reasonDetailed = e.message
            } finally {
                val feedFile = (request.destination).toUF()
                if (feedFile.exists()) {
                    feedFile.delete()
                    Logd(TAG, "downloadFeed Deletion of file '" + feedFile.absPath + "' ")
                }
            }
            if (isSuccessful) downloadResult = DownloadResult(feedRaw, DownloadError.SUCCESS, true, "")

            if (!request.source.isNullOrEmpty()) {
                fun updateFeedDownloadURL(original: String, updated: String) {
                    Logd(TAG, "updateFeedDownloadURL(original: $original, updated: $updated)")
                    val feed = realm.query(Feed::class).query("downloadUrl == $0", original).first().find()
                    if (feed != null) upsertBlk(feed) { it.downloadUrl = updated }
                }
                val redirectUrl: String? = feedHandlerResult?.redirectUrl
                when {
                    !downloader.permanentRedirectUrl.isNullOrEmpty() -> updateFeedDownloadURL(request.source, downloader.permanentRedirectUrl!!)
                    !redirectUrl.isNullOrBlank() && redirectUrl != request.source -> updateFeedDownloadURL(request.source, redirectUrl)
                }
            }
            if (downloadResult?.isSuccessful != true) {
                if (downloader.cancelled || downloader.result.reason == DownloadError.ERROR_DOWNLOAD_CANCELLED) {
                    Logd(TAG, "downloadFeed: feed refresh cancelled, likely due to feed not changed: ${feed.title}")
                    return@download
                }
                LogFor(TAG, feed, false, "downloadFeed: feed update failed: unsuccessful. cancelled?")
                upsert(feed) { it.lastUpdateFailed = true }
                logDownloadResult(downloader.result)
                return@download
            }
        }
        if (!isSuccessful) {
            onFail(feedRaw, reasonDetailed ?: "", reason ?: DownloadError.ERROR_NOT_FOUND)
            return null
        }
        return feed_
    }

    suspend fun refreshFeed(feed: Feed) {
        if (feed.downloadUrl.isNullOrBlank()) return

        val feed_ = when {
            feed.type in listOf(FeedType.Unknown.name, FeedType.RSS.name, FeedType.ATOM.name) -> downloadFeed(feed)
            else -> {
                val client = if (feed.type != null) typeClientMap[feed.type] else null
                when {
                    client != null -> {
                        val feedIpc = client.withProvider { it.feedToUpdate(feed.downloadUrl!!) }
                        if (feedIpc != null) {
                            val eList = mutableListOf<EpisodeIPC>()
                            var episodes = client.withProvider { it.getEpisodes(EPISODE_BATCH_SIZE, if (fullUpdate) 0L else feed.lastUpdateTime) } ?: listOf()
                            while (episodes.isNotEmpty()) {
                                if (fullUpdate) eList.addAll(episodes)
                                else {
                                    val eps = mutableListOf<EpisodeIPC>()
                                    for (e in episodes) if (e.pubDate > feed.lastUpdateTime) eps.add(e)
                                    if (eps.isEmpty()) break
                                    eList.addAll(eps)
                                }
                                val numEpisodes = eList.size
                                if (feed.limitEpisodesCount in 1..<numEpisodes || numEpisodes > EPISODES_LIMIT || episodes.size < EPISODE_BATCH_SIZE) break
                                Logd(TAG, "Subscribing eList: ${eList.size}")
                                episodes = client.withProvider { it.getEpisodes(EPISODE_BATCH_SIZE, if (fullUpdate) 0L else feed.lastUpdateTime) } ?: listOf()
                            }
                            feedIpc.episodes = eList
                        }
                        feedIpc?.toFeed()?.apply {
                            this.id = feed.id
                            this.title = feed.title
                        }
                    }
                    else -> null
                }
            }
        }

        Logd(TAG, "refreshFeed feed_: ${feed_?.id} ${feed_?.title}")
        if (feed_ != null) {
            val downloadStatus = DownloadResult(feed_, DownloadError.SUCCESS, true, "")
            if (fullUpdate) updateFeedFull(feed_, removeUnlistedItems = removeUnlisted, downloadStatus = downloadStatus)
            else updateFeedSimple(feed_, downloadStatus)

            if (!downloadStatus.isSuccessful) {
                LogFor(TAG, feed, false, "refreshFeed: feed update failed: unsuccessful. cancelled?")
                upsert(feed) { it.lastUpdateFailed = true }
            }
            logDownloadResult(downloadStatus)
        } else {
            LogFor(TAG, feed, false, "refreshFeed: feed update failed: unsuccessful. cancelled?")
            upsert(feed) { it.lastUpdateFailed = true }
        }
    }

    class InvalidFeedException(message: String?) : Exception(message)

    companion object {
        private const val TAG = "FeedUpdater"

        suspend fun updateFeedFull(newFeed: Feed, removeUnlistedItems: Boolean = false, overwriteStates: Boolean = false, downloadStatus: DownloadResult? = null) {
            Logd(TAG, "updateFeedFull feed: ${newFeed.title}")
            //        showStackTrace()

            Logd(TAG, "updateFeedFull newFeed id: ${newFeed.id} episodes: ${newFeed.episodes.size}")
            Logd(TAG, "updateFeedFull newFeed isLocal: ${newFeed.isLocal} volumeId: ${newFeed.volumeId}")
            // Look up feed in the feedslist
            val savedFeed = feedByIdentityOrID(newFeed, true)
            if (savedFeed == null) {
                Logd(TAG, "")
                addNewFeed(newFeed)
                return
            }

            Logd(TAG, "updateFeedFull Feed with title " + newFeed.title + " already exists. Syncing new with existing one.")
            newFeed.episodes.sortedByDescending { it.pubDate }
            if (newFeed.pageNr == savedFeed.pageNr) {
                if (overwriteStates) savedFeed.updateFromOther(newFeed, true)
                else if (savedFeed.differentFrom(newFeed)) {
                    Logd(TAG, "updateFeedFull Feed has updated attribute values. Updating old feed's attributes")
                    savedFeed.updateFromOther(newFeed)
                }
            } else {
                Logd(TAG, "updateFeedFull New feed has a higher page number: ${newFeed.nextPageLink}")
                savedFeed.nextPageLink = newFeed.nextPageLink
            }
            Logd(TAG, "updateFeedFull savedFeed.isLocal: ${savedFeed.isLocal} savedFeed.prefStreamOverDownload: ${savedFeed.prefStreamOverDownload}")
            val priorMostRecent = realm.query(Episode::class).query("feedId == ${savedFeed.id} SORT (pubDate DESC)").first().find()
            val priorMostRecentDate = priorMostRecent?.pubDate
            var idLong = getEntityId()
            Logd(TAG, "updateFeedFull building savedFeedAssistant")
            val savedFeedAssistant = FeedAssistant(savedFeed)
            //    val oldestDate = realm.query(Episode::class).query("feedId == ${savedFeed.id} SORT (pubDate ASC)").first().find()?.pubDate ?: 0L
            var nNew = 0
            var nUpdated = 0
            for (idx in newFeed.episodes.indices) {
                var episode = newFeed.episodes[idx]
                val oldItems = savedFeedAssistant.guessDuplicate(episode)
                if (!oldItems.isNullOrEmpty()) {
                    if (oldItems.size > 1) {
                        Loge(TAG, "found duplicate episodes in feed: ${savedFeed.title}")
                        for (e in oldItems) Loge(TAG, "duplicate episode: ${e.title}")
                    }
                    if (!newFeed.isLocal) {
                        //            Logd(TAG, "updateFeedFull Update existing episode: ${episode.title}")
                        oldItems[0].identifier = episode.identifier
                        // queue for syncing with server
                        if (isSyncProviderConnected && oldItems[0].isPlayed()) {
                            val durs = oldItems[0].duration / 1000
                            val action = EpisodeAction.Builder(oldItems[0], EpisodeAction.PLAY)
                                .currentTimestamp()
                                .started(durs)
                                .position(durs)
                                .total(durs)
                                .build()
                            SynchronizationQueueSink.enqueueEpisodeActionIfSyncActive(action)
                        }
                    }
                    nUpdated++
                    upsertBlk(oldItems[0]) { it.updateFromOther(episode, includeState = overwriteStates, includeDuration = it.playState < EpisodeState.PROGRESS.code ) }
                } else {
                    Logd(TAG, "updateFeedFull Found new episode: ${episode.pubDate} ${episode.title}")
                    nNew++
                    episode.id = idLong++
                    episode.feedId = savedFeed.id
                    if (appPrefsFlow!!.value.fetchmediaSizes && !savedFeed.isLocal && !savedFeed.prefStreamOverDownload) episode.fetchMediaSize(false)
                    if (!savedFeed.hasVideoMedia && episode.mediaType == MediaType.VIDEO) savedFeed.hasVideoMedia = true
                    savedFeedAssistant.addidvToMap(episode)
                    val pubDate = episode.pubDate
                    if (priorMostRecentDate == null || priorMostRecentDate < pubDate || priorMostRecentDate == pubDate) {
                        Logd(TAG, "updateFeedFull Marking episode published on $pubDate new, prior most recent date = $priorMostRecentDate")
                        episode = upsertBlk(episode) { it.setPlayState(EpisodeState.NEW) }
                    } else upsertBlk(episode) {}
                }
                if (idx % 50 == 0) Logd(TAG, "updateFeedFull processing item $idx / ${newFeed.episodes.size} ")
            }
            savedFeedAssistant.clear()
            downloadStatus?.addDetail("Added new episodes: $nNew")
            downloadStatus?.addDetail("Updated existing episodes: $nUpdated")

            val unlistedUnworthyItems: MutableList<Episode> = mutableListOf()
            // identify episodes to be removed
            if (removeUnlistedItems) {
                Logd(TAG, "updateFeedFull building newFeedAssistant")
                val newFeedAssistant = FeedAssistant(newFeed, savedFeed.id, isNew = true)
                val iterator = getEpisodes(null, null, feedId=savedFeed.id, copy = false).toMutableList().iterator()
                while (iterator.hasNext()) {
                    val feedItem = iterator.next()
                    Logd(TAG, "updateFeedFull feedItem.identifyingValue ${feedItem.identifyingValue}")
                    if (newFeedAssistant.getEpisodeByIdentifyingValue(feedItem) == null) {
                        if (!feedItem.isWorthy) unlistedUnworthyItems.add(feedItem)
                        iterator.remove()
                    }
                }
                newFeedAssistant.clear()
                if (unlistedUnworthyItems.isNotEmpty()) {
                    eraseEpisodes(unlistedUnworthyItems)
                    downloadStatus?.addDetail("Erased unlisted episodes: ${unlistedUnworthyItems.size}")
                }
            }

            val nTrimmed = trimEpisodes(savedFeed)
            downloadStatus?.addDetail("Trimmed episodes: $nTrimmed")

            // update attributes
            savedFeed.lastUpdate = newFeed.lastUpdate
            savedFeed.lastUpdateTime = nowInMillis()
            savedFeed.lastFullUpdateTime = nowInMillis()
            savedFeed.type = newFeed.type
            savedFeed.lastUpdateFailed = false
            Logd(TAG, "updateFeedFull savedFeed lastFullUpdateTime: ${savedFeed.lastFullUpdateTime}")

            val feed = upsert(savedFeed) {}
            sumup(feed)
        }

        suspend fun updateFeedSimple(newFeed: Feed, downloadStatus: DownloadResult? = null) {
            Logd(TAG, "updateFeedSimple called on feed: ${newFeed.title}")
            val savedFeed = feedByIdentityOrID(newFeed, true)
            if (savedFeed == null) {
                downloadStatus?.let {
                    it.isSuccessful = false
                    it.addDetail("updateFeedSimple existing feed not found")
                }
                return
            }

            Logd(TAG, "Feed with title " + newFeed.title + " already exists. Syncing new with existing one.")
            newFeed.episodes.sortedByDescending { it.pubDate }
            if (newFeed.pageNr == savedFeed.pageNr) {
                if (savedFeed.differentFrom(newFeed)) {
                    Logd(TAG, "Feed has updated attribute values. Updating old feed's attributes")
                    savedFeed.updateFromOther(newFeed)
                }
            } else {
                Logd(TAG, "New feed has a higher page number: ${newFeed.nextPageLink}")
                savedFeed.nextPageLink = newFeed.nextPageLink
            }
            val priorMostRecents = realm.query(Episode::class).query("feedId == ${savedFeed.id} SORT (pubDate DESC) LIMIT(5)").find()
            val priorMostRecentDate = if (priorMostRecents.isNotEmpty()) priorMostRecents[0].pubDate else savedFeed.lastUpdateTime
            var idLong = getEntityId()
            Logd(TAG, "updateFeedSimple building savedFeedAssistant")

            var nNew = 0
            // Look for new or updated Items
            for (idx in newFeed.episodes.indices) {
                var episode = newFeed.episodes[idx]
                if (episode.duration < 1000 && !savedFeed.acceptTinyEpisodes) {
                    //            LogtFor(TAG, episode.id, "new episode duration less than 1 second, ignored. in Feed: ${newFeed.title}")
                    Logd(TAG, "new episode duration less than 1 second, ignored. in Feed: ${newFeed.title}")
                    downloadStatus?.addDetail("new episode duration less than 1 second, ignored: ${episode.title}")
                    continue
                }
                val pubDate = episode.pubDate
                if (pubDate <= priorMostRecentDate || episode.downloadUrl in priorMostRecents.map { it.downloadUrl} || episode.title in priorMostRecents.map { it.title }) continue
                nNew++

                Logd(TAG, "Found new episode: ${episode.title}")
                episode.id = idLong++
                episode.feedId = savedFeed.id
                if (appPrefsFlow!!.value.fetchmediaSizes && !savedFeed.isLocal && !savedFeed.prefStreamOverDownload) episode.fetchMediaSize(persist = false)
                if (!savedFeed.hasVideoMedia && episode.mediaType == MediaType.VIDEO) savedFeed.hasVideoMedia = true

                Logd(TAG, "Marking episode published on $pubDate new, prior most recent date = $priorMostRecentDate")
                episode = upsert(episode) { it.setPlayState(EpisodeState.NEW) }
            }
            downloadStatus?.addDetail("Added new episodes: $nNew")

            val nTrimmed = trimEpisodes(savedFeed)
            downloadStatus?.addDetail("trimmed episodes: $nTrimmed")

            // update attributes
            savedFeed.lastUpdate = newFeed.lastUpdate
            savedFeed.lastUpdateTime = nowInMillis()
            savedFeed.type = newFeed.type
            savedFeed.lastUpdateFailed = false
            val feed = upsert(savedFeed) {}

            sumup(feed)
        }

        fun createNotification(titles: List<String>?): Notification {
            val context = getAppContext()
            var contentText = ""
            var bigText: String? = ""
            if (titles != null) {
                contentText = context.resources.getQuantityString(R.plurals.downloads_left, titles.size, titles.size)
                bigText = titles.joinToString("\n") { "• $it" }
            }
            return NotificationCompat.Builder(context, CHANNEL_ID.refreshing.name)
                .setContentTitle(context.getString(R.string.download_notification_title_feeds))
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                .setSmallIcon(R.drawable.ic_notification_sync)
                .setOngoing(true)
//                .addAction(R.drawable.ic_cancel, context.getString(R.string.cancel_label), WorkManager.getInstance(context).createCancelPendingIntent(id))
                .build()
        }
    }
}