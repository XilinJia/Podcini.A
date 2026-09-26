package ac.mdiq.podcini.playback

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.utils.NetworkUtils
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.specs.EpisodeState
import ac.mdiq.podcini.storage.utils.MediaMetadataRetrieverCompat
import ac.mdiq.podcini.storage.utils.div
import ac.mdiq.podcini.storage.utils.generateFileName
import ac.mdiq.podcini.storage.utils.mediaDir
import ac.mdiq.podcini.storage.utils.toUF
import ac.mdiq.podcini.ui.compose.CommonMessageAttrib
import ac.mdiq.podcini.ui.compose.commonMessage
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logs
import ac.mdiq.podcini.utils.Logt
import android.media.MediaMetadataRetriever
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.text.HtmlCompat
import androidx.core.text.parseAsHtml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import net.dankito.readability4j.Readability4J
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

object TTSEngine {
    const val TAG = "TTSEngine"

    val ttsTmpFiles = mutableListOf<String>()
    var ttsJob: Job? = null

    var tts: TextToSpeech? = null
    var ttsReady = false
    var ttsWorking = false

    fun ensureTTS() {
        val context = getAppContext()
        if (!ttsReady && tts == null) CoroutineScope(Dispatchers.Default).launch {
            Logd(TAG) { "starting TTS" }
            tts = TextToSpeech(context) { status: Int ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    Logt(TAG, "TTS init success")
                } else Loge(TAG, context.getString(R.string.tts_init_failed))
            }
        }
    }

    fun closeTTS() {
        if (ttsWorking) CoroutineScope(Dispatchers.Default).launch {
            while (ttsWorking) delay(10000.milliseconds)
            tts?.stop()
            tts?.shutdown()
            ttsWorking = false
            ttsReady = false
            tts = null
        }
    }

    fun doTTSNow(item_: Episode, textSourceIndex: Int, speakCB: (Boolean)->Unit) {
        var item = item_
        runOnIOScope {
            var readerText: String? = null
            when (textSourceIndex) {
                1 -> readerText = item.description?.parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
                2 -> {
                    if (item.transcript == null) {
                        val url = item.link!!
                        val htmlSource = NetworkUtils.fetchHtmlSource(url)
                        val article = Readability4J(item.link!!, htmlSource).parse()
                        readerText = article.textContent ?: ""
                        item = upsert(item) { it.setTranscriptIfLonger(article.contentWithDocumentsCharsetOrUtf8) }
                        Logd(TAG) { "readability4J: ${readerText.substring(max(0, readerText.length - 100), readerText.length)}" }
                    } else readerText = item.transcript!!.parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
                }
            }
            Logd(TAG) { "readerText: $readerText" }
            commonMessage = CommonMessageAttrib(
                title = "",
                message = readerText!!,
                okRes = R.string.stop,
                onOK = {
                    tts?.stop()
                    commonMessage = null
                }
            )
            while (!ttsReady) delay(200.milliseconds)
            if (tts?.isSpeaking == true) tts?.stop()
            speakCB(true)
            if (!item.feed?.langSet.isNullOrEmpty()) {
                val lang = item.feed!!.langSet.first()
                val result = tts?.setLanguage(Locale.forLanguageTag(lang))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) Loge(TAG, getAppContext().getString(R.string.language_not_supported_by_tts) + lang)
            }
            var startIndex = 0
            tts?.setSpeechRate(item.feed?.playSpeed ?: 1.0f)
            val chunkLength = TextToSpeech.getMaxSpeechInputLength() / 2
            while (startIndex < readerText.length) {
                val endIndex = minOf(startIndex + chunkLength, readerText.length)
                Logd(TAG) { "startIndex: $startIndex endIndex: $endIndex" }
                val chunk = readerText.substring(startIndex, endIndex)
                Logd(TAG) { "chunk: $chunk" }
                tts?.speak(chunk, TextToSpeech.QUEUE_ADD, null, null)
                startIndex += chunkLength
            }
            while (tts?.isSpeaking == true) delay(1000.milliseconds)
            speakCB(false)
        }
    }

    fun doTTS(item_: Episode, textSourceIndex: Int, processCB: (Int)-> Unit, update: (Episode)->Unit) {
        var item = item_
        processCB(1)
        item = upsertBlk(item) { it.setPlayState(EpisodeState.BUILDING) }
        ttsTmpFiles.clear()
        ensureTTS()
        ttsJob = runOnIOScope {
            var readerText: String? = null
            processCB(1)
            when (textSourceIndex) {
                1 -> readerText = item.description?.parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
                2 -> {
                    if (item.transcript == null) {
                        val url = item.link!!
                        val htmlSource = NetworkUtils.fetchHtmlSource(url)
                        val article = Readability4J(item.link!!, htmlSource).parse()
                        readerText = article.textContent
                        item = upsertBlk(item) { it.setTranscriptIfLonger(article.contentWithDocumentsCharsetOrUtf8) }
                        Logd(TAG) { "readability4J: ${readerText?.substring(max(0, readerText.length - 100), readerText.length)}" }
                    } else readerText = item.transcript!!.parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
                }
            }

            Logd(TAG) { "readerText: [$readerText]" }
            if (!readerText.isNullOrBlank()) {
                processCB(5)
                while (!ttsReady) { delay(100.milliseconds) }
                withContext(Dispatchers.Main) { processCB(15) }
                while (ttsWorking) { delay(100.milliseconds) }
                ttsWorking = true
                if (!item.feed?.langSet.isNullOrEmpty()) {
                    val lang = item.feed!!.langSet.first()
                    val result = tts?.setLanguage(Locale.forLanguageTag(lang))
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) Loge(TAG, getAppContext().getString(R.string.language_not_supported_by_tts) + " $lang $result")
                }

                var engineIndex = 0
                val mediaFile = mediaDir / generateFileName(item.feed?.title ?: "") / item.mediafilename()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { engineIndex++ }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String) {}    // deprecated but have to override
                    override fun onError(utteranceId: String, errorCode: Int) {}
                })

                Logd(TAG) { "readerText length: ${readerText.length}" }
                var startIndex = 0
                var i = 0
                val chunkLength = TextToSpeech.getMaxSpeechInputLength() / 5   // TTS engine can't handle longer text
                var status = TextToSpeech.ERROR
                while (startIndex < readerText.length) {
                    Logd(TAG) { "working on chunk $i $startIndex" }
                    val endIndex = minOf(startIndex + chunkLength, readerText.length)
                    val chunk = readerText.substring(startIndex, endIndex)
                    Logd(TAG) { "chunk: $chunk" }
                    try {
                        val tempFile = File.createTempFile("tts_temp_${i}_", ".wav")
                        ttsTmpFiles.add(tempFile.absolutePath)
                        status = tts?.synthesizeToFile(chunk, null, tempFile, tempFile.absolutePath) ?: 0
                        Logd(TAG) { "status: $status chunk: ${chunk.take(min(80, chunk.length))}" }
                        if (status == TextToSpeech.ERROR) {
                            Loge(TAG, "Error generating audio file ${tempFile.absolutePath}")
                            break
                        }
                    } catch (e: Exception) { Logs(TAG, e, "writing temp file error")}
                    startIndex += chunkLength
                    i++
                    while (i - engineIndex > 0) delay(100.milliseconds)
                    withContext(Dispatchers.Main) { processCB(15 + 70 * startIndex / readerText.length) }
                }
                Logd(TAG) { "chunks finished, status: $status" }
                withContext(Dispatchers.Main) { processCB(85) }
                if (status == TextToSpeech.SUCCESS) {
                    Logd(TAG) { "TTS success, merging files to: ${mediaFile.absPath}" }
                    mergeAudios(ttsTmpFiles.toTypedArray(), mediaFile.absPath, null)
                    var durationMs = 0
                    val retriever = MediaMetadataRetrieverCompat()
                    try {
                        retriever.setDataSource(mediaFile.absPath)
                        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        if (!durationStr.isNullOrBlank()) durationMs = durationStr.toInt()
                    } catch (e: Exception) {
                        Logs(TAG, e, "Get duration failed.")
                        if (durationMs !in 30000..18000000) durationMs = 30000
                    } finally { retriever.release() }

                    val mFilename = mediaFile.absPath
                    Logd(TAG) { "saving TTS to file $mFilename" }
                    val item_ = realm.query(Episode::class).query("id = ${item.id}").first().find()
                    if (item_ != null) {
                        item = upsertBlk(item_) {
                            it.size = 0
                            it.mimeType = "audio/*"
                            it.fileUrl = mediaFile.absPath
                            it.duration = durationMs
                            it.downloaded = true
                            it.setTranscriptIfLonger(readerText)
                        }
                    }
                }
                for (p in ttsTmpFiles) p.toUF().delete()
                tts?.setOnUtteranceProgressListener(null)
                ttsWorking = false
                item = upsertBlk(item) { it.setPlayState(EpisodeState.UNPLAYED) }
                withContext(Dispatchers.Main) { processCB(-1) }
            } else {
                Loge(TAG, getAppContext().getString(R.string.episode_has_no_content))
                withContext(Dispatchers.Main) {
                    processCB(-1)
                    update(item)
                }
            }
        }
    }

    // converted to Kotlin from the java file: https://gist.github.com/DrustZ/d3d3fc8fcc1067433db4dd3079f8d187
    // TODO: need to accept uri rather than path
    fun mergeAudios(selection: Array<String>, outpath: String?, callback: OperationCallbacks?) {
        var RECORDER_SAMPLERATE = 0
        try {
            val amplifyOutputStream = DataOutputStream(BufferedOutputStream(FileOutputStream(outpath)))
            val mergeFilesStream = arrayOfNulls<DataInputStream>(selection.size)
            val sizes = LongArray(selection.size)
            for (i in selection.indices) {
                val file = File(selection[i])
                sizes[i] = (file.length() - 44) / 2
            }
            for (i in selection.indices) {
                mergeFilesStream[i] = DataInputStream(BufferedInputStream(FileInputStream(selection[i])))
                if (i == selection.size - 1) {
                    mergeFilesStream[i]!!.skip(24)
                    val sampleRt = ByteArray(4)
                    mergeFilesStream[i]!!.read(sampleRt)
                    val bbInt = ByteBuffer.wrap(sampleRt).order(ByteOrder.LITTLE_ENDIAN)
                    RECORDER_SAMPLERATE = bbInt.getInt()
                    mergeFilesStream[i]!!.skip(16)
                } else mergeFilesStream[i]!!.skip(44)
            }

            for (b in selection.indices) {
                for (i in 0 until sizes[b].toInt()) {
                    val dataBytes = ByteArray(2)
                    try {
                        dataBytes[0] = mergeFilesStream[b]!!.readByte()
                        dataBytes[1] = mergeFilesStream[b]!!.readByte()
                    } catch (e: EOFException) {
                        amplifyOutputStream.close()
                        Loge(TAG, e, "mergeAudios error")
                    }

                    val dataInShort = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN).getShort()
                    val dataInFloat = dataInShort.toFloat() / 37268.0f

                    val outputSample = (dataInFloat * 37268.0f).toInt().toShort()
                    val dataFin = ByteArray(2)
                    dataFin[0] = (outputSample.toInt() and 0xff).toByte()
                    dataFin[1] = ((outputSample.toInt() shr 8) and 0xff).toByte()
                    amplifyOutputStream.write(dataFin, 0, 2)
                }
            }
            amplifyOutputStream.close()
            for (i in selection.indices) mergeFilesStream[i]!!.close()
        } catch (e: FileNotFoundException) {
            callback?.onAudioOperationError(e)
            Logs(TAG, e)
        } catch (e: IOException) {
            callback?.onAudioOperationError(e)
            Logs(TAG, e)
        }
        var size: Long = 0
        try {
            val fileSize = FileInputStream(outpath)
            size = fileSize.channel.size()
            fileSize.close()
        } catch (e1: FileNotFoundException) { Logs(TAG, e1)
        } catch (e: IOException) { Logs(TAG, e) }

        val RECORDER_BPP = 16

        val datasize = size + 36
        val byteRate = ((RECORDER_BPP * RECORDER_SAMPLERATE) / 8).toLong()
        val longSampleRate = RECORDER_SAMPLERATE.toLong()
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte() // RIFF/WAVE header
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (datasize and 0xffL).toByte()
        header[5] = ((datasize shr 8) and 0xffL).toByte()
        header[6] = ((datasize shr 16) and 0xffL).toByte()
        header[7] = ((datasize shr 24) and 0xffL).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1
        header[21] = 0
        header[22] = 1.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xffL).toByte()
        header[25] = ((longSampleRate shr 8) and 0xffL).toByte()
        header[26] = ((longSampleRate shr 16) and 0xffL).toByte()
        header[27] = ((longSampleRate shr 24) and 0xffL).toByte()
        header[28] = (byteRate and 0xffL).toByte()
        header[29] = ((byteRate shr 8) and 0xffL).toByte()
        header[30] = ((byteRate shr 16) and 0xffL).toByte()
        header[31] = ((byteRate shr 24) and 0xffL).toByte()
        header[32] = ((RECORDER_BPP) / 8).toByte() // block align
        header[33] = 0
        header[34] = RECORDER_BPP.toByte() // bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (size and 0xffL).toByte()
        header[41] = ((size shr 8) and 0xffL).toByte()
        header[42] = ((size shr 16) and 0xffL).toByte()
        header[43] = ((size shr 24) and 0xffL).toByte()

        // out.write(header, 0, 44);
        try {
            val rFile = RandomAccessFile(outpath, "rw")
            rFile.seek(0)
            rFile.write(header)
            rFile.close()
            callback?.onAudioOperationFinished()
        } catch (e: FileNotFoundException) {
            // TODO Auto-generated catch block
            callback?.onAudioOperationError(e)
            Logs(TAG, e)
        } catch (e: IOException) {
            // TODO Auto-generated catch block
            callback?.onAudioOperationError(e)
            Logs(TAG, e)
        }
    }

    interface OperationCallbacks {
        fun onAudioOperationFinished()
        fun onAudioOperationError(e: Exception?)
    }
}