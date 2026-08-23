package ac.mdiq.podcini.playback.base

import ac.mdiq.podcini.PodciniApp.Companion.appMainScope
import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.net.sync.queue.SynchronizationQueueSink
import ac.mdiq.podcini.net.utils.NetworkUtils.isNetworkUrl
import ac.mdiq.podcini.net.utils.NetworkUtils.networkMonitor
import ac.mdiq.podcini.playback.base.InTheatre.actQueue
import ac.mdiq.podcini.playback.base.InTheatre.isCurMedia
import ac.mdiq.podcini.playback.base.SleepManager.Companion.autoEnableFrom
import ac.mdiq.podcini.playback.base.SleepManager.Companion.autoEnableTo
import ac.mdiq.podcini.playback.base.SleepManager.Companion.lastTimerValue
import ac.mdiq.podcini.playback.base.SleepManager.Companion.sleepManager
import ac.mdiq.podcini.playback.service.PlaybackService
import ac.mdiq.podcini.playback.service.PlaybackService.Companion.isAutoController
import ac.mdiq.podcini.playback.service.PlaybackService.Companion.isCasting
import ac.mdiq.podcini.playback.service.PlaybackService.Companion.playbackService
import ac.mdiq.podcini.shared.AudioSpec
import ac.mdiq.podcini.shared.VideoSpec
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.sources.SourceGatewayClient
import ac.mdiq.podcini.sources.clientByEpisode
import ac.mdiq.podcini.storage.database.MonitorEntity
import ac.mdiq.podcini.storage.database.allFeeds
import ac.mdiq.podcini.storage.database.allowForAutoDelete
import ac.mdiq.podcini.storage.database.appAttribsFlow
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.checkAndMarkDuplicates
import ac.mdiq.podcini.storage.database.createSynthetic
import ac.mdiq.podcini.storage.database.curIndexInActQueue
import ac.mdiq.podcini.storage.database.deleteMedia
import ac.mdiq.podcini.storage.database.episodeById
import ac.mdiq.podcini.storage.database.feedsMap
import ac.mdiq.podcini.storage.database.queuesLive
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.removeFromAllQueues
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.sleepPrefs
import ac.mdiq.podcini.storage.database.subscribeEpisode
import ac.mdiq.podcini.storage.database.unsubscribeEpisode
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.CurrentState
import ac.mdiq.podcini.storage.model.CurrentState.Companion.LONG_MINUS_1
import ac.mdiq.podcini.storage.model.CurrentState.Companion.LONG_PLUS_1
import ac.mdiq.podcini.storage.model.CurrentState.Companion.SPEED_USE_GLOBAL
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.Feed.AudioType
import ac.mdiq.podcini.storage.model.Feed.AutoDeleteAction
import ac.mdiq.podcini.storage.model.QueueEntry
import ac.mdiq.podcini.storage.specs.AVQuality
import ac.mdiq.podcini.storage.specs.EpisodeState
import ac.mdiq.podcini.storage.specs.MediaType
import ac.mdiq.podcini.storage.specs.Rating
import ac.mdiq.podcini.storage.specs.VideoMode
import ac.mdiq.podcini.storage.utils.loadChapters
import ac.mdiq.podcini.ui.screens.curVideoMode
import ac.mdiq.podcini.utils.FlowEvent
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.LogeFor
import ac.mdiq.podcini.utils.LogsFor
import ac.mdiq.podcini.utils.Logt
import ac.mdiq.podcini.utils.LogtFor
import ac.mdiq.podcini.utils.showStackTrace
import android.media.MediaCodecList
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

abstract class MediaPlayerBase {
    val context = getAppContext()

    var playerId: Int = -1

    var castPlayer: Player? = null

    private var oldStatus: PlayerStatus? = null

    @get:Synchronized
    var status by mutableStateOf(PlayerStatus.STOPPED)

    var statusSimple by mutableStateOf(PlayerStatusSimple.OTHER)

    var curState: CurrentState = CurrentState()

    val isPlaying: Boolean
        get() = status == PlayerStatus.PLAYING
    val isPaused: Boolean
        get() = status == PlayerStatus.PAUSED
    val isPrepared: Boolean
        get() = status == PlayerStatus.PREPARED
    val isPreparing: Boolean
        get() = status == PlayerStatus.PREPARING
    val isInitialized: Boolean
        get() = status == PlayerStatus.INITIALIZED
    val isStopped: Boolean
        get() = status == PlayerStatus.STOPPED
    val isUnknown: Boolean
        get() = status == PlayerStatus.INDETERMINATE
    val isError: Boolean
        get() = status == PlayerStatus.ERROR

    internal var isSkipping = false

    private var normalSpeed = 1.0f
    var isSpeedForward = false
    var isFallbackSpeed = false

    open val audioTracks: List<String> = listOf()

    private var autoSkippedFeedMediaId: String? = null

    private val startWhenPrepared = atomic(false)
    internal var isStartWhenPrepared: Boolean
        get() = startWhenPrepared.value
        set(s) {
            startWhenPrepared.value = s
        }

    var isStreaming = false
    var playingMuxedVideo = false
    val curLocales = mutableSetOf<String>()

    var widgetId: String = ""

    var audioSpecs: List<AudioSpec> = listOf()
    var videoSpecs: List<VideoSpec> = listOf()
    var muxedSpecs: List<VideoSpec> = listOf()

    private var prevPosition: Int = -1
    private var samePositionCount: Int = 0

    var curSpeed: Float = SPEED_USE_GLOBAL
    var curPBSpeed by mutableFloatStateOf(1f)
    var curPitch: Float = SPEED_USE_GLOBAL

    internal var prevMedia: Episode? = null
    var curEpisode by mutableStateOf<Episode?>(null)
    var currentMediaType: MediaType? = MediaType.UNKNOWN

    var playingVideo by mutableStateOf(false)

    var curClient: SourceGatewayClient? = null

//    internal var videoSize: Pair<Int, Int>? = null
//    open val videoWidth: Int = 0
//    open val videoHeight: Int = 0

    var skipSilence: Boolean? = null
    var bitrate by mutableIntStateOf(0)
    var resolution by mutableStateOf("")
    var mimeType by mutableStateOf("")
    var channelCount by mutableIntStateOf(0)
    var sampleRate by mutableIntStateOf(0)

    var shouldRepeat by mutableStateOf(false)

