package ac.mdiq.podcini.playback.base

import ac.mdiq.podcini.PodciniApp.Companion.appMainScope
import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.sync.queue.SynchronizationQueueSink
import ac.mdiq.podcini.utils.NetworkUtils.isNetworkUrl
import ac.mdiq.podcini.utils.NetworkUtils.networkMonitor
import ac.mdiq.podcini.playback.base.SleepManager.Companion.autoEnableFrom
import ac.mdiq.podcini.playback.base.SleepManager.Companion.autoEnableTo
import ac.mdiq.podcini.playback.base.SleepManager.Companion.lastTimerValue
import ac.mdiq.podcini.playback.base.SleepManager.Companion.sleepManager
import ac.mdiq.podcini.playback.service.PlaybackService
import ac.mdiq.podcini.playback.service.PlaybackService.Companion.isAutoController
import ac.mdiq.podcini.playback.service.PlaybackService.Companion.isCasting
import ac.mdiq.podcini.playback.service.PlaybackService.Companion.playbackService
import ac.mdiq.podcini.playback.service.QuickSettingsTileService
import ac.mdiq.podcini.shared.AudioSpec
import ac.mdiq.podcini.shared.VideoSpec
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.sourcing.SourceGatewayClient
import ac.mdiq.podcini.sourcing.clientByEpisode
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
import android.content.ComponentName
import android.media.MediaCodecList
import android.os.Build
import android.service.quicksettings.TileService
import androidx.media3.common.Player
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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

    val statusFlow = MutableStateFlow(PlayerStatus.STOPPED)

    val statusSimpleFlow = MutableStateFlow(PlayerStatusSimple.OTHER)

    var curState: CurrentState = CurrentState()

    val isPlaying: Boolean
        get() = statusFlow.value == PlayerStatus.PLAYING
    val isPaused: Boolean
        get() = statusFlow.value == PlayerStatus.PAUSED
    val isPrepared: Boolean
        get() = statusFlow.value == PlayerStatus.PREPARED
    val isPreparing: Boolean
        get() = statusFlow.value == PlayerStatus.PREPARING
    val isInitialized: Boolean
        get() = statusFlow.value == PlayerStatus.INITIALIZED
    val isStopped: Boolean
        get() = statusFlow.value == PlayerStatus.STOPPED
    val isUnknown: Boolean
        get() = statusFlow.value == PlayerStatus.INDETERMINATE
    val isError: Boolean
        get() = statusFlow.value == PlayerStatus.ERROR

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
    val curPlayerSpeedFlow = MutableStateFlow(1f)
    var curPitch: Float = SPEED_USE_GLOBAL

    internal var prevMedia: Episode? = null

    var curMediaScope: CoroutineScope? = null

    val curMediaFlow = MutableStateFlow<Episode?>(null)

    var currentMediaType: MediaType? = MediaType.UNKNOWN

    val playingVideoFlow = MutableStateFlow(false)

    var curClient: SourceGatewayClient? = null

