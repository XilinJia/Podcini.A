package ac.mdiq.podcini.sourcing.download

import ac.mdiq.podcini.R
import ac.mdiq.podcini.shared.PodciniHttpClient.documentKtor
import ac.mdiq.podcini.shared.PodciniHttpClient.mediaKtor
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.sourcing.download.DownloadRequest.Companion.CredentialsKey
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.model.DownloadResult
import ac.mdiq.podcini.storage.utils.div
import ac.mdiq.podcini.storage.utils.freeSpaceAvailable
import ac.mdiq.podcini.storage.utils.parent
import ac.mdiq.podcini.storage.utils.parseDate
import ac.mdiq.podcini.storage.utils.toUF
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logs
import ac.mdiq.podcini.utils.Logt
import ac.mdiq.podcini.utils.NetworkUtils.getURIFromRequestUrl
import ac.mdiq.podcini.utils.NetworkUtils.wasDownloadBlocked
import ac.mdiq.podcini.utils.PODCINI_USER_AGENT
import android.os.SystemClock
import androidx.annotation.StringRes
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.http.userAgent
import io.ktor.util.network.UnresolvedAddressException
import io.ktor.utils.io.asSource
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.buffer
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.use

sealed class DownloadStatus {
    object Queued : DownloadStatus()
    data class Running(val progress: Int) : DownloadStatus()
    object Completed : DownloadStatus()
    data class Failed(val reason: String? = null) : DownloadStatus()
}

@Serializable
data class AVJsonPkg(
    val audio: String,
    val video: String
)

class Downloader(val request: DownloadRequest) {
    private val downloadDispatcher = Dispatchers.IO.limitedParallelism(3)

    var cancelled: Boolean
    var permanentRedirectUrl: String? = null
    val result: DownloadResult

    init {
        request.statusMsg = (R.string.download_pending)
        cancelled = false
        result = DownloadResult(request.feedfileId, request.title?:"", null, false, "", request.feedfileType, nowInMillis())
    }

