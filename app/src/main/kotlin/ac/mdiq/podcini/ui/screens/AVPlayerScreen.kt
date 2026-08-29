package ac.mdiq.podcini.ui.screens

import ac.mdiq.podcini.R
import ac.mdiq.podcini.activity.MainActivity.Companion.findActivity
import ac.mdiq.podcini.playback.PlaybackStarter
import ac.mdiq.podcini.playback.base.actQueueFlow
import ac.mdiq.podcini.playback.base.activeTheatresFlow
import ac.mdiq.podcini.playback.base.ensureAController
import ac.mdiq.podcini.playback.base.theatres
import ac.mdiq.podcini.playback.base.Media3Player.Companion.getCache
import ac.mdiq.podcini.playback.base.Media3Player.Companion.nuclearCacheWipe
import ac.mdiq.podcini.playback.base.PlayerStatusSimple
import ac.mdiq.podcini.playback.base.SleepManager.Companion.isSleepTimerActive
import ac.mdiq.podcini.playback.cast.BaseActivity
import ac.mdiq.podcini.playback.forcePlaybackReset
import ac.mdiq.podcini.playback.isRecordingFlow
import ac.mdiq.podcini.playback.service.PlaybackService.Companion.playbackService
import ac.mdiq.podcini.shared.AudioSpec
import ac.mdiq.podcini.shared.VideoSpec
import ac.mdiq.podcini.sources.clientByEpisode
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.fallbackSpeed
import ac.mdiq.podcini.storage.database.fastForwardSecs
import ac.mdiq.podcini.storage.database.rewindSecs
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.skipforwardSpeed
import ac.mdiq.podcini.storage.database.speedforwardSpeed
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.specs.EmbeddedChapterImage
import ac.mdiq.podcini.storage.specs.MediaType
import ac.mdiq.podcini.storage.specs.Rating
import ac.mdiq.podcini.storage.specs.VideoMode
import ac.mdiq.podcini.storage.specs.VolumeAdaptionSetting
import ac.mdiq.podcini.storage.utils.durationStringAdapt
import ac.mdiq.podcini.storage.utils.durationStringFull
import ac.mdiq.podcini.ui.actions.Combo
import ac.mdiq.podcini.ui.compose.ChooseRatingDialog
import ac.mdiq.podcini.ui.compose.CommonPopupCard
import ac.mdiq.podcini.ui.compose.EpisodeDetails
import ac.mdiq.podcini.ui.compose.PlaybackSpeedFullDialog
import ac.mdiq.podcini.ui.compose.ShareDialog
import ac.mdiq.podcini.ui.compose.SleepTimerDialog
import ac.mdiq.podcini.ui.compose.borderColor
import ac.mdiq.podcini.ui.compose.buttonColor
import ac.mdiq.podcini.ui.compose.distinctColorOf
import ac.mdiq.podcini.ui.compose.filterChipBorder
import ac.mdiq.podcini.ui.compose.textColor
import ac.mdiq.podcini.utils.EventFlow
import ac.mdiq.podcini.utils.FlowEvent
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logt
import ac.mdiq.podcini.utils.formatDateTimeFlex
import ac.mdiq.podcini.utils.formatLargeIntegerBrief
import ac.mdiq.podcini.utils.formatNumberKmp
import ac.mdiq.podcini.utils.formatWithGrouping
import ac.mdiq.podcini.utils.openInSystemDefault
import ac.mdiq.podcini.utils.shareLink
import ac.mdiq.podcini.utils.timeIt
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.core.app.ShareCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private const val TAG = "AudioPlayerScreen"

enum class PSState {
    Hidden, PartiallyExpanded, Expanded;
    companion object {
        @OptIn(ExperimentalMaterial3Api::class)
        fun fromSheet(value:  SheetValue): PSState {
            return when (value) {
                SheetValue.Hidden -> Hidden
                SheetValue.PartiallyExpanded -> PartiallyExpanded
                SheetValue.Expanded -> Expanded
            }
        }
    }
}

private var activePlayer by mutableIntStateOf(0)

var allowSheetHide by mutableStateOf(false)
var psState by mutableStateOf(PSState.PartiallyExpanded)

var curVideoMode by mutableStateOf(VideoMode.DEFAULT)

class AVPlayerVM0: ViewModel() {
    var landscape by mutableStateOf(false)
    internal var sleepTimerActive by mutableStateOf(isSleepTimerActive())

    private var eventSink by mutableStateOf<Job?>(null)
    fun procFlowEvents() {
        Logd(TAG, "procFlowEvents")
        if (eventSink == null) eventSink = viewModelScope.launch {
            EventFlow.events.collectLatest { event ->
                Logd(TAG, "Received event: ${event.TAG}")
                when (event) {
//                    is FlowEvent.PlaybackServiceEvent -> {
//                        //                        if (event.action == FlowEvent.PlaybackServiceEvent.Action.SERVICE_SHUT_DOWN)
//                        //                            (context as? MainActivity)?.bottomSheet?.state = BottomSheetBehavior.STATE_EXPANDED
//                        //                        when (event.action) {
//                        //                            FlowEvent.PlaybackServiceEvent.Action.SERVICE_SHUT_DOWN -> actMain?.setPlayerVisible(false)
//                        //                            FlowEvent.PlaybackServiceEvent.Action.SERVICE_STARTED -> if (curEpisode != null) actMain?.setPlayerVisible(true)
//                        //                PlaybackServiceEvent.Action.SERVICE_RESTARTED -> (context as MainActivity).setPlayerVisible(true)
//                        //                        }
//                    }
                    is FlowEvent.SleepTimerUpdatedEvent -> sleepTimerActive = isSleepTimerActive()
                    else -> {}
                }
            }
        }
    }

    init {
        procFlowEvents()
    }

    override fun onCleared() {
        Logd(TAG, "VM onCleared")
        eventSink?.cancel()
        eventSink = null
    }
}

class AVPlayerVM(val playerId: Int): ViewModel() {
    var episodeFeed = theatres[playerId].mPlayerFlow.value?.curMediaFlow?.value?.feed

    var showActionBar by mutableStateOf(true)

    internal var curPlaybackSpeed by mutableFloatStateOf(1f)

    var forceVideo by mutableStateOf(false)

    var volumeAdaption by mutableStateOf(VolumeAdaptionSetting.OFF)

    var showPlayButton by mutableStateOf(true)

    private var posJob: Job? = null
    private var curIdJob: Job? = null
    private var curStateJob: Job? = null

    private var curSpeedJob: Job? = null

