package ac.mdiq.podcini.storage.utils

import ac.mdiq.podcini.storage.model.CaptionCue
import ac.mdiq.podcini.utils.Logd
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "CaptionUtils"

private val VTT_TIMING = Regex("""^\s*(\d+):(\d{2}):(\d{2})\.(\d{3})\s*-->\s*(\d+):(\d{2}):(\d{2})\.(\d{3})(?:\s+.*)?$""")
private val VTT_TIMING_SHORT = Regex("""^\s*(\d{2}):(\d{2})\.(\d{3})\s*-->\s*(\d{2}):(\d{2})\.(\d{3})(?:\s+.*)?$""")
private val VTT_SPEAKER = Regex("""<v(?:\s+([^>]*))?>""")

fun parseWebVtt(input: String): List<CaptionCue> {
    fun isNotControlBlock(line: String): Boolean = !line.startsWith("NOTE") && !line.startsWith("STYLE") && !line.startsWith("REGION")
    fun parseVttTiming(line: String): Pair<Long, Long>? {
        VTT_TIMING.matchEntire(line)?.let { m ->
            val start = hmsToMs( m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4] )
            val end = hmsToMs( m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8] )
            return start to end
        }
        VTT_TIMING_SHORT.matchEntire(line)?.let { m ->
            val start = msToMs( m.groupValues[1], m.groupValues[2], m.groupValues[3] )
            val end = msToMs( m.groupValues[4], m.groupValues[5], m.groupValues[6] )
            return start to end
        }
        return null
    }

    val lines = input.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val cues = mutableListOf<CaptionCue>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()
        if (line.isEmpty() || line == "WEBVTT" || line.startsWith("NOTE") || line.startsWith("STYLE") || line.startsWith("REGION")) {
            i++
            continue
        }
//        Logd(TAG) { "parseWebVtt $i $line" }
        val timingLine: String
        when {
            line.contains("-->") -> timingLine = line
            i + 1 < lines.size && lines[i + 1].contains("-->") -> {
                i++
                timingLine = lines[i].trim()
            }
            else -> {
                i++
                continue
            }
        }
        val timing = parseVttTiming(timingLine)
//        Logd(TAG) { "parseWebVtt $i ${timing?.first}" }
        if (timing == null) {
            i++
            continue
        }
        val (startMs, endMs) = timing
        i++
        val rawText = buildString {
            while (i < lines.size && lines[i].isNotEmpty()) {
                if (isNotControlBlock(lines[i])) {
                    if (isNotEmpty()) append('\n')
                    append(lines[i])
                }
                i++
            }
        }.trim()
        if (rawText.isNotEmpty() && endMs > startMs) {
            val speaker = VTT_SPEAKER.find(rawText)?.groupValues?.getOrNull(1)?.trim()
            val text = rawText.replace(VTT_SPEAKER, "").trim()
            if (text.isNotEmpty()) cues += CaptionCue(startMs = startMs, endMs = endMs, text = text, speaker = speaker ?: "")
        }
        i++
    }
    return cues.sortedBy { it.startMs }
}

private fun hmsToMs(hours: String, minutes: String, seconds: String, millis: String ): Long = hours.toLong() * 3_600_000 + minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + millis.toLong()

private fun msToMs(minutes: String, seconds: String, millis: String ): Long = minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + millis.toLong()

private val SRT_TIMING = Regex("""^\s*(\d{2}):(\d{2}):(\d{2})[,\.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,\.](\d{3})(?:\s+.*)?$""")
private val SRT_SPEAKER = Regex("""^([^:\r\n]+):\s*""")

