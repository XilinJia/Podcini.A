package ac.mdiq.podcini.playback.cast

import ac.mdiq.podcini.playback.PlaybackService.Companion.isCasting
import ac.mdiq.podcini.ui.compose.isLightTheme
import ac.mdiq.podcini.utils.Logd
import android.os.Bundle
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

abstract class BaseActivity : AppCompatActivity() {
    private var canCast by mutableStateOf(false)
    private var TAG = "BaseActivity"

    private var castContext: CastContext? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        canCast = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        castContext = CastContext.getSharedInstance(this)
    }

    @Composable
    fun CastIconButton() {
        val isLight = isLightTheme()
        if (canCast) AndroidView(modifier = Modifier.size(24.dp),
            factory = { ctx ->
                val themedContext = ContextThemeWrapper(ctx, if (!isLight) androidx.appcompat.R.style.Theme_AppCompat
                else androidx.appcompat.R.style.Theme_AppCompat_Light)
                MediaRouteButton(themedContext).apply { CastButtonFactory.setUpMediaRouteButton(ctx, this) }
            }
        )
    }

    override fun onStart() {
        super.onStart()
        castContext?.sessionManager?.addSessionManagerListener(sessionManagerListener, CastSession::class.java)
    }

    override fun onStop() {
        super.onStop()
        castContext?.sessionManager?.removeSessionManagerListener(sessionManagerListener, CastSession::class.java)
    }

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            isCasting = true
            Logd(TAG) { "onSessionStarted isCasting: $isCasting" }
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            isCasting = false
            Logd(TAG) { "onSessionEnded isCasting: $isCasting" }
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            isCasting = true
            Logd(TAG) { "onSessionResumed isCasting: $isCasting" }
        }

        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }
}
