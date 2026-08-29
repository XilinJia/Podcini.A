package ac.mdiq.podcini.storage.model

import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.specs.Rating
import io.github.xilinjia.krdb.types.RealmObject
import io.github.xilinjia.krdb.types.annotations.PrimaryKey

// TODO: better to rename to DeletionLog
class SubscriptionLog: RealmObject {
    @PrimaryKey
    var id: Long = 0L   // this is the id of the deleted

    var url: String? = null

    var link: String? = null

    var title: String = ""

    var type: String? = null

    var description: String? = null

    var cancelDate: Long = 0

    var rating: Int = Rating.UNRATED.code

    var comment: String = ""

    constructor() {}

    constructor(itemId: Long, title: String, url: String, link: String, type: String) {
        this.title = title
        this.url = url
        this.link = link
        this.type = type
        id = itemId
    }

    enum class Type {
        Feed,
        Media,
    }

    companion object {
        private const val TAG: String = "SubscriptionLog"

        fun String.takeCodePoints(maxCodePoints: Int): String {
            var end = 0
            var count = 0
            while (end < length && count < maxCodePoints) {
                end += Character.charCount(codePointAt(end))
                count++
            }
            return substring(0, end)
        }

        // needs to be reset when log is added or removed
        var feedLogsMap: Map<String, SubscriptionLog>? = null
            get() {
                if (field == null) {
                    val logs = realm.query(SubscriptionLog::class).query("type == $0", "Feed").find()
                    val map = mutableMapOf<String, SubscriptionLog>()
                    for (l in logs) {
                        map[l.id.toString()] = l
                        if (!l.description.isNullOrBlank()) map[l.description!!] = l
                        if (l.title.isNotBlank()) map[l.title] = l
                        if (!l.url.isNullOrBlank()) map[l.url!!] = l
                        if (!l.link.isNullOrBlank()) map[l.link!!] = l
                    }
                    field = map.toMap()
                }
                return field
            }
    }
}