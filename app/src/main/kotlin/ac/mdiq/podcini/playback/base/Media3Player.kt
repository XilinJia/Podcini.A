package ac.mdiq.podcini.playback.base

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.playback.SegmentSavingDataSource
import ac.mdiq.podcini.playback.SegmentSavingDataSourceFactory
import ac.mdiq.podcini.playback.cast.CastMediaPlayer.buildCastPlayer
import ac.mdiq.podcini.playback.service.PlaybackService.Companion.isCasting
import ac.mdiq.podcini.playback.service.PlaybackService.Companion.playbackService
import ac.mdiq.podcini.receiver.PodciniWidget
import ac.mdiq.podcini.shared.PodciniHttpClient.proxyConfig
import ac.mdiq.podcini.shared.ProxyConfig
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.fastForwardSecs
import ac.mdiq.podcini.storage.database.isSkipSilence
import ac.mdiq.podcini.storage.database.rewindSecs
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.streamingCacheSizeMB
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.toIPC
import ac.mdiq.podcini.storage.model.toWidget
import ac.mdiq.podcini.storage.specs.EpisodeState
import ac.mdiq.podcini.storage.specs.VideoMode
import ac.mdiq.podcini.storage.utils.cacheDir
import ac.mdiq.podcini.storage.utils.div
import ac.mdiq.podcini.storage.utils.durationStringFull
import ac.mdiq.podcini.storage.utils.durationStringShort
import ac.mdiq.podcini.storage.utils.parent
import ac.mdiq.podcini.storage.utils.toSafeUri
import ac.mdiq.podcini.storage.utils.toUF
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.LogeFor
import ac.mdiq.podcini.utils.LogsFor
import ac.mdiq.podcini.utils.Logt
import ac.mdiq.podcini.utils.LogtFor
import ac.mdiq.podcini.utils.timeIt
import android.content.Context
import android.media.RingtoneManager
import android.media.audiofx.LoudnessEnhancer
import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Base64
import android.util.Pair
import androidx.core.net.toUri
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes.BASE_TYPE_APPLICATION
import androidx.media3.common.MimeTypes.BASE_TYPE_AUDIO
import androidx.media3.common.MimeTypes.BASE_TYPE_VIDEO
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_GET_CURRENT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_PLAY_PAUSE
import androidx.media3.common.Player.Listener
import androidx.media3.common.Player.PositionInfo
import androidx.media3.common.Player.STATE_BUFFERING
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.Player.State
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpEngineDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.TrackNameProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import okio.ByteString
import okio.buffer
import org.chromium.net.CronetEngine
import org.chromium.net.Proxy
import org.chromium.net.ProxyOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.UnsupportedEncodingException
import java.lang.reflect.Field
import java.net.Proxy.Type
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.time.Instant

class Media3Player(playerId: Int, val lr: Int) : MediaPlayerBase() {
    private var exoPlayer: ExoPlayer? = null

    private var curDataSource: SegmentSavingDataSource? = null
    private var recordingFactory: SegmentSavingDataSourceFactory? = null

    private var loadControl: DynamicLoadControl? = null

    private var mediaSource: MediaSource? = null
    private var mediaItem: MediaItem? = null

    private var exoplayerListener: Listener? = null

    private var exoplayerOffloadListener: ExoPlayer.AudioOffloadListener? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private var trackSelector: DefaultTrackSelector? = null

    private var playbackParameters: PlaybackParameters

    private var speedEnablesOffload = true
    private var silenceEnablesOffload = !isSkipSilence
    private var offloadEnabled = speedEnablesOffload && silenceEnablesOffload
    private var needChangeOffload = false

    private val formats: List<Format>
        get() {
            val formats_: MutableList<Format> = arrayListOf()
            val trackInfo = trackSelector!!.currentMappedTrackInfo ?: return emptyList()
            val trackGroups = trackInfo.getTrackGroups(audioRendererIndex)
            for (i in 0 until trackGroups.length) formats_.add(trackGroups[i].getFormat(0))
            return formats_
        }

    override val audioTracks: List<String>
        get() {
            val trackNames: MutableList<String> = mutableListOf()
            val trackNameProvider: TrackNameProvider = DefaultTrackNameProvider(context.resources)
            for (format in formats) trackNames.add(trackNameProvider.getTrackName(format))
            return trackNames
        }

    private val audioRendererIndex: Int
        get() {
            for (i in 0 until(exoPlayer?.rendererCount?:0)) if (exoPlayer?.getRendererType(i) == C.TRACK_TYPE_AUDIO) return i
            return -1
        }

