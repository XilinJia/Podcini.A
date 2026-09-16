package ac.mdiq.podcini.storage.model

import io.github.xilinjia.krdb.types.EmbeddedRealmObject

class Image: EmbeddedRealmObject {
    var href: String = ""
    var alt: String? = null
    var aspectRatio: String? = null
    var width: Int? = null
    var height: Int? = null
    var type: String? = null
    var purpose: String? = null

    constructor() {}

    constructor(href: String, alt: String? = null, aspectRatio: String? = null, width: Int? = null, height: Int? = null, type: String? = null, purpose: String? = null) {
        this.href = href
        this.alt = alt
        this.aspectRatio = aspectRatio
        this.width = width
        this.height = height
        this.type = type
        this.purpose = purpose
    }
}