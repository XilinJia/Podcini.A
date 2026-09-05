package ac.mdiq.podcini.playback.service

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.playback.PlaybackStarter
import ac.mdiq.podcini.playback.base.Media3Player
import ac.mdiq.podcini.playback.base.Media3Player.Companion.buildMetadata
import ac.mdiq.podcini.playback.base.Media3Player.Companion.createDataSourceEngine
import ac.mdiq.podcini.playback.base.SleepManager
import ac.mdiq.podcini.playback.base.SleepManager.Companion.sleepManager
import ac.mdiq.podcini.playback.base.actQueueFlow
import ac.mdiq.podcini.playback.base.activeTheatresCount
import ac.mdiq.podcini.playback.base.cleanupTheatres
import ac.mdiq.podcini.playback.base.isCurMedia
import ac.mdiq.podcini.playback.base.theatres
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.episodeByGuidOrUrl
import ac.mdiq.podcini.storage.database.episodeById
import ac.mdiq.podcini.storage.database.fastForwardSecs
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.rewindSecs
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.CurrentState
import ac.mdiq.podcini.storage.model.PlayQueue
import ac.mdiq.podcini.storage.model.QueueEntry
import ac.mdiq.podcini.storage.utils.toSafeUri
import ac.mdiq.podcini.utils.EventFlow
import ac.mdiq.podcini.utils.FlowEvent
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.LogeFor
import ac.mdiq.podcini.utils.LogsFor
import ac.mdiq.podcini.utils.LogtFor
import ac.mdiq.podcini.utils.timeIt
import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.bluetooth.BluetoothA2dp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.EXTRA_KEY_EVENT
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.KeyEvent.KEYCODE_MEDIA_STOP
import android.view.ViewConfiguration
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.work.impl.utils.futures.SettableFuture
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(Dispatchers.Main)

    private var mediaLibrarySession: MediaLibrarySession? = null
    private val notificationCustomButtons = NotificationCustomButton.entries.map { command -> command.commandButton }

    private var clickCount = 0
    private val clickHandler = Handler(Looper.getMainLooper())

    private val autoStateUpdated: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val player = theatres[0].mPlayerFlow.value ?: return
            Logd(TAG, "autoStateUpdated onReceive called with action: ${intent.action}")
            val status = intent.getStringExtra("media_connection_status")
            Logd(TAG, "Received Auto Connection update: $status")
            if ("media_connected" != status) Logd(TAG, "Car was unplugged during playback.")
            else {
                when  {
                    player.isPaused || player.isPrepared -> player.play()
                    player.isInitialized -> {
                        player.isStartWhenPrepared = true
                        player.prepareInitialized()
                    }
                    else -> {}
                }
            }
        }
    }

    private val headsetDisconnected: BroadcastReceiver = object : BroadcastReceiver() {
        private val TAG = "headsetDisconnected"
        private val UNPLUGGED = 0
        private val PLUGGED = 1

        @RequiresPermission(Manifest.permission.VIBRATE)
        override fun onReceive(context: Context, intent: Intent) {
            // Don't pause playback after we just started, just because the receiver
            // delivers the current headset state (instead of a change)
            if (isInitialStickyBroadcast) return
            Logd(TAG, "headsetDisconnected onReceive called with action: ${intent.action}")
            if (intent.action == Intent.ACTION_HEADSET_PLUG) {
                val state = intent.getIntExtra("state", -1)
                Logd(TAG, "Headset plug event. State is $state")
                when (state) {
                    -1 -> LogeFor(TAG, theatres[0].mPlayerFlow.value?.curMediaFlow?.value?.id,"Received invalid ACTION_HEADSET_PLUG intent")
                    UNPLUGGED -> {}
                    PLUGGED -> unpauseIfPauseOnDisconnect(false)
                }
            }
        }
    }

    private val bluetoothStateUpdated: BroadcastReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.VIBRATE)
        override fun onReceive(context: Context, intent: Intent) {
            Logd(TAG, "bluetoothStateUpdated onReceive called with action: ${intent.action}")
            if (intent.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothA2dp.EXTRA_STATE, -1)
                if (state == BluetoothA2dp.STATE_CONNECTED) {
                    Logd(TAG, "Received bluetooth connection intent")
                    unpauseIfPauseOnDisconnect(true)
                }
            }
        }
    }

    private val audioBecomingNoisy: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (theatres[0].mPlayerFlow.value == null) return
            // sound is about to change, eg. bluetooth -> speaker
            Logd(TAG, "audioBecomingNoisy onReceive called with action: ${intent.action}")
            Logd(TAG, "Pausing playback because audio is becoming noisy")