    fun start() {
        timeIt("$TAG start of init vm $playerId")

        posJob = viewModelScope.launch { theatres[playerId].mPlayerFlow.flatMapLatest { player -> player?.curMediaFlow?.map { media -> player to media } ?: flowOf(null) }
            .distinctUntilChanged().collect { playerAndMedia ->
                if (showPlayButton) {
                    val (player, media) = playerAndMedia ?: (null to null)
                    showPlayButton = player?.isCurrentlyPlaying(media) != true
                }
            }
        }
        curIdJob = viewModelScope.launch {
            theatres[playerId].mPlayerFlow.flatMapLatest { player -> player?.curMediaFlow?.map { media -> player to media } ?: flowOf(null) }
                .distinctUntilChanged { old, new -> old?.second?.id == new?.second?.id }
                .collect { playerAndMedia ->
                    val (player, media) = playerAndMedia ?: (null to null)
                    episodeFeed = media?.feed
                    volumeAdaption = VolumeAdaptionSetting.OFF
                    curPlaybackSpeed = player?.curPlayerSpeedFlow?.value ?: 1f
                    forceVideo = false
                }
        }
        curStateJob = viewModelScope.launch { theatres[playerId].mPlayerFlow.flatMapLatest { player -> player?.statusSimpleFlow ?: flowOf(null) }.collect {
            showPlayButton = it != PlayerStatusSimple.PLAYING
            Logd(TAG, "curPlayerStatus changed playerId: $playerId showPlayButton $showPlayButton")
        } }
        curSpeedJob = viewModelScope.launch { theatres[playerId].mPlayerFlow.flatMapLatest { player -> player?.curPlayerSpeedFlow ?: flowOf(1f) }.distinctUntilChanged().collect { speed ->
            curPlaybackSpeed = speed
            Logd(TAG, "curPlaybackSpeed changed playerId: $playerId curPlaybackSpeed $curPlaybackSpeed")
        } }
        timeIt("$TAG end of vm init")
    }

    fun stop() {
        posJob?.cancel()
        posJob = null
        curIdJob?.cancel()
        curIdJob = null
        curStateJob?.cancel()
        curStateJob = null
        curSpeedJob?.cancel()
        curSpeedJob = null
    }

    override fun onCleared() {
        Logd(TAG, "VM onCleared")
        stop()
    }
}

