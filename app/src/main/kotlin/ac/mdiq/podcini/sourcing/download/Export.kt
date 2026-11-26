package ac.mdiq.podcini.sourcing.download

import ac.mdiq.podcini.PodciniApp.Companion.appIOScope
import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R

import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.utils.UnifiedFile
import ac.mdiq.podcini.storage.utils.cacheDir
import ac.mdiq.podcini.storage.utils.div
import ac.mdiq.podcini.storage.utils.parent
import ac.mdiq.podcini.storage.utils.toAndroidUri
import ac.mdiq.podcini.storage.utils.toUF
import ac.mdiq.podcini.ui.compose.commonConfirms
import ac.mdiq.podcini.ui.compose.confirm
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Logs
import ac.mdiq.podcini.utils.Logt
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import kotlin.collections.plus
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "Export"

val transcodeProgFlow = MutableStateFlow<Map<Long, String>>(emptyMap())

suspend fun exportMediaFiles(media: Episode, outputDir: UnifiedFile) {
    val fileUrl = media.fileUrl ?: run {
        Logt(TAG, "Media is not downloaded, nothing to export")
        return
    }
    try {
        val ext = fileUrl.substringAfterLast(".")
        val uf = fileUrl.toUF()
        when (ext) {
            "json" -> {
                val dir = uf.parent()!!
                val name = uf.name.substringBeforeLast(".")
                val json = uf.readString()
                val pkg = Json.decodeFromString<AVJsonPkg>(json)
                val audioUF = (dir / pkg.audio)
                val videoUF = (dir / pkg.video)
                val ctx = getAppContext()
                confirm(title = ctx.getString(R.string.export_media), message = ctx.getString(R.string.export_av_message), confirmRes = R.string.export_combined,
                    OptionRow = {c->
                        Row {
                            Spacer(Modifier.weight(0.2f))
                            TextButton(onClick = {
                                val out = outputDir / ("${name.substringBeforeLast(".")}.${pkg.audio}")
                                appIOScope.launch {
                                    audioUF.copyTo(out)
                                    Logt(TAG, "audio file exported: ${media.title}")
                                }
                                commonConfirms.remove(c)
                            }) { Text(stringResource(R.string.audio_only)) }
                            Spacer(Modifier.weight(0.2f))
                            TextButton(onClick = {
                                val out = outputDir / ("${name.substringBeforeLast(".")}.${pkg.video}")
                                appIOScope.launch {
                                    videoUF.copyTo(out)
                                    Logt(TAG, "video file exported: ${media.title}")
                                }
                                commonConfirms.remove(c)
                            }) { Text(stringResource(R.string.video_only)) }
                            Spacer(Modifier.weight(0.2f))
                            TextButton(onClick = {
                                appIOScope.launch {
                                    val vout = outputDir / ("${name.substringBeforeLast(".")}.${pkg.video}")
                                    videoUF.copyTo(vout)
                                    val aout = outputDir / ("${name.substringBeforeLast(".")}.${pkg.audio}")
                                    audioUF.copyTo(aout)
                                    Logt(TAG, "audio and video files exported separately: ${media.title}")
                                }
                                commonConfirms.remove(c)
                            }) { Text(stringResource(R.string.separately)) }
                            Spacer(Modifier.weight(0.2f))
                        }
                    },
                    onConfirm = {
                        val outputPath = (outputDir / name).absPath
                        appIOScope.launch {
                            exportMuxed(audioUF, videoUF, outputPath) { prog-> transcodeProgFlow.update { it + (media.id to prog) } }
                            Logt(TAG, "audio/video container file exported: ${media.title}")
                        }
                    })
            }
            "m3u8" -> {
                val output = outputDir / uf.name
                uf.copyTo(output)
                uf.parent()?.let { dir -> dir.listChildren().filter { it.name.startsWith(media.id.toString()) }.forEach { it.copyTo(outputDir / it.name) } }
                Logt(TAG, "media playlist files exported: ${media.id} ${media.title}")
            }
            else -> {
                uf.copyTo(outputDir / uf.name)
                Logt(TAG, "media file exported: ${media.title}")
            }
        }
    } catch (e: Exception) { Logs(TAG, "export file failed: ${e.message}")}
}

enum class Target(val muxerFormat: Int, val ext: String, val mime: String) {
    MP4(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "mp4", "video/mp4"),
    WEBM(MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, "webm", "video/webm")
}

data class Plan(
    val target: Target,
    val transcodeVideoTo: String?,  // null = copy as is
    val transcodeAudioTo: String?
)

