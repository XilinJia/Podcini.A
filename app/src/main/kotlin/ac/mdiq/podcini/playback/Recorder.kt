package ac.mdiq.podcini.playback

import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.storage.utils.UnifiedFile
import ac.mdiq.podcini.storage.utils.div
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.LogeFor
import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okio.BufferedSink
import okio.buffer

val isRecordingFlow = MutableStateFlow(false)

class SegmentSavingDataSource(private val cacheDataSource: CacheDataSource) : DataSource {
    private val TAG = "SegmentSavingDataSource"

    private var currentDataSpec: DataSpec? = null

    private var mediaId: String = ""

    private var clipTempFile: UnifiedFile? = null
    private var clipTempFos: BufferedSink? = null
    private var clipStartByte: Long = 0L
    private var clipBytesWritten: Long = 0L

    private var bitrate: Int = 0

    private var isOpen = false

    override fun open(dataSpec: DataSpec): Long {
        close()
        currentDataSpec = dataSpec
        val t0 = SystemClock.elapsedRealtime()
        val byteToRead = try {
            cacheDataSource.open(dataSpec).also { isOpen = true }
        } catch (e: Throwable) {
            isOpen = false
            try { cacheDataSource.close() } catch (_: Exception) { }
            throw e
        }
        val t1 = SystemClock.elapsedRealtime()
//        Logd(TAG) { "open requested=${dataSpec.uri} resolved=${cacheDataSource.uri}" }
        Logd(TAG) { "open ${t1 - t0}ms requested=${dataSpec.uri} resolved=${cacheDataSource.uri}" }
        return byteToRead
    }

    private var readCalls = 0L
    private var totalBytesRead = 0L

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = cacheDataSource.read(buffer, offset, length)
        readCalls++
        if (bytesRead > 0) totalBytesRead += bytesRead
        if (readCalls % 1000 == 0L) Logd(TAG) { "read readCalls=$readCalls totalBytes=$totalBytesRead" }
        if (isRecordingFlow.value) {
//            if (readCalls % 100 == 0L) Logd(TAG) { "read isRecording readCalls=$readCalls totalBytes=$totalBytesRead" }
            if (bytesRead > 0) {
                clipTempFos?.write(buffer, offset, bytesRead)
                clipBytesWritten += bytesRead
            } else if (bytesRead == -1) clipTempFos?.flush()
        }
        return bytesRead
    }

    override fun close() {
        if (!isOpen) return
        try { cacheDataSource.close()
        } finally {
            isOpen = false
            currentDataSpec = null
        }
    }

    fun startRecording(startPositionMs: Long, bitrate: Int, tmpDir: UnifiedFile) {
        if (!isRecordingFlow.value) {
            isRecordingFlow.value = true
            this.bitrate = bitrate
            clipTempFile = tmpDir / "clip_temp_${nowInMillis()}.tmp"
            clipTempFos = clipTempFile!!.sink().buffer()
            clipStartByte = (startPositionMs * bitrate / 8 / 1000)
            clipBytesWritten = 0L
            Logd(TAG) { "Started recording at byte offset $clipStartByte" }
        } else LogeFor(TAG, mediaId.toLongOrNull(), "Cannot start recording: tempDir not set or already recording")
    }

    fun stopRecording(endPositionMs: Long): UnifiedFile? {
        Logd(TAG) { "stopRecording isRecording: ${isRecordingFlow.value}" }
        if (isRecordingFlow.value) {
            isRecordingFlow.value = false
            clipTempFos?.flush()
            clipTempFos?.close()
            val endByte = (endPositionMs * bitrate / 8 / 1000)
            Logd(TAG) { "stopRecording at byte offset $endByte, written: $clipBytesWritten" }
            return clipTempFile?.takeIf { runBlocking { it.exists() } && clipBytesWritten > 0 }
        }
        return null
    }

    override fun getUri(): Uri? = cacheDataSource.uri
    override fun addTransferListener(transferListener: TransferListener) {
        cacheDataSource.addTransferListener(transferListener)
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return cacheDataSource.responseHeaders
    }
}

class SegmentSavingDataSourceFactory(private val upstreamFactory: CacheDataSource.Factory) : DataSource.Factory {
    @Volatile
    var currentDataSource: SegmentSavingDataSource? = null
        private set

    override fun createDataSource(): DataSource {
        return SegmentSavingDataSource(upstreamFactory.createDataSource()).also { currentDataSource = it }
    }
}
