package ac.mdiq.podcini.storage.model

import io.github.xilinjia.krdb.types.EmbeddedRealmObject

class Chapter : EmbeddedRealmObject {
    var start: Long = 0     // milliseconds
    var title: String? = null
    var link: String? = null
    var imageUrl: String? = null

    var chapterId: String? = null   // ID from the chapter source, not the database ID.

    constructor() {}

    constructor(start: Long, title: String?, link: String?, imageUrl: String?) {
        this.start = start
        this.title = title
        this.link = link
        this.imageUrl = imageUrl
    }

    override fun toString(): String {
        return "ID3Chapter [title=$title, start=$start, url=$link]"
    }
}