    val isPlayingVideoLocally: Boolean
        get() = when {
            isCasting -> false
            playbackService != null -> currentMediaType == MediaType.VIDEO
            else -> curEpisode?.mediaType == MediaType.VIDEO
        }

    init {
        status = PlayerStatus.STOPPED
    }

    fun prefSpeedOf(media: Episode?): Pair<Float, Float> {
        var speed = SPEED_USE_GLOBAL
        if (media != null) {
            speed = curSpeed
            if (speed == SPEED_USE_GLOBAL && media.feedId != null && feedsMap.containsKey(media.feedId!!)) speed = feedsMap[media.feedId!!]!!.playSpeed
        }
        if (speed == SPEED_USE_GLOBAL) speed = appPrefsFlow!!.value.playbackSpeed

        var pitch = SPEED_USE_GLOBAL
        if (media != null) {
            pitch = curPitch
            if (pitch == SPEED_USE_GLOBAL && media.feedId != null && feedsMap.containsKey(media.feedId!!)) pitch = feedsMap[media.feedId!!]!!.playPitch
        }
        if (pitch == SPEED_USE_GLOBAL) pitch = appPrefsFlow!!.value.playbackPitch
        return Pair(speed, pitch)
    }

    fun toggleFallbackSpeed(speed: Float) {
        if (isSpeedForward) return
        if (isPlaying) {
            if (!isFallbackSpeed) {
                normalSpeed = getPlaybackSpeed()
                setPlaybackParams(speed)
            } else setPlaybackParams(normalSpeed)
            isFallbackSpeed = !isFallbackSpeed
        }
    }

    fun speedForward(speed: Float) {
        if (isFallbackSpeed) return
        if (!isSpeedForward) {
            normalSpeed = getPlaybackSpeed()
            setPlaybackParams(speed)
        } else setPlaybackParams(normalSpeed)
        isSpeedForward = !isSpeedForward
    }

    fun setAsCurEpisode(episode: Episode?) {
        Logd(TAG, "setAsCurEpisode episode: ${episode?.title}")
        //        showStackTrace()
        if (episode != null && episode.id == curEpisode?.id) return
        if (curEpisode != null) unsubscribeEpisode(curEpisode!!, TAG)
        val episode_ = if (episode != null) {
            val e = episodeById(episode.id)
            if (e == null) {
                val name = "Remote history"
                val f = allFeeds.firstOrNull { it.title == name } ?: upsertBlk(createSynthetic(0, name, true)) {}
                Logd(TAG, "adding to feed Remote history ${f.id} ${episode.id} ${episode.title}")
                episode.feedId = f.id
                upsertBlk(episode) { }
            } else e
        } else null
        when {
            episode_ != null -> {
                bitrate = 0
                resolution = ""
                curEpisode = episode_
                curClient = clientByEpisode(curEpisode!!)
                setAudioStream()
                useVCodex = null
                useResolution = null
                playingVideo = (episode_.forceVideo || (episode_.feed?.videoModePolicy != VideoMode.AUDIO_ONLY && appPrefsFlow!!.value.videoPlaybackMode != VideoMode.AUDIO_ONLY.code && curVideoMode != VideoMode.AUDIO_ONLY && episode_.mediaType == MediaType.VIDEO))
                skipSilence = null
                shouldRepeat = false
                curSpeed = SPEED_USE_GLOBAL
                Logd(TAG, "setAsCurEpisode start monitoring curEpisode ${curEpisode?.title}")
                runOnIOScope {
                    subscribeEpisode(curEpisode!!, MonitorEntity(TAG, onInit = { },
                        onChanges = { e, f ->
                            if (e.id == curEpisode?.id) {
                                curEpisode = e
                                Logd(TAG, "setAsCurEpisode updating curEpisode [${curEpisode?.title}] ${f.joinToString()}")
                            }
                        }
                    ))
                    if (!actQueue.contains(curEpisode!!)) {
                        val qes = realm.query(QueueEntry::class).query("episodeId == ${curEpisode!!.id}").find()
                        if (qes.isNotEmpty()) {
                            val q = queuesLive.find { it.id == qes[0].queueId }
                            if (q != null) actQueue = q
                        }
                    }
                }
            }
            else -> {
                curEpisode = null
                savePlayerStatus(null, null)
            }
        }
    }

    fun savePlayerStatus(episode: Episode?, playerStatus: PlayerStatus?) {
        Logd(TAG, "savePlayerStatus episode ${episode?.id}")
        runOnIOScope {
            when {
                episode == null && playerStatus != null -> statusSimple = playerStatus.toStatusInt()
                episode == null || playerStatus == null -> {
                    statusSimple = PlayerStatusSimple.OTHER
                    upsert(curState) {
                        it.curMediaType = LONG_MINUS_1
                        it.curFeedId = LONG_MINUS_1
                        it.curMediaId = LONG_MINUS_1
                    }
                }
                else -> {
                    statusSimple = playerStatus.toStatusInt()
                    upsert(curState) {
                        it.curMediaType = LONG_PLUS_1
                        it.curIsVideo = episode.mediaType == MediaType.VIDEO
                        val feedId = episode.feed?.id
                        if (feedId != null) it.curFeedId = feedId
                        it.curMediaId = episode.id
                    }
                }
            }
        }
    }

    private fun getNextInQueue(): Episode? {
        Logd(TAG, "getNextInQueue called curEpisode: ${curEpisode?.getEpisodeTitle()}")
        if (!actQueue.playInSequence) {
            Logd(TAG, "getNextInQueue(), but follow queue is not enabled.")
            savePlayerStatus(null, null)
            return null
        }
        val qes = actQueue.entries
        if (qes.isEmpty()) {
            Logd(TAG, "getNextInQueue queue is empty")
            savePlayerStatus(null, null)
            return null
        }
        var curIndex = qes.indexOfFirst { isCurMedia(it.episodeId) }
        if (curIndex < 0 && curIndexInActQueue >= 0) {
            curIndex = curIndexInActQueue
            curIndexInActQueue = -1
        }
        Logd(TAG, "getNextInQueue curIndexInQueue: $curIndex ${qes.size}")
        val nextQE = if (curIndex >= 0 && curIndex < qes.size) {
            when {
                !isCurMedia(qes[curIndex].episodeId) -> qes[curIndex]
                qes.size == 1 -> return null
                else -> {
                    var j = if (curIndex < qes.size - 1) curIndex + 1 else 0
                    val start = j
                    while (isCurMedia(qes[j].episodeId)) {
                        j = if (j < qes.size - 1) j + 1 else 0
                        if (j == start) break
                    }
                    qes[j]
                }
            }
        } else qes[0]
        if (isCurMedia(nextQE.episodeId)) return null
        var nextItem = episodeById(nextQE.episodeId) ?: return null
        Logd(TAG, "getNextInQueue nextItem ${nextItem.title}")
        nextItem = checkAndMarkDuplicates(nextItem)
        return nextItem
    }