    suspend fun downloadFeed(cb: suspend (Source)->Unit) {
        Logd(TAG) { "starting downloadFeed() source: ${request.sources[0]} dest: ${request.destination}" }
        if (request.sources[0].isNullOrBlank()) {
            Loge(TAG, "downloadFeed url is null or empty: ${request.title}")
            return
        }

        val destFile = request.destination.toUF()
        val fileExists = destFile.exists()
        Logd(TAG) { "destination: ${request.destination} fileExists: $fileExists" }

        try {
            val uri = getURIFromRequestUrl(request.sources[0]!!)
            documentKtor.prepareGet(uri.toString()) {
                userAgent(PODCINI_USER_AGENT)
                attributes.put(CredentialsKey, request)
                header(HttpHeaders.CacheControl, "no-store")
                if (uri.scheme == "http") header("Upgrade-Insecure-Requests", "1")
                Logd(TAG) { "starting download: ${request.feedfileType} ${uri.scheme}" }

                if (!request.lastModified.isNullOrEmpty()) {
                    val lastModified = request.lastModified
                    val lastModifiedDate = parseDate(lastModified)
                    if (lastModifiedDate != null) {
                        val threeDaysAgo = nowInMillis() - 1000 * 60 * 60 * 24 * 3
                        if (lastModifiedDate.toEpochMilliseconds() > threeDaysAgo) {
                            Logd(TAG) { "addHeader(\"If-Modified-Since\", \"$lastModified\")" }
                            header(HttpHeaders.IfModifiedSince, lastModified)
                        }
                    } else {
                        Logd(TAG) { "addHeader(\"If-None-Match\", \"$lastModified\")" }
                        header(HttpHeaders.IfNoneMatch, lastModified?:"")
                    }
                }
                val size = destFile.size()?:0
                if (fileExists && size > 0) {
                    request.soFar = size
                    header(HttpHeaders.Range, "bytes=${request.soFar}-")
                    Logd(TAG) { "Adding range header: " + request.soFar }
                }
                header(HttpHeaders.AcceptEncoding, "identity")
            }.execute { response ->
//                val contentEncodingHeader = response.headers[HttpHeaders.ContentEncoding]
                Logd(TAG) { "response.status: ${response.status}" }

                when {
                    response.status == HttpStatusCode.NotModified -> {
                        Logd(TAG) { "Feed '" + request.sources[0] + "' not modified since last update, Download canceled" }
                        onCancelled()
                        return@execute
                    }
                    response.status == HttpStatusCode.RequestedRangeNotSatisfiable -> {
                        val lastModified = response.headers[HttpHeaders.LastModified]
                        if (lastModified != null) request.lastModified = lastModified
                        else request.lastModified = response.headers[HttpHeaders.ETag]
                        result.setSuccessful()
                        request.progressPercent = 100
                        return@execute
                    }
                    response.status == HttpStatusCode.PartialContent -> {
                        // TODO: this appears not needed
                        val contentRangeHeader = if (fileExists) response.headers[HttpHeaders.ContentRange] else null
                        if (fileExists && response.status == HttpStatusCode.PartialContent && !contentRangeHeader.isNullOrEmpty()) {
                            val start = contentRangeHeader.removePrefix("bytes ").substringBefore('-').toLong()
                            if (start != request.soFar) {
                                Logt(TAG, "Unexpected resume offset $start, restarting download")
                                destFile.delete()
                            } else Logd(TAG) { "Resuming download at $start" }
                            val remaining = response.contentLength()
                            request.size = if (remaining != null) remaining + request.soFar else DownloadResult.SIZE_UNKNOWN.toLong()
                        }
                    }
                    response.status == HttpStatusCode.OK -> {
                        destFile.delete()
                        request.soFar = 0
                        request.size = response.contentLength() ?: DownloadResult.SIZE_UNKNOWN.toLong()
                    }
                    response.status == HttpStatusCode.NoContent -> {
                        callOnFailByResponseCode(response)
                        return@execute
                    }
                    !response.status.isSuccess() -> {
                        callOnFailByResponseCode(response)
                        return@execute
                    }
                    else -> throw IOException("Unexpected HTTP status ${response.status}")
                }
                checkIfRedirect(response)

                request.statusMsg = (R.string.download_running)
                Logd(TAG) { "Getting size of download" }
                val contentLength = response.contentLength()
                request.size = if (contentLength != null)  contentLength + request.soFar else -1L
                Logd(TAG) { "downloadRequest size is " + request.size }
                if (request.size < 0) request.size = DownloadResult.SIZE_UNKNOWN.toLong()

                response.bodyAsChannel().asSource().buffered().use { source -> cb(source) }

                if (cancelled) onCancelled()
                else {
                    val lastModified = response.headers[HttpHeaders.LastModified]
                    if (lastModified != null) request.lastModified = lastModified
                    else request.lastModified = response.headers[HttpHeaders.ETag]
                    result.setSuccessful()
                }
            }
        } catch (e: IllegalArgumentException) { onFail(DownloadError.ERROR_MALFORMED_URL, e.message)
        } catch (e: SocketTimeoutException) { onFail(DownloadError.ERROR_CONNECTION_ERROR, e.message)
        } catch (e: UnresolvedAddressException) { onFail(DownloadError.ERROR_UNKNOWN_HOST, e.message)
        } catch (e: IOException) {
            if (wasDownloadBlocked(e)) {
                onFail(DownloadError.ERROR_IO_BLOCKED, e.message)
                return
            }
            val message = e.message
            if (message != null && message.contains("Trust anchor for certification path not found")) {
                onFail(DownloadError.ERROR_CERTIFICATE, e.message)
                return
            }
            onFail(DownloadError.ERROR_IO_ERROR, e.message)
        } catch (e: NullPointerException) { onFail(DownloadError.ERROR_CONNECTION_ERROR, request.sources[0])
        } catch (e: Throwable) { onFail(DownloadError.ERROR_MISC, e.message)
        } finally { }
    }

    private val BUFFER_SIZE = 64 * 1024

    sealed interface Probe {
        data class Playlist(val url: String, val text: String) : Probe
        data class Media(val url: String) : Probe
    }

