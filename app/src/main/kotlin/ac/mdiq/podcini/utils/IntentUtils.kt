package ac.mdiq.podcini.utils

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.storage.utils.toSafeUri
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri


private const val TAG: String = "IntentUtils"

fun isCallable(intent: Intent?): Boolean {
    if (intent == null) return false
    val list = getAppContext().packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    for (info in list) if (info.activityInfo.exported) return true
    return false
}

fun openInSystemDefault(url: String) {
    Logd(TAG) { "url: $url" }
    val context = getAppContext()
    try {
        val myIntent = Intent(Intent.ACTION_VIEW, url.toSafeUri())
        myIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(myIntent)
    } catch (e: ActivityNotFoundException) { Logs(TAG, e, context.getString(R.string.pref_no_browser_found)) }
}

fun Context.shareText(text: String, titleRes: Int? = null) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        titleRes?.let { putExtra(Intent.EXTRA_TITLE, getString(it)) }
    }
    val chooserIntent = Intent.createChooser(sendIntent, titleRes?.let { getString(it) } ?: "")
    if (this !is Activity) chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(chooserIntent)
}

fun Context.shareFile(uri: Uri, mimeType: String, titleRes: Int? = null) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooserIntent = Intent.createChooser(sendIntent, titleRes?.let { getString(it) } ?: "").apply { if (this@shareFile !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    startActivity(chooserIntent)
}