@Composable
fun VolumeDialog(vm: AVPlayerVM, onDismiss: () -> Unit) {
    CommonPopupCard(onDismiss = onDismiss) {
        fun adaptionFactor(): Float = when {
            vm.volumeAdaption != VolumeAdaptionSetting.OFF -> vm.volumeAdaption.adaptionFactor
            vm.episodeFeed != null -> vm.episodeFeed!!.volumeAdaptionSetting.adaptionFactor
            else -> 1f
        }
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var forCurrent by remember { mutableStateOf(true) }
            var forPodcast by remember { mutableStateOf(false) }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Checkbox(checked = forCurrent, onCheckedChange = { isChecked -> forCurrent = isChecked })
                Text(stringResource(R.string.current_episode))
                Spacer(Modifier.weight(1f))
                Checkbox(checked = forPodcast, onCheckedChange = { isChecked -> forPodcast = isChecked })
                Text(stringResource(R.string.current_podcast))
                Spacer(Modifier.weight(1f))
            }
            VolumeAdaptionSetting.entries.forEach { setting ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = (setting == vm.volumeAdaption),
                        onCheckedChange = { _ ->
                            if (forPodcast && vm.episodeFeed != null) runOnIOScope { upsert(vm.episodeFeed!!) { it.volumeAdaptionSetting = setting} }
                            if (setting != vm.volumeAdaption) {
                                vm.volumeAdaption = setting
                                theatres[vm.playerId].mPlayerFlow.value?.setVolume(1.0f, 1.0f, adaptionFactor())
                                onDismiss()
                            }
                        }
                    )
                    Text(text = stringResource(setting.resId), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlUI(vm: AVPlayerVM) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val buttonColor1 = Color(0xEEAA7700)
    val player_ by theatres[vm.playerId].mPlayerFlow.collectAsStateWithLifecycle()
    val player = player_
    val episode by player_?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }

    DisposableEffect(Unit) {
        timeIt("$TAG start of DisposableEffect(Unit")
        ensureAController()
        timeIt("$TAG end of DisposableEffect(Unit")
        onDispose {}
    }

    var showSpeedDialog by remember { mutableStateOf(false) }
    if (showSpeedDialog) PlaybackSpeedFullDialog(vm.playerId, indexDefault = 0, maxSpeed = 3f, onDismiss = {showSpeedDialog = false})

    var showVolumeDialog by remember { mutableStateOf(false) }
    if (showVolumeDialog) VolumeDialog(vm) { showVolumeDialog = false }

    var showSleepTimeDialog by remember { mutableStateOf(false) }
    if (showSleepTimeDialog) SleepTimerDialog { showSleepTimeDialog = false }

    @Composable
    fun SpeedometerWithArc(speed: Float, maxSpeed: Float, trackColor: Color, modifier: Modifier) {
        val needleAngleRad = remember(speed) { Math.toRadians(((speed / maxSpeed) * 270f - 225).toDouble()) }
        Canvas(modifier = modifier) {
            val radius = 1.3 * size.minDimension / 2
            val strokeWidth = 6.dp.toPx()
            val arcRect = Rect(left = strokeWidth / 2, top = strokeWidth / 2, right = size.width - strokeWidth / 2, bottom = size.height - strokeWidth / 2)
            drawArc(color = trackColor, startAngle = 135f, sweepAngle = 270f, useCenter = false, style = Stroke(width = strokeWidth), topLeft = arcRect.topLeft, size = arcRect.size)
            val needleEnd = Offset(x = size.center.x + (radius * 0.7f * cos(needleAngleRad)).toFloat(), y = size.center.y + (radius * 0.7f * sin(needleAngleRad)).toFloat())
            drawLine(color = Color.Red, start = size.center, end = needleEnd, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(color = Color.Cyan, center = size.center, radius = 3.dp.toPx())
        }
    }

    var recordingStartTime by remember { mutableStateOf<Long?>(null) }

    val velocityTracker = remember { VelocityTracker() }
    val offsetX = remember(episode?.id) { Animatable(0f) }
    val swipeVelocityThreshold = 1500f
    val swipeDistanceThreshold = with(LocalDensity.current) { 150.dp.toPx() }
    Row(Modifier.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { velocityTracker.resetTracking() },
            onHorizontalDrag = { change, dragAmount ->
                Logd(TAG, "detectHorizontalDragGestures onHorizontalDrag $dragAmount")
                if (abs(dragAmount) > 4) {
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                }
            },
            onDragEnd = {
                Logd(TAG, "detectHorizontalDragGestures onDragEnd")
                scope.launch {
                    val velocity = velocityTracker.calculateVelocity().x
                    val distance = offsetX.value
                    Logd(TAG, "detectHorizontalDragGestures velocity: $velocity distance: $distance")
                    val shouldSwipe = abs(distance) > swipeDistanceThreshold && abs(velocity) > swipeVelocityThreshold
                    if (shouldSwipe) {
                        if (distance < 0) {
                            allowSheetHide = true
                            psState = PSState.Hidden
                        }
                        else showSleepTimeDialog = true
                    }
//                    offsetX.animateTo(targetValue = 0f, animationSpec = tween(300))
                }
            },
        )
    }) {
        AsyncImage(model = ImageRequest.Builder(context).data(episode?.imageUrl).memoryCachePolicy(CachePolicy.ENABLED).build(), placeholder = painterResource(R.drawable.ic_launcher_foreground), error = painterResource(R.drawable.ic_launcher_foreground), contentDescription = "imgvCover", modifier = Modifier.width(50.dp).height(50.dp).border(border = BorderStroke(1.dp, borderColor)).padding(start = 5.dp).combinedClickable(
            onClick = {
                Logd(TAG, "playerUi icon was clicked $psState")
                activePlayer = vm.playerId
                if (psState == PSState.PartiallyExpanded) {
                    if (episode != null) {
                        if (playbackService == null) PlaybackStarter(episode!!).start(vm.playerId)
                        psState = PSState.Expanded
                    }
                } else psState = PSState.PartiallyExpanded
            },
            onLongClick = {
                if (vm.episodeFeed != null) {
                    navTo(FeedDetails(feedId=vm.episodeFeed!!.id))
                    psState = PSState.PartiallyExpanded
                }
            }))
        val buttonSize = 46.dp
        Spacer(Modifier.weight(0.1f))
        Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.size(50.dp).combinedClickable(onClick = { showSpeedDialog = true }, onLongClick = { showVolumeDialog = true })) {
            SpeedometerWithArc(speed = vm.curPlaybackSpeed*100, maxSpeed = 300f, trackColor = buttonColor, modifier = Modifier.width(40.dp).height(40.dp).align(Alignment.Center))
            Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_volume_adaption), tint = buttonColor1, contentDescription = "Volume adaptation", modifier = Modifier.align(Alignment.Center))
            Text(formatNumberKmp(vm.curPlaybackSpeed.toDouble()), color = textColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.weight(0.1f))
        val isRecording by isRecordingFlow.collectAsStateWithLifecycle()
        val recordColor = if (!isRecording) { if (episode != null && player?.isPlaying == true) buttonColor else Color.Gray } else Color.Red
        Icon(imageVector = ImageVector.vectorResource(R.drawable.baseline_fiber_manual_record_24), tint = recordColor, contentDescription = "record",
            modifier = Modifier.size(buttonSize).combinedClickable(
                onClick = {
                    if (episode != null && player?.isPlaying == true) {
                        val pos = player.getPosition().toLong()
                        runOnIOScope { upsert(episode!!) { it.marks.add(pos) } }
                        Logt(TAG, "position $pos marked for ${episode?.title}")
                    } else Loge(TAG, "Marking position only works during playback.") },
                onLongClick = {
                    if (episode != null && player?.isPlaying == true) {
                        if (recordingStartTime == null) {
                            recordingStartTime = player.getPosition().toLong()
                            player.recordClip(recordingStartTime!!)
                        } else {
                            player.recordClip(recordingStartTime!!, player.getPosition().toLong())
                            recordingStartTime = null
                        }
                    } else Loge(TAG, "Recording only works during playback.")
                }))
        Spacer(Modifier.weight(0.1f))
        Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.size(50.dp).combinedClickable(
            onClick = { player?.seekDelta(-rewindSecs * 1000) }, onLongClick = { player?.seekTo(0) })) {
            val rewindSecs = remember(rewindSecs) { formatWithGrouping(rewindSecs.toLong()) }
            Text(rewindSecs, color = textColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.TopCenter))
            Icon(imageVector = ImageVector.vectorResource(R.drawable.baseline_skip_previous_24), tint = buttonColor, contentDescription = "rewind", modifier = Modifier.size(buttonSize).align(Alignment.Center))
        }
        Spacer(Modifier.weight(0.1f))
        Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.size(50.dp).combinedClickable(
            onClick = {
                Logd(TAG, "onClick Play/Pause: vm.playerId: ${vm.playerId}")
                if (episode != null) {
                    vm.showPlayButton = !vm.showPlayButton
                    if (vm.showPlayButton && recordingStartTime != null) {
                        player?.recordClip(recordingStartTime!!, (player.getPosition()).toLong())
                        recordingStartTime = null
                    }
                    Logd(TAG, "Play button clicked: status: ${player?.statusFlow?.value} is ready: ${playbackService?.isServiceReady()}")
                    PlaybackStarter(episode!!).shouldStreamThisTime(null).start(vm.playerId)
                    if (episode?.mediaType == MediaType.VIDEO && player?.isPlaying != true && (vm.episodeFeed?.videoModePolicy != VideoMode.AUDIO_ONLY)) {
                        if (!vm.showPlayButton && psState != PSState.Expanded) psState = PSState.Expanded
                    }
                }
            },
            onLongClick = {
                if (player?.isPlaying == true) {
                    val speedFB = fallbackSpeed
                    if (speedFB > 0.1f) player.toggleFallbackSpeed(speedFB)
                } })) {
            val playButRes by remember(vm.showPlayButton) { mutableIntStateOf(if (vm.showPlayButton) R.drawable.ic_play_48dp else R.drawable.ic_pause) }
            Icon(imageVector = ImageVector.vectorResource(playButRes), tint = buttonColor, contentDescription = "play", modifier = Modifier.size(buttonSize).align(Alignment.Center))
            if (fallbackSpeed > 0.1f) Text(fallbackSpeed.toString(), color = textColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.weight(0.1f))
        Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.size(50.dp).combinedClickable(
            onClick = { player?.seekDelta(fastForwardSecs * 1000) }, onLongClick = {
                if (player?.isPlaying == true) {
                    val speedForward = speedforwardSpeed
                    if (speedForward > 0.1f) player.speedForward(speedForward)
                }
            })) {
            val fastForwardSecs = remember(fastForwardSecs) { formatWithGrouping(fastForwardSecs.toLong()) }
            Text(fastForwardSecs, color = textColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.TopCenter))
            Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_fast_forward), tint = buttonColor, contentDescription = "forward", modifier = Modifier.size(buttonSize).align(Alignment.Center))
            if (speedforwardSpeed > 0.1f) Text(formatNumberKmp(speedforwardSpeed), color = textColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.weight(0.1f))
        Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.size(50.dp).combinedClickable(
            onClick = {
                if (player?.isPlaying == true) {
                    val speedForward = skipforwardSpeed
                    if (speedForward > 0.1f) player.speedForward(speedForward)
                } },
            onLongClick = {
                //                    context.sendBroadcast(MediaButtonReceiver.createIntent(context, KeyEvent.KEYCODE_MEDIA_NEXT))
                if (player?.isPlaying == true || player?.isPaused == true) player.skip()
            })) {
            if (skipforwardSpeed > 0.1f) Text(formatNumberKmp(skipforwardSpeed), color = textColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.TopCenter))
            Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_skip_48dp), tint = buttonColor, contentDescription = "skip", modifier = Modifier.size(buttonSize).align(Alignment.Center))
        }
        Spacer(Modifier.weight(0.1f))
    }
}