fun parseSrt(input: String): List<CaptionCue> {
    val lines = input.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val cues = mutableListOf<CaptionCue>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()
//        Logd(TAG) { "parseSrt line: $line" }
        if (line.isEmpty() || line.toIntOrNull() != null) {
            i++
            continue
        }
        val match = SRT_TIMING.matchEntire(line)
        if (match == null) {
            i++
            continue
        }
        val startMs = hmsToMs(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
        val endMs = hmsToMs(match.groupValues[5], match.groupValues[6], match.groupValues[7], match.groupValues[8])
        i++
        val text = buildString {
            while (i < lines.size && lines[i].isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(lines[i])
                i++
            }
        }.trim()
        if (text.isNotEmpty() && endMs > startMs) {
            val speakerMatch = SRT_SPEAKER.find(text)
            val speaker = speakerMatch?.groupValues?.get(1)?.trim() ?:""
            val cleanText = if (speakerMatch != null) text.removeRange(speakerMatch.range).trim() else text
            if (cleanText.isNotEmpty()) cues += CaptionCue(startMs = startMs, endMs = endMs, text = cleanText, speaker = speaker)
        }
        i++
    }
    return cues.sortedBy { it.startMs }
}

fun parseHTMLCaptions(html: String, durationMs: Long = 0L): List<CaptionCue> {
    fun parseTimestampMs(value: String): Long? {
        val parts = value.trim().split(':')
        return try {
            when (parts.size) {
                2 -> {
                    val minutes = parts[0].toLong()
                    val seconds = parts[1].toDouble()
                    ((minutes * 60 + seconds) * 1000).toLong()
                }
                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val seconds = parts[2].toDouble()
                    ((hours * 3600 + minutes * 60 + seconds) * 1000).toLong()
                }
                else -> null
            }
        } catch (_: NumberFormatException) { null }
    }

    val document = Ksoup.parse(html)
    val cues = mutableListOf<CaptionCue>()
    var speaker = ""
    var startMs: Long? = null
    for (element in document.body().children()) {
        when (element.tagName().lowercase()) {
            "cite" -> speaker = element.text().trim()
            "time" -> startMs = parseTimestampMs(element.text())
            "p" -> {
                val start = startMs ?: continue
                val text = element.text().trim()
                if (text.isNotEmpty()) {
                    cues += CaptionCue().apply {
                        this.startMs = start
                        this.speaker = speaker
                        this.text = text
                    }
                }
                speaker = ""
                startMs = null
            }
        }
    }
    cues.sortBy { it.startMs }
    cues.forEachIndexed { index, cue -> cue.endMs = cues.getOrNull(index + 1)?.startMs ?: durationMs }
    return cues
}

@Serializable
private data class TranscriptJson(
    val version: String? = null,
    val segments: List<TranscriptJsonSegment> = emptyList(),
)

@Serializable
private data class TranscriptJsonSegment(
    val speaker: String? = null,
    val startTime: Double,
    val endTime: Double? = null,
    val body: String = "",
)

private const val MAX_CUE_DURATION_MS = 8_000L
private const val MAX_CUE_CHARS = 400
private const val MAX_GAP_MS = 1_000L

fun parseJsonCaptions(json: String): List<CaptionCue> {
    val transcript = Json.decodeFromString<TranscriptJson>(json)
    val cues = mutableListOf<CaptionCue>()
    var current: CaptionCue? = null
    for (segment in transcript.segments) {
        val startMs = (segment.startTime * 1000).toLong()
        val endMs = ((segment.endTime ?: segment.startTime) * 1000).toLong()
        val speaker = segment.speaker.orEmpty()
        val text = segment.body.trim()
        if (text.isEmpty()) continue

        val cue = current
        if (cue == null) {
            current = CaptionCue().apply {
                this.startMs = startMs
                this.endMs = endMs
                this.speaker = speaker
                this.text = text
            }
            continue
        }

        val gap = startMs - cue.endMs
        val combinedLength = cue.text.length + 1 + text.length
        val duration = endMs - cue.startMs
        val canMerge = cue.speaker == speaker && gap <= MAX_GAP_MS && duration <= MAX_CUE_DURATION_MS && combinedLength <= MAX_CUE_CHARS
        if (canMerge) {
            cue.text += " $text"
            cue.endMs = endMs
        } else {
            cues += cue
            current = CaptionCue().apply {
                this.startMs = startMs
                this.endMs = endMs
                this.speaker = speaker
                this.text = text
            }
        }
    }
    current?.let(cues::add)
    return cues.sortedBy { it.startMs }
}

