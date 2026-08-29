package ac.mdiq.podcini.receiver

import ac.mdiq.podcini.automation.ALARM_TYPE
import ac.mdiq.podcini.automation.AlarmTypes
import ac.mdiq.podcini.playback.PlaybackStarter
import ac.mdiq.podcini.sources.AppGatewayRegistry
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.episodeById
import ac.mdiq.podcini.utils.Logd
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

class TimerReceiver : BroadcastReceiver() {
    val TAG = "TimerReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val message = intent?.getStringExtra(ALARM_TYPE) ?: "Timer Fired!"

        Logd(TAG, "onReceive: message $message")
        if (message.startsWith(AlarmTypes.PLAY_EPISODE.name)) {
            CoroutineScope(Dispatchers.IO).launch {
                if (appPrefsFlow!!.value.loadExternalApp)  AppGatewayRegistry.awaitReady()
                delay(5.seconds)
                val msgs = message.split(':')
                if (msgs.size < 2) return@launch
                val id = msgs[1].toLong()
                val episode = episodeById(id) ?: return@launch
                Logd(TAG, "onReceive: episode ${episode.title}")
                val repeat = if (msgs.size == 3) msgs[2].toBoolean() else false

                withContext(Dispatchers.Main) { PlaybackStarter(episode).shouldStreamThisTime(null).setToRepeat(repeat).start(0) }
            }
        }
    }
}