    private val cacheMutex = Mutex()
    private suspend fun initCache() = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            simpleCache?.let { return@withLock }
            val appContext = getAppContext()
            val cacheDir = File(appContext.cacheDir, "media_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val databaseProvider = StandaloneDatabaseProvider(appContext)
            val evictor = LeastRecentlyUsedCacheEvictor(streamingCacheSizeMB * 1024L * 1024L)
            simpleCache = SimpleCache(cacheDir, evictor, databaseProvider)
        }
    }
    private val networkExecutor = Executors.newSingleThreadExecutor()

    init {
        this.playerId = playerId
        timeIt("$TAG start of init")
        if (exoPlayer == null) {
            exoplayerListener = object: Listener {
                private var hasStarted = false
                private var isSeeking = false
                private var wasPlayingBeforeSeek = false
                override fun onPlaybackStateChanged(playbackState: @State Int) {
                    Logd(TAG, "exoplayerListener onPlaybackStateChanged $playbackState")
                    Logd(TAG, "onPlaybackStateChanged state=$playbackState " +
                                "playWhenReady=${exoPlayer?.playWhenReady} " +
                                "isPlaying=${exoPlayer?.isPlaying} " +
                                "position=${exoPlayer?.currentPosition} " +
                                "buffered=${exoPlayer?.bufferedPosition} " +
                                "bufferedDuration=${exoPlayer?.totalBufferedDuration} " +
                                "isLoading=${exoPlayer?.isLoading}"
                    )
                    when (playbackState) {
                        STATE_BUFFERING -> bufferedPercentFlow.value = BUFFERING_STARTED
                        STATE_READY -> bufferedPercentFlow.value = BUFFERING_ENDED
                        STATE_ENDED -> {
                            val currentPos = exoPlayer?.currentPosition ?: 0L
                            val totalDuration = exoPlayer?.duration ?: 0L
                            Logd(TAG, "exoplayerListener onPlaybackStateChanged currentPos: $currentPos totalDuration: $totalDuration")
                            if (totalDuration > 0 && (totalDuration - currentPos) > 5000) {
                                Logt(TAG, "Stream ended prematurely at $currentPos ms. Resuming...")
//                                exoPlayer?.stop()
//                                val currentMediaItem = exoPlayer?.currentMediaItem
//                                currentMediaItem?.let { item ->
//                                    exoPlayer?.setMediaItem(item, currentPos)
//                                    exoPlayer?.prepare()
//                                    exoPlayer?.play()
//                                }
                            }
                            exoPlayer?.seekTo(C.TIME_UNSET)
                            if (!shouldRepeatFlow.value) endPlayback(hasEnded = true, wasSkipped = false)
                        }
                        STATE_IDLE -> {
                            Logd(TAG, "exoplayerListener onPlaybackStateChanged STATE_IDLE ")
                            if (isCasting && hasStarted && !isSkipping) endPlayback(hasEnded = true, wasSkipped = false)
                            hasStarted = false
                            isSkipping = false
                        }
                    }
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    Logd(TAG, "onPlayWhenReadyChanged value=$playWhenReady reason=$reason state=${exoPlayer?.playbackState} isPlaying=${exoPlayer?.isPlaying}")
                }
                override fun onPositionDiscontinuity(oldPosition: PositionInfo, newPosition: PositionInfo, reason: Int) {
                    Logd(TAG, "onPositionDiscontinuity ${oldPosition.positionMs} ${newPosition.positionMs} $reason")
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) isSeeking = true
                }
                override fun onEvents(player: Player, events: Player.Events) {
                    if (isCasting) {
                        if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) || events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                            val currentPos = player.currentPosition
                            val totalDuration = player.duration
                            if (player.playbackState == STATE_ENDED && totalDuration > 0 && (totalDuration - currentPos) > 10000) {
                                Logt(TAG, "Cast device disconnected stream early at $currentPos ms. Remotely resuming...")
                                val currentItem = player.currentMediaItem
                                currentItem?.let { item ->
                                    player.setMediaItem(item, currentPos)
                                    player.prepare()
                                    player.play()
                                }
                            }
                        }
                    }
                    if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                        val duration = player.duration
                        val curDuration = curMediaFlow.value?.duration
                        if (duration != C.TIME_UNSET && duration > 0 && curDuration != null && abs(duration - curDuration) > 5000) {
                            runOnIOScope { upsert(curMediaFlow.value!!) { it.duration = duration.toInt() } }
                            Logt(TAG, "Media duration adjusted to : ${durationStringFull(duration.toInt())}")
                        }
                    }
                    if (events.contains(Player.EVENT_IS_LOADING_CHANGED) || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                        bufferedPercentFlow.value = player.bufferedPercentage
                        Logd(TAG, "onEvents buffered: ${bufferedPercentFlow.value}")
                        if (bufferedPercentFlow.value == 100 && curMediaFlow.value != null && curMediaFlow.value!!.duration <= 0 && getDuration() > 0) upsertBlk(curMediaFlow.value!!) { it.duration = getDuration() }
                    }
                }
                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    curPlayerSpeedFlow.value = playbackParameters.speed
                }
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    Logd(TAG, "exoplayerListener onMediaItemTransition $reason")
//                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) endPlayback(hasEnded = true, wasSkipped = false)
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Logd(TAG, "exoplayerListener onIsPlayingChanged $isPlaying $isSeeking $wasPlayingBeforeSeek")
                    val media = curMediaFlow.value
                    if (isSeeking) {
                        if (isPlaying) {
                            if (wasPlayingBeforeSeek) {
                                isSeeking = false
                                return
                            }
                            isSeeking = false
                        } else return
                    }
                    if (isPlaying) {
                        hasStarted = true
                        wasPlayingBeforeSeek = true
                        media?.let { onPlaybackStart(it, it.position) }
                    } else {
                        wasPlayingBeforeSeek = false
                        onPlaybackPause(media, getPosition())
                    }
                    handlePlayerStatus(if (isPlaying) PlayerStatus.PLAYING else PlayerStatus.PAUSED, media)
                }
                override fun onPlayerError(error: PlaybackException) {
                    fun handleTerminalError(message: String) {
                        LogeFor(TAG, curMediaFlow.value?.id, message)
                        castPlayer?.stop()
                        castPlayer?.clearMediaItems()
                        handlePlayerStatus(PlayerStatus.STOPPED, curMediaFlow.value)
                    }
                    Loge(TAG, error, "exoplayerListener onPlayerError")
                    when (error.errorCode) {
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> {
                            if (curMediaFlow.value != null) getCache().removeResource(curMediaFlow.value!!.id.toString())
                            Logt(TAG, "corrupted cache is cleared, try playing it again")
                        }
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        PlaybackException.ERROR_CODE_TIMEOUT -> {
                            LogtFor(TAG, curMediaFlow.value?.id, "player error: ${error.localizedMessage}, retrying...")
                            val lastPosition = exoPlayer?.currentPosition ?: 0L
                            exoPlayer?.prepare()
                            if (lastPosition > 0) exoPlayer?.seekTo(lastPosition)
                            exoPlayer?.play()
                        }
                        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                            castPlayer?.prepare()
                            castPlayer?.play()
                        }
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                            val cause = error.cause
//                            LogtFor(TAG, curMediaFlow.value?.id, "Caught Source Error 2000 (NPE). Attempting a clean recovery...")
                            LogtFor(TAG, curMediaFlow.value?.id,
                                """
                                    IO error:
                                      class=${cause?.javaClass?.name}
                                      message=${cause?.message}
                                      root=${cause?.cause?.javaClass?.name}
                                      rootMessage=${cause?.cause?.message}
                                    """.trimIndent())
//                            when (val cause = error.cause) {
//                                is HttpDataSource.InvalidResponseCodeException -> LogtFor(TAG, curMediaFlow.value?.id, "Server rejected request. HTTP ${cause.responseCode}: ${cause.message}")
//                                is HttpDataSource.HttpDataSourceException -> LogtFor(TAG, curMediaFlow.value?.id, "HTTP Error playing media. Response Code: ${cause.message}")
//                                is java.io.FileNotFoundException -> LogtFor(TAG, curMediaFlow.value?.id, "Local file or cache source missing.")
//                                else -> LogtFor(TAG, curMediaFlow.value?.id, "Generic IO Error stack trace: ${cause?.message}")
//                            }
//                            val currentPosition = exoPlayer?.currentPosition ?: 0L
//                            val currentMediaItem = exoPlayer?.currentMediaItem
//                            if (currentMediaItem != null) {
//                                exoPlayer?.stop()
//                                exoPlayer?.setMediaItem(currentMediaItem)
//                                exoPlayer?.seekTo(currentPosition)
//                                exoPlayer?.prepare()
//                                exoPlayer?.play()
//                            }
                        }
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> {
                            handleTerminalError("Device media decoder failed. Try restarting the app.")
                        }
                        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
                        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED -> {
                            handleTerminalError("This content is protected and cannot be played.")
                        }
                        PlaybackException.ERROR_CODE_DECODING_FAILED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> {
                            handleTerminalError("This device cannot play this file format.")
                        }
                        else -> {
                            // Terminal errors (404, Media Unsupported)
                            val cause = error.cause
                            LogeFor(TAG, curMediaFlow.value?.id, "Player error: ${error.localizedMessage} ${error.errorCode} ${cause?.message}")
                            when {
                                cause is AudioSink.InitializationException -> {
                                    if (enableFloat) {
                                        Logt(TAG, "system can not handle float sampling, recreating players with float off")
                                        enableFloat = false
                                        playbackService?.switchPlayersMode()
                                    }
                                }
                                error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND || (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 404) -> handleTerminalError("Episode not found on server (404).")
                                cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 403 -> handleTerminalError("Access denied (403). Check your subscription.")
                            }
                        }
                    }
