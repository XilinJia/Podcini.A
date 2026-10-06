package ac.mdiq.podcini.utils

import ac.mdiq.podcini.BuildConfig
import ac.mdiq.podcini.storage.utils.UnifiedFile
import ac.mdiq.podcini.storage.utils.div
import ac.mdiq.podcini.storage.utils.internalDir
import android.os.Build
import android.util.Log
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import okio.buffer
import kotlin.time.Clock

class CrashReportWriter : Thread.UncaughtExceptionHandler {
    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        try {
            Log.d(TAG, "writeCrashToFile ${ex.message}")
            fun Int.pad() = this.toString().padStart(2, '0')
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            crashLogFile.sink().buffer().use { sink ->
                sink.writeUtf8("## Crash info\n")
                sink.writeUtf8("Time: ${now.day.pad()}-${now.month.number.pad()}-${now.year} " + "${now.hour.pad()}:${now.minute.pad()}:${now.second.pad()}\n")
                sink.writeUtf8("Podcini version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
                sink.writeUtf8("## StackTrace\n```\n")
                sink.writeUtf8(ex.stackTraceToString())
                sink.writeUtf8("\n```\n")
                sink.flush()
            }
        } catch (e: Throwable) {
            try {
                ex.addSuppressed(e)
                crashLogFile.sink().buffer().use { sink -> sink.writeUtf8("## Crash info (fallback)\n```\n${ex.stackTraceToString()}\n```\n") }
            } catch (_: Throwable) {  }
        } finally { defaultHandler?.uncaughtException(thread, ex) }
    }

    companion object {
        private val TAG: String = CrashReportWriter::class.simpleName ?: "Anonymous"

        val crashLogFile: UnifiedFile
            get() = internalDir / "crash-report.log"
    }
}
