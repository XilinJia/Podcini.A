package ac.mdiq.podcini.utils

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.storage.utils.toSafeUri
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ShareCompat.IntentBuilder


private const val TAG: String = "IntentUtils"

fun isCallable(intent: Intent?): Boolean {
    if (intent == null) return false
    val list = getAppContext().packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    for (info in list) if (info.activityInfo.exported) return true
    return false
}

fun openInSystemDefault(url: String) {
    Logd(TAG, "url: $url")
    val context = getAppContext()
    try {
        val myIntent = Intent(Intent.ACTION_VIEW, url.toSafeUri())
        myIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(myIntent)
    } catch (e: ActivityNotFoundException) { Logs(TAG, e, context.getString(R.string.pref_no_browser_found)) }
}

fun shareLink(context: Context, text: String) {
    val intent = IntentBuilder(context)
        .setType("text/plain")
        .setText(text)
        .setChooserTitle(R.string.share_url_label)
        .createChooserIntent()
    context.startActivity(intent)
}
