package ac.mdiq.podcini.sourcing.download

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.config.AppConfig.initialize
import ac.mdiq.podcini.config.CHANNEL_ID
import ac.mdiq.podcini.config.NotificationIds
import ac.mdiq.podcini.playback.actQueueFlow
import ac.mdiq.podcini.shared.HTTPAttribs
import ac.mdiq.podcini.sourcing.AppGatewayRegistry
import ac.mdiq.podcini.sourcing.clientByEpisode
import ac.mdiq.podcini.sourcing.download.DownloadRequest.Companion.requestFor
import ac.mdiq.podcini.sourcing.download.Downloader.Companion.downloadStatesFlow
import ac.mdiq.podcini.sourcing.download.EpisodeDLManager.Companion.WORK_DATA_PROGRESS
import ac.mdiq.podcini.sourcing.download.EpisodeDLManager.Companion.updateDB
import ac.mdiq.podcini.storage.database.addToAssQueue
import ac.mdiq.podcini.storage.database.appAttribsFlow
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.deleteMedia
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.removeFromAllQueues
import ac.mdiq.podcini.storage.database.removeFromQueue
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.model.DownloadResult.Companion.logDownloadResult
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.SubscriptionLog.Companion.takeCodePoints
import ac.mdiq.podcini.storage.specs.EpisodeState
import ac.mdiq.podcini.storage.utils.fetchChapters
import ac.mdiq.podcini.storage.utils.quietlyDeleteFile
import ac.mdiq.podcini.storage.utils.toSafeUri
import ac.mdiq.podcini.storage.utils.toUF
import ac.mdiq.podcini.sync.SynchronizationSettings.isSyncProviderConnected
import ac.mdiq.podcini.sync.model.EpisodeAction
import ac.mdiq.podcini.sync.queue.SynchronizationQueueSink
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logm
import ac.mdiq.podcini.utils.Logs
import ac.mdiq.podcini.utils.NetworkUtils.mobileAllowEpisodeDownload
import ac.mdiq.podcini.utils.PODCINI_USER_AGENT
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.Constraints.Builder
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.collections.plus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

class EpisodeDLManager {
    private val constraints: Constraints
        get() = Builder()
            .setRequiresCharging(!appPrefsFlow!!.value.enableAutoDownloadOnBattery)
            .setRequiredNetworkType(if (mobileAllowEpisodeDownload) NetworkType.CONNECTED else NetworkType.UNMETERED).build()