fun parseTextCaptions(text: String, durationMs: Long = 0L): List<CaptionCue> {
    val timestampRegex = Regex("""(?m)^\s*(\d{1,2}:\d{2}(?::\d{2})?(?:[.,]\d+)?)\s*$""")
    val matches = timestampRegex.findAll(text).toList()
    if (matches.isEmpty()) return emptyList()
    fun parseTimestampMs(value: String): Long? {
        val parts = value.replace(',', '.').split(':')
        return try {
            when (parts.size) {
                2 -> ((parts[0].toLong() * 60 + parts[1].toDouble()) * 1000).toLong()
                3 -> ((parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toDouble()) * 1000).toLong()
                else -> null
            }
        } catch (_: NumberFormatException) { null }
    }

    val cues = mutableListOf<CaptionCue>()
    matches.forEachIndexed { index, match ->
        val startMs = parseTimestampMs(match.groupValues[1]) ?: return@forEachIndexed
        val contentStart = match.range.last + 1
        val contentEnd = matches.getOrNull(index + 1)?.range?.first ?: text.length
        val content = text.substring(contentStart, contentEnd).trim()
        if (content.isEmpty()) return@forEachIndexed
        val speakerMatch = Regex("""^([^:\r\n]+):\s*(.*)$""", RegexOption.DOT_MATCHES_ALL).matchEntire(content)
        val speaker: String
        val captionText: String
        if (speakerMatch != null) {
            speaker = speakerMatch.groupValues[1].trim()
            captionText = speakerMatch.groupValues[2].trim()
        } else {
            speaker = ""
            captionText = content
        }
        if (captionText.isEmpty()) return@forEachIndexed
        cues += CaptionCue().apply {
            this.startMs = startMs
            this.endMs = durationMs
            this.speaker = speaker
            this.text = captionText
        }
    }
    cues.sortBy { it.startMs }
    cues.forEachIndexed { index, cue -> if (index + 1 < cues.size) cue.endMs = cues[index + 1].startMs }
    return cues
}

fun parseTTMLCaptions(xml: String): List<CaptionCue> {
    fun parseTimestampMs(value: String): Long? {
        val parts = value.trim().split(':')
        return try {
            when (parts.size) {
                2 -> ((parts[0].toLong() * 60 + parts[1].toDouble()) * 1000).toLong()
                3 -> ((parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toDouble()) * 1000).toLong()
                else -> null
            }
        } catch (_: NumberFormatException) { null }
    }
    fun mergeCaptionCues(cues: List<CaptionCue>): List<CaptionCue> {
        if (cues.isEmpty()) return emptyList()
        val result = mutableListOf<CaptionCue>()
        var current = cues.first()
        fun endsSentence(text: String): Boolean = text.trimEnd().lastOrNull() in setOf('.', '!', '?', '…')
        for (next in cues.drop(1)) {
            val gap = next.startMs - current.endMs
            val duration = next.endMs - current.startMs
            val combinedLength = current.text.length + 1 + next.text.length
            val shouldBreak = gap > MAX_GAP_MS || duration > MAX_CUE_DURATION_MS || combinedLength > MAX_CUE_CHARS || endsSentence(current.text)
            if (shouldBreak) {
                result += current
                current = next
            } else {
                current.text = "${current.text.trim()} ${next.text.trim()}"
                current.endMs = next.endMs
            }
        }
        result += current
        return result.sortedBy { it.startMs }
    }

    val document = Ksoup.parse(xml, parser = Parser.xmlParser())
    val cues = document.select("p").mapNotNull { p ->
        val startMs = p.attr("begin").let(::parseTimestampMs) ?: return@mapNotNull null
        val endMs = p.attr("end").let(::parseTimestampMs) ?: return@mapNotNull null
        val text = p.text().trim()
        if (text.isEmpty()) return@mapNotNull null
        Logd(TAG) { "parseTTMLCaptions $startMs $text" }
        CaptionCue().apply {
            this.startMs = startMs
            this.endMs = endMs
            this.text = text
        }
    }
    return mergeCaptionCues(cues)
}