@Composable
fun ProgressBar(vm: AVPlayerVM) {
    val player_ by theatres[vm.playerId].mPlayerFlow.collectAsStateWithLifecycle()
    val player = player_
    val episode by player_?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val bufferValue by player?.bufferedPercentFlow?.collectAsStateWithLifecycle() ?: remember { mutableIntStateOf(0) }
    Box(modifier = Modifier.fillMaxWidth()) {
        var sliderValue by remember(episode?.position) { mutableFloatStateOf((episode?.position?:0).toFloat()) }
        val actColor = MaterialTheme.colorScheme.tertiary
        val inActColor = MaterialTheme.colorScheme.secondaryFixedDim
        val distColor = remember { distinctColorOf(actColor, inActColor) }
        Slider(colors = SliderDefaults.colors(activeTrackColor = actColor,  inactiveTrackColor = inActColor), modifier = Modifier.height(12.dp).padding(top = 2.dp),
            value = sliderValue, valueRange = 0f..( if ((episode?.duration?:0) > 0) episode?.duration?:0 else 30000).toFloat(),
            onValueChange = { sliderValue = it }, onValueChangeFinished = { player?.seekTo(sliderValue.toInt()) })
        LinearProgressIndicator(progress = { 0.01f*bufferValue }, color = distColor.copy(alpha = 0.3f), trackColor = inActColor, modifier = Modifier.height(4.dp).fillMaxWidth().align(Alignment.BottomStart))
        Text(durationStringFull(episode?.duration?:0), color = distColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.BottomCenter))
    }
    Row {
        val pastText = remember(episode?.position) { if (episode == null) "" else durationStringAdapt(episode!!.position) + " *" + durationStringAdapt(episode!!.timeSpent.toInt()) }
        Text(pastText, color = textColor, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        val bitrate by player?.bitrateFlow?.collectAsStateWithLifecycle() ?: remember { mutableIntStateOf(0) }
        val resolution by player?.resolutionFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf("") }
        val mimeType by player?.mimeTypeFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf("") }
        val channelCount by player?.channelCountFlow?.collectAsStateWithLifecycle() ?: remember { mutableIntStateOf(0) }
        val info = remember(mimeType, channelCount, bitrate, resolution) {
            val mime = if (mimeType.isBlank()) "" else "$mimeType "
            val bitrate = if (bitrate > 0) " ${formatLargeIntegerBrief(bitrate)}bps" else ""
            "$mime$channelCount $bitrate $resolution"
        }
        Text(info, color = textColor, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        val curPlayerSpeed by player?.curPlayerSpeedFlow?.collectAsStateWithLifecycle() ?: remember { mutableFloatStateOf(1f) }
        val lengthText = remember(curPlayerSpeed, episode?.position) {  run {
            if (episode == null) return@run ""
            val remainingTime = max((episode!!.duration - episode!!.position), 0)
            val onSpeed = if (curPlayerSpeed > 0 && abs(curPlayerSpeed-1f) > 0.001) (remainingTime / curPlayerSpeed).toInt() else 0
            (if (onSpeed > 0) "*" + durationStringAdapt(onSpeed) else "") + " -" + durationStringAdapt(remainingTime)
        } }
        Text(lengthText, color = textColor, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AVPlayerScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appPrefs by appPrefsFlow!!.collectAsStateWithLifecycle()

    val vm0: AVPlayerVM0 = viewModel()
    val vms: List<AVPlayerVM> = listOf(
        viewModel(key = "0", factory = viewModelFactory { initializer { AVPlayerVM(playerId = 0) } }),
        viewModel(key = "1", factory = viewModelFactory { initializer { AVPlayerVM(playerId = 1) } })
    )

    DisposableEffect(vms[0]) {
        vms[0].start()
        onDispose { vms[0].stop() }
    }

    val activeTheatres by activeTheatresFlow.collectAsStateWithLifecycle()
    DisposableEffect(vms[1], activeTheatres) {
        if (activeTheatres == 2) vms[1].start()
        else vms[1].stop()
        onDispose { vms[1].stop() }
    }

    LaunchedEffect(activeTheatres) { if (activeTheatres ==1) activePlayer = 0 }

    var showHomeText by remember { mutableStateOf(false) }

    // TODO: somehow, these 2 are not used?
    //     var homeText: String? = remember { null }
    var readerhtml: String? by remember { mutableStateOf(null) }

    //    private var chapterControlVisible by mutableStateOf(false)
    var chapterIndex by remember { mutableIntStateOf(-1) }
    var displayedChapterIndex by remember { mutableIntStateOf(-1) }
//    val curChapter = remember(curEpisode?.id, displayedChapterIndex) { if (curEpisode?.chapters.isNullOrEmpty() || displayedChapterIndex == -1) null else curEpisode.chapters[displayedChapterIndex] }
//    var nextChapterStart by remember { mutableIntStateOf(Int.MAX_VALUE) }

    fun isAutoRotateEnabled(): Boolean {
        return Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
    }
    var isRotationEnabled by remember { mutableStateOf(isAutoRotateEnabled()) }
    DisposableEffect(context) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                isRotationEnabled = isAutoRotateEnabled()
                Logd(TAG, "ContentObserver onChange isRotationEnabled: $isRotationEnabled")
            }
        }
        context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    val player0_ by theatres[vms[0].playerId].mPlayerFlow.collectAsStateWithLifecycle()
    val player0 = player0_
    val player1 by theatres[vms[1].playerId].mPlayerFlow.collectAsStateWithLifecycle()
    val player = (if (activePlayer == 0) player0 else player1)
    val curMedia0 by player0?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val curMedia1 by player1?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val curMedia = if (activePlayer == 0) curMedia0 else curMedia1
    val playingVideo by player?.playingVideoFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    if (isRotationEnabled) vm0.landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    else if (player?.isPlayingVideoLocally == true && vms[activePlayer].episodeFeed != null && vms[activePlayer].episodeFeed!!.videoModePolicy != VideoMode.AUDIO_ONLY)
        vm0.landscape = vms[activePlayer].episodeFeed!!.videoModePolicy == VideoMode.FULL_SCREEN || appPrefs.videoPlaybackMode == VideoMode.FULL_SCREEN.code
    if (!vm0.landscape) vms[activePlayer].showActionBar = true

    LaunchedEffect(key1 = curMedia0?.id) {
        Logd(TAG, "LaunchedEffect curMediaId: ${curMedia0?.title}")
        showHomeText = false
        displayedChapterIndex = -1
        vms[0].episodeFeed = curMedia0?.feed
        if (psState == PSState.Hidden && vms[0].episodeFeed != null) psState = PSState.PartiallyExpanded
    }

    LaunchedEffect(key1 = curMedia1?.id) {
        Logd(TAG, "LaunchedEffect curMediaId: ${curMedia1?.title}")
        showHomeText = false
        displayedChapterIndex = -1
        vms[1].episodeFeed = curMedia1?.feed
        if (psState == PSState.Hidden && vms[1].episodeFeed != null) psState = PSState.PartiallyExpanded
    }

    LaunchedEffect(psState, activePlayer, curMedia?.id) {
        Logd(TAG, "LaunchedEffect(isBSExpanded, curItem?.id) isBSExpanded: $psState")
        if (psState == PSState.Expanded) vm0.sleepTimerActive = isSleepTimerActive()
    }

    LaunchedEffect(activePlayer, curMedia?.position) {
        if (curMedia != null) {
            if (psState == PSState.Expanded) {
                chapterIndex = curMedia.getCurrentChapterIndex(curMedia.position)
                displayedChapterIndex = if (curMedia.position > curMedia.duration || chapterIndex >= curMedia.chapters.size - 1) curMedia.chapters.size - 1 else chapterIndex
                Logd(TAG, "LaunchedEffect(curEpisode?.position) chapterIndex $chapterIndex $displayedChapterIndex")
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            Logd(TAG, "DisposableEffect Lifecycle.Event: $event")
            when (event) {
                Lifecycle.Event.ON_CREATE -> { }
                Lifecycle.Event.ON_START -> {}
                Lifecycle.Event.ON_RESUME -> {}
                Lifecycle.Event.ON_STOP -> {}
                Lifecycle.Event.ON_DESTROY -> {}
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var showAudioControlDialog by remember { mutableStateOf(false) }
    if (showAudioControlDialog) AlertDialog(modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.extraLarge), onDismissRequest = { showAudioControlDialog = false }, title = { Text(stringResource(R.string.audio_controls)) },
        text = {
            LazyColumn {
                items(player?.audioTracks?:listOf()) { track ->
                    Text(track, color = textColor, modifier = Modifier.clickable {
                        player?.setAudioTrack(((player.getSelectedAudioTrack()) + 1) % player.audioTracks.size)
                        //                            Handler(Looper.getMainLooper()).postDelayed({ setupAudioTracks() }, 500)
                    })
                }
            }
        },
        confirmButton = { TextButton(onClick = { showAudioControlDialog = false }) { Text(stringResource(R.string.close_label)) } }
    )

    var showVolumeDialog by remember { mutableStateOf(false) }
    if (showVolumeDialog) VolumeDialog(vms[activePlayer]) { showVolumeDialog = false }

    var showSleepTimeDialog by remember { mutableStateOf(false) }
    if (showSleepTimeDialog) SleepTimerDialog { showSleepTimeDialog = false }

    var showSpeedDialog by remember { mutableStateOf(false) }
    if (showSpeedDialog) PlaybackSpeedFullDialog(vms[activePlayer].playerId, indexDefault = 0, maxSpeed = 3f, onDismiss = {showSpeedDialog = false})

    @Composable
    fun PlayerUI(vm: AVPlayerVM, modifier: Modifier) {
        val player by theatres[vm.playerId].mPlayerFlow.collectAsStateWithLifecycle()
        val episode by player?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
        Box(modifier = modifier.fillMaxWidth().height(100.dp).border(1.dp, MaterialTheme.colorScheme.tertiary)) {
            AsyncImage(model = episode?.imageUrl?:episode?.feed?.imageUrl?:"", contentDescription = "bgImage", contentScale = ContentScale.FillBounds, error = painterResource(R.drawable.teaser), modifier = Modifier.matchParentSize().blur(radiusX = 3.dp, radiusY = 3.dp))
            Box(modifier = Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)))
            Column {
                Text(episode?.title ?: "No title", maxLines = 1, color = textColor, style = MaterialTheme.typography.bodyMedium)
                ProgressBar(vm)
                ControlUI(vm)
            }
        }
    }

    var showShareDialog by remember { mutableStateOf(false) }
    if (showShareDialog && curMedia != null) ShareDialog(curMedia) {showShareDialog = false }

    var showAVChooser by remember { mutableStateOf(false) }

    @Composable
    fun VideoToolBar(vm: AVPlayerVM, modifier: Modifier = Modifier) {
        var expanded by remember { mutableStateOf(false) }
        val player by theatres[vm.playerId].mPlayerFlow.collectAsStateWithLifecycle()
        val episode_ by player?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
        val episode = episode_ ?: return
        val client = remember(episode.id) { clientByEpisode(episode) }
        if (vm.showActionBar) Row(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_arrow_down), tint = textColor, contentDescription = "Collapse", modifier = Modifier.clickable { psState = PSState.PartiallyExpanded })
            if (vm0.landscape) Column {
                Text(text = episode.title?:"", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = episode.feed?.title?:"", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                if (client?.attributes?.hasSeparateAVs == true) IconButton(onClick = {
                    val media = upsertBlk(episode) { it.forceVideo = false }
                    vm.forceVideo = false
                    forcePlaybackReset = true
                    PlaybackStarter(media).shouldStreamThisTime(null).start()
                    player?.playingVideoFlow?.value = false
                }) { Icon(imageVector = ImageVector.vectorResource(R.drawable.baseline_audiotrack_24), contentDescription = "audio only") }
                var sleepIconRes by remember { mutableIntStateOf(if (!isSleepTimerActive()) R.drawable.ic_sleep else R.drawable.ic_sleep_off) }
                IconButton(onClick = { showSleepTimeDialog = true }) { Icon(imageVector = ImageVector.vectorResource(sleepIconRes), contentDescription = "sleeper") }
                (context as? BaseActivity)?.CastIconButton()
                IconButton(onClick = { activePlayer = vm.playerId; showShareDialog = true }) { Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_share), contentDescription = "share") }
            }
            Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                DropdownMenu(expanded = expanded, border = BorderStroke(1.dp, borderColor), onDismissRequest = { expanded = false }) {
                    if (client?.attributes?.hasMultiQualities == true) DropdownMenuItem(text = { Text(stringResource(R.string.change_stream)) }, onClick = {
                        showAVChooser = true
                        expanded = false
                    })
                    if (vm0.landscape) {
                        var sleeperRes by remember { mutableIntStateOf(if (!isSleepTimerActive()) R.string.set_sleeptimer_label else R.string.sleep_timer_label) }
                        DropdownMenuItem(text = { Text(stringResource(sleeperRes)) }, onClick = {
                            showSleepTimeDialog = true
                            expanded = false
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.queue)) }, onClick = {
                            navTo(Queues(id=actQueueFlow.value.id))
                            expanded = false
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.open_podcast)) }, onClick = {
                            if (vm.episodeFeed != null) navTo(FeedDetails(feedId=vm.episodeFeed!!.id))
                            expanded = false
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.share_label)) }, onClick = {
                            activePlayer = vm.playerId
                            showShareDialog = true
                            expanded = false
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.playback_speed)) }, onClick = {
                            activePlayer = vm.playerId
                            showSpeedDialog = true
                            expanded = false
                        })
                    }
                    if ((player?.audioTracks?.size?:0) >= 2) DropdownMenuItem(text = { Text(stringResource(R.string.audio_controls)) }, onClick = {
                        activePlayer = vm.playerId
                        showAudioControlDialog = true
                        expanded = false
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.visit_website_label)) }, onClick = {
                        val url = when {
                            !episode.link.isNullOrBlank() -> episode.link
                            else -> episode.linkOrFeedlink
                        }
                        if (url != null) openInSystemDefault(url)
                        expanded = false
                    })
                }
            }
        }
    }

    @Composable
    fun Toolbar(vm: AVPlayerVM) {
        var expanded by remember { mutableStateOf(false) }
        val player by theatres[vm.playerId].mPlayerFlow.collectAsStateWithLifecycle()
        val episode_ by player?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
        val episode = episode_ ?: return
        val mediaType = remember(episode.id) { episode.mediaType }
        val client = remember(episode.id) { clientByEpisode(episode) }
        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_arrow_down), tint = textColor, contentDescription = "Collapse", modifier = Modifier.clickable { psState = PSState.PartiallyExpanded })
            if (mediaType == MediaType.VIDEO && client?.attributes?.hasSeparateAVs == true) Icon(imageVector = ImageVector.vectorResource(R.drawable.baseline_fullscreen_24), tint = textColor, contentDescription = "Play video",
                modifier = Modifier.clickable {
                    val media = upsertBlk(episode) { it.forceVideo = true }
                    vm.forceVideo = true
                    forcePlaybackReset = true
                    PlaybackStarter(media).shouldStreamThisTime(null).start()
                    player?.playingVideoFlow?.value = true
                })
            Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_volume_adaption), tint = textColor, contentDescription = "Volume adaptation", modifier = Modifier.clickable {
                activePlayer = vm.playerId
                showVolumeDialog = true
            })
            val sleepRes = if (vm0.sleepTimerActive) R.drawable.ic_sleep_off else R.drawable.ic_sleep
            Icon(imageVector = ImageVector.vectorResource(sleepRes), tint = textColor, contentDescription = "Sleep timer", modifier = Modifier.clickable { showSleepTimeDialog = true })
            (context as? BaseActivity)?.CastIconButton()
            Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                DropdownMenu(expanded = expanded, border = BorderStroke(1.dp, borderColor), onDismissRequest = { expanded = false }) {
                    if (client?.attributes?.hasMultiQualities == true) DropdownMenuItem(text = { Text(stringResource(R.string.change_stream)) }, onClick = {
                        showAVChooser = true
                        expanded = false
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.share_label)) }, onClick = {
                        activePlayer = vm.playerId
                        showShareDialog = true
                        expanded = false
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.share_notes_label)) }, onClick = {
                        val notes = if (showHomeText) readerhtml else episode.description
                        if (!notes.isNullOrEmpty()) {
                            val shareText = HtmlCompat.fromHtml(notes, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
                            val intent = ShareCompat.IntentBuilder(context).setType("text/plain").setText(shareText).setChooserTitle(R.string.share_notes_label).createChooserIntent()
                            context.startActivity(intent)
                        }
                        expanded = false
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.clear_cache)) }, onClick = {
                        runOnIOScope { getCache().removeResource(episode.id.toString()) }
                        expanded = false
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.clear_all_cache)) }, onClick = {
                        runOnIOScope { nuclearCacheWipe() }
                        expanded = false
                    })