//            pauseIfPauseOnDisconnect()
            transientPause = theatres[0].mPlayerFlow.value!!.isPlaying
            if (appPrefsFlow!!.value.pauseOnHeadsetDisconnect && !isCasting) theatres[0].mPlayerFlow.value?.pause(false)
        }
    }

//    private val shutdownReceiver: BroadcastReceiver = object : BroadcastReceiver() {
//        override fun onReceive(context: Context, intent: Intent) {
//            Logd(TAG, "shutdownReceiver onReceive called with action: ${intent.action}")
////            if (intent.action == ACTION_SHUTDOWN_PLAYBACK_SERVICE) EventFlow.postEvent(FlowEvent.PlaybackServiceEvent(FlowEvent.PlaybackServiceEvent.Action.SERVICE_SHUT_DOWN))
//        }
//    }

    inner class MediaLibrarySessionCK : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            Logd(TAG, "in MyMediaSessionCallback onConnect")
            isAutoController = controller.packageName == "com.google.android.projection.gearhead" || controller.packageName == "com.google.android.apps.automotive.templates.host"
            when {
                session.isMediaNotificationController(controller) -> {
                    Logd(TAG, "MyMediaSessionCallback onConnect isMediaNotificationController")
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    notificationCustomButtons.forEach { commandButton ->
                        Logd(TAG, "MyMediaSessionCallback onConnect commandButton ${commandButton.displayName}")
                        commandButton.sessionCommand?.let(sessionCommands::add)
                    }
                    return MediaSession.ConnectionResult.accept(sessionCommands.build(), playerCommands.build())
                }
                session.isAutoCompanionController(controller) -> {
                    Logd(TAG, "MyMediaSessionCallback onConnect isAutoCompanionController")
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    notificationCustomButtons.forEach { commandButton ->
                        Logd(TAG, "MyMediaSessionCallback onConnect commandButton ${commandButton.displayName}")
                        commandButton.sessionCommand?.let(sessionCommands::add)
                    }
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands.build())
                        .build()
                }
                else -> {
                    Logd(TAG, "MyMediaSessionCallback onConnect other controller: $controller")
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
                }
            }
        }
        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            super.onPostConnect(session, controller)
            Logd(TAG, "MyMediaSessionCallback onPostConnect")
            if (notificationCustomButtons.isNotEmpty()) {
                mediaLibrarySession?.setCustomLayout(notificationCustomButtons)
//                mediaSession?.setCustomLayout(customMediaNotificationProvider.notificationMediaButtons)
            }
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            val player = theatres[0].mPlayerFlow.value
            Logd(TAG, "MyMediaSessionCallback onCustomCommand ${customCommand.customAction}")
            when (customCommand.customAction) {
                NotificationCustomButton.REWIND.customAction -> player?.seekDelta(-rewindSecs * 1000)
                NotificationCustomButton.FORWARD.customAction -> player?.seekDelta(fastForwardSecs * 1000)
                NotificationCustomButton.SKIP.customAction -> if (appPrefsFlow!!.value.showSkip) player?.skip()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            Logd(TAG, "MyMediaSessionCallback onPlaybackResumption isForPlayback: $isForPlayback")
            val settable = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
//            scope.launch {
//                // Your app is responsible for storing the playlist and the start position to use here
//                val resumptionPlaylist = restorePlaylist()
//                settable.set(resumptionPlaylist)
//            }
            return settable
        }
        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            Logd(TAG, "in MyMediaSessionCallback onDisconnected")
            when {
                session.isMediaNotificationController(controller) -> {
                    Logd(TAG, "MyMediaSessionCallback onDisconnected isMediaNotificationController")
                }
                session.isAutoCompanionController(controller) -> {
                    Logd(TAG, "MyMediaSessionCallback onDisconnected isAutoCompanionController")
                }
            }
        }
        override fun onMediaButtonEvent(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, intent: Intent): Boolean {
            val keyEvent = if (Build.VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) intent.extras!!.getParcelable(EXTRA_KEY_EVENT, KeyEvent::class.java)
            else {
                @Suppress("DEPRECATION")
                intent.extras!!.getParcelable(EXTRA_KEY_EVENT) as? KeyEvent
            }
            Logd(TAG, "onMediaButtonEvent ${keyEvent?.keyCode}")
            if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                val keyCode = keyEvent.keyCode
                if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                    clickCount++
                    clickHandler.removeCallbacksAndMessages(null)
                    clickHandler.postDelayed({
                        when (clickCount) {
                            1 -> handleKeycode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, false)
                            2 -> theatres[0].mPlayerFlow.value?.seekDelta(fastForwardSecs * 1000)
                            3 -> theatres[0].mPlayerFlow.value?.seekDelta(-rewindSecs * 1000)
                        }
                        clickCount = 0
                    }, ViewConfiguration.getDoubleTapTimeout().toLong())
                    return true
                } else return handleKeycode(keyCode, false)
            }
            return false
        }
        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            Logd(TAG, "MyMediaSessionCallback onGetItem called mediaId:$mediaId")
            return super.onGetItem(session, browser, mediaId)
        }
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
            Logd(TAG, "MyMediaSessionCallback onGetLibraryRoot called")
            val rootItem: MediaItem = MediaItem.Builder().setMediaId("ActQueue")
                .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).setTitle(actQueueFlow.value.name).build())
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }
        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int,
                                   params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Logd(TAG, "MyMediaSessionCallback onGetChildren called parentId:$parentId page:$page pageSize:$pageSize")
