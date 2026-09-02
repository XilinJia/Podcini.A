package ac.mdiq.podcini.playback.base

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.playback.service.PlaybackService
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.model.CurrentState
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.PlayQueue
import ac.mdiq.podcini.utils.Logd
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.xilinjia.krdb.notifications.InitialObject
import io.github.xilinjia.krdb.notifications.SingleQueryChange
import io.github.xilinjia.krdb.notifications.UpdatedObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

private const val TAG: String = "Theatres"

internal var aCtrlFuture: ListenableFuture<MediaController>? = null
var aController: MediaController? = null

internal var vCtrlFuture: ListenableFuture<MediaController>? = null
var vController: MediaController? = null

val actQueueFlow = MutableStateFlow(PlayQueue())

val activeTheatresCount = MutableStateFlow(1)

val theatres: List<Theatre> = listOf(Theatre(0), Theatre(1))

class Theatre(val id: Int) {
    val mPlayerFlow = MutableStateFlow<MediaPlayerBase?>(null)

    var curStateMonitor: Job? = null

    fun monitorState() {
        if (curStateMonitor == null) curStateMonitor = runOnIOScope {
            val stateFlow = realm.query(CurrentState::class).query("id == $id").first().asFlow()
            Logd(TAG, "start monitoring curState: ")
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
    return theatres[0].mPlayerFlow.value?.isCurrentlyPlaying(media) == true || theatres[1].mPlayerFlow.value?.isCurrentlyPlaying(media) == true
}

fun isCurrentlyPlaying(media: Episode?, playerId: Int): Boolean {
    return playerId in listOf(0,1) && theatres[playerId].mPlayerFlow.value?.isCurrentlyPlaying(media) == true
}

fun isCurMedia(media: Episode?): Boolean {
    return media != null && (theatres[0].mPlayerFlow.value?.curMediaFlow?.value?.id == media.id || theatres[1].mPlayerFlow.value?.curMediaFlow?.value?.id == media.id)
}

//fun isCurMedia(media: Episode?, playerId: Int): Boolean {
//    return media != null && playerId in listOf(0,1) && theatres[playerId].mPlayerFlow.value?.curMediaFlow?.value?.id == media.id
//}

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
