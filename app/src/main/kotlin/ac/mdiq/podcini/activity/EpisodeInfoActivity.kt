package ac.mdiq.podcini.activity

import ac.mdiq.podcini.ui.compose.CommonConfirmDialog
import ac.mdiq.podcini.ui.compose.CommonToast
import ac.mdiq.podcini.ui.compose.LargePoster
import ac.mdiq.podcini.ui.compose.PodciniTheme
import ac.mdiq.podcini.ui.compose.commonConfirms
import ac.mdiq.podcini.ui.compose.commonMessage
import ac.mdiq.podcini.ui.screens.EpisodeInfo
import ac.mdiq.podcini.ui.screens.navTo
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.toastMessages
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow

private const val TAG = "EpisodeInfoActivity"

class EpisodeInfoActivity : ComponentActivity() {
    private val currentEpisodeId = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
//        installSplashScreen()
        super.onCreate(savedInstanceState)
        Logd(TAG, "in onCreate")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        setContent {
            PodciniTheme {
//                val navController = rememberNavController()
//                val navigator = remember { MyNavigator(navController) { route -> Logd(TAG, "Navigated to: $route") } }
                val episodeId by currentEpisodeId.collectAsStateWithLifecycle()
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
                    if (toastMessages.isNotEmpty()) CommonToast(toasts = toastMessages, onDismiss = { })
                    if (commonConfirms.isNotEmpty()) CommonConfirmDialog(commonConfirms[0])
                    if (commonMessage != null) LargePoster(commonMessage!!)
                    episodeId?.let { navTo(EpisodeInfo(episodeId = episodeId!!)) }
                }
            }
        }

        currentEpisodeId.value = intent.getLongExtra("episode_info_id", -1L)

        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            (resources.displayMetrics.heightPixels * 0.90).toInt()
        )
    }

//    private fun notifyWidget() {
//        lifecycleScope.launch {
//            val gidString = intent.getStringExtra("WidgetGlanceId") ?: return@launch
//            val manager = GlanceAppWidgetManager(this@EpisodeInfoActivity)
//            val glanceId = manager.getGlanceIds(PodciniWidget::class.java).find { it.toString() == gidString } ?: return@launch
//            updateAppWidgetState(this@EpisodeInfoActivity, glanceId) { prefs -> prefs[IS_LOADING_KEY] = false }
//            PodciniWidget().update(this@EpisodeInfoActivity, glanceId)
//        }
//    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Logd(TAG, "onNewIntent")
        setIntent(intent)
        currentEpisodeId.value = intent.getLongExtra("episode_info_id", -1L)
    }

//    override fun onResume() {
//        super.onResume()
//        if (lastTheme != AppPreferences.theme) {
//            finish()
//            startActivity(Intent(this, MainActivity::class.java))
//        }
//    }

    override fun onDestroy() {
        super.onDestroy()
        Logd(TAG, "onDestroy called")
    }
}