package ac.mdiq.podcini.playback.base

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.playback.service.PlaybackService
import ac.mdiq.podcini.storage.database.episodeById
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.CurrentState
import ac.mdiq.podcini.storage.model.CurrentState.Companion.LONG_MINUS_1
import ac.mdiq.podcini.storage.model.CurrentState.Companion.LONG_PLUS_1
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.PlayQueue
import ac.mdiq.podcini.storage.model.QueueEntry
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.timeIt
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.xilinjia.krdb.notifications.InitialObject
import io.github.xilinjia.krdb.notifications.SingleQueryChange
import io.github.xilinjia.krdb.notifications.UpdatedObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val TAG: String = "Theatres"

internal var aCtrlFuture: ListenableFuture<MediaController>? = null
var aController: MediaController? = null

internal var vCtrlFuture: ListenableFuture<MediaController>? = null
var vController: MediaController? = null

val actQueueFlow = MutableStateFlow(PlayQueue())

val activeTheatresFlow = MutableStateFlow(1)

class Theatre(val id: Int) {
    val mPlayerFlow = MutableStateFlow<MediaPlayerBase?>(null)

    var curStateMonitor: Job? = null

    fun monitorState() {
        if (curStateMonitor == null) curStateMonitor = runOnIOScope {
            val cst = realm.query(CurrentState::class).query("id == $id").first()
            Logd(TAG, "start monitoring curState: ")
            val stateFlow = cst.asFlow()
            stateFlow.collect { changes: SingleQueryChange<CurrentState> ->
                when (changes) {
                    is UpdatedObject -> {
                        mPlayerFlow.value?.curState = changes.obj
                        Logd(TAG, "stateMonitor UpdatedObject ${changes.obj.curMediaId} playerStat: $theatres[0].playerStat ${changes.changedFields.joinToString()}")
                    }
                    is InitialObject -> {
                        mPlayerFlow.value?.curState = changes.obj
                        Logd(TAG, "stateMonitor InitialObject ${changes.obj.curMediaId}")
                    }
                    else -> Logd(TAG, "stateMonitor other changes: $changes")
                }
            }
        }
    }
}

val theatres: List<Theatre> = listOf(Theatre(0), Theatre(1))

fun startTheatres() {
    timeIt("$TAG start of init")
    CoroutineScope(Dispatchers.IO).launch {
        for (i in 0..1) {
            Logd(TAG, "starting curState for player: ${theatres[i].mPlayerFlow.value?.playerId}")
            theatres[i].mPlayerFlow.value?.curState = realm.query(CurrentState::class).query("id == $i").first().find() ?: run {
                val cs = CurrentState()
                cs.id = i.toLong()
                upsertBlk(cs) { }
            }
            if (theatres[i].mPlayerFlow.value?.curState?.curMediaType != LONG_MINUS_1) {
                if (theatres[i].mPlayerFlow.value?.curState?.curMediaType == LONG_PLUS_1) {
                    if (theatres[i].mPlayerFlow.value?.curState?.curMediaId != 0L) theatres[i].mPlayerFlow.value?.setAsCurMedia(episodeById(theatres[i].mPlayerFlow.value?.curState?.curMediaId?:-1))
                }
                //                    else Logpe(TAG, theatres[i].mPlayerFlow.value?.curMediaFlow.value,  "Could not restore EpisodeMedia object from preferences for theatre $i, curMediaType: ${theatres[i].mPlayerFlow.value?.curState?.curMediaType} ")
            }
            Logd(TAG, "curMediaFlow.value from preference: ${theatres[i].mPlayerFlow.value?.curMediaFlow?.value?.title}")
            if (theatres[i].mPlayerFlow.value?.curMediaFlow?.value != null) {
                val qes = realm.query(QueueEntry::class).query("episodeId == ${theatres[i].mPlayerFlow.value?.curMediaFlow?.value!!.id}").find()
                if (qes.isNotEmpty()) {
                    realm.query(PlayQueue::class).query("id == ${qes[0].queueId}").first().find()?. let { actQueueFlow.value = it }
                }
            }
            theatres[i].curStateMonitor?.cancel()
            theatres[i].curStateMonitor = null
            theatres[i].monitorState()
        }
    }
    timeIt("$TAG end of init")
}

fun ensureAController() {
    if (aCtrlFuture == null) {
        val appContext = getAppContext()
        val sessionToken = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        aCtrlFuture = MediaController.Builder(appContext, sessionToken).buildAsync()
        aCtrlFuture?.addListener({ aController = aCtrlFuture!!.get() }, ContextCompat.getMainExecutor(appContext))
    }
}

fun releaseAController() {
    aCtrlFuture?.let { future ->
        aController = null
        MediaController.releaseFuture(future)
        aCtrlFuture = null
    }
}

fun isCurrentlyPlaying(media: Episode?): Boolean {
    return isCurMedia(media) && PlaybackService.isRunning && (theatres[0].mPlayerFlow.value?.isPlaying == true || theatres[1].mPlayerFlow.value?.isPlaying == true)
}

fun isCurMedia(media: Episode?): Boolean {
    return media != null && (theatres[0].mPlayerFlow.value?.curMediaFlow?.value?.id == media.id || theatres[1].mPlayerFlow.value?.curMediaFlow?.value?.id == media.id)
}

fun isCurMedia(id: Long): Boolean {
    return (theatres[0].mPlayerFlow.value?.curMediaFlow?.value?.id == id || theatres[1].mPlayerFlow.value?.curMediaFlow?.value?.id == id)
}

fun cleanupTheatres() {
    Logd(TAG, "cleanup()")
    for (i in 0..1) {
        if (theatres[i].mPlayerFlow.value?.curMediaFlow?.value != null) theatres[i].mPlayerFlow.value?.curMediaScope?.cancel()
        theatres[i].curStateMonitor?.cancel()
        theatres[i].curStateMonitor = null
    }
}
