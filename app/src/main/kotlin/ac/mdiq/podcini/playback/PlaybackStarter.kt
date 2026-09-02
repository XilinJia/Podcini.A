package ac.mdiq.podcini.playback

import ac.mdiq.podcini.playback.base.aController
import ac.mdiq.podcini.playback.base.aCtrlFuture
import ac.mdiq.podcini.playback.base.ensureAController
import ac.mdiq.podcini.playback.base.theatres
import ac.mdiq.podcini.playback.base.Media3Player.Companion.getCache
import ac.mdiq.podcini.playback.base.Media3Player.Companion.simpleCache
import ac.mdiq.podcini.playback.base.MediaPlayerBase.Companion.isStreamingCapable
import ac.mdiq.podcini.playback.base.SleepManager.Companion.sleepManager
import ac.mdiq.podcini.playback.service.PlaybackService
import ac.mdiq.podcini.storage.database.checkAndMarkDuplicates
import ac.mdiq.podcini.storage.database.isMediaDownloadable
import ac.mdiq.podcini.storage.database.prefStreamOverDownload
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

var forcePlaybackReset: Boolean = false
    set(value) {
        field = value
        if (value) {
            theatres[0].mPlayerFlow.value?.pause(false)
            theatres[1].mPlayerFlow.value?.pause(false)
        }
    }

class PlaybackStarter(private val media: Episode) {
    private val TAG = "PlaybackStarter"

    private var shouldStreamThisTime = false
    private var repeat = false

    private var widgetId: String = ""

    fun shouldStreamThisTime(shouldStreamThisTime: Boolean?): PlaybackStarter {
        if (shouldStreamThisTime == null) {
            this.shouldStreamThisTime = media.feed == null || media.feedId == null || (!media.downloaded && media.feed?.isLocal != true)
                    || !isMediaDownloadable(media) || (prefStreamOverDownload && media.feed?.prefStreamOverDownload == true)
        } else this.shouldStreamThisTime = shouldStreamThisTime
        return this
    }

    fun setToRepeat(repeat_: Boolean): PlaybackStarter {
        repeat = repeat_
        return this
    }

    fun setWidgetId(widgetId: String): PlaybackStarter {
        this.widgetId = widgetId
        return this
    }

    fun start(playerId: Int = 0) {
        Logd(TAG, "start PlaybackService.isRunning: ${PlaybackService.isRunning}")
//        showStackTrace()
        ensureAController()

        var media_ = media
        if (forcePlaybackReset && simpleCache != null) getCache().removeResource(media.id.toString())
        var sameMedia = !forcePlaybackReset
        val player = theatres[playerId].mPlayerFlow.value
        if (player?.curMediaFlow?.value?.id != media.id) {
            sameMedia = false
            media_ = checkAndMarkDuplicates(media)
//            player.setAsCurEpisode(media_)   // seems redundant
        }

        fun processTask() {
            if (player == null) {
                Loge(TAG, "processTask mPlayerFlow.value == null")
                return
            }
            Logd(TAG, "aCtrlFuture: ${aCtrlFuture != null} player status: ${player.status}")
            player.shouldRepeatFlow.value = repeat
            Logd(TAG, "start: statusFlow: ${player.status} sameMedia: $sameMedia")
            player.isStreaming = shouldStreamThisTime
            player.widgetId = widgetId
            when {
                player.isPlaying -> {
                    player.pause(false)
                    if (!sameMedia) {
                        player.isSkipping = true
                        player.prepareMedia(media_, shouldStreamThisTime, startWhenPrepared = true, prepareImmediately = true, forceReset = forcePlaybackReset)
                        sleepManager?.restart()
                    }
                }
                player.isPaused || player.isPrepared -> {
                    if (sameMedia) player.play()
                    else {
                        player.isSkipping = true
                        player.prepareMedia(media_, shouldStreamThisTime, startWhenPrepared = true, prepareImmediately = true, forceReset = forcePlaybackReset)
                    }
                    sleepManager?.restart()
                }
                player.isStopped -> {
//                    ContextCompat.startForegroundService(getAppContext(), Intent(getAppContext(), PlaybackService::class.java))
                    player.prepareMedia(media_, shouldStreamThisTime, startWhenPrepared = true, prepareImmediately = true, forceReset = forcePlaybackReset)
                    sleepManager?.restart()
                }
                // TODO: test
                player.isInitialized -> {
                    player.prepareMedia(media_, shouldStreamThisTime, startWhenPrepared = true, prepareImmediately = true, forceReset = forcePlaybackReset)
                    sleepManager?.restart()
                }
                else -> {
                    player.setAsCurMedia(media_)
                    player.reinit()
                    sleepManager?.restart()
                }
            }
            forcePlaybackReset = false
        }
        aCtrlFuture?.let { future ->
            if (future.isDone && aController?.isConnected == true) {
                Logd(TAG, "aCtrlFuture aController ready, play, ${player?.status} $shouldStreamThisTime")
                if (shouldStreamThisTime && !isStreamingCapable(media)) return
                processTask()
            } else {
                Logd(TAG, "aCtrlFuture starting PlaybackService")
//                ContextCompat.startForegroundService(getAppContext(), Intent(getAppContext(), PlaybackService::class.java))
                CoroutineScope(Dispatchers.Default).launch {
                    while (!future.isDone || aController?.isConnected != true) {
                        Logd(TAG, "aCtrlFuture delay ${future.isDone} ${aController?.isConnected}")
                        delay(1.seconds)
                    }
                    withContext(Dispatchers.Main) { processTask() }
                }
            }
        } ?: run {
            Logd(TAG, "aCtrlFuture is null, starting service")
            processTask()
        }
    }
}
