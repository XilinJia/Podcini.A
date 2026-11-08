package ac.mdiq.podcini.receiver

import ac.mdiq.podcini.config.AppConfig.initialize
import ac.mdiq.podcini.utils.Logd
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaButtonReceiver as Media3ButtonReceiver

// appears only needed for older devices or physical buttons
class MediaButtonReceiver : Media3ButtonReceiver() {
    private val TAG = "MediaButtonReceiver"

    override fun onReceive(context: Context, intent: Intent?) {
        initialize()
        Logd(TAG) { "onReceive Received intent: $intent Action: ${intent?.action}" }

        super.onReceive(context, intent)
    }
}