//                    DropdownMenuItem(text = { Text(stringResource(R.string.reset_player)) }, onClick = {
//                        playbackService?.recreateMediaPlayer()
//                        expanded = false
//                    })
                }
            }
        }
    }

    @Composable
    fun DetailUI(vm: AVPlayerVM, modifier: Modifier) {
        val comboAction = remember { Combo() }
        comboAction.ActionOptions()
        val player_ by theatres[vm.playerId].mPlayerFlow.collectAsStateWithLifecycle()
        val player = player_
        val episode_ by player?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
        val episode = episode_ ?: return
        val client = remember(episode.id) { clientByEpisode(episode) }

        @Composable
        fun StreamChanger(onDismiss: () -> Unit) {
            if (player == null) return
            var reset by remember { mutableStateOf(false) }
            var locales by remember { mutableStateOf<List<String>>(listOf()) }
            var locale by remember { mutableStateOf(player.useLocale) }
            var codecs by remember { mutableStateOf<List<String>>(listOf()) }
            var codec by remember { mutableStateOf(player.useCodex) }
            var bitRates by remember { mutableStateOf<List<Int>>(listOf()) }
            var bitrate by remember { mutableIntStateOf(player.useABPS) }
            var vcodecs by remember { mutableStateOf<List<String>>(listOf()) }
            var vcodec by remember { mutableStateOf(player.useVCodex) }
            var protocols by remember { mutableStateOf<List<String>>(listOf()) }
            var protocol by remember { mutableStateOf(player.useVCodex) }
            var resolutions by remember { mutableStateOf<List<String>>(listOf()) }
            var resolution by remember { mutableStateOf(player.useResolution) }
            fun buildResoSet(s: VideoSpec, rSet: MutableSet<String>) {
                if (s.resolution != null) when {
                    vcodec == null && protocol == null -> rSet.add(s.resolution!!)
                    protocol == null -> if (s.codec == vcodec) rSet.add(s.resolution!!)
                    vcodec == null -> if (s.deliveryMethod == protocol) rSet.add(s.resolution!!)
                    else -> if (s.deliveryMethod == protocol && s.codec == vcodec) rSet.add(s.resolution!!)
                }
            }
            fun buildBRSet(s: AudioSpec, bSet: MutableSet<Int>) {
                when {
                    locale == null && codec == "Any" -> bSet.add(s.averageBitrate)
                    locale == null -> if (s.codec == codec) bSet.add(s.averageBitrate)
                    codec == "Any" -> if (s.audioLocale == locale) bSet.add(s.averageBitrate)
                    else -> if (s.codec == codec && s.audioLocale == locale) bSet.add(s.averageBitrate)
                }
            }
            LaunchedEffect(episode.id) {
                val lSet = mutableSetOf<String>()
                val bSet = mutableSetOf<Int>()
                val cSet = mutableSetOf("Any")
                for (s in player.audioSpecs) {
                    lSet.add(s.audioLocale?:"")
                    buildBRSet(s, bSet)
                    if (s.codec != null) cSet.add(s.codec!!) else cSet.add("null")
                }
                locales = lSet.toList()
                bitRates = bSet.toList()
                codecs = cSet.toList()
                if (locale == null && locales.isNotEmpty()) locale = locales.firstOrNull { it in player.useLocales }
                if (codecs.isNotEmpty()) codec = codecs[0]
                if (bitRates.isNotEmpty()) bitrate = bitRates[0]
                val rSet = mutableSetOf<String>()
                val vcSet = mutableSetOf<String>()
                val vpSet = mutableSetOf<String>()
                val vSpecs = if (client?.attributes?.hasSeparateAVs == true) player.videoSpecs else player.muxedSpecs
                for (s in vSpecs) {
                    buildResoSet(s, rSet)
                    if (s.codec != null) vcSet.add(s.codec!!)
                    if (s.deliveryMethod != null) vpSet.add(s.deliveryMethod!!)
                }
                protocols = vpSet.toList()
                resolutions = rSet.toList()
                vcodecs = vcSet.toList()
            }

            Popup(onDismissRequest = { onDismiss() }, alignment = Alignment.TopStart, offset = IntOffset(100, 100), properties = PopupProperties(focusable = true)) {
                Card(modifier = Modifier.width(300.dp), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, borderColor), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                        if (client?.attributes?.hasSeparateAVs == true) {
                            var showLocales by remember { mutableStateOf(false) }
                            Text("Audio:", color = textColor, style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(top = 5.dp).clickable { showLocales = !showLocales }) {
                                Text(" Locale: ${locale ?: "null"}", color = textColor, modifier = Modifier.padding(horizontal = 3.dp))
                            }
                            if (showLocales && locales.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(10.dp)) {
                                val langs = remember { player.curLocales.toList() }
                                for (index in langs.indices) {
                                    FilterChip(label = { Text(langs[index]) }, selected = locale==langs[index], border = filterChipBorder(locale==langs[index]), onClick = {
                                        locale = langs[index]
                                        val bSet = mutableSetOf<Int>()
                                        for (s in player.audioSpecs) buildBRSet(s, bSet)
                                        bitRates = bSet.toList()
                                        bitrate = if (bitRates.isNotEmpty()) bitRates[0] else 0
//                                        reset = true
                                        showLocales = false
                                    })
                                }
                            }
                            var showCodecs by remember { mutableStateOf(false) }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(top = 5.dp).clickable { showCodecs = !showCodecs }) {
                                Text("Codec: $codec", color = textColor, modifier = Modifier.padding(end = 10.dp))
                            }
                            if (showCodecs && codecs.size > 1) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(10.dp)) {
                                    for (index in codecs.indices) {
                                        FilterChip(label = { Text(codecs[index]) }, selected = codec==codecs[index], border = filterChipBorder(codec==codecs[index]), onClick = {
                                            codec = codecs[index]
                                            val bSet = mutableSetOf<Int>()
                                            for (s in player.audioSpecs) buildBRSet(s, bSet)
                                            bitRates = bSet.toList()
                                            bitrate = if (bitRates.isNotEmpty()) bitRates[0] else 0
//                                            reset = true
                                            showCodecs = false
                                        })
                                    }
                                }
                            }
                            var showbitrates by remember { mutableStateOf(false) }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(top = 5.dp).clickable { showbitrates = !showbitrates }) {
                                Text("Bitrate: $bitrate", color = textColor, modifier = Modifier.padding(end = 10.dp))
                            }
                            if (showbitrates && bitRates.size > 1) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(10.dp)) {
                                    for (index in bitRates.indices) {
                                        FilterChip(label = { Text(bitRates[index].toString()) }, selected = bitrate==bitRates[index], border = filterChipBorder(bitrate==bitRates[index]), onClick = {
                                            bitrate = bitRates[index]
                                            reset = true
                                            showbitrates = false
                                        })
                                    }
                                }
                            }
                        }
                        if (playingVideo) {
                            Text("Video:", color = textColor, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 3.dp))
                            var showProts by remember { mutableStateOf(false) }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(top = 5.dp).clickable { showProts = !showProts }) {
                                Text("Protocols: $protocol", color = textColor, modifier = Modifier.padding(end = 10.dp))
                            }
                            if (showProts && protocols.size > 1) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(10.dp)) {
                                    for (index in protocols.indices) {
                                        FilterChip(label = { Text(protocols[index]) }, selected = protocol==protocols[index], border = filterChipBorder(protocol==protocols[index]), onClick = {
                                            protocol = protocols[index]
                                            val rSet = mutableSetOf<String>()
                                            for (s in player.videoSpecs) buildResoSet(s, rSet)
                                            resolutions = rSet.toList()
                                            resolution = if (resolutions.isNotEmpty()) resolutions[0] else null
//                                            reset = true
                                            showProts = false
                                        })
                                    }
                                }
                            }

                            var showCodecs by remember { mutableStateOf(false) }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(top = 5.dp).clickable { showCodecs = !showCodecs }) {
                                Text("Video Codec: $vcodec", color = textColor, modifier = Modifier.padding(end = 10.dp))
                            }
                            if (showCodecs && vcodecs.size > 1) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(10.dp)) {
                                    for (index in vcodecs.indices) {
                                        FilterChip(label = { Text(vcodecs[index]) }, selected = vcodec==vcodecs[index], border = filterChipBorder(vcodec==vcodecs[index]), onClick = {
                                            vcodec = vcodecs[index]
                                            val rSet = mutableSetOf<String>()
                                            for (s in player.videoSpecs) buildResoSet(s, rSet)
                                            resolutions = rSet.toList()
                                            resolution = if (resolutions.isNotEmpty()) resolutions[0] else null
//                                            reset = true
                                            showCodecs = false
                                        })
                                    }
                                }
                            }

                            var showResolutions by remember { mutableStateOf(false) }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(top = 5.dp).clickable { showResolutions = !showResolutions }) {
                                Text("Resolution: $resolution", color = textColor, modifier = Modifier.padding(end = 10.dp))
                            }
                            if (showResolutions && resolutions.size > 1) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(10.dp)) {
                                    for (index in resolutions.indices) {
                                        FilterChip(label = { Text(resolutions[index]) }, selected = resolution==resolutions[index], border = filterChipBorder(resolution==resolutions[index]), onClick = {
                                            resolution = resolutions[index]
                                            reset = true
                                            showResolutions = false
                                        })
                                    }
                                }
                            }
                        }
                        if (reset) Button(onClick = {
                            Logd(TAG, "before restart episode ${episode.forceVideo}")
                            player.pause(false)
                            getCache().removeResource(episode.id.toString())
                            player.setAudioStream(locale, codec, bitrate)
                            if (resolution != null) player.useResolution = resolution
                            if (vcodec != null) player.useVCodex = vcodec
                            val media = if (vm.forceVideo) upsertBlk(episode) { it.forceVideo = true } else null
                            player.startPlaying(media)
                            reset = false
                        }) { Text(stringResource(R.string.confirm_label)) }
                    }
                }
            }
        }

        if (showAVChooser) StreamChanger { showAVChooser = false}
        var showChooseRatingDialog by remember { mutableStateOf(false) }
        if (showChooseRatingDialog) ChooseRatingDialog(listOf(episode)) { showChooseRatingDialog = false }
        val swipeVelocityThreshold = 1500f
        val swipeDistanceThreshold = with(LocalDensity.current) { 100.dp.toPx() }
        val velocityTracker = remember { VelocityTracker() }
        val offsetX = remember { Animatable(0f) }
        Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surface).pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { velocityTracker.resetTracking() },
                onHorizontalDrag = { change, dragAmount ->
                    if (abs(dragAmount) > 4) {
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                    }
                },
                onDragEnd = {
                    scope.launch {
                        val velocity = velocityTracker.calculateVelocity().x
                        val distance = offsetX.value
                        val shouldSwipe = abs(distance) > swipeDistanceThreshold && abs(velocity) > swipeVelocityThreshold
                        if (shouldSwipe) {
                            when {
                                distance > 0 -> {
                                    if (vm.episodeFeed?.queue != null) navTo(Queues(id = vm.episodeFeed?.queue!!.id))
                                    else Logt(TAG, "No associated queue to go to")
                                }
                                else -> {
                                    if (vm.episodeFeed != null) navTo(FeedDetails(feedId = vm.episodeFeed!!.id))
                                    else Logt(TAG, "curEpisode is not set, no navigation options")
                                }
                            }
                            psState = PSState.PartiallyExpanded
                        }
                        offsetX.animateTo(targetValue = 0f, animationSpec = tween(300))
                    }
                },
            )
        }.offset { IntOffset(offsetX.value.roundToInt(), 0) }) {
            SelectionContainer { Text(episode.title ?: "No title", textAlign = TextAlign.Center, color = textColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 5.dp)) }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.weight(0.2f))
                val ratingIconRes by remember(episode.rating) { mutableIntStateOf( Rating.fromCode(episode.rating).res) }
                Icon(imageVector = ImageVector.vectorResource(ratingIconRes), tint = MaterialTheme.colorScheme.tertiary, contentDescription = "rating", modifier = Modifier.background(MaterialTheme.colorScheme.tertiaryContainer).width(24.dp).height(24.dp).clickable { showChooseRatingDialog = true })
                Spacer(modifier = Modifier.weight(0.4f))
                val episodeDate = remember(episode.pubDate) { formatDateTimeFlex(episode.pubDate).trim() }
                Text(episodeDate, textAlign = TextAlign.Center, color = textColor, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.weight(0.4f))
                Icon(imageVector = ImageVector.vectorResource(comboAction.iconRes), tint = MaterialTheme.colorScheme.tertiary, contentDescription = "Combo", modifier = Modifier.background(MaterialTheme.colorScheme.tertiaryContainer).clickable {  comboAction.performAction(episode) })
                Spacer(modifier = Modifier.weight(0.2f))
            }
            SelectionContainer { Text((vm.episodeFeed?.title?:"").trim(), textAlign = TextAlign.Center, color = textColor, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 5.dp)) }

            EpisodeDetails(episode, psState == PSState.Expanded, true)
            val imgLarge = remember(episode.id, displayedChapterIndex) {
                if (displayedChapterIndex == -1 || episode.chapters.isEmpty() || episode.chapters[displayedChapterIndex].imageUrl.isNullOrEmpty()) episode.imageUrl ?: episode.feed?.imageUrl
                else EmbeddedChapterImage.getModelFor(episode, displayedChapterIndex)?.toString()
            }
            if (imgLarge != null) AsyncImage( ImageRequest.Builder(context).data(imgLarge).memoryCachePolicy(CachePolicy.ENABLED).build(), placeholder = painterResource(R.drawable.ic_launcher_foreground), error = painterResource(R.drawable.ic_launcher_foreground), contentDescription = "imgvCover", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().padding(10.dp))
            Text(episode.link ?: "Link not included", color = textColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 15.dp).combinedClickable(
                onClick = { if (!episode.link.isNullOrBlank()) openInSystemDefault(episode.link!!) },
                onLongClick = { if (!episode.link.isNullOrBlank()) shareLink(context, episode.link!!) }
            ) )
        }
    }

    @Composable
    fun FullScreenVideoPlayer(vm: AVPlayerVM) {
        val context = LocalContext.current
        val view = LocalView.current
        DisposableEffect(Unit) {
            val activity = context.findActivity()
            Logd(TAG, "FullScreenVideoPlayer activity: ${activity?.title}")
            if (!isRotationEnabled) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            val window = activity?.window ?: return@DisposableEffect onDispose {}
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            onDispose {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                if (!isRotationEnabled) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = theatres[vm.playerId].mPlayerFlow.value?.castPlayer
                        useController = true
                        //                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
                update = { playerView ->
                    playerView.player = theatres[vm.playerId].mPlayerFlow.value?.castPlayer
                    playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> vm.showActionBar = visibility == View.VISIBLE })
                },
                onRelease = { playerView -> playerView.player = null }
            )
        }