//                    if (wasDownloadBlocked(error)) {
//                        Logpe(TAG, "audioErrorListener: ${getAppContext().getString(R.string.download_error_blocked)}")
//                        setPlayerStatus(PlayerStatus.ERROR, curMediaFlow.value)
//                    } else {
//                        var cause = error.cause
//                        if (cause is HttpDataSourceException && cause.cause != null) cause = cause.cause
//                        if (cause != null && "Source error" == cause.message) cause = cause.cause
//                        Logpe(TAG, "audioErrorListener: ${if (cause != null) cause.message else error.message}")
//                        setPlayerStatus(PlayerStatus.ERROR, curMediaFlow.value)
//                    }
                }
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    Logd(TAG, "exoplayerListener onAudioSessionIdChanged $audioSessionId")
                    runOnIOScope {
                        try {
                            val newEnhancer = LoudnessEnhancer(audioSessionId)
                            val oldEnhancer = loudnessEnhancer
                            if (oldEnhancer != null) {
                                newEnhancer.enabled = oldEnhancer.enabled
                                if (oldEnhancer.enabled) newEnhancer.setTargetGain(oldEnhancer.targetGain.toInt())
                                oldEnhancer.release()
                            }
                            loudnessEnhancer = newEnhancer
                        } catch (e: Throwable) { LogsFor(TAG, curMediaFlow.value?.id, e, "Failed to init LoudnessEnhancer") }
                    }
                }
                override fun onTracksChanged(tracks: Tracks) {
                    Logd(TAG, "exoplayerListener onTracksChanged tracks: ${tracks.groups.size}")
                    tracks.groups.forEach { group ->
                        for (i in 0 until group.length) {
                            if (group.isTrackSelected(i)) {
                                val format = group.getTrackFormat(i)
                                mimeTypeFlow.value = when {
                                    format.sampleMimeType.isNullOrBlank() -> ""
                                    format.sampleMimeType!!.contains(BASE_TYPE_AUDIO) -> format.sampleMimeType!!.replace("$BASE_TYPE_AUDIO/", "")
                                    format.sampleMimeType!!.contains(BASE_TYPE_VIDEO) -> format.sampleMimeType!!.replace("$BASE_TYPE_VIDEO/", "")
                                    format.sampleMimeType!!.contains(BASE_TYPE_APPLICATION) -> format.sampleMimeType!!.replace("$BASE_TYPE_APPLICATION/", "")
                                    else -> format.sampleMimeType!!
                                }
                                channelCountFlow.value = format.channelCount
                                Logd(TAG, "exoplayerListener onTracksChanged $i ${format.averageBitrate} ${format.bitrate}")
                                if (format.averageBitrate != Format.NO_VALUE) bitrateFlow.value = format.averageBitrate
                                return@forEach
                            }
                        }
                    }
                }
                override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                    if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                        Logd(TAG, "exoplayerListener onDeviceInfoChanged Casting active: Switching to remote URLs")
                        isCasting = true
                    } else {
                        Logd(TAG, "exoplayerListener onDeviceInfoChanged Local play: Switching to local files")
                        isCasting = false
                    }
                }
            }
            exoplayerOffloadListener = object: ExoPlayer.AudioOffloadListener {
                override fun onOffloadedPlayback(offloadSchedulingEnabled: Boolean) {
                    LogtFor(TAG, curMediaFlow.value?.id,  "AudioOffloadListener Offload scheduling enabled: $offloadSchedulingEnabled")
                }
                override fun onSleepingForOffloadChanged(isSleepingForOffload: Boolean) {
                    LogtFor(TAG, curMediaFlow.value?.id, "AudioOffloadListener CPU is sleeping for offload: $isSleepingForOffload")
                }
            }
            createNativePlayer()
        }
        playbackParameters = castPlayer!!.playbackParameters
        timeIt("$TAG end of init")
    }

    override suspend fun getPlayerBuffer() {
        if (exoPlayer != null && isPlaying) {
            withContext(Dispatchers.Main) {
                val pct = exoPlayer!!.bufferedPercentage
                if (bufferedPercentFlow.value != pct) {
                    bufferedPercentFlow.value = pct
                    Logd(TAG, "getPlayerBuffer updated: ${bufferedPercentFlow.value}")
                }
            }
        }
    }

    private fun switchOffload() {
        if (!needChangeOffload || exoPlayer == null || isCasting) return

        Logd(TAG, "switchOffload offloadSpeedEnabled: $speedEnablesOffload offloadSilenceEnabled: $silenceEnablesOffload")
        val enabled = speedEnablesOffload && silenceEnablesOffload
        if (enabled == offloadEnabled) {
            needChangeOffload = false
            return
        }
        offloadEnabled = enabled
        Logt(TAG, "switchOffload set audio offload $offloadEnabled")

        val wasPlaying = castPlayer!!.isPlaying
        castPlayer!!.pause()
        exoPlayer!!.trackSelectionParameters = exoPlayer!!.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(if (offloadEnabled) AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                .build())
            .build()

        needChangeOffload = false

        if (mediaSource != null) exoPlayer?.setMediaSource(mediaSource!!, curMediaFlow.value!!.position.toLong())
        else if (mediaItem != null) castPlayer?.setMediaItem(mediaItem!!)

        castPlayer!!.prepare()
        if (wasPlaying) castPlayer!!.play()
    }

    abstract class ChannelAudioProcessor : BaseAudioProcessor() {
        override fun onConfigure(inputFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            return AudioProcessor.AudioFormat(inputFormat.sampleRate, 2, inputFormat.encoding)
        }
        abstract fun setOutputBuffer(outputBuffer: ByteBuffer, mono: Float)
        abstract fun setOutputBuffer(outputBuffer: ByteBuffer, mono: Short)

        override fun queueInput(inputBuffer: ByteBuffer) {
            val encoding = inputAudioFormat.encoding
            val inChannels = inputAudioFormat.channelCount

            val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
            val frameSize = bytesPerSample * inChannels
            val frameCount = inputBuffer.remaining() / frameSize

            val outputSize = frameCount * 2 * bytesPerSample // always stereo output
            val outputBuffer = replaceOutputBuffer(outputSize)

            if (encoding == C.ENCODING_PCM_FLOAT) {
                while (inputBuffer.remaining() >= frameSize) {
                    val left = inputBuffer.float
                    val right = if (inChannels > 1) inputBuffer.float else left
                    val mono = (left + right) / 2f
                    setOutputBuffer(outputBuffer, mono)
                }
            } else {
                while (inputBuffer.remaining() >= frameSize) {
                    val left = inputBuffer.short.toInt()
                    val right = if (inChannels > 1) inputBuffer.short.toInt() else left
                    val mono = ((left + right) / 2).coerceIn(-32768, 32767).toShort()
                    setOutputBuffer(outputBuffer, mono)
                }
            }
            outputBuffer.flip()
        }
    }

    class LeftChannelAudioProcessor: ChannelAudioProcessor() {
        override fun setOutputBuffer(outputBuffer: ByteBuffer, mono: Float) {
            outputBuffer.putFloat(mono) // L
            outputBuffer.putFloat(0f)   // R
        }
        override fun setOutputBuffer(outputBuffer: ByteBuffer, mono: Short) {
            outputBuffer.putShort(mono) // L
            outputBuffer.putShort(0)   // R
        }
    }
    class RightChannelAudioProcessor : ChannelAudioProcessor() {
        override fun setOutputBuffer(outputBuffer: ByteBuffer, mono: Float) {
            outputBuffer.putFloat(0f) // L
            outputBuffer.putFloat(mono)   // R
        }
        override fun setOutputBuffer(outputBuffer: ByteBuffer, mono: Short) {
            outputBuffer.putShort(0) // L
            outputBuffer.putShort(mono)   // R
        }
    }

    class DynamicLoadControl : LoadControl {
        private val sharedAllocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        private val delegate: DefaultLoadControl = DefaultLoadControl.Builder()
            .setAllocator(sharedAllocator)
            .setBufferDurationsMs(15_000, 50_000, 2500, 5000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        private val minBufferUsField: Field? = runCatching { DefaultLoadControl::class.java.getDeclaredField("minBufferUs").apply { isAccessible = true } }.getOrNull()
        private val maxBufferUsField: Field? = runCatching { DefaultLoadControl::class.java.getDeclaredField("maxBufferUs").apply { isAccessible = true } }.getOrNull()
        private val bufferForPlaybackUsField: Field? = runCatching { DefaultLoadControl::class.java.getDeclaredField("bufferForPlaybackUs").apply { isAccessible = true } }.getOrNull()
        private val bufferForPlaybackAfterRebufferUsField: Field? = runCatching { DefaultLoadControl::class.java.getDeclaredField("bufferForPlaybackAfterRebufferUs").apply { isAccessible = true } }.getOrNull()
        private val prioritizeTimeOverSizeThresholdsField: Field? = runCatching { DefaultLoadControl::class.java.getDeclaredField("prioritizeTimeOverSizeThresholds").apply { isAccessible = true } }.getOrNull()

        fun updateBufferParameters(minBufferMs: Int, maxBufferMs: Int, playbackMs: Int, rebufferMs: Int, prioritizeTime: Boolean) {
            runCatching {
                minBufferUsField?.set(delegate, minBufferMs * 1000L)
                maxBufferUsField?.set(delegate, maxBufferMs * 1000L)
                bufferForPlaybackUsField?.set(delegate, playbackMs * 1000L)
                bufferForPlaybackAfterRebufferUsField?.set(delegate, rebufferMs * 1000L)
                prioritizeTimeOverSizeThresholdsField?.set(delegate, prioritizeTime)
            }.onFailure { e -> Loge(TAG, e, "Failed to dynamically update buffer parameters ") }
        }

        override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)
        override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)
        override fun getAllocator(playerId: PlayerId): Allocator = sharedAllocator
        override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)
        override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)
        override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)
        override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = delegate.shouldContinueLoading(parameters)
        override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = delegate.shouldStartPlayback(parameters)
        override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    }

    fun createCronetEngine(context: Context, config: ProxyConfig?, executor: Executor): CronetEngine {
        Logd(TAG, "createCronetEngine")
        val builder = CronetEngine.Builder(context)
            .enableHttp2(true)
            .enableQuic(true)
            .enableBrotli(true)
//            .setUserAgent(USER_AGENT)
            .setStoragePath(File(context.cacheDir, "cronet$playerId").apply { mkdirs() }.absolutePath)
            .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, 10L * 1024 * 1024)
        if (config?.type == Type.HTTP && !config.host.isNullOrEmpty()) {
            val port = if (config.port > 0) config.port else ProxyConfig.DEFAULT_PORT
            val proxy = Proxy.createHttpProxy(Proxy.SCHEME_HTTP, config.host!!, port, executor,
                object : Proxy.HttpConnectCallback() {
                    override fun onBeforeRequest(request: Request) {
                        if (!config.username.isNullOrEmpty() && config.password != null) {
                            val credentials = "${config.username}:${config.password}"
                            val encoded = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                            request.proceed(listOf(Pair("Proxy-Authorization", "Basic $encoded")))
                        } else request.proceed(emptyList())
                    }
                    override fun onResponseReceived(responseHeaders: MutableList<Pair<String, String>>, statusCode: Int): Int {
                        return RESPONSE_ACTION_PROCEED
                    }
                }
            )
            val proxyOptions = ProxyOptions.fromProxyList(listOf(proxy), ProxyOptions.ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT)
            builder.setProxyOptions(proxyOptions)
        }
        return builder.build()
    }

    fun createHttpDataSourceFactory(context: Context, executor: Executor): HttpDataSource.Factory {
        Logd(TAG, "createHttpDataSourceFactory proxyConfig: ${proxyConfig?.host}")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 && (proxyConfig == null || proxyConfig!!.host == null)) {
            Logd(TAG, "createHttpDataSourceFactory setting HttpEngine")
            if (httpEngine == null) httpEngine = HttpEngine.Builder(context)
                .setEnableQuic(true)
//                .setUserAgent(USER_AGENT)
                .setStoragePath(File(context.cacheDir, "httpengine$playerId").apply { mkdirs() }.absolutePath)
                .setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, 10L * 1024 * 1024)
                .build()
            HttpEngineDataSource.Factory(httpEngine!!, executor)
                .setConnectionTimeoutMs(8_000)
                .setReadTimeoutMs(8_000)
        } else {
            if (cronetEngine == null) cronetEngine = createCronetEngine(context, proxyConfig, executor)
            CronetDataSource.Factory(cronetEngine!!, executor)
                .setConnectionTimeoutMs(8_000)
                .setReadTimeoutMs(8_000)
        }
    }

    override fun createNativePlayer() {
        if (exoPlayer != null) return
        timeIt("$TAG createNativePlayer")

        loadControl = DynamicLoadControl()
        val audioOffloadPreferences = AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(if (offloadEnabled) AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
            .build()
        Logd(TAG, "createNativePlayer creating exoPlayer lr: $lr")

        runBlocking { initCache() }

        trackSelector = DefaultTrackSelector(context)
        val renderersFactory = object : DefaultRenderersFactory(context) {
            init {
                setEnableAudioOutputPlaybackParameters(false)
            }
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val builder = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloat)
                    .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                if (lr == -1) builder.setAudioProcessors(arrayOf(LeftChannelAudioProcessor()))
                else if (lr == 1) builder.setAudioProcessors(arrayOf(RightChannelAudioProcessor()))
                return builder.build()
            }
        }

        //        val baseHttpDataSourceFactory = OkHttpDataSource.Factory(getOKHttpClient())
        //        val upstreamFactory = DefaultDataSource.Factory(context, baseHttpDataSourceFactory)
        //        val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(upstreamFactory)

        val extractorsFactory = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val httpDataSourceFactory = createHttpDataSourceFactory(context, networkExecutor)
        val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(getCache())
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        cacheDataSourceFactory.setEventListener(
            object : CacheDataSource.EventListener {
                override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                    Logd(TAG, "CacheDataSource onCachedBytesRead cachedBytes=$cachedBytesRead cacheSize=$cacheSizeBytes")
                }
                override fun onCacheIgnored(reason: Int) {
                    Logd(TAG, "CacheDataSource onCacheIgnored ignored=$reason")
                }
            }
        )
        recordingFactory = SegmentSavingDataSourceFactory(cacheDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory).setDataSourceFactory(recordingFactory!!)

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl!!)
            .setTrackSelector(trackSelector!!)
            .setSeekBackIncrementMs(rewindSecs * 1000L)
            .setSeekForwardIncrementMs(fastForwardSecs * 1000L)
            .build()
        exoPlayer?.setSeekParameters(SeekParameters.DEFAULT)

        exoPlayer?.trackSelectionParameters = exoPlayer!!.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(audioOffloadPreferences)
            .build()

        Logd(TAG, "createNativePlayer exoplayerListener == null: ${exoplayerListener == null}")
        if (exoplayerListener != null) {
            exoPlayer?.removeListener(exoplayerListener!!)
            exoPlayer?.addListener(exoplayerListener!!)
        }
        if (exoplayerOffloadListener != null) {
            exoPlayer?.removeAudioOffloadListener(exoplayerOffloadListener!!)
            exoPlayer?.addAudioOffloadListener(exoplayerOffloadListener!!)
        }
        castPlayer = buildCastPlayer(exoPlayer!!)

        timeIt("$TAG createNativePlayer end")
    }

    private fun release() {
        Logd(TAG, "release() called")
        castPlayer?.stop()
        exoPlayer?.stop()
//        castPlayer?.seekTo(0L)
//        castPlayer?.clearMediaItems()
//        bufferingUpdater = null
    }

    fun mediaSourceFromClient(needVideo: Boolean, sameMedia: Boolean = false): MediaSource? {
        val media = curMediaFlow.value ?: return null
        if (curClient == null)  return null

        var mSource: MediaSource? = null
        val context = getAppContext()
        val metadata = buildMetadata(media)

        playingMuxedVideo = false

        fun setMuxedVideo() {
            if (!sameMedia || muxedSpecs.isEmpty()) muxedSpecs = curClient?.withProviderBlocking { it.getVideoSpecs(media.toIPC()) } ?: listOf()
            if (muxedSpecs.isNotEmpty()) {
                val videoSpec = setVideoSpec(muxedSpecs, media)
                if (!videoSpec.url.isNullOrBlank()) {
                    val vSource = DefaultMediaSourceFactory(context).createMediaSource(MediaItem.Builder().setMediaMetadata(metadata).setTag(metadata).setUri(videoSpec.url!!.toSafeUri()).build())
                    mSource = MergingMediaSource(true, vSource)
                    playingVideoFlow.value = true
                    playingMuxedVideo = true
                    Logt(TAG, "Using muxed video stream")
                } else Loge(TAG, "videoStream or url is null or blank")
            } else Logt(TAG, "Client provided no muxed video stream")
        }

        Logd(TAG, "mediaSourceFromClient setting for source needVideo: $needVideo media: ${media.title}")
        if (!sameMedia) {
            audioSpecs = listOf()
            videoSpecs = listOf()
            muxedSpecs = listOf()
        }
        if ((curClient?.attributes?.hasSeparateAVs == true && media.feed?.useMuxedVideo != true) || curClient?.attributes?.hasVideo != true) {
            if (!sameMedia || audioSpecs.isEmpty()) audioSpecs = curClient?.withProviderBlocking { it.getAudioSpecs(media.toIPC()) } ?: listOf()
            var aSource: ProgressiveMediaSource? = null
            if (audioSpecs.isNotEmpty()) {
                Logd(TAG, "mediaSourceFromClient audioSpecs ${audioSpecs.size}")
                val audioSpec = setAudioSpec(audioSpecs, media)
                if (audioSpec != null) {
                    if (!audioSpec.url.isNullOrBlank()) {
                        aSource = ProgressiveMediaSource.Factory(recordingFactory!!).createMediaSource(MediaItem.Builder().setMediaMetadata(metadata).setTag(metadata).setUri(audioSpec.url!!.toSafeUri()).setCustomCacheKey(media.id.toString()).build())
                        Logd(TAG, "mediaSourceFromClient aSource set to: ${audioSpec.url}")
                    } else Loge(TAG, "eligible audioStream or its url is null or blank")
                }
            } else Logt(TAG, "Client provided no audio stream, trying with muxed video stream")

            if ((aSource == null || needVideo) && curClient?.attributes?.hasVideo == true) {
                if (aSource == null) setMuxedVideo()
                else {
                    if (!sameMedia || videoSpecs.isEmpty()) videoSpecs = curClient?.withProviderBlocking { it.getVideoOnlySpecs(media.toIPC()) } ?: listOf()
                    Logd(TAG, "mediaSourceFromClient videoSpecs ${videoSpecs.size}")
                    if (videoSpecs.isNotEmpty()) {
                        val videoSpec = setVideoSpec(videoSpecs, media)
                        if (!videoSpec.url.isNullOrBlank()) {
                            val vSource = DefaultMediaSourceFactory(context).createMediaSource(MediaItem.Builder().setMediaMetadata(metadata).setTag(metadata).setUri(videoSpec.url!!.toSafeUri()).build())
                            val mediaSources: MutableList<MediaSource> = mutableListOf()
                            mediaSources.add(vSource)
                            mediaSources.add(aSource)
                            mSource = MergingMediaSource(true, *mediaSources.toTypedArray<MediaSource>())
                            Logd(TAG, "mediaSourceFromClient vSource set to: ${videoSpec.url}")
                        } else Loge(TAG, "videoStream or url is null or blank")
                    } else Logt(TAG, "Client provided no video stream")
                }
            } else mSource = aSource
        } else setMuxedVideo()
        return mSource
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    override fun prepareDataSource(sameMedia: Boolean) {
        val media = curMediaFlow.value ?: return
        Logd(TAG, "prepareDataSource called ${media.title}")
        Logd(TAG, "prepareDataSource url [${media.downloadUrl}]")
        mediaItem = null
        mediaSource = null
        val url = media.downloadUrl
        if (url.isNullOrBlank()) {
            LogeFor(TAG, media.id, "prepareDataSource: media downloadUrl is null or blank ${media.title}")
            upsertBlk(media) { it.setPlayState(EpisodeState.ERROR) }
            throw IllegalArgumentException("blank url")
        }
        val feed = media.feed
        val user = feed?.username
        val password = feed?.password
        bitrateFlow.value = 0
        resolutionFlow.value = ""
        try {
            mediaSource = mediaSourceFromClient(media.forceVideo || media.feed?.videoModePolicy != VideoMode.AUDIO_ONLY, sameMedia = sameMedia)
            if (mediaSource != null) {
                Logd(TAG, "prepareDataSource setting with mediaSource")
                mediaItem = mediaSource?.mediaItem
                setSourceCredentials(user, password)
            } else {
                curClient = null
                Logd(TAG, "prepareDataSource setting date source")
                prepareDataSource(url, user, password)
            }
        } catch (e: Throwable) {
            LogsFor(TAG, media.id, "prepareDataSource: ${e.message}")
            upsertBlk(media) { it.setPlayState(EpisodeState.ERROR) }
            throw e
        }
    }

    override fun prepareDataSource(mediaUrl: String, user: String?, password: String?) {
        val media = curMediaFlow.value ?: return
        mediaItem = null
        mediaSource = null
        val metadata = buildMetadata(media)
        Logd(TAG, "prepareDataSource: $mediaUrl")
        val uri = mediaUrl.toSafeUri()
        Logd(TAG, "prepareDataSource position: ${media.position} uri: $uri")
        mediaItem = MediaItem.Builder().setUri(uri).setCustomCacheKey(media.id.toString()).setMediaMetadata(metadata).build()
        setSourceCredentials(user, password)
    }

    private fun setSourceCredentials(user: String?, password: String?) {
        fun encodeCredentials(username: String, password: String, charset: String?): String {
            try {
                val credentials = "$username:$password"
                val bytes = credentials.toByteArray(charset(charset!!))
                val encoded: String = ByteString.of(*bytes).base64()
                return "Basic $encoded"
            } catch (e: UnsupportedEncodingException) { throw AssertionError(e) }
        }
        if (!user.isNullOrEmpty() && !password.isNullOrEmpty()) {
            // TODO: need to check
            mediaItem = mediaItem!!.buildUpon().setTag(encodeCredentials(user, password, "ISO-8859-1")).build()
        }
    }

    override fun shouldSetSource(): Boolean {
        return castPlayer?.playbackState in listOf(STATE_IDLE, STATE_ENDED)
    }

    override fun setSource() {
        Logd(TAG, "setSource() called")
        if (mediaSource == null && mediaItem == null) return
        if (needChangeOffload) {
            val enabled = speedEnablesOffload && silenceEnablesOffload
            if (enabled != offloadEnabled) {
                offloadEnabled = enabled
                Logt(TAG, "switchOffload set audio offload $offloadEnabled")
                exoPlayer!!.trackSelectionParameters = exoPlayer!!.trackSelectionParameters.buildUpon().setAudioOffloadPreferences(AudioOffloadPreferences.Builder().setAudioOffloadMode(if (offloadEnabled) AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build()).build()
            }
            needChangeOffload = false
        }
        if (isCasting) castPlayer?.setMediaItem(mediaItem!!, curMediaFlow.value!!.position.toLong())
        else {
            if (mediaSource != null) exoPlayer?.setMediaSource(mediaSource!!, positionWithRewind(curMediaFlow.value!!.position, curMediaFlow.value!!.lastPlayedTime).toLong())
            else castPlayer?.setMediaItem(mediaItem!!, positionWithRewind(curMediaFlow.value!!.position, curMediaFlow.value!!.lastPlayedTime).toLong())
        }
        castPlayer?.prepare()
    }

    override fun setPlaybackParams() {
        castPlayer?.playbackParameters = playbackParameters
        curPlayerSpeedFlow.value = playbackParameters.speed
    }

    override fun setPlaybackParams(speed: Float, pitch: Float) {
        if (castPlayer == null) return

        resetPosSaverInterval(speed)

        if (abs(castPlayer!!.playbackParameters.speed - speed) < 0.01f) return
        Logd(TAG, "setPlaybackParams speed=$speed pitch=${playbackParameters.pitch}")
        val wantsOffload = speed == 1f
        if (wantsOffload != speedEnablesOffload) {
            speedEnablesOffload = wantsOffload
            needChangeOffload = true
            if (isPlaying) switchOffload()
        }

        val basePlaybackMs = 800
        val baseRebufferMs = 2500
        val baseMinBufferMs = 15_000
        val baseMaxBufferMs = 50_000

//        val targetPlaybackMs = (basePlaybackMs * speed).toInt().coerceIn(800, 3000)
        val targetPlaybackMs = basePlaybackMs
        val targetRebufferMs = (baseRebufferMs * speed).toInt().coerceIn(2000, 8000)
        val targetMaxBufferMs = maxOf(baseMaxBufferMs, (baseMinBufferMs * speed).toInt() + 10_000)
        Logd(TAG, "set player buffer: $baseMinBufferMs $targetMaxBufferMs $targetPlaybackMs $targetRebufferMs")
        loadControl?.updateBufferParameters(minBufferMs = baseMinBufferMs, maxBufferMs = targetMaxBufferMs, playbackMs = targetPlaybackMs, rebufferMs = targetRebufferMs, true)

        playbackParameters = PlaybackParameters(if (speed <= 0) playbackParameters.speed else speed, if (pitch <= 0f) playbackParameters.pitch else pitch)
        setPlaybackParams()
        Logd(TAG, "setPlaybackParams offloadEnabled $speedEnablesOffload")
    }

    override fun setSkipSilence() {
        val skipSilence = skipSilence ?: curMediaFlow.value?.feed?.skipSilence ?: isSkipSilence
        Logd(TAG, "setSkipSilence skipSilence: $skipSilence")
        val wantsOffload = !skipSilence
        if (wantsOffload != silenceEnablesOffload) {
            silenceEnablesOffload = wantsOffload
            needChangeOffload = true
            if (isPlaying) switchOffload()
        }
        exoPlayer?.skipSilenceEnabled = skipSilence
        Logd(TAG, "setSkipSilence offloadEnabled $silenceEnablesOffload")
    }

    override fun getPlaybackSpeed(): Float {
        var retVal = 1f
        if (isPlaying || isPaused || isInitialized || isPrepared) retVal = playbackParameters.speed
        return retVal
    }

    override fun getPlayerPosition(): Int {
        return if (castPlayer?.isCommandAvailable(COMMAND_GET_CURRENT_MEDIA_ITEM) == true) castPlayer!!.currentPosition.toInt() else Episode.INVALID_TIME
    }

    override fun setCastPlayImmediately() {
        if (castPlayer?.isCommandAvailable(COMMAND_PLAY_PAUSE ) == true) castPlayer?.playWhenReady = true
    }

    override fun playChime() {
        RingtoneManager.getRingtone(context, appPrefsFlow!!.value.ringToneUriString!!.toUri()).play()
    }

    override fun notifyWidget() {
        CoroutineScope(Dispatchers.IO).launch {
            val manager = GlanceAppWidgetManager(getAppContext())
            val glanceId = manager.getGlanceIds(PodciniWidget::class.java).find { it.toString() == widgetId }
            glanceId?.let { id ->
                val episodes = actQueueFlow.value.episodesSorted.take(40).map { it.toWidget() }
                val json = Json.encodeToString(episodes)
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                    prefs.toMutablePreferences().apply {
                        this[stringPreferencesKey("episodes")] = json
                        this[stringPreferencesKey("update_type")] = "update"
                    }
                }
                PodciniWidget().update(context, id)
            }
        }
    }

    override fun setVolume(volumeLeft: Float, volumeRight: Float, adaptionFactor: Float) {
        var volumeLeft = volumeLeft
        var volumeRight = volumeRight
//        Logd(TAG, "setVolume: $volumeLeft $volumeRight $adaptionFactor")
        if (adaptionFactor != 1f) {
            volumeLeft *= adaptionFactor
            volumeRight *= adaptionFactor
        }
        Logd(TAG, "setVolume 1: $volumeLeft $volumeRight")
        if (volumeLeft > 1) {
            castPlayer?.volume = 1f
            loudnessEnhancer?.enabled = true
            loudnessEnhancer?.setTargetGain((1000 * (volumeLeft - 1)).toInt())
        } else {
            castPlayer?.volume = volumeLeft
            loudnessEnhancer?.enabled = false
        }
        Logd(TAG, "Media player volume was set to $volumeLeft $volumeRight")
    }

    override fun shutdown() {
        Logd(TAG, "shutdown() called")
        try {
//            bufferingUpdater = { }
            if (exoPlayer?.isPlaying == true) exoPlayer?.stop()
        } catch (e: Exception) { LogsFor(TAG, curMediaFlow.value?.id, e) }
        release()
        status = PlayerStatus.STOPPED
        statusSimpleFlow.value = PlayerStatusSimple.fromStatus(status)
    }

    override fun setAudioTrack(track: Int) {
        val trackGroups = trackSelector!!.currentMappedTrackInfo?.getTrackGroups(audioRendererIndex) ?: return
        val override = TrackSelectionOverride(trackGroups.get(track), 0)
        val params = trackSelector!!.buildUponParameters().addOverride(override).build()
        trackSelector!!.setParameters(params)
    }

    override fun getSelectedAudioTrack(): Int {
        val tracks = exoPlayer?.currentTracks ?: return -1
        val availableFormats = formats
        Logd(TAG, "selectedAudioTrack called tracks: ${tracks.groups.size} formats: ${availableFormats.size}")
        for (group in tracks.groups) {
            if (group.isSelected) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        val selectedFormat = group.getTrackFormat(i)
                        val index = availableFormats.indexOf(selectedFormat)
                        if (index != -1) return index
                    }
                }
            }
        }
        return -1
    }

    override fun resetMediaPlayer() {
        Logd(TAG, "resetMediaPlayer()")
        // TODO: test
//        if (isCasting) release()
        if (curMediaFlow.value == null) {
            release()
            handlePlayerStatus(PlayerStatus.STOPPED, null)
            return
        }
        val i = curMediaFlow.value?.feed?.audioType?: C.AUDIO_CONTENT_TYPE_SPEECH
        val a = exoPlayer!!.audioAttributes
        val b = AudioAttributes.Builder().setContentType(i).setUsage(C.USAGE_MEDIA)
        Logd(TAG, "activeTheatres: ${activeTheatresCount.value}")
        exoPlayer?.setAudioAttributes(b.build(), activeTheatresCount.value <= 1 && handleAudioFocus)
        Logd(TAG, "AudioAttributes: usage=${b.build().usage} contentType=${b.build().contentType} handleAudioFocus=${activeTheatresCount.value <= 2}")
    }

    fun isRangeCached(cache:  SimpleCache, key: String, startByte: Long, endByte: Long): Boolean {
        var coveredUntil = startByte
//        Logd(TAG, "isRangeCached cache keys=${cache.keys}")
        val spans = cache.getCachedSpans(key).sortedBy { it.position }
        Logd(TAG, "isRangeCached key: $key spans: ${spans.size}")
        for (span in spans) {
            val spanStart = span.position
            val spanEnd = span.position + span.length
            if (spanEnd <= coveredUntil) continue
            if (spanStart > coveredUntil) return false
            coveredUntil = maxOf(coveredUntil, spanEnd)
            if (coveredUntil >= endByte) return true
        }
        return false
    }

    override fun recordClip(startPositionMs: Long, endPositionMs: Long?) {
        val mediaItem = exoPlayer!!.currentMediaItem ?: run {
            Loge(TAG, "recordClip failed: No current media item.")
            return
        }
        val uri = mediaItem.localConfiguration?.uri ?: run {
            Loge(TAG, "recordClip failed: No URI in MediaItem.")
            return
        }
        val tracks = exoPlayer!!.currentTracks
        val audioFormat = tracks.groups.asSequence()
            .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it) } }
            .firstOrNull { it.sampleMimeType?.startsWith("audio/") == true }
        if (audioFormat == null) {
            Loge(TAG,  "recordClip failed: No audio track found.")
            return
        }
        val mimeType = audioFormat.sampleMimeType
        Logd(TAG, "mimeType: [$mimeType]")
        val ext = getFileExtensionFromMimeType(mimeType)
        if (ext == null) {
            Loge(TAG, "recordClip failed: Audio format not supported for recording: $ext")
            return
        }
        if (endPositionMs == null) {
            if (uri.scheme == "file" || uri.scheme == "content") {
                Logd(TAG, "uri is file or content, will extract from the file.")
                return
            }
            curDataSource = recordingFactory?.currentDataSource
            curDataSource?.startRecording(startPositionMs, bitrateFlow.value, cacheDir)
            val currentPos = exoPlayer!!.currentPosition
            val seekTarget = (currentPos - 1000L).coerceAtLeast(0L)
            exoPlayer?.seekTo(seekTarget)
            return
        }

        runOnIOScope {
            val clipname = "${durationStringShort(startPositionMs, false, "m")}-${durationStringShort(endPositionMs, false, "m")}.$ext"
            val outputFile = curMediaFlow.value!!.getClipFile(clipname)
            when {
                uri.scheme == "file" || uri.scheme == "content" -> {
                    val bytesPerSecond = bitrateFlow.value / 8.0
                    val startByte = (startPositionMs * bytesPerSecond / 1000).toLong()
                    val endByte = (endPositionMs * bytesPerSecond / 1000).toLong()
                    val bytesToRead = endByte - startByte
                    val tempFile = cacheDir / "temp_segment.${outputFile.extension}"
                    try {
                        val sourceFile = uri.toUF()
                        val allBytes = sourceFile.readBytes()
                        val segmentBytes = allBytes.sliceArray(startByte.toInt() until (startByte + bytesToRead).toInt())
                        tempFile.writeBytes(segmentBytes)
                        val segment = tempFile.readBytes()
                        if (segment.isNotEmpty()) {
                            val adjustedSegment = when (audioFormat.sampleMimeType) {
                                "audio/mp3" -> adjustMp3Clip(segment)
                                "audio/aac" -> adjustRawAacClip(segment)
                                "audio/ogg" -> adjustLocalOggClip(segment)
                                "audio/mp4" -> adjustLocalMp4Clip(segment)
                                else -> segment
                            }
                            outputFile.writeBytes(adjustedSegment)
                            upsert(curMediaFlow.value!!) { it.clips.add(clipname) }
                            Logd(TAG, "Saved local clip to: ${outputFile.absPath}")
                        } else Loge(TAG, "recordClip: Failed to extract segment from local media")
                    } catch (e: Exception) { Loge(TAG, e, "recordClip failed: FileKit operation failed") } finally { tempFile.delete() }
                }
                else -> {   // streaming
                    Logd(TAG, "curDataSource==null: ${curDataSource==null}")
                    val tempFileDS = curDataSource?.stopRecording(endPositionMs)
                    val cache = getCache()
                    val key = curMediaFlow.value!!.id.toString()

                    if (tempFileDS != null) {
                        Logd(TAG, "Segment not available in cache or full file extraction. Trying with player extract")
                        val bytesPerSecond = bitrateFlow.value / 8.0
                        val startByte = (startPositionMs * bytesPerSecond / 1000).toLong()
                        val endByte = (endPositionMs * bytesPerSecond / 1000).toLong()
                        val bytesToRead = endByte - startByte
                        val tempOutput = outputFile.parent()!! / "temp_segment.${outputFile.extension}"
                        try {
                            tempFileDS.source().buffer().use { input ->
                                val segmentData = input.readByteArray(bytesToRead)
                                val totalRead = segmentData.size
                                tempOutput.writeBytes(segmentData)
                                Logd(TAG, "Total written: $totalRead bytes")
                            }
                        } catch (e: Exception) { Loge(TAG, e, "recordClip: Failed to extract from temp files") }
                        val segment = tempOutput.readBytes()
                        tempOutput.delete()
                        if (segment.isNotEmpty()) {
                            val adjustedSegment = when (audioFormat.sampleMimeType) {
                                "audio/mp3" -> adjustMp3Clip(segment)
                                "audio/aac" -> adjustRawAacClip(segment)
                                "audio/ogg" -> adjustOggClip(segment, cache, key, startByte, endByte)
                                "audio/mp4" -> adjustMp4Clip(segment, cache, key, startByte, endByte)
                                else -> segment
                            }
                            outputFile.writeBytes(adjustedSegment)
                            upsert(curMediaFlow.value!!) { it.clips.add(clipname) }
                            Logd(TAG, "Saved clip to: ${outputFile.absPath}")
                        } else Loge(TAG, "recordClip: Failed to extract segment from temp file")
                        tempFileDS.delete()
                    } else Loge(TAG, "recordClip: Failed saving clip: No temp file available after stopping recording")
                }
            }
        }
    }

    private fun adjustMp3Clip(bytes: ByteArray): ByteArray = bytes
    private fun adjustRawAacClip(bytes: ByteArray): ByteArray = bytes

    private fun adjustOggClip(bytes: ByteArray, cache: SimpleCache, key: String, startByte: Long, endByte: Long): ByteArray {
        if (startByte > 0) {
            val headerBytes = getHeaderBytesFromCache(cache, key, 1024)
            return headerBytes?.plus(bytes) ?: bytes
        }
        return bytes
    }

    private fun adjustMp4Clip(bytes: ByteArray, cache: SimpleCache, key: String, startByte: Long, endByte: Long): ByteArray {
        if (startByte > 0 || endByte < spansTotalLength(cache, key)) {
            LogtFor(TAG, curMediaFlow.value?.id, "MP4 clip may not be playable without re-muxing.")
            val fullFileBytes = getFullFileFromCache(cache, key)
            return fullFileBytes ?: bytes
        }
        return bytes
    }
    private fun adjustLocalOggClip(bytes: ByteArray): ByteArray = bytes
    private fun adjustLocalMp4Clip(bytes: ByteArray): ByteArray {
        LogtFor(TAG, curMediaFlow.value?.id, "Local MP4 clip may not be playable without re-muxing.")
        return bytes
    }

    private fun getHeaderBytesFromCache(cache: SimpleCache, key: String, maxHeaderSize: Int): ByteArray? {
        val firstSpan = cache.getCachedSpans(key).minByOrNull { it.position } ?: return null
        if (firstSpan.position > 0 || firstSpan.file?.exists() != true) return null
        return firstSpan.file!!.inputStream().use { input ->
            val buffer = ByteArray(maxHeaderSize)
            val bytesRead = input.read(buffer, 0, maxHeaderSize)
            if (bytesRead > 0) buffer.copyOf(bytesRead) else null
        }
    }

    private fun getFullFileFromCache(cache: SimpleCache, key: String): ByteArray? {
        val spans = cache.getCachedSpans(key).sortedBy { it.position }
        if (spans.isEmpty()) return null
        val outputStream = ByteArrayOutputStream()
        spans.forEach { span -> span.file?.inputStream()?.use { it.copyTo(outputStream) } }
        return outputStream.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun spansTotalLength(cache: SimpleCache, key: String): Long = cache.getCachedSpans(key).sumOf { it.length }

    private fun getFileExtensionFromMimeType(mimeType: String?): String? {
        return when (mimeType) {
            "audio/mp3", "audio/mpeg" -> "mp3"
            "audio/aac" -> "aac"
//            "audio/mp4" -> "m4a"
//            "audio/ogg" -> "ogg"
            else -> null
        }
    }

    fun Player.contentPositionToByte(positionMs: Long): Long? {
        val timeline = currentTimeline
        if (timeline.isEmpty) return null
        val window = Timeline.Window()
        timeline.getWindow(currentMediaItemIndex, window)
        val format = currentTracks.groups.firstOrNull { it.isSelected }?.getTrackFormat(0)
        val bitrate = format?.averageBitrate?.takeIf { it != Format.NO_VALUE } ?: return null
        return (positionMs * bitrate) / 8000 // bps to bytes
    }

    override fun onDestroy() {
        if (exoplayerListener != null) exoPlayer?.removeListener(exoplayerListener!!)
        if (exoplayerOffloadListener != null) exoPlayer?.removeAudioOffloadListener(exoplayerOffloadListener!!)
        exoplayerListener = null
        exoplayerOffloadListener = null
//        bufferingUpdater = null
        loudnessEnhancer = null
//        httpDataSourceFactory = null

        castPlayer = null
        exoPlayer = null

        super.onDestroy()
    }

    companion object {
        private val TAG: String = Media3Player::class.simpleName ?: "Anonymous"

//        private const val ACTION_PLAYER_STATUS_CHANGED: String = "action.ac.mdiq.podcini.service.playerStatusChanged"
//        private const val AVRCP_ACTION_PLAYER_STATUS_CHANGED = "com.android.music.playstatechanged"
//        private const val AVRCP_ACTION_META_CHANGED = "com.android.music.metachanged"

        const val BUFFERING_STARTED: Int = -1
        const val BUFFERING_ENDED: Int = -2

        private var enableFloat = false     // float is not well handled in Android devices

        var httpEngine: HttpEngine? = null
        var cronetEngine: CronetEngine? = null

        var simpleCache: SimpleCache? = null

        fun getCache(): SimpleCache {
            return simpleCache ?: throw IllegalStateException("Cache not initialized yet!")
        }

        fun releaseCache() {
            simpleCache?.release()
            simpleCache = null
        }

        fun nuclearCacheWipe() {
            val cacheDir = File(getAppContext().cacheDir, "media_cache")
            if (cacheDir.exists()) {
                val success = cacheDir.deleteRecursively()
                Logt(TAG, "Physical cache folder deleted: $success")
            }
        }

        fun buildMetadata(e: Episode): MediaMetadata {
            val date = Instant.fromEpochMilliseconds(e.pubDate).toLocalDateTime(TimeZone.UTC).date

            val builder = MediaMetadata.Builder()
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)

                .setTitle(e.title)
                .setDisplayTitle(e.title)
                .setSubtitle(e.feed?.title ?: "")
                .setDescription(e.description ?: "")

                .setArtist(e.feed?.title ?: "")
                .setAlbumArtist(e.feed?.title ?: "")
                .setAlbumTitle(e.feed?.title ?: "")

                // Release date (expects "YYYY-MM-DD" format)
                .setRecordingDay(date.day)
                .setRecordingMonth(date.month.number)
                .setRecordingYear(date.year)

                .setArtworkUri((e.imageUrl ?: e.feed?.imageUrl ?: "").toSafeUri())
            return builder.build()
        }
    }
}
