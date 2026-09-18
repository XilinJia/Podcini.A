package ac.mdiq.podcini.storage.model

import io.github.xilinjia.krdb.types.EmbeddedRealmObject

class CaptionCue: EmbeddedRealmObject {
    var startMs: Long = 0
    var endMs: Long = 0
    var speaker: String = ""
    var text: String = ""

    constructor() {}

    constructor(startMs: Long, endMs: Long, text: String, speaker: String = "") {
        this.startMs = startMs
        this.endMs = endMs
        this.speaker = speaker
        this.text = text
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CaptionCue

        if (startMs != other.startMs) return false
        if (endMs != other.endMs) return false
        if (speaker != other.speaker) return false
        if (text != other.text) return false

        return true
    }

    override fun hashCode(): Int {
        var result = startMs.hashCode()
        result = 31 * result + endMs.hashCode()
        result = 31 * result + speaker.hashCode()
        result = 31 * result + text.hashCode()
        return result
    }
}