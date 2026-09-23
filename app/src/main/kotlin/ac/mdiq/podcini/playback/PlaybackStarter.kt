package ac.mdiq.podcini.playback

import ac.mdiq.podcini.PodciniApp.Companion.appMainScope
import ac.mdiq.podcini.playback.BasePlayer.Companion.isStreamingCapable
import ac.mdiq.podcini.playback.PlaybackService.Companion.isAutoController
import ac.mdiq.podcini.playback.SleepManager.Companion.sleepManager
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.checkAndMarkDuplicates
import ac.mdiq.podcini.storage.database.isMediaDownloadable
import ac.mdiq.podcini.storage.database.prefStreamOverDownload
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.specs.MediaType
import ac.mdiq.podcini.storage.specs.VideoMode
import ac.mdiq.podcini.ui.screens.PSState
import ac.mdiq.podcini.ui.screens.curVideoMode
import ac.mdiq.podcini.ui.screens.psState
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

class PlaybackStarter(private val media: Episode) {
    private val TAG = "PlaybackStarter"

    private var startImmediately = true
    private var shouldStreamThisTime = false
    private var audioOnly = false
    private var repeat = false

    private var widgetId: String = ""

    fun shouldStreamThisTime(shouldStreamThisTime: Boolean?): PlaybackStarter {
        if (shouldStreamThisTime == null) {
            this.shouldStreamThisTime = media.feed == null || media.feedId == null || (!media.downloaded && media.feed?.isLocal != true)
                    || !isMediaDownloadable(media) || (prefStreamOverDownload && media.feed?.prefStreamOverDownload == true)
        } else this.shouldStreamThisTime = shouldStreamThisTime
        return this
    }

    fun setAudioOnly(): PlaybackStarter {
        audioOnly =  true
        return this
    }

    fun setStartNow(start: Boolean): PlaybackStarter {
        startImmediately = start
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
        Logd(TAG) { "start PlaybackService.isRunning: ${PlaybackService.isRunning}" }
//        showStackTrace()
        ensureAController()

        appMainScope.launch {
            var media_ = media
            val player = theatres[playerId].mPlayerFlow.value
            if (forcePlaybackReset) player?.clearFromCache(media.id.toString())
            var sameMedia = !forcePlaybackReset
            if (player?.curMediaFlow?.value?.id != media.id) {
                sameMedia = false
                withContext(Dispatchers.IO) { media_ = checkAndMarkDuplicates(media) }
            //            player.setAsCurEpisode(media_)   // seems redundant
            }

            fun playVideoIfNeeded() {
                Logd("ActionButton") { "playVideoIfNeeded got item ${media_.id}" }
                if (!isAutoController && (media_.forceVideo || (media_.feed?.videoModePolicy != VideoMode.AUDIO_ONLY && appPrefsFlow!!.value.videoPlaybackMode != VideoMode.AUDIO_ONLY.code && curVideoMode != VideoMode.AUDIO_ONLY && media_.mediaType == MediaType.VIDEO))) {
                    player?.playingVideoFlow?.value = true
                    psState = PSState.Expanded
                } else player?.playingVideoFlow?.value = false
            }

            fun processTask() {
                if (player == null) {
                    Loge(TAG, "processTask mPlayerFlow.value == null")
                    return
                }
                Logd(TAG) { "aCtrlFuture: ${aCtrlFuture != null} player status: ${player.status}" }
                player.shouldRepeatFlow.value = repeat
                Logd(TAG) { "start: statusFlow: ${player.status} sameMedia: $sameMedia" }
                player.isStreaming = shouldStreamThisTime
                player.widgetId = widgetId
                when {
                    player.isPlaying -> {
                        player.pause(false)
                        if (!sameMedia) {
                            player.isSkipping = true
                            player.prepareMedia(media_, shouldStreamThisTime, startWhenPrepared = startImmediately, prepareImmediately = true, audioOnly = audioOnly, forceReset = forcePlaybackReset)
                            sleepManager?.restart()
                        }
                    }
                    player.isPaused || player.isPrepared -> {
                        if (sameMedia) player.play()
                        else {
                            player.isSkipping = true
                            player.prepareMedia(media_, shouldStreamThisTime, startWhenPrepared = startImmediately, prepareImmediately = true, audioOnly = audioOnly, forceReset = forcePlaybackReset)
                        }
                        sleepManager?.restart()
                    }
                    player.isStopped -> { //                    ContextCompat.startForegroundService(getAppContext(), Intent(getAppContext(), PlaybackService::class.java))
                        player.prepareMedia(media_, shouldStreamThisTime, startWhenPrepared = startImmediately, prepareImmediately = true, audioOnly = audioOnly, forceReset = forcePlaybackReset)
                        sleepManager?.restart()
                    } // TODO: test
                    player.isInitialized -> {
                        player.prepareMedia(media_, shouldStreamThisTime, startWhenPrepared = startImmediately, prepareImmediately = true, audioOnly = audioOnly, forceReset = forcePlaybackReset)
                        sleepManager?.restart()
                    }
                    else -> {
                        player.setAsCurMedia(media_)
                        player.reprepareMedia()
                        sleepManager?.restart()
                    }
                }
                forcePlaybackReset = false

                playVideoIfNeeded()
            }

            aCtrlFuture?.let { future ->
                if (future.isDone && aController?.isConnected == true) {
                    Logd(TAG) { "aCtrlFuture aController ready, play, ${player?.status} $shouldStreamThisTime" }
                    if (shouldStreamThisTime && !isStreamingCapable(media)) return@launch
                    processTask()
                } else {
                    Logd(TAG) { "aCtrlFuture starting PlaybackService" } //                ContextCompat.startForegroundService(getAppContext(), Intent(getAppContext(), PlaybackService::class.java))
                    CoroutineScope(Dispatchers.Default).launch {
                        while (!future.isDone || aController?.isConnected != true) {
                            Logd(TAG) { "aCtrlFuture delay ${future.isDone} ${aController?.isConnected}" }
                            delay(1.seconds)
                        }
                        withContext(Dispatchers.Main) { processTask() }
                    }
                }
            } ?: run {
                Logd(TAG) { "aCtrlFuture is null, starting service" }
                processTask()
            }
        }
    }
}