suspend fun exportMuxed(audioUF: UnifiedFile, videoUF: UnifiedFile, outputPath: String, onProgress: (String)->Unit) {
    val videoPath = videoUF.absPath
    val audioPath = audioUF.absPath
    fun plan(v: String, a: String): Plan {
        val webmV = setOf(MimeTypes.VIDEO_VP8, MimeTypes.VIDEO_VP9)
        val webmA = setOf(MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS)
        val mp4V = setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)
        return when {
            v in webmV && a in webmA -> Plan(Target.WEBM, null, null)
            else -> Plan(Target.MP4,
                transcodeVideoTo = if (v in mp4V) null else MimeTypes.VIDEO_H264,
                transcodeAudioTo = if (a == MimeTypes.AUDIO_AAC) null else MimeTypes.AUDIO_AAC
            )
        }
    }
    fun probe(path: String, prefix: String): String {
        val ex = MediaExtractor().apply { setDataSource(path) }
        try {
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (m != null && m.startsWith(prefix)) return m
            }
            error("No $prefix track in $path")
        } finally { ex.release() }
    }
    var tcType = ""
    suspend fun transcode(input: Uri, out: String, video: Boolean, mime: String) = suspendCancellableCoroutine { cont ->
        val item = EditedMediaItem.Builder(MediaItem.fromUri(input)).apply { if (video) setRemoveAudio(true) else setRemoveVideo(true) }.build()
        val handler = Handler(Looper.getMainLooper())
        val holder = ProgressHolder()
        lateinit var t: Transformer
        var finished = false
        val poller = object : Runnable {
            override fun run() {
                if (finished) return
                if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) onProgress("$tcType: ${holder.progress}")
                handler.postDelayed(this, 10000)
            }
        }
        val builder = Transformer.Builder(getAppContext()).setLooper(Looper.getMainLooper())
        if (video) builder.setVideoMimeType(mime) else builder.setAudioMimeType(mime)
        t = builder.addListener(object : Transformer.Listener {
            override fun onCompleted(c: Composition, r: ExportResult) {
                finished = true
                handler.removeCallbacks(poller)
                onProgress("$tcType: 100")
                cont.resume(Unit)
            }
            override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                finished = true
                handler.removeCallbacks(poller)
                cont.resumeWithException(e)
            }
        }).build()
        cont.invokeOnCancellation {
            finished = true
            handler.removeCallbacks(poller)
            handler.post { t.cancel() }
        }
        t.start(item, out)
        handler.post(poller)
    }
    val p = plan(probe(videoPath, "video/"), probe(audioPath, "audio/"))
    var v = videoPath
    var a = audioPath
    p.transcodeVideoTo?.let {
        v = (cacheDir / "v_tc.mp4").absPath
        tcType = "Video"
        withContext(Dispatchers.Main) { transcode(videoUF.toAndroidUri()!!, v, true, it) }
    }
    p.transcodeAudioTo?.let {
        a = (cacheDir / "a_tc.m4a").absPath
        tcType = "Audio"
        withContext(Dispatchers.Main) { transcode(audioUF.toAndroidUri()!!, a, false, it) }
    }
    withContext(Dispatchers.IO) {
        val output = "$outputPath.${p.target.ext}".toUF()
        if (output.exists()) output.delete()
        mux(a,v,  p.target.muxerFormat).copyTo(output)
    }
}

private fun sampleToBufferFlags(sampleFlags: Int): Int {
    var flags = 0
    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
    return flags
}

private fun mux(audioPath: String, videoPath: String, format: Int): UnifiedFile {
    val tmpfile = cacheDir / "mux_${System.currentTimeMillis()}.mp4"
    val muxer = MediaMuxer(tmpfile.absPath, format)
    Logd(TAG) { "mux audio file ${File(audioPath).length()} bytes"}
    val vEx = MediaExtractor().apply { setDataSource(videoPath) }
    val aEx = MediaExtractor().apply { setDataSource(audioPath) }
    try {
        fun pick(ex: MediaExtractor, prefix: String): Int {
            val i = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(prefix) }
            ex.selectTrack(i); return i
        }
        val vOut = muxer.addTrack(vEx.getTrackFormat(pick(vEx, "video/")))
        val aOut = muxer.addTrack(aEx.getTrackFormat(pick(aEx, "audio/")))
        muxer.start()

        val buf = ByteBuffer.allocate(2 shl 20)
        val info = MediaCodec.BufferInfo()

        var vDone = vEx.sampleTrackIndex < 0
        var aDone = aEx.sampleTrackIndex < 0

        val firsts = listOfNotNull(if (!vDone) vEx.sampleTime else null, if (!aDone) aEx.sampleTime else null)
        val base = firsts.minOrNull() ?: 0L
        var vCount = 0; var aCount = 0

        while (!vDone || !aDone) {
            val vT = if (vDone) Long.MAX_VALUE else vEx.sampleTime - base
            val aT = if (aDone) Long.MAX_VALUE else aEx.sampleTime - base
            val useVideo = vT <= aT
            val ex = if (useVideo) vEx else aEx

            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) {
                if (useVideo) vDone = true else aDone = true
                continue
            }
            info.set(0, size, if (useVideo) vT else aT, sampleToBufferFlags(ex.sampleFlags))
            muxer.writeSampleData(if (useVideo) vOut else aOut, buf, info)
            if (useVideo) vCount++ else aCount++

            if (!ex.advance()) {
                if (useVideo) vDone = true else aDone = true
            }
        }
        Logd(TAG) { "mux written: video=$vCount audio=$aCount"}
        muxer.stop()
    } finally {
        runCatching { muxer.release() }; vEx.release(); aEx.release()
    }
    return tmpfile
}