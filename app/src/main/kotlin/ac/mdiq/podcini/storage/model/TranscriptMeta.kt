package ac.mdiq.podcini.storage.model

import ac.mdiq.podcini.shared.CaptionSpec
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
        var result = url?.hashCode() ?: 0
        result = 31 * result + (type?.hashCode() ?: 0)
        result = 31 * result + (language?.hashCode() ?: 0)
        result = 31 * result + (rel?.hashCode() ?: 0)
        return result
    }
}

fun CaptionSpec.toTranscriptMeta(): TranscriptMeta {
    return TranscriptMeta(this.url, this.mimeType, this.language, this.suffix)
}