package ac.mdiq.podcini.playback.cast

import android.view.Menu
import androidx.compose.runtime.Composable
import androidx.activity.ComponentActivity

abstract class BaseActivity : ComponentActivity() {
    val TAG = this::class.simpleName ?: "Anonymous"

    fun requestCastButton(menu: Menu?) {}

    @Composable
    fun CastIconButton() {}
}
