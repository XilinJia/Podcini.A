package ac.mdiq.podcini.storage.model

import ac.mdiq.podcini.shared.CaptionSpec
import ac.mdiq.podcini.utils.Logd
import io.github.xilinjia.krdb.types.EmbeddedRealmObject

class TranscriptMeta: EmbeddedRealmObject {
    var url: String? = null
    var type: String? = null
    var language: String? = null
    var rel: String? = null

    constructor() {}

    constructor(url: String?, type: String?, language: String?, rel: String?) {
        this.url = url
        this.type = type
        this.language = language
        this.rel = rel
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TranscriptMeta

        if (url != other.url) return false
        if (type != other.type) return false
        if (language != other.language) return false
        if (rel != other.rel) return false

        return true
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + language.hashCode()
        result = 31 * result + rel.hashCode()
        return result
    }
}

fun CaptionSpec.toTranscriptMeta(): TranscriptMeta {
    Logd("CaptionSpec") { "toTranscriptMeta $url"}
    // TODO: this is better in ut.urn
    val kind = Regex("[?&]kind=([^&]*)").find(url)?.groupValues?.get(1)
    val source = if ("kind=asr" in url) "auto" else "manual"
    val translated = "tlang=" in url
    val summary = if (translated) "$source, translated" else source
    Logd("CaptionSpec") { "toTranscriptMeta kind=[$kind] $summary"}
    return TranscriptMeta(this.url, this.mimeType, this.language, kind.takeIf { !it.isNullOrBlank() } ?: this.suffix)
}