    fun downloadNow(episodes: List<Episode>, ignoreConstraints: Boolean) {
        Logd(TAG) { "starting downloadNow" }
        val workRequest: OneTimeWorkRequest.Builder = requestBuilderFor(episodes)
        workRequest.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        if (ignoreConstraints) workRequest.setConstraints(Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        else workRequest.setConstraints(constraints)
        WorkManager.getInstance(getAppContext()).enqueueUniqueWork("DownloadEpisodesNow", ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest.build())
    }

    fun download(episodes: List<Episode>) {
        if (episodes.isEmpty()) return
        Logd(TAG) { "starting download" }
        val workRequest: OneTimeWorkRequest.Builder = requestBuilderFor(episodes)
        workRequest.setConstraints(constraints)
        WorkManager.getInstance(getAppContext()).enqueueUniqueWork("DownloadEpisodes", ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest.build())
    }

    suspend fun cancel(media: Episode) {
        Logd(TAG) { "starting cancel" }
        // This needs to be done here, not in the worker. Reason: The worker might or might not be running.
        // Remove partially downloaded file
        val episode_ = deleteMedia(media)
        if (appPrefsFlow!!.value.deleteRemovesFromQueue) removeFromAllQueues(listOf(episode_))
        val tag = WORK_TAG_EPISODE_URL + media.downloadUrl
        val future: Future<List<WorkInfo>> = WorkManager.getInstance(getAppContext()).getWorkInfosByTag(tag)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val workInfoList = future.get() // Wait for the completion of the future operation and retrieve the result
                workInfoList.forEach { workInfo -> if (workInfo.tags.contains(WORK_DATA_WAS_QUEUED)) removeFromQueue(actQueueFlow.value, listOf(media), playState = EpisodeState.UNSPECIFIED) }
            } catch (exception: Throwable) { Logs(TAG, exception)
            } finally { WorkManager.getInstance(getAppContext()).cancelAllWorkByTag(tag) }
        }
    }

    private fun requestBuilderFor(episodes: List<Episode>): OneTimeWorkRequest.Builder {
        Logd(TAG) { "starting getRequest" }
        val workRequest: OneTimeWorkRequest.Builder = OneTimeWorkRequest.Builder(EpisodesDownloadWorker::class.java)
            .setInitialDelay(0L, TimeUnit.MILLISECONDS)
            .addTag(EpisodesDownload)
        runOnIOScope {
            upsert(appAttribsFlow!!.value) {
                episodes.forEach { episode ->
                    if (episode.suitableForDownload()) {
                        workRequest.addTag(WORK_TAG_EPISODE_URL + episode.downloadUrl)
                        it.episodeIdsToDownload.add(episode.id)
                    }
                }
            }
        }
        if (appPrefsFlow!!.value.enqueueDownloaded) runBlocking { addToAssQueue(episodes) }
        return workRequest
    }

    fun cancelAll() {
        WorkManager.getInstance(getAppContext()).cancelAllWorkByTag(WORK_TAG)
        WorkManager.getInstance(getAppContext()).cancelAllWorkByTag(EpisodesDownload)
    }

    companion object {
        private const val TAG = "DownloadService"

        const val WORK_TAG: String = "episodeDownload"
        const val EpisodesDownload: String = "episodesDownload"

        const val WORK_TAG_EPISODE_URL: String = "episodeUrl:"
        const val WORK_DATA_PROGRESS: String = "progress"
        const val WORK_DATA_WAS_QUEUED: String = "was_queued"

        val manager: EpisodeDLManager by lazy { EpisodeDLManager() }

        suspend fun updateDB(request: DownloadRequest) {
            downloadStatesFlow.update { it + (request.urlOrig!! to DownloadStatus.Completed) }

            var item = realm.query(Episode::class).query("id == ${request.feedfileId}").first().find()
            if (item == null) {
                Loge(TAG, "Could not find downloaded episode object in database")
                return
            }
            //                val broadcastUnreadStateUpdate = item.isNew
            val chapters = if (!item.chaptersLoaded) try { fetchChapters(item) } catch (e: Exception) { Logs(TAG, e, "Get chapters failed for ${item.title}");listOf() } else listOf()
            item = upsert(item) {
                it.downloaded = true
                it.fileUrl = request.destination
                Logd(TAG) { "run() set request.destination: ${request.destination}" }
                if (request.destination.isNotBlank()) {
                    val file = request.destination.toUF()
                    runBlocking { it.size = if (file.exists()) file.size()?: 0 else 0 }
                    Logd(TAG) { "run() set size: ${it.size}" }
                }
                if (chapters.isNotEmpty()) it.setChapters(chapters)

                // TODO: this seems not really needed
                //                if (!it.fileUrl.isNullOrBlank() && it.size > 0) {
                //                    Logd(TAG) { "run() it.fileUrl: ${it.fileUrl}" }
                //                    try {
                //                        MediaMetadataRetrieverCompat().use { mmr ->
                //                            mmr.setDataSource(it.fileUrl!!)
                //                            val image = mmr.embeddedPicture
                //                            it.hasEmbeddedPicture = image != null
                //                            val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
                //                            if (duration != null) it.duration = duration
                //                            val mimeType = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                //                            if (mimeType != null) it.mimeType = mimeType
                //                        }
                //                    } catch (e: Exception) {
                //                        Logs(TAG, e, "Get duration failed. current duration is ${it.duration}. If lower than 30sc or higher than 5h, reset to 30sc")
                //                        if (it.duration !in 30000..18000000) it.duration = 30000
                //                        it.hasEmbeddedPicture = false
                //                    }
                //                } else Loge(TAG, "Get metadata failed for ${it.title}: fileUrl: ${it.fileUrl}")
                //                Logd(TAG) { "run() set duration: ${it.duration}" }
                it.isAutoDownloadEnabled = false
            }
            // TODO: need to post two events?
            //                if (broadcastUnreadStateUpdate) EventFlow.postEvent(FlowEvent.EpisodeMediaEvent.updated(item))
            if (isSyncProviderConnected) {
                Logd(TAG) { "enqueue synch" }
                val action = EpisodeAction.Builder(item, EpisodeAction.DOWNLOAD).currentTimestamp().build()
                SynchronizationQueueSink.enqueueEpisodeActionIfSyncActive(action)
            }
            //                Logd(TAG) { "episode.isNew: ${item.isNew} ${item.playState}" }
        }
    }
}

class EpisodesDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private var downloader: Downloader? = null
    private val isLastRunAttempt: Boolean
        get() = runAttemptCount >= 2

    override suspend fun doWork(): Result = coroutineScope {
        initialize()
        AppGatewayRegistry.ensureSourceClients()
        getForegroundInfo()

        Logd(TAG) { "starting doWork" }
        val ids = appAttribsFlow!!.value.episodeIdsToDownload
        if (ids.isEmpty()) return@coroutineScope Result.Success()

        val medias = realm.query(Episode::class).query("id IN $0", ids).find()
        if (medias.isEmpty()) {
            Loge(TAG, "no media is available for download")
            return@coroutineScope Result.failure()
        }
        for (media in medias) {
            val request = requestFor(media) ?: continue
            val progressUpdaterJob = CoroutineScope(Dispatchers.IO).launch {
                val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                while (isActive) {
                    try {
                        synchronized(notificationProgress) { notificationProgress.put(media.titleOrIdv(), request.progressPercent) }
                        withTimeoutOrNull(5000.milliseconds) {
                            setProgressAsync(Data.Builder().putInt(WORK_DATA_PROGRESS, request.progressPercent).build()).get()
                            nm.notify(NotificationIds.downloading, generateProgressNotification())
                        }
                        delay(1000.milliseconds)
                    } catch (e: CancellationException) { return@launch
                    } catch (e: Exception) {
                        Loge(TAG, e, "Episode download progressUpdaterJob exception")
                        return@launch
                    }
                }
            }
            var result: Result = Result.failure()
            try {
                result = performTasks(request, media)
                if (result == Result.failure()) return@coroutineScope result
            } catch (e: Exception) {
                Logs(TAG, e)
                return@coroutineScope Result.failure()
            } finally {
                if (result == Result.failure() && downloader?.request?.destination != null) quietlyDeleteFile(downloader!!.request.destination.toSafeUri())
                downloader?.cancel()
            }
            progressUpdaterJob.cancel()
            progressUpdaterJob.join()
            synchronized(notificationProgress) {
                notificationProgress.remove(media.titleOrIdv())
                if (notificationProgress.isEmpty()) {
                    val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(NotificationIds.downloading)
                }
            }
            upsert(appAttribsFlow!!.value) { it.episodeIdsToDownload.remove(media.id) }
            Logd(TAG) { "Worker for " + media.downloadUrl + " returned." }
        }
        return@coroutineScope Result.Success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return withContext(Dispatchers.Main) { ForegroundInfo(NotificationIds.downloading, generateProgressNotification()) }
    }

    private suspend fun performTasks(request: DownloadRequest, media: Episode): Result {
        Logd(TAG) { "starting performDownload: ${request.destination}" }
        if (request.destination.isBlank()) {
            Loge(TAG, "performDownload request.destination is null or blank")
            return Result.failure()
        }

        Logd(TAG) { "request.destination: ${request.destination}" }
        request.ensureMediaFileExists()
        downloader = Downloader.downloaderFor(request)
        if (downloader == null) {
            Loge(TAG, "performDownload Unable to create downloader")
            return Result.failure()
        }

        fun sendErrorNotification(title: String) {
            val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID.error.name)
            builder.setTicker(applicationContext.getString(R.string.download_report_title))
                .setContentTitle(applicationContext.getString(R.string.download_report_title))
                .setContentText(applicationContext.getString(R.string.download_error_tap_for_details))
                .setSmallIcon(R.drawable.ic_notification_sync_error)
                //                .setContentIntent(getDownloadLogsIntent(applicationContext))
                .setAutoCancel(true)
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NotificationIds.download_report, builder.build())
        }
        fun retry3times(): Result {
            if (isLastRunAttempt) {
                Loge(TAG, "retry3times failure on isLastRunAttempt")
                sendErrorNotification(downloader!!.request.title?:"")
                return Result.failure()
            } else return Result.retry()
        }
        fun sendMessage(episodeTitle_: String, isImmediateFail: Boolean) {
            var episodeTitle = episodeTitle_
            val retrying = !isLastRunAttempt && !isImmediateFail
            if (episodeTitle.length > 20) episodeTitle = episodeTitle.takeCodePoints(19) + "…"
            Logm(TAG, applicationContext.getString(if (retrying) R.string.download_error_retrying else R.string.download_error_not_retrying, episodeTitle), applicationContext.getString(R.string.download_error_details)) {
                //                    mainNavController.navigate(Screens.Logs.name)
            }
        }
        Logd(TAG) { "starting downloader" }
        try {
            var at = HTTPAttribs()
            clientByEpisode(media)?.let { c-> c.withProviderBlocking { it.httpAtribsFor(request.source) }?.let { at = it } }
            downloader!!.downloadMedia(at.useragent.takeIf { it.isNotBlank() }?: PODCINI_USER_AGENT)
        } catch (e: Exception) {
            Logs(TAG, e, "failed performDownload exception on downloader!!.call()")
            logDownloadResult(downloader!!.result)
            sendErrorNotification(request.title?:"")
            return Result.failure()
        }
        // This also happens when the worker was preempted, not just when the user canceled it
        if (downloader!!.cancelled) return Result.success()
        val status = downloader!!.result

        if (status.isSuccessful) {
            updateDB(request)
            logDownloadResult(downloader!!.result)
            return Result.success()
        }
        if (status.reason == DownloadError.ERROR_HTTP_DATA_ERROR && status.reasonDetailed.toInt() == 416) {
            Logd(TAG) { "Requested invalid range, restarting download from the beginning" }
            Logd(TAG) { "${downloader?.request?.destination}" }
            if (downloader?.request?.destination != null) quietlyDeleteFile(downloader!!.request.destination.toSafeUri())
            sendMessage(request.title?:"", false)
            return retry3times()
        }
        Loge(TAG, "Episode download failed ${request.title} ${status.reason}")
        logDownloadResult(status)
        if (status.reason in listOf(DownloadError.ERROR_FORBIDDEN, DownloadError.ERROR_NOT_FOUND, DownloadError.ERROR_UNAUTHORIZED, DownloadError.ERROR_IO_BLOCKED)) {
            Loge(TAG, "performDownload failure on various reasons ${status.reason?.name}")
            // Fail fast, these are probably unrecoverable
            sendErrorNotification(request.title?:"")
            return Result.failure()
        }
        sendMessage(request.title?:"", false)
        return retry3times()
    }
    private fun generateProgressNotification(): Notification {
        val sb = StringBuilder()
        var progressCopy: Map<String, Int>
        synchronized(notificationProgress) { progressCopy = notificationProgress.toMap() }
        for ((key, value) in progressCopy) sb.append("$key ($value%)\n")
        val bigText = sb.toString().trim { it <= ' ' }
        val contentText = if (progressCopy.size == 1) bigText else applicationContext.resources.getQuantityString(R.plurals.downloads_left, progressCopy.size, progressCopy.size)
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID.downloading.name)
        builder.setTicker(applicationContext.getString(R.string.download_notification_title_episodes))
            .setContentTitle(applicationContext.getString(R.string.download_notification_title_episodes))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            //                .setContentIntent(getDownloadsIntent(applicationContext))
            .setAutoCancel(false)
            .setOngoing(true)
            .setWhen(0)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSmallIcon(R.drawable.ic_notification_sync)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        return builder.build()
    }

    companion object {
        private const val TAG = "EpisodesDownloadWorker"
        private val notificationProgress: MutableMap<String, Int> = mutableMapOf()
    }
}