//    internal var videoSize: Pair<Int, Int>? = null
//    open val videoWidth: Int = 0
//    open val videoHeight: Int = 0

    val bufferedPercentFlow = MutableStateFlow(0)

    var skipSilence: Boolean? = null
    val bitrateFlow = MutableStateFlow(0)
    val resolutionFlow = MutableStateFlow("")
    val mimeTypeFlow = MutableStateFlow("")
    val channelCountFlow = MutableStateFlow(0)
    val shouldRepeatFlow = MutableStateFlow(false)

    private var positionSaverJob: Job? = null
    private var bufferPollingJob: Job? = null

    private var positionSaverInterval: Long = MIN_POSITION_SAVER_INTERVAL.toLong()
    private var dataSourceJob: Job? = null

    var useLocales: Set<String> = setOf()
    var useLocale: String? = null
    var useCodex: String = "Any"
    var useABPS: Int = 0

    var useVCodex: String? = null
    var useResolution: String? = null

    val isPlayingVideoLocally: Boolean
        get() = when {
            isCasting -> false
            playbackService != null -> currentMediaType == MediaType.VIDEO
            else -> curMediaFlow.value?.mediaType == MediaType.VIDEO
        }

    init {
        statusFlow.value = PlayerStatus.STOPPED
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

    fun setAsCurMedia(episode: Episode?) {
        if (episode != null && episode.id == curMediaFlow.value?.id) return
        curMediaScope?.cancel()
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
                bitrateFlow.value = 0
                resolutionFlow.value = ""
                curMediaFlow.value = episode_
                curClient = clientByEpisode(episode_)
                setAudioStream()
                useVCodex = null
                useResolution = null
                playingVideoFlow.value = (episode_.forceVideo || (episode_.feed?.videoModePolicy != VideoMode.AUDIO_ONLY && appPrefsFlow!!.value.videoPlaybackMode != VideoMode.AUDIO_ONLY.code && curVideoMode != VideoMode.AUDIO_ONLY && episode_.mediaType == MediaType.VIDEO))
                skipSilence = null
                shouldRepeatFlow.value = false
                curSpeed = SPEED_USE_GLOBAL
                Logd(TAG, "setAsCurMedia start monitoring curMedia ${curMediaFlow.value?.title}")
                curMediaScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                curMediaScope!!.launch {
                    realm.query(Episode::class).query("id == $0", episode_.id).asFlow().map { it.list.firstOrNull() }.collect { curMediaFlow.value = it }
                    if (!actQueueFlow.value.contains(curMediaFlow.value!!)) {
                        val qes = realm.query(QueueEntry::class).query("episodeId == ${curMediaFlow.value!!.id}").find()
                        if (qes.isNotEmpty()) {
                            val q = queuesLive.find { it.id == qes[0].queueId }
                            if (q != null) actQueueFlow.value = q
                        }
                    }
                }
            }
            else -> {
                curMediaFlow.value = null
                savePlayerStatus(null, null)
            }
        }

    }

    @Synchronized
    protected fun handlePlayerStatus(newStatus: PlayerStatus, media: Episode?) {
        Logd(TAG, "handlePlayerStatus: Setting player statusFlow.value from ${statusFlow.value} to $newStatus ${media?.id} == ${prevMedia?.id}")
        if (statusFlow.value == newStatus && media != null && media.id == prevMedia?.id) return
        //        showStackTrace()
        oldStatus = statusFlow.value
        statusFlow.value = newStatus

        //        currentMediaType = mediaType
        Logd(TAG, "handlePlayerStatus ${statusFlow.value}")
        when {
            isInitialized -> savePlayerStatus(media, newStatus)
            isPrepared -> {
                savePlayerStatus(media, newStatus)
                if (media != null) runOnIOScope {
                    try { loadChapters(media, false) } catch (e: Throwable) { LogsFor(TAG, media.id, e, "Error loading chapters for: ${media.title}") }
                }
            }
            isPaused -> savePlayerStatus(null, newStatus)
            isStopped -> {}
            isPlaying -> {
                savePlayerStatus(null, newStatus)
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
        TileService.requestListeningState(context, ComponentName(context, QuickSettingsTileService::class.java))
    }

    fun savePlayerStatus(episode: Episode?, playerStatus: PlayerStatus?) {
        Logd(TAG, "savePlayerStatus episode ${episode?.id}")
        when {
            episode == null && playerStatus != null -> statusSimpleFlow.value = playerStatus.toStatusInt()
            episode == null || playerStatus == null -> {
                statusSimpleFlow.value = PlayerStatusSimple.OTHER
                runOnIOScope { upsert(curState) {
                    it.curMediaType = LONG_MINUS_1
                    it.curFeedId = LONG_MINUS_1
                    it.curMediaId = LONG_MINUS_1
                } }
            }
            else -> {
                statusSimpleFlow.value = playerStatus.toStatusInt()
                runOnIOScope { upsert(curState) {
                    it.curMediaType = LONG_PLUS_1
                    it.curIsVideo = episode.mediaType == MediaType.VIDEO
                    val feedId = episode.feed?.id
                    if (feedId != null) it.curFeedId = feedId
                    it.curMediaId = episode.id
                } }
            }
        }
    }

    fun startPlaying(media_: Episode? = null) {
        Logd(TAG, "startPlaying called")
        if (curMediaFlow.value == null && media_ == null) {
            Logt(TAG, "startPlaying: No media to play")
            return
        }
        val media = media_ ?: curMediaFlow.value!!
        val needStreaming = media.feed?.isLocal != true && media.fileUrl.isNullOrBlank()
        if (needStreaming && !isStreamingCapable(media)) return
        prepareMedia(playable = media, streaming = needStreaming, startWhenPrepared = true, prepareImmediately = true, forceReset = true, doPostPlayback = false)
    }

    protected fun resetPosSaverInterval(speed: Float) {
        Logd(TAG, "resetPosSaverInterval curMediaFlow.value: ${curMediaFlow.value?.title}")
        curMediaFlow.value?.apply {
            Logd(TAG, "resetPosSaverInterval speed: $speed duration: ${this.duration} ${(0.02 * this.duration / speed).toInt()}")
            positionSaverInterval = (if (appPrefsFlow!!.value.useAdaptiveProgressUpdate) max(MIN_POSITION_SAVER_INTERVAL, (0.02 * this.duration / speed).toInt()) else MIN_POSITION_SAVER_INTERVAL).toLong()
        }
    }

    @Synchronized
    private fun startPositionSaver() {
        cancelPositionSaver()
        bufferPollingJob = appMainScope.launch {
            while (isActive) {
                delay(MIN_POSITION_SAVER_INTERVAL.milliseconds)
                getPlayerBuffer()
            }
        }
        positionSaverJob = appMainScope.launch {
            while (isActive) {
                delay(positionSaverInterval.milliseconds)
                val position = getPosition()
                Logd(TAG, "positionSaverTick positionSaverInterval: $positionSaverInterval currentPosition: $position $prevPosition")
                if (position != prevPosition) {
                    // skip ending
                    val duration = getDuration()
                    val remainingTime = duration - position
                    val item = curMediaFlow.value ?: continue
                    val skipEnd = item.feed?.endingSkip ?: 0
                    val skipEndMS = skipEnd * 1000
                    //                  Logd(TAG, "skipEndingIfNecessary: checking " + remainingTime + " " + skipEndMS + " speed " + currentPlaybackSpeed)
                    if (skipEnd > 0 && skipEndMS < duration && (remainingTime - skipEndMS < 0)) {
                        Logd(TAG, "skipEndingIfNecessary: Skipping the remaining $remainingTime $skipEndMS")
                        Logt(TAG, getAppContext().getString(R.string.pref_feed_skip_ending_toast, skipEnd))
                        autoSkippedFeedMediaId = item.identifyingValue
                        skip()
                    }
                    persistCurrentPosition(false, curMediaFlow.value, position)
                    prevPosition = position
                    samePositionCount = 0
                } else {
                    samePositionCount++
                    if (samePositionCount > 10) pause(false)
                }
            }
        }
        Logd(TAG, "Started PositionSaver with interval: $positionSaverInterval")
    }

    @Synchronized
    private fun cancelPositionSaver() {
        Logd(TAG, "canelling PositionSaver")
        bufferPollingJob?.cancel()
        bufferPollingJob = null
        positionSaverJob?.cancel()
        positionSaverJob = null
    }

    abstract fun getPlaybackSpeed(): Float

    abstract fun fixDuration()

    fun getDuration(): Int = curMediaFlow.value?.duration ?: Episode.INVALID_TIME

    abstract fun getPlayerPosition(): Int

    fun getPosition(): Int {
        //        showStackTrace()
        if (castPlayer?.isPlaying == true && !statusFlow.value.isAtLeast(PlayerStatus.PREPARED)) Logt(TAG, "exoPlayer playbackState ${castPlayer?.playbackState} player statusFlow.value ${statusFlow.value}")
        var retVal = getPlayerPosition()
        if (retVal <= 0 && curMediaFlow.value != null) retVal = curMediaFlow.value!!.position
        return retVal
    }

    open suspend fun getPlayerBuffer() {}

    open fun getSelectedAudioTrack(): Int = -1

    open fun resetMediaPlayer() {}

    open fun createNativePlayer() {}

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    protected abstract fun prepareDataSource(sameMedia: Boolean = false)

    protected abstract fun prepareDataSource(mediaUrl: String, user: String?, password: String?)

    protected abstract fun setCastPlayImmediately()

    private fun prefSpeedPitchOf(media: Episode): Pair<Float, Float> {
        var speed = curSpeed
        if (speed == SPEED_USE_GLOBAL && media.feedId != null && feedsMap.containsKey(media.feedId!!)) speed = feedsMap[media.feedId!!]!!.playSpeed

        if (speed == SPEED_USE_GLOBAL) speed = appPrefsFlow!!.value.playbackSpeed

        var pitch = curPitch
        if (pitch == SPEED_USE_GLOBAL && media.feedId != null && feedsMap.containsKey(media.feedId!!)) pitch = feedsMap[media.feedId!!]!!.playPitch

        if (pitch == SPEED_USE_GLOBAL) pitch = appPrefsFlow!!.value.playbackPitch
        return Pair(speed, pitch)
    }

    fun prepareMedia(playable: Episode, streaming: Boolean, startWhenPrepared: Boolean, prepareImmediately: Boolean, forceReset: Boolean = false, doPostPlayback: Boolean = true) {
        Logd(TAG, "prepareMedia statusFlow.value=${statusFlow.value} stream=$streaming startWhenPrepared=$startWhenPrepared prepareImmediately=$prepareImmediately forceReset=$forceReset ${playable.getEpisodeTitle()} ")
//        showStackTrace()
        if (!forceReset && playable.id == prevMedia?.id && isPlaying) {
            Logd(TAG, "prepareMedia Method call was ignored: media file already playing.")
            return
        }
        dataSourceJob?.cancel()
        if (curMediaFlow.value != null && curMediaFlow.value?.id != playable.id) {
            prevMedia = curMediaFlow.value
            if (doPostPlayback) {
                Logd(TAG, "prepareMedia: curMediaFlow.value exist statusFlow.value=${statusFlow.value}")
                Logd(TAG, "prepareMedia starts new playable:${playable.id} curMediaFlow.value:${curMediaFlow.value!!.id} prevMedia:${prevMedia?.id}")
                // set temporarily to pause in order to update list with current position
//                if (isPlaying || isPaused)
                onPlaybackPause(curMediaFlow.value, curMediaFlow.value?.position ?: -1)
                // stop playback of this episode
//                if (isPaused || isPlaying || isPrepared) castPlayer?.stop()
                if (curMediaFlow.value?.id != playable.id) onPostPlayback(curMediaFlow.value!!, ended = false, skipped = true, true)
                handlePlayerStatus(PlayerStatus.INDETERMINATE, null)
            }
        }

        if (isCasting) setCastPlayImmediately()
        Logd(TAG, "prepareMedia preparing for playable:${playable.id} ${playable.getEpisodeTitle()}")
        if (playable.playState < EpisodeState.PROGRESS.code) runOnIOScope { upsert(playable) { it.setPlayState(EpisodeState.PROGRESS) } }
        val sameMedia = playable.id == curMediaFlow.value?.id
        setAsCurMedia(playable)
        if (forceReset) {
            curMediaFlow.value = playable
            if (sameMedia) curClient = clientByEpisode(curMediaFlow.value!!)
        }
        Logd(TAG, "prepareMedia media.forceVideo: ${curMediaFlow.value?.forceVideo}")
        this.isStreaming = streaming
        if (curMediaFlow.value != null) currentMediaType = curMediaFlow.value!!.mediaType
//        videoSize = null
        resetMediaPlayer()

        isStartWhenPrepared = startWhenPrepared
        prefSpeedPitchOf(curMediaFlow.value!!).let { (sp, pi)-> setPlaybackParams(sp, pi) }
        setRepeat(shouldRepeatFlow.value)
        setSkipSilence()
        dataSourceJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                when {
                    streaming -> {
                        Logd(TAG, "prepareMedia streamurl: ${curMediaFlow.value?.downloadUrl}")
                        if (!curMediaFlow.value?.downloadUrl.isNullOrBlank()) prepareDataSource(sameMedia)
                        else throw IOException("episode downloadUrl is null or empty ${curMediaFlow.value?.title}")
                    }
                    else -> {
                        Logd(TAG, "prepareMedia localMediaurl: ${curMediaFlow.value?.fileUrl}")
                        if (!curMediaFlow.value?.fileUrl.isNullOrBlank()) prepareDataSource(curMediaFlow.value!!.fileUrl!!, null, null)
                        else throw IOException("Unable to read local file ${curMediaFlow.value?.fileUrl}")
                    }
                }
                withContext(Dispatchers.Main) {
                    if (!isAutoController) handlePlayerStatus(PlayerStatus.INITIALIZED, curMediaFlow.value)
                    if (prepareImmediately) prepareInitialized()
                }
            } catch (e: IOException) {
                LogsFor(TAG, curMediaFlow.value?.id, e, "prepareMedia failed ${e.localizedMessage ?: ""}")
                withContext(Dispatchers.Main) { handlePlayerStatus(PlayerStatus.ERROR, curMediaFlow.value) }
            } catch (e: IllegalStateException) {
                LogsFor(TAG, curMediaFlow.value?.id, e, "prepareMedia failed ${e.localizedMessage ?: ""}")
                withContext(Dispatchers.Main) { handlePlayerStatus(PlayerStatus.ERROR, curMediaFlow.value) }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { handlePlayerStatus(PlayerStatus.ERROR, curMediaFlow.value) }
                LogsFor(TAG, curMediaFlow.value?.id, e, "setDataSource error: [${e.localizedMessage}]")
            } finally { }
        }
    }

    open fun shouldSetSource(): Boolean = true

    fun playPause() {
        Logd(TAG, "playPause statusFlow.value: ${statusFlow.value}")
        when {
            isPlaying -> pause(reinit = false)
            isPaused || isPrepared -> play()
            isPreparing -> isStartWhenPrepared = !isStartWhenPrepared
            isInitialized -> {
                isStartWhenPrepared = true
                prepareInitialized()
            }
            else -> Loge(TAG, "Play/Pause button was pressed and PlaybackService state was unknown: ${statusFlow.value}")
        }
    }

    fun play() {
        Logd(TAG, "play(): statusFlow.value: ${statusFlow.value} playbackState: ${castPlayer?.playbackState}")
        if (isPaused || isPrepared) {
            Logd(TAG, "play() Resuming/Starting playback")
            if (shouldSetSource()) setSource()
            val volAdpFac = if (curMediaFlow.value != null) curMediaFlow.value!!.feed?.volumeAdaptionSetting?.adaptionFactor ?: 1f else 1f
            setVolume(1.0f, 1.0f, volAdpFac)
            Logd(TAG, "play(): position: ${curMediaFlow.value?.position}")
            castPlayer?.play()
            setPlaybackParams()
            handlePlayerStatus(PlayerStatus.PLAYING, curMediaFlow.value)
            sleepManager?.restart()
        } else Logd(TAG, "Call to play() was ignored because current state of PSMP object is ${statusFlow.value}")
    }

    fun pause(reinit: Boolean) {
        if (isPlaying || isError) {
            Logd(TAG, "Pausing playback $reinit")
            castPlayer?.pause()
            handlePlayerStatus(PlayerStatus.PAUSED, curMediaFlow.value)
            if (isStreaming && reinit) reinit()
            cancelPositionSaver()
            isSpeedForward = false
            isFallbackSpeed = false
//            if (curMediaFlow.value != null) upsertBlk(curMediaFlow.value!!) { it.forceVideo = false }
        } else Logd(TAG, "Ignoring call to pause: Player is in ${statusFlow.value} state")
    }

    internal abstract fun setSource()

    internal fun prepareInitialized() {
        Logd(TAG, "prepare Preparing media player: statusFlow.value: ${statusFlow.value} isStartWhenPrepared: $isStartWhenPrepared")
        if (isInitialized) {
            handlePlayerStatus(PlayerStatus.PREPARING, curMediaFlow.value)
            setSource()
//            if (mediaType == MediaType.VIDEO) videoSize = Pair(videoWidth, videoHeight)
            if (curMediaFlow.value != null && curMediaFlow.value!!.duration <= 0) fixDuration()
            handlePlayerStatus(PlayerStatus.PREPARED, curMediaFlow.value)
            if (isStartWhenPrepared) play()
        } else Logt(TAG, "prepare() call ignored with statusFlow.value: ${statusFlow.value}")
    }

    fun reinit() {
        Logd(TAG, "reinit() called")
        when {
            curMediaFlow.value != null -> prepareMedia(playable = curMediaFlow.value!!, streaming = isStreaming, startWhenPrepared = isStartWhenPrepared, prepareImmediately = false, forceReset = true, doPostPlayback = true)
            else -> Logd(TAG, "Call to reinit: media and mediaPlayer were null, ignored")
        }
    }

    fun seekTo(t_: Int) {
        var t = t_
        if (t < 0) t = 0
        Logd(TAG, "seekTo() called $t statusFlow.value: ${statusFlow.value}")
        when {
            isPlaying || isPaused || isPrepared -> {
                Logd(TAG, "seekTo t: $t statusFlow.value: ${statusFlow.value}")
                castPlayer?.seekTo(t.toLong())
                if (curMediaFlow.value != null) upsertBlk(curMediaFlow.value!!) { it.position = t }
            }
            isInitialized -> {
                if (curMediaFlow.value != null) upsertBlk(curMediaFlow.value!!) { it.position = t }
                isStartWhenPrepared = false
                prepareInitialized()
            }
            else -> {}
        }
    }

    fun seekDelta(delta: Int) {
        val curPosition = getPosition()
        if (curPosition != Episode.INVALID_TIME) seekTo(curPosition + delta)
        else LogeFor(TAG, curMediaFlow.value?.id, "seekDelta getPosition() returned INVALID_TIME in seekDelta")
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

    private fun getNextInQueue(): Episode? {
        Logd(TAG, "getNextInQueue called curMediaFlow.value: ${curMediaFlow.value?.getEpisodeTitle()}")
        if (!actQueueFlow.value.playInSequence) {
            Logd(TAG, "getNextInQueue(), but follow queue is not enabled.")
            savePlayerStatus(null, null)
            return null
        }
        val qes = actQueueFlow.value.entries
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

    internal fun endPlayback(hasEnded: Boolean, wasSkipped: Boolean, shouldContinue: Boolean = true) {
        showStackTrace()
        if (curMediaFlow.value == null) {
            Logd(TAG, "endPlayback curMediaFlow.value is null, return")
            return
        }
        // we're relying on the position stored in the EpisodeMedia object for post-playback processing
        val position = getPosition()
        if (position >= 0) upsertBlk(curMediaFlow.value!!) { it.position = position }
        Logd(TAG, "endPlayback hasEnded=$hasEnded wasSkipped=$wasSkipped shouldContinue=$shouldContinue ${curMediaFlow.value?.title}")

        fun stopPlayer() {
            Logd(TAG, "endPlayback stopPlayer is called")
            curSpeed = SPEED_USE_GLOBAL
            cancelPositionSaver()
            setAsCurMedia(null)
            castPlayer?.stop()
            if (isUnknown) handlePlayerStatus(PlayerStatus.STOPPED, null)
//            else Logd(TAG, "endPlayback Ignored call to stop: Current player state is: ${statusFlow.value}")
        }

        val currentMedia = curMediaFlow.value
        when {
            shouldContinue -> {
                // Load next episode if previous episode was in the queue and if there is an episode in the queue left.
                // Start playback immediately if continuous playback is enabled
                val nextMedia = getNextInQueue()
                if (nextMedia == null) {
                    if (currentMedia != null) onPostPlayback(currentMedia, hasEnded, wasSkipped, false)
                    stopPlayer()
                } else {
                    Logd(TAG, "endPlayback has nextMedia. statusFlow.value: ${statusFlow.value} ${nextMedia.title}")
                    val wasPlayng = isPlaying
                    if (!isCasting) pause(false)
                    if (wasSkipped) handlePlayerStatus(PlayerStatus.INDETERMINATE, null)
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
        endPlayback(hasEnded = false, wasSkipped = !shouldRepeatFlow.value)
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

    protected fun onPlaybackStart(playable: Episode, position: Int) {
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
                    LogtFor(TAG, curMediaFlow.value?.id, context.getString(R.string.pref_feed_skip_intro_toast, skipIntro))
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
        Logd(TAG, "onPostPlayback(): ended=$ended skipped=$skipped playingNext=$playingNext media=${playable.getEpisodeTitle()} ")
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
        var playable = if (curMediaFlow.value != null && playable_?.id == curMediaFlow.value?.id) curMediaFlow.value else playable_
        var position = position_
        val duration_: Int
        if (fromMediaPlayer) {
//            position = (media3Controller?.currentPosition ?: 0).toInt() // testing the controller
            position = getPosition()
            duration_ = getDuration()
            playable = curMediaFlow.value
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
            bitrateFlow.value = 0
            resolutionFlow.value = ""
            return null
        }

        if (useABPS > 0) {
            val audioSpec = asl.filter { it.averageBitrate == useABPS }.filter { if (useCodex != "Any") it.codec == useCodex else true }.firstOrNull { if (useLocale != null) it.audioLocale == useLocale else true }
            if (audioSpec != null) {
                bitrateFlow.value = audioSpec.bitrate
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

        for (a in asl) Logd(TAG, "setAudioSpec asl: bitrateFlow.value: ${a.bitrate} averageBitrate: ${a.averageBitrate} delivery: ${a.deliveryMethod}  codec: ${a.codec} audioLocale: ${a.audioLocale.toString()} id: ${a.audioTrackId} name: ${a.audioTrackName} format: ${a.format} ${a.url}")

        val audioSpec = if (audioIndex >= 0 && audioIndex < asl.size) asl[audioIndex] else null
        bitrateFlow.value = audioSpec?.bitrate ?: 0
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
                resolutionFlow.value = videoSpec.resolution ?: ""
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
        resolutionFlow.value = videoSpec.resolution ?: ""
        Logd(TAG, "setVideoSpec use video quality: ${videoSpec.resolution}")
        return videoSpec
    }

    abstract fun recordClip(startPositionMs: Long, endPositionMs: Long? = null)

    open fun onDestroy() {
        currentMediaType = MediaType.UNKNOWN
        cancelPositionSaver()
        shutdown()
    }

    fun isCurrentlyPlaying(media: Episode?): Boolean {
        return media != null && curMediaFlow.value?.id == media.id && PlaybackService.isRunning && isPlaying
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

    fun onEpisodeMediaEvent(event: FlowEvent.EpisodeMediaEvent) {
        if (event.action == FlowEvent.EpisodeMediaEvent.Action.REMOVED) {
            for (e in event.episodes) {
                if (e.id == curMediaFlow.value?.id) {
                    setAsCurMedia(e)  // TODO: seems having no effect
                    endPlayback(hasEnded = false, wasSkipped = true)
                    break
                }
            }
        }
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