    suspend fun downloadMedia() {
        Logd(TAG) { "downloadMedia" }
        val userAgent = request.useragent
        downloadStatesFlow.update { it + (request.urlOrig!! to DownloadStatus.Running(5)) }
        val filenames = MutableList<String?>(2) { null }
        try {
            for (i in 0..1) {
                var ext = ""
                Logd(TAG) { "downloadMedia i:$i ${request.sources[i]}" }
                if (request.sources[i].isNullOrBlank()) continue
                val probe: Probe = mediaKtor.prepareGet(requireNotNull(request.sources[i]) { "source is null" }) {
                    userAgent(userAgent)
                    header(HttpHeaders.AcceptEncoding, "identity")
                    header(HttpHeaders.Range, "bytes=0-4095")
                }.execute { response ->
                    check(response.status.isSuccess()) { "HTTP error: ${response.status}" }
                    val finalUrl = response.call.request.url.toString()
                    Logd(TAG) { "downloadMedia finalUrl: $finalUrl" }
                    val channel = response.bodyAsChannel()
                    val prefix = ByteArray(64)
                    var count = 0
                    while (count < prefix.size) {
                        val n = channel.readAvailable(prefix, count, prefix.size - count)
                        if (n < 0) break
                        count += n
                    }
                    Logd(TAG) { "downloadMedia count: $count" }

                    val header = prefix.decodeToString(0, count).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                    val contentType = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
                    ext = extensionFor(contentType, i==1) ?: "part"
                    Logd(TAG) { "downloadMedia $i contentType: $contentType ext: $ext" }
                    val playlistTypes = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "application/mpegurl", "audio/mpegurl", "audio/x-mpegurl", "audio/x-scpls")
                    val isPlaylist = header.startsWith("#EXTM3U", ignoreCase = true) || header.startsWith("[playlist]", ignoreCase = true) || contentType in playlistTypes
                    Logd(TAG) { "downloadMedia isPlaylist: $isPlaylist" }

                    if (isPlaylist) Probe.Playlist(finalUrl, prefix.decodeToString(0, count) + channel.toByteArray().decodeToString())
                    else Probe.Media(finalUrl)
                }
                when (probe) {
                    is Probe.Playlist -> {
                        val (text, url) = resolveMediaPlaylist(probe.text, probe.url)
                        downloadHLS(playlistText = text, playlistUrl = url)
                    }
                    is Probe.Media -> {
                        downloadStatesFlow.update { it + (request.urlOrig!! to DownloadStatus.Running(5)) }
                        filenames[i] = downloadChunked(i, ext)
                        if (filenames[i] != null) {
                            Logd(TAG) { "downloadChunked $i complete with success" }
                            result.setSuccessful()
                            request.progressPercent = 100
                        }
                    }
                }
            }
            if (!request.sources[1].isNullOrBlank() && filenames[0] != null && filenames[1] != null) {
                val absPath = request.destination
                val fileurl = absPath + "json"
                val pkg = AVJsonPkg(filenames[0]!!, filenames[1]!!)
                fileurl.toUF().writeString(Json.encodeToString(pkg))
                request.destination = fileurl
            }
        }
        catch (e: CancellationException) { }
        catch (e: IllegalArgumentException) { onFail(DownloadError.ERROR_MALFORMED_URL, e.message) }
        catch (e: SocketTimeoutException) { onFail(DownloadError.ERROR_CONNECTION_ERROR, e.message) }
        catch (e: UnresolvedAddressException) { onFail(DownloadError.ERROR_UNKNOWN_HOST, e.message) }
        catch (e: IOException) { if (wasDownloadBlocked(e)) { onFail(DownloadError.ERROR_IO_BLOCKED, e.message); return }; val message = e.message; if (message != null && message.contains("Trust anchor for certification path not found")) { onFail(DownloadError.ERROR_CERTIFICATE, e.message); return }; onFail(DownloadError.ERROR_IO_ERROR, e.message) } catch (e: Exception) { onFail(DownloadError.ERROR_MISC, e.message) }
        finally { downloadStatesFlow.update { it + (request.urlOrig!! to DownloadStatus.Completed) } }
    }

    fun extensionFor(contentType: String?, isVideo: Boolean): String? = when (contentType) {
        "video/mp4" -> if (isVideo) "mp4" else "m4a"   // audio-only streams sometimes labeled video/mp4
        "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
        "video/webm", "audio/webm" -> "webm"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/opus" -> "opus"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/aac", "audio/aacp" -> "aac"
        "video/mp2t" -> "ts"
        "video/quicktime" -> "mov"
        else -> null
    }

    private suspend fun resolveMediaPlaylist(text: String, url: String): Pair<String, String> {
        if (!text.contains("#EXT-X-STREAM-INF")) return text to url
        val lines = text.lines().map { it.trim() }
        var best: Pair<Long, String>? = null
        lines.forEachIndexed { i, l ->
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("""BANDWIDTH=(\d+)""").find(l)?.groupValues?.get(1)?.toLong() ?: 0L
                val uri = lines.drop(i + 1).first { it.isNotEmpty() && !it.startsWith("#") }
                if (best == null || bw > best.first) best = bw to uri
            }
        }
        val variantUrl = URI(url).resolve(requireNotNull(best) { "no variants" }.second).toString()
        val variantText = mediaKtor.get(variantUrl) { header(HttpHeaders.UserAgent, request.useragent) }.bodyAsText()
        return variantText to variantUrl
    }

    private val uriAttr = Regex("""URI="([^"]+)"""")

    private suspend fun downloadHLS(playlistText: String, playlistUrl: String) {
        val out = StringBuilder()
        val absPath = request.destination
        val dir = absPath.toUF().parent()
        require(dir != null) { "downloadHLS directory is null" }
        val withoutExtension = absPath.substringBeforeLast(".")
        val id = withoutExtension.substringAfterLast(".")

        suspend fun fetch(ref: String, name: String) {
            val abs = URI(playlistUrl).resolve(ref).toString()
            (dir / name).sink().buffer().use { sink->
                val response = mediaKtor.get(abs) { header(HttpHeaders.UserAgent, request.useragent) }
                check(response.status.value in 200..299) { "HTTP error downloading segment: ${response.status}" }
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) continue
                    sink.write(buffer, 0, n)
                }
            }
        }
        try {
            var index = 0
            val lineCount = playlistText.lines().size
            for (raw in playlistText.lineSequence()) {
                if (cancelled) throw CancellationException()
                val line = raw.trim()
                when {
                    line.startsWith("#EXT-X-MAP") -> {
                        val m = uriAttr.find(line) ?: error("EXT-X-MAP without URI")
                        val name = "$id.init.mp4"
                        fetch(m.groupValues[1], name)
                        out.appendLine(line.replace(m.value, "URI=\"$name\""))
                    }
                    line.isEmpty() || line.startsWith("#") -> out.appendLine(raw)
                    else -> {
                        val name = "$id.seg%05d.m4s".format(index++)
                        fetch(line, name)
                        out.appendLine(name)
                    }
                }
                request.soFar = (++index).toLong()
                reportProgress(lineCount.toLong())
            }
//            Logd(TAG) { "downloadHls out: $out" }
            val fileUrl = absPath + "m3u8"
            Logd(TAG) { "downloadHLS fileUrl: $fileUrl" }
            fileUrl.toUF().writeString(out.toString())
            request.destination = fileUrl
            result.setSuccessful()
            request.progressPercent = 100
        } catch (e: Exception) {
            dir.listChildren().filter { it.name.startsWith(id) }.forEach { it.delete() }
            throw e
        }
    }

    private var lastReported = -1

    private fun reportProgress(total: Long?) {
        if (total == null || total <= 0) return
        val p = (100.0 * request.soFar / total).toInt().coerceIn(0, 99)
        if (p == lastReported) return
        lastReported = p
        request.progressPercent = p
//        Logd(TAG) { "reportProgress $p"}
        downloadStatesFlow.update { it + (request.urlOrig!! to DownloadStatus.Running(p)) }
    }

    private suspend fun downloadChunked(index: Int, ext: String): String? {
        val chunkSize: Long = 8L * 1024 * 1024
        var reachedEnd = false
        val source = request.sources[index] ?: return null
        return withContext(downloadDispatcher) {
            val buffer = ByteArray(64 * 1024)
            var url = getURIFromRequestUrl(source).toString()
            var total: Long? = null
            var failuresInRow = 0
            if (appPrefsFlow!!.value.checkAvailableSpace) {
                val freeSpace = freeSpaceAvailable
                Logd(TAG) { "Free space is $freeSpace" }
                if (freeSpace < 100000000) {
                    onFail(DownloadError.ERROR_NOT_ENOUGH_SPACE, "freeSpace is only $freeSpace bytes")
                    return@withContext null
                }
            }
            val absPath = request.destination
            val id = absPath.substringBeforeLast(".").substringAfterLast(".")
            val destFile = if (request.sources[1].isNullOrBlank()) {
                request.destination = "${absPath.substringBeforeLast(".")}.$ext"
                request.destination.toUF()
            } else {
                val dir = absPath.toUF().parent()
                require(dir != null) { "downloadChunked directory is null" }
                (dir / "$id.${if (index==0) "a" else "v"}.$ext")
            }
            request.ensureMediaFileExists()
            while (true) {
                val start = destFile.size() ?: 0
                request.soFar = start
                Logd(TAG) { "downloadChunked start=$start total=$total"}
                total?.let { if (start >= it) { reachedEnd = true; break} }
                Logd(TAG) { "downloadChunked start=$start"}
                var received = 0L
                var done = false
                val t0 = SystemClock.elapsedRealtime()
                Logd(TAG) { "downloadChunked chunk start=$start url=$url" }
                try {
                    if (cancelled) throw CancellationException()
                    mediaKtor.prepareGet(url) {
                        userAgent(request.useragent)
                        header(HttpHeaders.AcceptEncoding, "identity")
                        header(HttpHeaders.Range, "bytes=$start-${start + chunkSize - 1}")
                        timeout {
                            connectTimeoutMillis = 5_000
                            socketTimeoutMillis = 30_000
                            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        }
                    }.execute { resp ->
                        Logd(TAG) { "downloadChunked chunk ${resp.status} ${resp.headers[HttpHeaders.ContentRange]} after ${SystemClock.elapsedRealtime() - t0}ms" }
                        when (resp.status) {
                            HttpStatusCode.RequestedRangeNotSatisfiable -> {
                                val serverTotal = resp.headers[HttpHeaders.ContentRange]?.substringAfterLast('/')?.toLongOrNull()
                                val have = destFile.size()?:0
                                when {
                                    serverTotal == have -> done = true
                                    serverTotal != null && have > serverTotal -> {
                                        destFile.delete(); request.soFar = 0
                                        throw IOException("Restarting from 0")
                                    }
                                    else -> {
                                        destFile.delete(); request.soFar = 0
                                        throw IOException("416, restarting from 0")
                                    }
                                }
                                return@execute
                            }
                            HttpStatusCode.PartialContent -> {}
                            HttpStatusCode.OK -> {
                                destFile.delete()
                                request.soFar = 0
                                request.size = resp.contentLength() ?: DownloadResult.SIZE_UNKNOWN.toLong()
                                done = true
                            }
                            else -> throw IOException("Unexpected ${resp.status}")
                        }
                        url = resp.request.url.toString()
                        resp.headers[HttpHeaders.ContentRange]?.substringAfterLast('/')?.toLongOrNull()?.let { total = it }
                        destFile.sink(append = true).buffer().use { out ->
                            val ch = resp.bodyAsChannel()
                            while (true) {
                                val n = ch.readAvailable(buffer)
                                if (n <= 0) break
                                out.write(buffer, 0, n)
                                received += n.toLong()
                                request.soFar += n
                                reportProgress(total)
                            }
                        }
                    }
                    Logd(TAG) { "before done: $done"}
                    if (done) { reachedEnd = true; break }
                    if (received == 0L) throw IOException("Empty chunk")   // bug fix: no silent no-progress loop
                    failuresInRow = 0
                    Logd(TAG) { "downloadChunked before received check: $total $received $chunkSize"}
                    if (total == null && received < chunkSize) { reachedEnd = true; break }
                    Logd(TAG) { "downloadChunked after received check: $received"}
                } catch (e: UnsupportedRangeException) { throw e }
                catch (e: CancellationException) { throw e }
                catch (e: IOException) {
                    val dSize = destFile.size()?:0
                    Logd(TAG) { "downloadChunked chunk failed: $e (progress=${dSize > start})" }
                    url = getURIFromRequestUrl(source).toString()
                    failuresInRow = if (dSize > start) 0 else failuresInRow + 1
                    if (failuresInRow >= 6) throw e
                    delay((500L * failuresInRow.coerceAtLeast(1)).milliseconds)
                }
            }
            Logd(TAG) { "downloadChunked after while loop reachedEnd=$reachedEnd"}
            if (!reachedEnd) return@withContext null
            val len = destFile.size()?:0
            if (total != null && len != total) throw IOException("Size mismatch $len/$total")
            return@withContext destFile.name
        }
    }

    class UnsupportedRangeException : IOException("Server ignored Range")

    fun cancel() {
        cancelled = true
    }

    private fun callOnFailByResponseCode(response: HttpResponse) {
        val statusCodeInt = response.status.value
        val details = statusCodeInt.toString()
        val error: DownloadError = when (response.status) {
            HttpStatusCode.Unauthorized -> DownloadError.ERROR_UNAUTHORIZED
            HttpStatusCode.Forbidden -> DownloadError.ERROR_FORBIDDEN
            HttpStatusCode.NotFound, HttpStatusCode.Gone -> DownloadError.ERROR_NOT_FOUND
            else -> DownloadError.ERROR_HTTP_DATA_ERROR
        }
        onFail(error, details)
    }

    private fun checkIfRedirect(response: HttpResponse) {
        val isRedirect = response.status.value in 300..399
        if (!isRedirect) return
        val location = response.headers["Location"] ?: return
        val originalUrl = response.request.url.toString()
        when {
            response.status == HttpStatusCode.MovedPermanently -> { // 301
                Logd(TAG) { "Detected permanent redirect from $originalUrl to $location" }
                permanentRedirectUrl = location
            }
            location == originalUrl.replace("http://", "https://") -> {
                Logd(TAG) { "Treating http->https redirect as permanent: $originalUrl" }
                permanentRedirectUrl = location
            }
        }
    }

    private fun onFail(reason: DownloadError, reasonDetailed: String?) {
        Logs(TAG, "onFail() called with: reason = [$reason], reasonDetailed = [$reasonDetailed]")
        result.isSuccessful = false
        result.reason = reason
        result.addDetail(reasonDetailed?:"")
    }

    private fun onCancelled() {
        Logd(TAG) { "Download was cancelled" }
        result.isSuccessful = false
        result.reason = DownloadError.ERROR_DOWNLOAD_CANCELLED
        cancelled = true
    }

    companion object {
        private const val TAG = "Downloader"

        val downloadStatesFlow = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())

        fun isDownloading(url_: String?): Boolean {
            val url = url_ ?: return false
            val st = downloadStatesFlow.value[url] ?: return false
            return st is DownloadStatus.Queued || st is DownloadStatus.Running
        }
    }
}