    fun startPlaying(media_: Episode? = null) {
        Logd(TAG, "startPlaying called")
        if (curEpisode == null && media_ == null) {
            Logt(TAG, "startPlaying: No media to play")
            return
        }
        val media = media_ ?: curEpisode!!
        val needStreaming = media.feed?.isLocal != true && media.fileUrl.isNullOrBlank()
        if (needStreaming && !isStreamingCapable(media)) return
        prepareMedia(playable = media, streaming = needStreaming, startWhenPrepared = true, prepareImmediately = true, forceReset = true, doPostPlayback = false)
    }

    fun onSleepTimerUpdate(event: FlowEvent.SleepTimerUpdatedEvent) {
        when {
            event.isOver -> {
                Logd(TAG, "sleep timer is over")
                pause(reinit = false)
                setVolume(1.0f, 1.0f)
            }
            event.getTimeLeft() < SleepManager.SLEEP_TIMER_ENDING_THRESHOLD -> {
                val multiplicators = floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f, 0.3f, 0.3f, 0.4f, 0.4f, 0.5f, 0.5f, 0.6f, 0.6f, 0.7f, 0.7f, 0.8f, 0.8f, 0.9f, 0.9f)
                val multiplicator = multiplicators[min(multiplicators.size - 1, (event.getTimeLeft().toInt() / 1000))]
                Logd(TAG, "onSleepTimerAlmostExpired: $multiplicator")
                setVolume(multiplicator, multiplicator)
            }
            event.isCancelled -> setVolume(1.0f, 1.0f)
        }
    }

    fun onBufferUpdate(event: FlowEvent.BufferUpdateEvent) {
        if (event.episode.id != curEpisode?.id) return
        if (event.hasEnded() && curEpisode != null && curEpisode!!.duration <= 0 && getDuration() > 0) upsertBlk(curEpisode!!) { it.duration = getDuration() }
    }

    fun onEpisodeMediaEvent(event: FlowEvent.EpisodeMediaEvent) {
        if (event.action == FlowEvent.EpisodeMediaEvent.Action.REMOVED) {
            for (e in event.episodes) {
                if (e.id == curEpisode?.id) {
                    setAsCurEpisode(e)  // TODO: seems having no effect
                    endPlayback(hasEnded = false, wasSkipped = true)
                    break
                }
            }
        }
    }

    private var positionSaverJob: Job? = null

    private var positionSaverInterval: Long = MIN_POSITION_SAVER_INTERVAL.toLong()

    protected fun resetPosSaverInterval(speed: Float) {
        Logd(TAG, "resetPosSaverInterval curEpisode: ${curEpisode?.title}")
        curEpisode?.apply {
            Logd(TAG, "resetPosSaverInterval speed: $speed duration: ${this.duration} ${(0.02 * this.duration / speed).toInt()}")
            positionSaverInterval = (if (appPrefsFlow!!.value.useAdaptiveProgressUpdate) max(MIN_POSITION_SAVER_INTERVAL, (0.02 * this.duration / speed).toInt()) else MIN_POSITION_SAVER_INTERVAL).toLong()
        }
    }

    @Synchronized
    private fun startPositionSaver() {
        cancelPositionSaver()
        positionSaverJob = appMainScope.launch {
            while (isActive) {
                delay(positionSaverInterval.milliseconds)
                val position = getPosition()
                Logd(TAG, "positionSaverTick positionSaverInterval: $positionSaverInterval currentPosition: $position $prevPosition")
                if (position != prevPosition) {
                    // skip ending
                    val duration = getDuration()
                    val remainingTime = duration - position
                    val item = curEpisode ?: continue
                    val skipEnd = item.feed?.endingSkip ?: 0
                    val skipEndMS = skipEnd * 1000
                    //                  Logd(TAG, "skipEndingIfNecessary: checking " + remainingTime + " " + skipEndMS + " speed " + currentPlaybackSpeed)
                    if (skipEnd > 0 && skipEndMS < duration && (remainingTime - skipEndMS < 0)) {
                        Logd(TAG, "skipEndingIfNecessary: Skipping the remaining $remainingTime $skipEndMS")
                        Logt(TAG, getAppContext().getString(R.string.pref_feed_skip_ending_toast, skipEnd))
                        autoSkippedFeedMediaId = item.identifyingValue
                        skip()
                    }
                    persistCurrentPosition(false, curEpisode, position)
                    prevPosition = position
                    samePositionCount = 0
                } else {
                    samePositionCount++
                    if (samePositionCount > 10) pause(false)
                }
                invokeBufferListener()
            }
        }
        Logd(TAG, "Started PositionSaver with interval: $positionSaverInterval")
    }

    @Synchronized
    private fun cancelPositionSaver() {
        Logd(TAG, "canelling PositionSaver")
        positionSaverJob?.cancel()
        positionSaverJob = null
    }

    abstract fun getPlaybackSpeed(): Float

    abstract fun fixDuration()

    fun getDuration(): Int = curEpisode?.duration ?: Episode.INVALID_TIME

    abstract fun getPlayerPosition(): Int

    fun getPosition(): Int {
        //        showStackTrace()
        if (castPlayer?.isPlaying == true && !status.isAtLeast(PlayerStatus.PREPARED)) Logt(TAG, "exoPlayer playbackState ${castPlayer?.playbackState} player status $status")
        var retVal = getPlayerPosition()
        if (retVal <= 0 && curEpisode != null) retVal = curEpisode!!.position
        return retVal
    }

    open suspend fun invokeBufferListener() {}

    open fun getSelectedAudioTrack(): Int = -1

    open fun resetMediaPlayer() {}

    open fun createNativePlayer() {}

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    protected abstract fun prepareDataSource(sameMedia: Boolean = false)

    protected abstract fun prepareDataSource(mediaUrl: String, user: String?, password: String?)

    protected abstract fun setCastPlayImmediately()

    var dataSourceJob: Job? = null

    fun prepareMedia(playable: Episode, streaming: Boolean, startWhenPrepared: Boolean, prepareImmediately: Boolean, forceReset: Boolean = false, doPostPlayback: Boolean = true) {
        Logd(TAG, "prepareMedia status=$status stream=$streaming startWhenPrepared=$startWhenPrepared prepareImmediately=$prepareImmediately forceReset=$forceReset ${playable.getEpisodeTitle()} ")
//        showStackTrace()
        if (!forceReset && playable.id == prevMedia?.id && isPlaying) {
            Logd(TAG, "prepareMedia Method call was ignored: media file already playing.")
            return
        }
        dataSourceJob?.cancel()
        if (curEpisode != null && curEpisode?.id != playable.id) {
            prevMedia = curEpisode
            if (doPostPlayback) {
                Logd(TAG, "prepareMedia: curEpisode exist status=$status")
                Logd(TAG, "prepareMedia starts new playable:${playable.id} curEpisode:${curEpisode!!.id} prevMedia:${prevMedia?.id}")
                // set temporarily to pause in order to update list with current position
//                if (isPlaying || isPaused)
                onPlaybackPause(curEpisode, curEpisode?.position ?: -1)
                // stop playback of this episode
//                if (isPaused || isPlaying || isPrepared) castPlayer?.stop()
                if (curEpisode?.id != playable.id) onPostPlayback(curEpisode!!, ended = false, skipped = true, true)
                setPlayerStatus(PlayerStatus.INDETERMINATE, null)
            }
        }

        if (isCasting) setCastPlayImmediately()
        Logd(TAG, "prepareMedia preparing for playable:${playable.id} ${playable.getEpisodeTitle()}")
        if (playable.playState < EpisodeState.PROGRESS.code) runOnIOScope { upsert(playable) { it.setPlayState(EpisodeState.PROGRESS) } }
        val sameMedia = playable.id == curEpisode?.id
        setAsCurEpisode(playable)
        if (forceReset) {
            curEpisode = playable
            if (sameMedia) curClient = clientByEpisode(curEpisode!!)
        }
        Logd(TAG, "prepareMedia media.forceVideo: ${curEpisode?.forceVideo}")
        this.isStreaming = streaming
        if (curEpisode != null) currentMediaType = curEpisode!!.mediaType
//        videoSize = null
        resetMediaPlayer()

        isStartWhenPrepared = startWhenPrepared
        prefSpeedOf(curEpisode).let { (sp, pi)-> setPlaybackParams(sp, pi) }
        setRepeat(shouldRepeat)
        setSkipSilence()
        dataSourceJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                when {
                    streaming -> {
                        Logd(TAG, "prepareMedia streamurl: ${curEpisode?.downloadUrl}")
                        if (!curEpisode?.downloadUrl.isNullOrBlank()) prepareDataSource(sameMedia)
                        else throw IOException("episode downloadUrl is null or empty ${curEpisode?.title}")
                    }
                    else -> {
                        Logd(TAG, "prepareMedia localMediaurl: ${curEpisode?.fileUrl}")
                        if (!curEpisode?.fileUrl.isNullOrBlank()) prepareDataSource(curEpisode!!.fileUrl!!, null, null)
                        else throw IOException("Unable to read local file ${curEpisode?.fileUrl}")
                    }
                }
                withContext(Dispatchers.Main) {
                    if (!isAutoController) setPlayerStatus(PlayerStatus.INITIALIZED, curEpisode)
                    if (prepareImmediately) prepare()
                }
            } catch (e: IOException) {
                LogsFor(TAG, curEpisode?.id, e, "prepareMedia failed ${e.localizedMessage ?: ""}")
                withContext(Dispatchers.Main) { setPlayerStatus(PlayerStatus.ERROR, curEpisode) }
            } catch (e: IllegalStateException) {
                LogsFor(TAG, curEpisode?.id, e, "prepareMedia failed ${e.localizedMessage ?: ""}")
                withContext(Dispatchers.Main) { setPlayerStatus(PlayerStatus.ERROR, curEpisode) }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { setPlayerStatus(PlayerStatus.ERROR, curEpisode) }
                LogsFor(TAG, curEpisode?.id, e, "setDataSource error: [${e.localizedMessage}]")
            } finally { }
        }
    }

    open fun shouldSetSource(): Boolean = true

    fun playPause() {
        Logd(TAG, "playPause status: $status")
        when {
            isPlaying -> pause(reinit = false)
            isPaused || isPrepared -> play()
            isPreparing -> isStartWhenPrepared = !isStartWhenPrepared
            isInitialized -> {
                isStartWhenPrepared = true
                prepare()
            }
            else -> Loge(TAG, "Play/Pause button was pressed and PlaybackService state was unknown: $status")
        }
    }

    fun play() {
        Logd(TAG, "play(): status: $status playbackState: ${castPlayer?.playbackState}")
        if (isPaused || isPrepared) {
            Logd(TAG, "play() Resuming/Starting playback")
            if (shouldSetSource()) setSource()
            val volAdpFac = if (curEpisode != null) curEpisode!!.feed?.volumeAdaptionSetting?.adaptionFactor ?: 1f else 1f
            setVolume(1.0f, 1.0f, volAdpFac)
            Logd(TAG, "play(): position: ${curEpisode?.position}")
            castPlayer?.play()
            setPlaybackParams()
            setPlayerStatus(PlayerStatus.PLAYING, curEpisode)
            sleepManager?.restart()
        } else Logd(TAG, "Call to play() was ignored because current state of PSMP object is $status")
    }

    fun pause(reinit: Boolean) {
        if (isPlaying || isError) {
            Logd(TAG, "Pausing playback $reinit")
            castPlayer?.pause()
            val pos = getPosition()
            setPlayerStatus(PlayerStatus.PAUSED, curEpisode, pos)
            if (isStreaming && reinit) reinit()
            cancelPositionSaver()
            isSpeedForward = false
            isFallbackSpeed = false
//            if (curEpisode != null) upsertBlk(curEpisode!!) { it.forceVideo = false }
        } else Logd(TAG, "Ignoring call to pause: Player is in $status state")
    }

    internal abstract fun setSource()

    internal fun prepare() {
        Logd(TAG, "prepare Preparing media player: status: $status isStartWhenPrepared: $isStartWhenPrepared")
        if (isInitialized) {
            setPlayerStatus(PlayerStatus.PREPARING, curEpisode)
            setSource()
//            if (mediaType == MediaType.VIDEO) videoSize = Pair(videoWidth, videoHeight)
            if (curEpisode != null && curEpisode!!.duration <= 0) fixDuration()
            setPlayerStatus(PlayerStatus.PREPARED, curEpisode)
            if (isStartWhenPrepared) play()
        } else Logt(TAG, "prepare() call ignored with status: $status")
    }

    fun reinit() {
        Logd(TAG, "reinit() called")
        when {
            curEpisode != null -> prepareMedia(playable = curEpisode!!, streaming = isStreaming, startWhenPrepared = isStartWhenPrepared, prepareImmediately = false, forceReset = true, doPostPlayback = true)
            else -> Logd(TAG, "Call to reinit: media and mediaPlayer were null, ignored")
        }
    }

    fun seekTo(t_: Int) {
        var t = t_
        if (t < 0) t = 0
        Logd(TAG, "seekTo() called $t status: $status")
        when {
            isPlaying || isPaused || isPrepared -> {
                Logd(TAG, "seekTo t: $t status: $status")
                castPlayer?.seekTo(t.toLong())
                if (curEpisode != null) upsertBlk(curEpisode!!) { it.position = t }
            }
            isInitialized -> {
                if (curEpisode != null) upsertBlk(curEpisode!!) { it.position = t }
                isStartWhenPrepared = false
                prepare()
            }
            else -> {}
        }
    }

    fun seekDelta(delta: Int) {
        val curPosition = getPosition()
        if (curPosition != Episode.INVALID_TIME) seekTo(curPosition + delta)
        else LogeFor(TAG, curEpisode?.id, "seekDelta getPosition() returned INVALID_TIME in seekDelta")
    }

    abstract fun setPlaybackParams()

    abstract fun setPlaybackParams(speed: Float, pitch: Float = 0f)

    open fun setSkipSilence() {}

    fun setRepeat(repeat: Boolean) {
        castPlayer?.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    abstract fun setVolume(volumeLeft: Float, volumeRight: Float, adaptionFactor: Float = 1.0f)

    internal abstract fun playChime()

    internal abstract fun notifyWidget()

    internal fun endPlayback(hasEnded: Boolean, wasSkipped: Boolean, shouldContinue: Boolean = true) {
        showStackTrace()
        if (curEpisode == null) {
            Logd(TAG, "endPlayback curEpisode is null, return")
            return
        }
        // we're relying on the position stored in the EpisodeMedia object for post-playback processing
        val position = getPosition()
        if (position >= 0) upsertBlk(curEpisode!!) { it.position = position }
        Logd(TAG, "endPlayback hasEnded=$hasEnded wasSkipped=$wasSkipped shouldContinue=$shouldContinue ${curEpisode?.title}")

        fun stopPlayer() {
            Logd(TAG, "endPlayback stopPlayer is called")
            curSpeed = SPEED_USE_GLOBAL
            cancelPositionSaver()
            setAsCurEpisode(null)
            castPlayer?.stop()
            if (isUnknown) setPlayerStatus(PlayerStatus.STOPPED, null)
//            else Logd(TAG, "endPlayback Ignored call to stop: Current player state is: $status")
        }

        val currentMedia = curEpisode
        when {
            shouldContinue -> {
                // Load next episode if previous episode was in the queue and if there is an episode in the queue left.
                // Start playback immediately if continuous playback is enabled
                val nextMedia = getNextInQueue()
                if (nextMedia == null) {
                    if (currentMedia != null) onPostPlayback(currentMedia, hasEnded, wasSkipped, false)
                    stopPlayer()
                } else {
                    Logd(TAG, "endPlayback has nextMedia. status: $status ${nextMedia.title}")
                    val wasPlayng = isPlaying
                    if (!isCasting) pause(false)
                    if (wasSkipped) setPlayerStatus(PlayerStatus.INDETERMINATE, null)
                    curSpeed = SPEED_USE_GLOBAL
                    cancelPositionSaver()
                    Logd(TAG, "endPlayback useRingTone: ${appPrefsFlow!!.value.useRingTone} ringToneUriString: ${appPrefsFlow!!.value.ringToneUriString}")
                    if (appPrefsFlow!!.value.useRingTone && !appPrefsFlow!!.value.ringToneUriString.isNullOrBlank() && (nextMedia.feed?.audioType != AudioType.MUSIC.code || !appPrefsFlow!!.value.disableRingToneOnMusic)) playChime()

                    val needStreaming = (nextMedia.feed?.isLocal != true && nextMedia.fileUrl.isNullOrBlank())
                    if (needStreaming) {
                        if (!isStreamingCapable(nextMedia)) {
                            if (currentMedia != null) onPostPlayback(currentMedia, hasEnded, wasSkipped, false)
                            return
                        }
                    }
                    prepareMedia(playable = nextMedia, streaming = needStreaming, startWhenPrepared = wasPlayng, prepareImmediately = wasPlayng)
                    if (widgetId.isNotEmpty()) notifyWidget()
                }
            }

            isPlaying -> {
                // TODO: likely not reached?
                Logd(TAG, "endPlayback isPlaying")
                onPlaybackPause(currentMedia, currentMedia?.position ?: 0)
            }

            else -> {
                Logd(TAG, "endPlayback else")
                if (currentMedia != null) onPostPlayback(currentMedia, hasEnded, wasSkipped, false)
                stopPlayer()
            }
        }
    }

    abstract fun shutdown()

    open fun setAudioTrack(track: Int) {}

    fun skip() {
//        in first second of playback, ignoring skip
        if (getPosition() < 1000) return
        isSkipping = true
        endPlayback(hasEnded = false, wasSkipped = !shouldRepeat)
    }

    protected fun positionWithRewind(currentPosition: Int, lastPlayedTime: Long): Int {
        if (currentPosition > 0 && lastPlayedTime > 0) {
            val elapsedTime = nowInMillis() - lastPlayedTime
            val rewindTime: Long = when {
                elapsedTime > 1.days.inWholeMilliseconds -> 20.seconds.inWholeMilliseconds
                elapsedTime > 1.hours.inWholeMilliseconds -> 10.seconds.inWholeMilliseconds
                elapsedTime > 1.minutes.inWholeMilliseconds -> 3.seconds.inWholeMilliseconds
                else -> 0L
            }
            val newPosition = currentPosition - rewindTime.toInt()
            return max(newPosition, 0)
        } else return currentPosition
    }

    private fun onPlaybackStart(playable: Episode, position: Int) {
        Logd(TAG, "onPlaybackStart ${playable.title}")
        Logd(TAG, "onPlaybackStart position: $position delayInterval: $positionSaverInterval")
        if (position != Episode.INVALID_TIME) {
            upsertBlk(playable) {
                it.position = position
                it.setPlaybackStart()
            }
        } else {
            // skip intro
            val feed = playable.feed
            val skipIntro = feed?.introSkip ?: 0
            val skipIntroMS = skipIntro * 1000
            if (skipIntro > 0 && playable.position < skipIntroMS) {
                val duration = getDuration()
                if (duration !in 1..skipIntroMS) {
                    Logd(TAG, "onPlaybackStart skipIntro ${playable.getEpisodeTitle()}")
                    seekTo(skipIntroMS)
                    LogtFor(TAG, curEpisode?.id, context.getString(R.string.pref_feed_skip_intro_toast, skipIntro))
                }
            }
            upsertBlk(playable) { it.setPlaybackStart() }
        }
        startPositionSaver()
    }

    protected fun onPlaybackPause(playable: Episode?, position: Int) {
        Logd(TAG, "onPlaybackPause $position ${playable?.title}")
        cancelPositionSaver()
        persistCurrentPosition(position == Episode.INVALID_TIME || playable == null, playable, position)
        Logd(TAG, "onPlaybackPause start ${playable?.timeSpent}")
        if (playable != null) SynchronizationQueueSink.enqueueEpisodePlayedIfSyncActive(playable, false)
    }

    private fun onPostPlayback(playable: Episode, ended: Boolean, skipped: Boolean, playingNext: Boolean) {
        Logd(
            TAG,
            "onPostPlayback(): ended=$ended skipped=$skipped playingNext=$playingNext media=${playable.getEpisodeTitle()} "
        )
        var item = playable
        val smartMarkAsPlayed = playable.hasAlmostEnded()
        if (!ended && smartMarkAsPlayed) Logd(TAG, "smart mark as played")

        var autoSkipped = false
        if (autoSkippedFeedMediaId != null && autoSkippedFeedMediaId == item.identifyingValue) {
            autoSkippedFeedMediaId = null
            autoSkipped = true
        }
        val completed = ended || smartMarkAsPlayed
        SynchronizationQueueSink.enqueueEpisodePlayedIfSyncActive(playable, completed)

        fun shouldSetPlayed(e: Episode): Boolean {
            return when (e.playState) {
                EpisodeState.FOREVER.code, EpisodeState.PLAYED.code -> false
                EpisodeState.AGAIN.code -> nowInMillis() - e.playStateSetTime >= e.duration
                else -> true
            }
        }
        runOnIOScope {
            if (ended || smartMarkAsPlayed || autoSkipped || (skipped && !appPrefsFlow!!.value.skipKeepsEpisode)) {
                Logd(TAG, "onPostPlayback ended: $ended smartMarkAsPlayed: $smartMarkAsPlayed autoSkipped: $autoSkipped skipped: $skipped")
                // only mark the item as played if we're not keeping it anyway
                item = upsert(item) {
                    if (it.playState == EpisodeState.FOREVER.code) it.repeatTime = it.repeatInterval + nowInMillis()
                    if (shouldSetPlayed(it)) it.setPlayState(EpisodeState.PLAYED)
                    upsertDB(it, item.position)
                    it.startTime = 0
                    it.startPosition = if (completed) -1 else it.position
                    if (ended || (skipped && smartMarkAsPlayed)) it.position = 0
                    if (ended || skipped || playingNext) it.playbackCompletionTime = nowInMillis()
                }
                val action = item.feed?.autoDeleteAction
                val shouldAutoDelete = (action == AutoDeleteAction.ALWAYS || (action == AutoDeleteAction.GLOBAL && item.feed != null && allowForAutoDelete(item.feed!!)))
                val isItemdeletable = (!appPrefsFlow!!.value.favoriteKeepsEpisode || (item.rating < Rating.GOOD.code && item.playState != EpisodeState.AGAIN.code && item.playState != EpisodeState.FOREVER.code))
                if (shouldAutoDelete && isItemdeletable) {
                    if (!item.fileUrl.isNullOrBlank()) item = deleteMedia(item)
                    if (appPrefsFlow!!.value.deleteRemovesFromQueue) removeFromAllQueues(listOf(item))
                } else if (appPrefsFlow!!.value.removeFromQueueMarkPlayed) removeFromAllQueues(listOf(item))
            }
        }
    }

    private fun persistCurrentPosition(fromMediaPlayer: Boolean, playable_: Episode?, position_: Int) {
        var playable = if (curEpisode != null && playable_?.id == curEpisode?.id) curEpisode else playable_
        var position = position_
        val duration_: Int
        if (fromMediaPlayer) {
//            position = (media3Controller?.currentPosition ?: 0).toInt() // testing the controller
            position = getPosition()
            duration_ = getDuration()
            playable = curEpisode
        } else duration_ = playable?.duration ?: Episode.INVALID_TIME

        if (position != Episode.INVALID_TIME && duration_ != Episode.INVALID_TIME && playable != null) {
            Logd(TAG, "persistCurrentPosition to position: $position duration: $duration_ ${playable.getEpisodeTitle()}")
            upsertBlk(playable) { upsertDB(it, position) }
            prevPosition = position
        }
//        val cache = getCache()
//        Logd(TAG, "persistCurrentPosition cache keys=${cache.keys}")
//        Logd(TAG, "persistCurrentPosition cache space=${cache.cacheSpace}")
//        for (key in cache.keys) Logd(TAG, "persistCurrentPosition key=$key spans=${cache.getCachedSpans(key)}")
    }

    private fun upsertDB(it: Episode, position: Int) {
        it.position = position
        if (position > it.duration) it.duration = position
        if (it.startPosition >= 0 && it.position > it.startPosition) it.playedDuration = (it.playedDurationWhenStarted + it.position - it.startPosition)
        if (it.startTime > 0) {
            var delta = nowInMillis() - it.startTime
            if (delta > 3 * max(it.playedDuration, 60000)) {
                it.startTime = nowInMillis()
                delta = 0L
            }
            it.timeSpent = it.timeSpentOnStart + delta
        }
        it.lastPlayedTime = nowInMillis()
        if (it.playState == EpisodeState.NEW.code) it.setPlayState(EpisodeState.UNPLAYED)
        Logd(TAG, "upsertDB ${it.startTime} timeSpent: ${it.timeSpent} playedDuration: ${it.playedDuration}")
    }

    // TODO: this routine can be very problematic!!!
    @Synchronized
    protected fun setPlayerStatus(newStatus: PlayerStatus, media: Episode?, position: Int = Episode.INVALID_TIME) {
        Logd(TAG, "setPlayerStatus: Setting player status from $status to $newStatus ${media?.id} == ${prevMedia?.id}")
        if (status == newStatus && media != null && media.id == prevMedia?.id) return
//        showStackTrace()
        oldStatus = status
        status = newStatus
        if (media != null) {
            if (!isUnknown) {
                val position_ = if (position == Episode.INVALID_TIME) media.position else position
                when {
                    oldStatus == PlayerStatus.PLAYING && !isPlaying && media.id == prevMedia?.id -> onPlaybackPause(media, position_)
                    oldStatus != PlayerStatus.PLAYING && isPlaying -> onPlaybackStart(media, position_)
                    else -> Logd(TAG, "setPlayerStatus case else, isPlaying: $isPlaying ${media.id == prevMedia?.id} not handled")
                }
            }
        }

//        currentMediaType = mediaType
        Logd(TAG, "setPlayerStatus $status")
        when {
            isInitialized -> savePlayerStatus(curEpisode, status)
            isPrepared -> {
                savePlayerStatus(curEpisode, status)
                if (curEpisode != null) runOnIOScope {
                    try { loadChapters(curEpisode!!, false) } catch (e: Throwable) { LogsFor(TAG, curEpisode?.id, e, "Error loading chapters for: ${curEpisode?.title}") }
                }
            }
            isPaused -> savePlayerStatus(null, status)
            isStopped -> {}
            isPlaying -> {
                savePlayerStatus(null, status)
                // set sleep timer if auto-enabled
                fun isInTimeRange(from: Int, to: Int, current: Int): Boolean {
                    return when {
                        from < to -> current in from..<to
                        from <= current -> true
                        else -> current < to
                    }
                }
                var autoEnableByTime = true
                val fromSetting = autoEnableFrom
                val toSetting = autoEnableTo
                if (fromSetting != toSetting) autoEnableByTime = isInTimeRange(fromSetting, toSetting, Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour)
                if (oldStatus != null && sleepPrefs.AutoEnable && autoEnableByTime && sleepManager?.isActive != true) {
                    sleepManager?.setTimer(lastTimerValue.minutes.inWholeMilliseconds)
                    // TODO: what to do?
//                    EventFlow.postEvent(FlowEvent.MessageEvent(context.getString(R.string.sleep_timer_enabled_label), { sleepManager?.disableSleepTimer() }, context.getString(R.string.undo)))
                }
            }
            isError -> {
                savePlayerStatus(null, null)
                pause(reinit = false)
            }
            else -> {}
        }
        notifySystem()
    }

    var useLocales: Set<String> = setOf()
    var useLocale: String? = null
    var useCodex: String = "Any"
    var useABPS: Int = 0

    var useVCodex: String? = null
    var useResolution: String? = null

    fun setAudioStream(locale: String? = null, codec: String = "Any", aveBitrate: Int = 0) {
        Logd(TAG, "setAudioStream: locale: $locale codec: $codec averageBitrate: $aveBitrate")
        useLocale = locale
        useCodex = codec
        useABPS = aveBitrate
    }

    internal fun setAudioSpec(audioSpecs: List<AudioSpec>, media: Episode): AudioSpec? {
        val asl = mutableListOf<AudioSpec>()
//        Logd(TAG, "useLocale: $useLocale useCodex: $useCodex useABPS: $useABPS audioIndex: $audioIndex")
        Logd(TAG, "setAudioSpec media.feed?.preferredLnaguages: [${media.feed?.preferredLnaguages?.joinToString()}]")
        Logd(TAG, "setAudioSpec appAttribs.langsPreferred: [${appAttribsFlow!!.value.langsPreferred.joinToString()}]")
        useLocales = media.feed?.preferredLnaguages?.ifEmpty { appAttribsFlow!!.value.langsPreferred }?.ifEmpty { setOf("en-US", "en-GB", "en") } ?: setOf("en-US", "en-GB", "en")
        Logd(TAG, "setAudioSpec useLocales: ${useLocales.joinToString()}")
        curLocales.clear()
        for (s in audioSpecs) {
            Logd(TAG, "setAudioSpec s.audioLocale [${s.audioLocale}] ${s.codec} ${s.averageBitrate}")
            curLocales.add(s.audioLocale ?: "")
            if ((useCodex == "Any" || s.codec == useCodex) && (useABPS == 0 || s.averageBitrate == useABPS)) {
                when {
                    s.audioLocale == null -> asl.add(s)
                    useLocale != null -> if (s.audioLocale == useLocale) asl.add(s)
                    s.audioLocale in useLocales -> asl.add(s)
                }
            }
        }
        Logd(TAG, "setAudioSpec langset: [${curLocales.joinToString()}]")
        if (curLocales.isNotEmpty()) {
            runOnIOScope {
                if (media.feed != null && !media.feed!!.langSet.containsAll(curLocales)) upsert(media.feed!!) { it.langSet.addAll(curLocales) }
                if (!appAttribsFlow!!.value.langSet.containsAll(curLocales)) upsertBlk(appAttribsFlow!!.value) { it.langSet.addAll(curLocales) }
            }
        }
        Logd(TAG, "setAudioSpec asl: ${asl.size}")
        if (asl.isEmpty()) {
            Loge(TAG, "setAudioSpec: eligible audio stream list is empty.\nAvailable languages: ${curLocales.joinToString()}.\nYou prefer: ${useLocales.joinToString()}")
            bitrate = 0
            resolution = ""
            return null
        }

        if (useABPS > 0) {
            val audioSpec = asl.filter { it.averageBitrate == useABPS }.filter { if (useCodex != "Any") it.codec == useCodex else true }.firstOrNull { if (useLocale != null) it.audioLocale == useLocale else true }
            if (audioSpec != null) {
                bitrate = audioSpec.bitrate
                return audioSpec
            } else Logt(TAG, "setAudioSpec Requested audio doesn't exist ($useLocale, $useCodex, $useABPS), getting one based on settings.")
        }

        val prefLowQualityMedia: Boolean = appPrefsFlow!!.value.lowQualityOnMobile
        val audioIndex =
            if (networkMonitor.isNetworkRestricted && prefLowQualityMedia && media.feed?.audioQualitySetting == AVQuality.GLOBAL) 0
            else {
                when (media.feed?.audioQualitySetting) {
                    AVQuality.LOW -> 0
                    AVQuality.MEDIUM -> asl.size / 2
                    AVQuality.HIGH -> asl.size - 1
                    else -> {
                        when (appPrefsFlow!!.value.audioQuality) {
                            AVQuality.LOW.code -> 0
                            AVQuality.MEDIUM.code -> asl.size / 2
                            AVQuality.HIGH.code -> asl.size - 1
                            else -> asl.size - 1
                        }
                    }
                }
            }

        for (a in asl) Logd(TAG, "setAudioSpec asl: bitrate: ${a.bitrate} averageBitrate: ${a.averageBitrate} delivery: ${a.deliveryMethod}  codec: ${a.codec} audioLocale: ${a.audioLocale.toString()} id: ${a.audioTrackId} name: ${a.audioTrackName} format: ${a.format} ${a.url}")

        val audioSpec = if (audioIndex >= 0 && audioIndex < asl.size) asl[audioIndex] else null
        bitrate = audioSpec?.bitrate ?: 0
        Logd(TAG, "setAudioSpec use audio quality: ${audioSpec?.bitrate} forceVideo: ${media.forceVideo}")
        Logd(TAG, "setAudioSpec: ${audioSpec?.url}")
        return audioSpec
    }

    fun setVideoSpec(videoSpecs: List<VideoSpec>, media: Episode): VideoSpec {
        if (useResolution != null || useVCodex != null) {
            val videoSpec = when {
                useVCodex == null ->  videoSpecs.firstOrNull { it.resolution == useResolution }
                useResolution == null -> videoSpecs.firstOrNull { it.codec == useVCodex }
                else -> videoSpecs.firstOrNull { it.codec == useVCodex && it.resolution == useResolution }
            }
            if (videoSpec != null) {
                resolution = videoSpec.resolution ?: ""
                Logd(TAG, "setVideoSpec use video quality: ${videoSpec.resolution}")
                return videoSpec
            } else Logt(TAG, "setVideoSpec Requested video with ($useVCodex and $useResolution) doesn't exist, getting one based on settings")
        }
        val videoIndex =
            if (networkMonitor.isNetworkRestricted && appPrefsFlow!!.value.lowQualityOnMobile && media.feed?.videoQualitySetting == AVQuality.GLOBAL) 0
            else {
                when (media.feed?.videoQualitySetting) {
                    AVQuality.LOW -> 0
                    AVQuality.MEDIUM -> videoSpecs.size / 2
                    AVQuality.HIGH -> videoSpecs.size - 1
                    else -> {
                        when (appPrefsFlow!!.value.videoQuality) {
                            AVQuality.LOW.code -> 0
                            AVQuality.MEDIUM.code -> videoSpecs.size / 2
                            AVQuality.HIGH.code -> videoSpecs.size - 1
                            else -> 0
                        }
                    }
                }
            }

        for (i in videoSpecs.indices) Logd(TAG, "setVideoSpec $i ${videoSpecs[i].bitrate} ${videoSpecs[i].fps} ${videoSpecs[i].codec} ${videoSpecs[i].deliveryMethod} ${videoSpecs[i].resolution} ${videoSpecs[i].url}")

        val videoSpec = videoSpecs[videoIndex]
        resolution = videoSpec.resolution ?: ""
        Logd(TAG, "setVideoSpec use video quality: ${videoSpec.resolution}")
        return videoSpec
    }

    abstract fun notifySystem()

    abstract fun recordClip(startPositionMs: Long, endPositionMs: Long? = null)

    open fun onDestroy() {
        currentMediaType = MediaType.UNKNOWN
        cancelPositionSaver()
        shutdown()
    }

    fun isCurrentlyPlaying(media: Episode?): Boolean {
        return isCurMedia(media) && PlaybackService.isRunning && isPlaying
    }

    fun isCurMedia(media: Episode?): Boolean {
        return media != null && curEpisode?.id == media.id
    }

    companion object {
        private val TAG: String = MediaPlayerBase::class.simpleName ?: "Anonymous"

        private const val MIN_POSITION_SAVER_INTERVAL: Int = 5000   // in millisoconds

        var handleAudioFocus: Boolean = false

        val hardwareVp9 by lazy { supportsHardwareVp9() }
        val hardwareAv1 by lazy { supportsHardwareAv1() }
        val hardwareHevc by lazy { supportsHardwareHevc() }
        val hardwareAvc by lazy { supportsHardwareAvc() }

        fun isStreamingCapable(media: Episode): Boolean {
//            showStackTrace()
            if (!isNetworkUrl(media.downloadUrl)) {
                LogeFor(TAG, media.id, "streaming media without a remote downloadUrl: ${media.downloadUrl}. Abort")
                return false
            }
            if (!networkMonitor.isConnected) {
                Loge(TAG, "streaming media but network is not available, abort")
                return false
            }
            return true
        }

        private fun supportsHardwareDecoder(mimeType: String): Boolean {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            return codecList.codecInfos.any { codec ->
                if (codec.isEncoder) return@any false
                if (!codec.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) return@any false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)  codec.isHardwareAccelerated
                else !codec.name.startsWith("OMX.google.", ignoreCase = true) && !codec.name.startsWith("c2.android.", ignoreCase = true)
            }
        }

        fun supportsHardwareVp9(): Boolean = supportsHardwareDecoder("video/x-vnd.on2.vp9")

        fun supportsHardwareAv1(): Boolean = supportsHardwareDecoder("video/av01")

        fun supportsHardwareHevc(): Boolean = supportsHardwareDecoder("video/hevc")

        fun supportsHardwareAvc(): Boolean = supportsHardwareDecoder("video/avc")
    }
}