//        Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.showActionBar = !vm.showActionBar }) {
//            PlayerSurface(player = player?.castPlayer, modifier = Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_SURFACE_VIEW)
//        }
    }

    @Composable
    fun VideoPlayer(vm: AVPlayerVM) {
        AndroidView(modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
//                    findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            },
            update = { playerView -> playerView.player = theatres[vm.playerId].mPlayerFlow.value?.castPlayer },
            onRelease = { view -> view.player = null }
        )
//        var aspectRatio by remember { mutableFloatStateOf(16f / 9f) }
//        DisposableEffect(player, curMedia?.id) {
//            val listener = object : Player.Listener {
//                override fun onVideoSizeChanged(videoSize: VideoSize) {
//                    if (videoSize.width > 0 && videoSize.height > 0) {
//                        aspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
//                    }
//                }
//            }
//            player?.castPlayer?.addListener(listener)
//            onDispose { player?.castPlayer?.removeListener(listener) }
//        }
//        PlayerSurface(player = player?.castPlayer, modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio))
    }

//    Logd(TAG, "landscape: ${vm.landscape}")
//    if ((landscape || curVideoMode == VideoMode.FULL_SCREEN || (curVideoMode == VideoMode.DEFAULT && appPrefs.videoPlaybackMode == VideoMode.FULL_SCREEN.code)) && playVideo && bsState == BSState.Expanded) {
    if (vm0.landscape && playingVideo && psState == PSState.Expanded) {
        Box {
            FullScreenVideoPlayer(vms[activePlayer])
            VideoToolBar(vms[activePlayer], modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    } else Box(modifier = Modifier.fillMaxWidth().then(if (psState == PSState.PartiallyExpanded) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier.statusBarsPadding().navigationBarsPadding())) {
        Column(Modifier.align(if (psState == PSState.PartiallyExpanded) Alignment.TopCenter else Alignment.BottomCenter).zIndex(1f)) {
            Logd(TAG, "activeTheatres: $activeTheatres playerMinHeight: $playerMinHeight")
            if (activeTheatres == 2) {
                PlayerUI(vms[1], Modifier)
                var sliderValue by remember { mutableFloatStateOf(0.5f) }
                Slider(modifier = Modifier.height(12.dp).padding(top = 2.dp), value = sliderValue, onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        when {
                            sliderValue > 0.51 -> {
                                val v = ((1f - sliderValue) / 0.5f).pow(2)
                                player1?.setVolume(1f, 1f)
                                player0?.setVolume(v, v)
                            }
                            sliderValue < 0.49 -> {
                                val v = (sliderValue / 0.5f).pow(2)
                                player1?.setVolume(v, v)
                                player0?.setVolume(1f, 1f)
                            }
                            else -> {
                                player0?.setVolume(1f, 1f)
                                player1?.setVolume(1f, 1f)
                            }
                        }
                    })
            }
            PlayerUI(vms[0], Modifier)
        }
        if (psState == PSState.Expanded) {
            Column(Modifier.padding(bottom = playerMinHeight.dp)) {
                if (playingVideo) {
                    VideoToolBar(vms[activePlayer])
                    VideoPlayer(vms[activePlayer])
                } else Toolbar(vms[activePlayer])
                Row(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    Icon(imageVector = ImageVector.vectorResource(R.drawable.playlist_play), tint = buttonColor, contentDescription = "queues icon", modifier = Modifier.width(24.dp).height(24.dp))
                    Icon(imageVector = ImageVector.vectorResource(R.drawable.baseline_arrow_left_alt_24), tint = textColor, contentDescription = "left_arrow", modifier = Modifier.width(24.dp).height(24.dp))
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(imageVector = ImageVector.vectorResource(R.drawable.baseline_arrow_right_alt_24), tint = textColor, contentDescription = "right_arrow", modifier = Modifier.width(24.dp).height(24.dp))
                    Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_feed), tint = buttonColor, contentDescription = "feed icon", modifier = Modifier.width(24.dp).height(24.dp))
                }
                DetailUI(vms[activePlayer], modifier = Modifier.fillMaxSize())
            }
        }
    }
}
