@file:Suppress("FunctionName")

package ac.mdiq.podcini.utils

import ac.mdiq.podcini.BuildConfig
import ac.mdiq.podcini.PodciniApp.Companion.appMainScope
import ac.mdiq.podcini.net.download.DownloadError
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.model.DownloadResult
import ac.mdiq.podcini.storage.model.DownloadResult.Companion.logDownloadResult
import ac.mdiq.podcini.storage.model.Feed
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

val toastMessagesFlow = MutableStateFlow<List<ToastMessage>>(emptyList())
data class ToastMessage(
    val id: Long = nowInMillis(),
    val t: String,
    val m: String
)

val sessionLogsFlow = MutableStateFlow<List<String>>(emptyList())

private suspend fun trimSessionLogs() {
    val size = sessionLogsFlow.value.size
    if (size > 120) sessionLogsFlow.update { it - sessionLogsFlow.value.subList(20, size).toSet() }
}

fun Logd(t: String, m: String) {
    if (BuildConfig.DEBUG || appPrefsFlow!!.value.printDebugLogs) Log.d(t, m)
}

fun Loge(t: String, m: String) {
    if (BuildConfig.DEBUG || appPrefsFlow!!.value.printDebugLogs) Log.e(t, m)
    appMainScope.launch {
        trimSessionLogs()
        if (appPrefsFlow!!.value.showErrorToasts) toastMessagesFlow.update { it + (ToastMessage(t = t, m = "Error: $m")) }
        sessionLogsFlow.update { it + "${fullDateTimeString()} $t: Error: $m" }
    }
}

fun Loge(t: String, e: Throwable, m: String) {
    if (BuildConfig.DEBUG || appPrefsFlow!!.value.printDebugLogs) Log.e(t, m + ": "+ e.message)
    val me = e.message
    appMainScope.launch {
        trimSessionLogs()
        if (appPrefsFlow!!.value.showErrorToasts) toastMessagesFlow.update { it + (ToastMessage(t = t, m = "Error: $m: $me")) }
        sessionLogsFlow.update { it + "${fullDateTimeString()} $t: Error: $m: $me" }
    }
}

fun LogeFor(t: String, episodeId: Long?, m: String) {
    if (BuildConfig.DEBUG || appPrefsFlow!!.value.printDebugLogs) Log.e(t, m)
    appMainScope.launch {
        trimSessionLogs()
        if (appPrefsFlow!!.value.showErrorToasts) toastMessagesFlow.update { it + (ToastMessage(t = t, m = "Error: $m")) }
        sessionLogsFlow.update { it + "${fullDateTimeString()} $t: $episodeId Error: $m" }
    }
}

fun Logs(t: String, m: String) {
    if (BuildConfig.DEBUG || appPrefsFlow!!.value.printDebugLogs) Log.e(t, m)
    showStackTrace()
    appMainScope.launch {
        trimSessionLogs()
        if (appPrefsFlow!!.value.showErrorToasts) toastMessagesFlow.update { it + (ToastMessage(t = t, m = "Error: $m ")) }
        sessionLogsFlow.update { it + "${fullDateTimeString()} $t: Error: $m " }
    }
}

fun LogsFor(t: String, episodeId: Long?, m: String) {
    if (BuildConfig.DEBUG || appPrefsFlow!!.value.printDebugLogs) Log.e(t, m)
    showStackTrace()
    appMainScope.launch {
        trimSessionLogs()
        if (appPrefsFlow!!.value.showErrorToasts) toastMessagesFlow.update { it + (ToastMessage(t = t, m = "Error: $m ")) }
        sessionLogsFlow.update { it + "${fullDateTimeString()} $t: $episodeId Error: $m " }
    }
}

fun Logs(t: String, e: Throwable, m: String = "") {
    if (BuildConfig.DEBUG || appPrefsFlow!!.value.printDebugLogs) Log.e(t, m + ": "+ e.message + "\n" + Log.getStackTraceString(e))
    val me = e.message
    appMainScope.launch {
        trimSessionLogs()
        if (appPrefsFlow!!.value.showErrorToasts) toastMessagesFlow.update { it + (ToastMessage(t = t, m = "$m Error: $me")) }
        sessionLogsFlow.update { it + "${fullDateTimeString()} $t: $m Error: $me" }
    }
}

fun LogsFor(t: String, episodeId: Long?, e: Throwable, m: String = "") {
    if (BuildConfig.DEBUG || appPrefsFlow!!.value.printDebugLogs) Log.e(t, m + ": "+ e.message + "\n" + Log.getStackTraceString(e))
    val me = e.message
    appMainScope.launch {
        trimSessionLogs()
        if (appPrefsFlow!!.value.showErrorToasts) toastMessagesFlow.update { it + (ToastMessage(t = t, m = "$m Error: $me")) }
        sessionLogsFlow.update { it + "${fullDateTimeString()} $t: $episodeId $m Error: $me" }
    }
}

fun Logt(t: String, m: String) {
    if (BuildConfig.DEBUG || appPrefsFlow!!.value.printDebugLogs) Log.d(t, m)
    appMainScope.launch {
        trimSessionLogs()
        toastMessagesFlow.update { it + (ToastMessage(t = t, m = m)) }
        sessionLogsFlow.update { it + "${fullDateTimeString()} $t: $m" }
    }
}

fun LogtFor(t: String, episodeId: Long?, m: String) {
    if (BuildConfig.DEBUG || appPrefsFlow!!.value.printDebugLogs) Log.d(t, m)
    appMainScope.launch {
        trimSessionLogs()
        toastMessagesFlow.update { it + (ToastMessage(t = t, m = m)) }
        sessionLogsFlow.update { it + "${fullDateTimeString()} $t: $episodeId $m" }
    }
}

fun LogFor(t: String, feed: Feed, success: Boolean, message: String, reason:  DownloadError? = null, toastAnyway: Boolean = false) {
    runOnIOScope { logDownloadResult(DownloadResult(feed, reason, success, message)) }
    if (toastAnyway && success) Logt(t, "Feed operation: success=$success, $message: ${feed.title}")
    if (!success) Loge(t, "Feed operation: success=$success, $message: ${feed.title}")
}

fun showStackTrace() {
    if (BuildConfig.DEBUG || appPrefsFlow!!.value.printDebugLogs) {
        val stackTraceElements = Thread.currentThread().stackTrace
        stackTraceElements.forEach { element -> Log.w("showStackTrace", element.toString()) }
    }
}

fun stackTraceShort() {
    if (BuildConfig.DEBUG || appPrefsFlow!!.value.printDebugLogs) {
        val stackTrace = Thread.currentThread().stackTrace
        val caller = if (stackTrace.size > 4) stackTrace[4] else null
        Log.d("stackTraceShort", "${caller?.className}.${caller?.methodName}")
    }
}

var startTime: Long = 0
var nanoTime: Long = 0

fun startTiming() {
    nanoTime = System.nanoTime()
    startTime = nanoTime
}
fun timeIt(msg: String) {
    if (BuildConfig.DEBUG) {
        val time = System.nanoTime()
        val dTime = (time - nanoTime) / 1000000
        val dsTime = (time - startTime) / 1000000
        Logd("TimeIt", "$msg $time delta: $dTime from Start: $dsTime" )
        nanoTime = time
    }
}