//            return super.onGetChildren(session, browser, parentId, page, pageSize, params)
            val mediaItemsInQueue: MutableList<MediaItem> by lazy {
                val list = mutableListOf<MediaItem>()
                actQueueFlow.value.episodesSorted.forEach { e-> e.downloadUrl?.let { list += MediaItem.Builder().setMediaId(it).setUri(it.toSafeUri()).setMediaMetadata(buildMetadata(e)).build() } }
                Logd(TAG, "mediaItemsInQueue: ${list.size}")
                list
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(mediaItemsInQueue, params))
        }
        override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
                                 params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }
        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
            Logd(TAG, "MyMediaSessionCallback onAddMediaItems called ${mediaItems.size} ${mediaItems[0]}")
            // TODO check this out
            val episode = episodeByGuidOrUrl(null, mediaItems.first().mediaId, copy = false) ?: return Futures.immediateFuture(mutableListOf())
            if (!isCurMedia(episode)) {
                for (i in 0..1) {
                    if (episode.id != theatres[i].mPlayerFlow.value?.curMediaFlow?.value?.id) continue
                    PlaybackStarter(episode).start(i)
                }
            }
            val updatedMediaItems = mediaItems.map { it.buildUpon().setUri(it.mediaId).build() }.toMutableList()
//            updatedMediaItems += mediaItemsInQueue
//            Logd(TAG, "MyMediaSessionCallback onAddMediaItems updatedMediaItems: ${updatedMediaItems.size} ")
            return Futures.immediateFuture(updatedMediaItems)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        Logd(TAG, "onCreate Service created.")
        timeIt("$TAG onCreate Service")

        createDataSourceEngine()

        isRunning = true
        playbackService = this

        if (Build.VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
            registerReceiver(autoStateUpdated, IntentFilter("com.google.android.gms.car.media.STATUS"), RECEIVER_NOT_EXPORTED)
//            registerReceiver(shutdownReceiver, IntentFilter(ACTION_SHUTDOWN_PLAYBACK_SERVICE), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(autoStateUpdated, IntentFilter("com.google.android.gms.car.media.STATUS"))
//            registerReceiver(shutdownReceiver, IntentFilter(ACTION_SHUTDOWN_PLAYBACK_SERVICE))
        }

        registerReceiver(headsetDisconnected, IntentFilter(Intent.ACTION_HEADSET_PLUG))
        registerReceiver(bluetoothStateUpdated, IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED))
        registerReceiver(audioBecomingNoisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        procFlowEvents()
        sleepManager = SleepManager()

        if (mediaLibrarySession == null) createMediaSessionAndPlayers()

//        EventFlow.postEvent(FlowEvent.PlaybackServiceEvent(FlowEvent.PlaybackServiceEvent.Action.SERVICE_STARTED))
        timeIt("$TAG onCreate Service end")
    }

    private fun startTheatres() {
        timeIt("$TAG start of init")
        CoroutineScope(Dispatchers.IO).launch {
            for (i in 0..1) {
                val player = theatres[i].mPlayerFlow.value
                Logd(TAG, "starting curState for player: ${player?.playerId}")
                player?.curState = realm.query(CurrentState::class).query("id == $i").first().find() ?: run {
                    val cs = CurrentState()
                    cs.id = i.toLong()
                    upsertBlk(cs) { }
                }
                if (player != null && player.curState.curMediaId > 0L) player.setAsCurMedia(episodeById(player.curState.curMediaId))

                Logd(TAG, "curMediaFlow.value from preference: ${player?.curMediaFlow?.value?.title}")
                player?.curMediaFlow?.value?.let {
                    val qes = realm.query(QueueEntry::class).query("episodeId == ${it.id}").find()
                    if (qes.isNotEmpty()) realm.query(PlayQueue::class).query("id == ${qes[0].queueId}").first().find()?.let { q-> actQueueFlow.value = q }
                }
                theatres[i].curStateMonitor?.cancel()
                theatres[i].curStateMonitor = null
                theatres[i].monitorState()
            }
        }
        timeIt("$TAG end of init")
    }

    fun createMediaSessionAndPlayers() {
        Logd(TAG, "recreateMediaSession")
        setMediaNotificationProvider(CustomMediaNotificationProvider())

        recreateMediaPlayers()
        startTheatres()

        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)
        mediaLibrarySession = MediaLibrarySession.Builder(applicationContext, theatres[0].mPlayerFlow.value!!.castPlayer!!, MediaLibrarySessionCK())
            .setId(packageName)
            .setSessionActivity(pendingIntent)
            .setCustomLayout(notificationCustomButtons)
            .build()
    }

    fun shutdownPlayer(id: Int) {
        try {
            theatres[id].mPlayerFlow.value?.let {
                val wasPlaying = it.isPlaying
                if (wasPlaying) it.pause(reinit = false)
                it.shutdown()
            }
        } catch (e: Exception) { Loge(TAG, e, "Error shutting down player $id")}
    }

    fun recreateMediaPlayers() {
        for (id in 0..<activeTheatresCount.value) {
            Logd(TAG, "recreateMediaPlayer creating player $id of ${activeTheatresCount.value}")
            shutdownPlayer(id)
            theatres[id].mPlayerFlow.value = Media3Player(id, if (activeTheatresCount.value > 1) { if (id == 0) -1 else 1} else 0)
        }
    }

    fun switchPlayersMode() {
        recreateMediaPlayers()
        startTheatres()
        mediaLibrarySession?.player = theatres[0].mPlayerFlow.value!!.castPlayer!!
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Logd(TAG, "onTaskRemoved")
        val player = mediaLibrarySession?.player ?: return
        // Stop the service if not playing, continue playing in the background otherwise.
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == STATE_ENDED) stopSelf()
    }

    override fun onDestroy() {
        Logd(TAG, "Service is about to be destroyed")
        theatres[0].mPlayerFlow.value?.onDestroy()
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        theatres[1].mPlayerFlow.value?.onDestroy()

        cancelFlowEvents()
        unregisterReceiver(autoStateUpdated)
        unregisterReceiver(headsetDisconnected)
//        unregisterReceiver(shutdownReceiver)
        unregisterReceiver(bluetoothStateUpdated)
        unregisterReceiver(audioBecomingNoisy)
        sleepManager?.disable()

        cleanupTheatres()
        playbackService = null
        isRunning = false
        super.onDestroy()
    }

    fun isServiceReady(): Boolean = mediaLibrarySession?.player?.playbackState != STATE_IDLE && mediaLibrarySession?.player?.playbackState != STATE_ENDED

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    private fun handleKeycode(keycode: Int, notificationButton: Boolean): Boolean {
        val player = theatres[0].mPlayerFlow.value ?: return false
        LogtFor(TAG, player.curMediaFlow.value?.id, "Handling keycode: $keycode")
        // TODO: check out this
        fun startPlayingFromPreferences() {
            if (mediaLibrarySession == null) createMediaSessionAndPlayers()
            try {
                startTheatres()
                player.startPlaying()
            } catch (e: Throwable) { LogsFor(TAG, player.curMediaFlow.value?.id, e, "EpisodeMedia was not loaded from preferences.") }
        }
        when (keycode) {
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                when {
                    player.isPlaying -> player.pause(false)
                    player.isPlaying || player.isPrepared -> player.play()
                    player.isInitialized -> {
                        player.isStartWhenPrepared = true
                        player.prepareInitialized()
                    }
                    player.curMediaFlow.value == null -> startPlayingFromPreferences()
                    else -> return false
                }
                sleepManager?.restart()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                when {
                    player.isPlaying || player.isPrepared -> player.play()
                    player.isInitialized -> {
                        player.isStartWhenPrepared = true
                        player.prepareInitialized()
                    }
                    player.curMediaFlow.value == null -> startPlayingFromPreferences()
                    else -> return false
                }
                sleepManager?.restart()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (player.isPlaying) {
                    player.pause(false)
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                when {
                    // Handle remapped button as notification button which is not remapped again.
                    !notificationButton -> return handleKeycode(appPrefsFlow!!.value.hardwareForwardButton.toInt(), true)
                    player.isPlaying || player.isPaused -> {
                        player.skip()
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (player.isPlaying || player.isPaused) {
                    player.seekDelta(fastForwardSecs * 1000)
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                when {
                    // Handle remapped button as notification button which is not remapped again.
                    !notificationButton -> return handleKeycode(appPrefsFlow!!.value.hardwarePreviousButton.toInt(), true)
                    player.isPlaying || player.isPaused -> {
                        player.seekTo(0)
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (player.isPlaying || player.isPaused) {
                    player.seekDelta(-rewindSecs * 1000)
                    return true
                }
            }
            KEYCODE_MEDIA_STOP -> {
                if (player.isPlaying) player.pause(reinit = true)
                return true
            }
            else -> {
                Logd(TAG, "Unhandled key code: $keycode")
                // only notify the user about an unknown key event if it is actually doing something
                if (player.curMediaFlow.value != null && player.isPlaying) LogeFor(TAG, player.curMediaFlow.value?.id, resources.getString(R.string.unknown_media_key, keycode))
            }
        }
        return false
    }

    private var eventSink: Job?     = null
    private fun cancelFlowEvents() {
        eventSink?.cancel()
        eventSink = null
    }
    private fun procFlowEvents() {
        if (eventSink == null) eventSink = scope.launch {
            EventFlow.events.collectLatest { event ->
                Logd(TAG, "Received event: ${event.TAG}")
                when (event) {
                    is FlowEvent.QueueEvent -> onQueueEvent(event)
//                    is FlowEvent.BufferUpdateEvent -> for (i in 0..1) theatres[i].mPlayerFlow.value?.onBufferUpdate(event)
                    is FlowEvent.SleepTimerUpdatedEvent -> for (i in 0..1) theatres[i].mPlayerFlow.value?.onSleepTimerUpdate(event)
                    is FlowEvent.EpisodeMediaEvent -> for (i in 0..1) theatres[i].mPlayerFlow.value?.onEpisodeMediaEvent(event)   // TODO
                    else -> {}
                }
            }
        }
    }

    private fun onQueueEvent(event: FlowEvent.QueueEvent) {
        if (event.action == FlowEvent.QueueEvent.Action.REMOVED) {
            mediaLibrarySession?.notifyChildrenChanged("ActQueue", actQueueFlow.value.size(), null)
            for (e in event.episodes) {
                for (i in 0..1) {
                    if (e.id == theatres[i].mPlayerFlow.value?.curMediaFlow?.value?.id) {
                        Logd(TAG, "onQueueEvent: queue event removed ${e.title}")
                        theatres[i].mPlayerFlow.value?.endPlayback(hasEnded = false, wasSkipped = true, shouldContinue = theatres[i].mPlayerFlow.value!!.isPlaying)
                        break
                    }
                }
            }
        } else if (event.action == FlowEvent.QueueEvent.Action.CLEARED) {
            mediaLibrarySession?.notifyChildrenChanged("ActQueue", 0, null)
            for (i in 0..1) theatres[i].mPlayerFlow.value?.endPlayback(hasEnded = false, wasSkipped = true, shouldContinue = theatres[i].mPlayerFlow.value!!.isPlaying)
        }
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun unpauseIfPauseOnDisconnect(bluetooth: Boolean) {
        if (theatres[0].mPlayerFlow.value != null) {
            val audioManager = getAppContext().getSystemService(AUDIO_SERVICE) as AudioManager
            if (audioManager.mode != AudioManager.MODE_NORMAL || audioManager.isMusicActive) {
                Logd(TAG, "unpauseIfPauseOnDisconnect() audio is in use")
                return
            }
        }
        if (transientPause) {
            transientPause = false
            when {
                !bluetooth && appPrefsFlow!!.value.unpauseOnHeadsetReconnect -> theatres[0].mPlayerFlow.value?.play()
                bluetooth && appPrefsFlow!!.value.unpauseOnBluetoothReconnect -> {
                    val vibrator = if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
                        val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                        manager.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        getSystemService(VIBRATOR_SERVICE) as Vibrator
                    }
                    vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                    theatres[0].mPlayerFlow.value?.play()
                }
            }
        }
    }

    enum class NotificationCustomButton(val customAction: String, val commandButton: CommandButton) {
        SKIP(customAction = CUSTOM_COMMAND_SKIP_ACTION_ID,
            commandButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setDisplayName("Skip")
                .setSessionCommand(SessionCommand(CUSTOM_COMMAND_SKIP_ACTION_ID, Bundle()))
                .setCustomIconResId(R.drawable.ic_notification_skip)
                .build(),
        ),
        REWIND(customAction = CUSTOM_COMMAND_REWIND_ACTION_ID,
            commandButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setDisplayName("Rewind")
                .setSessionCommand(SessionCommand(CUSTOM_COMMAND_REWIND_ACTION_ID, Bundle()))
                .setCustomIconResId(R.drawable.ic_notification_fast_rewind)
                .build(),
        ),
        FORWARD(customAction = CUSTOM_COMMAND_FORWARD_ACTION_ID,
            commandButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setDisplayName("Forward")
                .setSessionCommand(SessionCommand(CUSTOM_COMMAND_FORWARD_ACTION_ID, Bundle()))
                .setCustomIconResId(R.drawable.ic_notification_fast_forward)
                .build(),
        ),
        RESTART(customAction = CUSTOM_COMMAND_RESTART_ACTION_ID,
            commandButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setDisplayName("Restart")
                .setSessionCommand(SessionCommand(CUSTOM_COMMAND_RESTART_ACTION_ID, Bundle()))
                .setCustomIconResId(R.drawable.baseline_skip_previous_24)
                .build(),
        ),
    }

    class CustomMediaNotificationProvider : DefaultMediaNotificationProvider(getAppContext()) {
        override fun addNotificationActions(mediaSession: MediaSession, mediaButtons: ImmutableList<CommandButton>, builder: NotificationCompat.Builder, actionFactory: MediaNotification.ActionFactory): IntArray {
            val defaultPlayPauseButton = mediaButtons.getOrNull(1)
            val notificationMediaButtons = ImmutableList.builder<CommandButton>().apply {
                add(NotificationCustomButton.RESTART.commandButton)
                add(NotificationCustomButton.REWIND.commandButton)
                defaultPlayPauseButton?.let { add(it) }
                add(NotificationCustomButton.FORWARD.commandButton)
                if (appPrefsFlow!!.value.showSkip) add(NotificationCustomButton.SKIP.commandButton)
            }.build()
            return super.addNotificationActions(mediaSession, notificationMediaButtons, builder, actionFactory)
        }
        override fun getNotificationContentTitle(metadata: MediaMetadata): CharSequence = metadata.title ?: "No title"
        override fun getNotificationContentText(metadata: MediaMetadata): CharSequence = metadata.subtitle ?: "No text"
    }

    companion object {
        private val TAG: String = PlaybackService::class.simpleName ?: "Anonymous"

        var isAutoController: Boolean = false

        private const val CHANNEL_ID = "podcini playback service"

        private const val CUSTOM_COMMAND_SKIP_ACTION_ID = "ac.mdiq.podcini.SKIP"
        private const val CUSTOM_COMMAND_REWIND_ACTION_ID = "ac.mdiq.podcini.REWIND"
        private const val CUSTOM_COMMAND_FORWARD_ACTION_ID = "ac.mdiq.podcini.FORWARD"
        private const val CUSTOM_COMMAND_RESTART_ACTION_ID = "ac.mdiq.podcini.RESTART"

//        const val ACTION_SHUTDOWN_PLAYBACK_SERVICE: String = "action.ac.mdiq.podcini.service.actionShutdownPlaybackService"

        var playbackService: PlaybackService? = null
        var mediaBrowser: MediaBrowser? = null

        var isRunning = false

        var isCasting: Boolean = false
            internal set

        /**
         * Is true if the service was running, but paused due to headphone disconnect
         */
        private var transientPause = false
    }
}