enum class DownloadError(val code: Int, @StringRes val res:  Int) {
    SUCCESS(0, R.string.download_successful),
    ERROR_PARSER_EXCEPTION(1, R.string.download_error_parser_exception),
    ERROR_UNSUPPORTED_TYPE(2, R.string.download_error_unsupported_type),
    ERROR_CONNECTION_ERROR(3, R.string.download_error_connection_error),
    ERROR_MALFORMED_URL(4, R.string.download_error_malformed_url),
    ERROR_IO_ERROR(5, R.string.download_error_io_error),
    //    ERROR_FILE_EXISTS(6, R.string.download_error_file_exist),
    ERROR_DOWNLOAD_CANCELLED(7, R.string.download_canceled_msg),
    //    ERROR_DEVICE_NOT_FOUND(8, R.string.download_error_device_not_found),
    ERROR_HTTP_DATA_ERROR(9, R.string.download_error_http_data_error),
    ERROR_NOT_ENOUGH_SPACE(10, R.string.download_error_insufficient_space),
    ERROR_UNKNOWN_HOST(11, R.string.download_error_unknown_host),
    //    ERROR_REQUEST_ERROR(12, R.string.download_error_request_error),
    //    ERROR_DB_ACCESS_ERROR(13, R.string.download_error_db_access),
    ERROR_UNAUTHORIZED(14, R.string.download_error_unauthorized),
    //    ERROR_FILE_TYPE(15, R.string.download_error_file_type),
    ERROR_FORBIDDEN(16, R.string.download_error_forbidden),
    //    ERROR_IO_WRONG_SIZE(17, R.string.download_error_wrong_size),
    ERROR_IO_BLOCKED(18, R.string.download_error_blocked),
    ERROR_UNSUPPORTED_TYPE_HTML(19, R.string.download_error_unsupported_type_html),
    ERROR_NOT_FOUND(20, R.string.download_error_not_found),
    ERROR_CERTIFICATE(21, R.string.download_error_certificate),
    ERROR_MISC(50, R.string.download_error_misc);

    companion object {
        fun fromCode(code: Int): DownloadError {
            return DownloadError.entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("unknown code: $code")
        }
    }
}
