package ac.mdiq.podcini.storage.model

import ac.mdiq.podcini.sourcing.download.DownloadError
import ac.mdiq.podcini.sourcing.download.DownloadError.Companion.fromCode
import ac.mdiq.podcini.sourcing.download.RequestType
import ac.mdiq.podcini.shared.FeedIPC
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.utils.Logd
import io.github.xilinjia.krdb.types.RealmObject
import io.github.xilinjia.krdb.types.annotations.Ignore
import io.github.xilinjia.krdb.types.annotations.PrimaryKey

class DownloadResult : RealmObject {
    @PrimaryKey
    var id: Long = 0L
        private set

    var title: String
    var feedfileId: Long

    // defined with RequestType
    var feedfileType: Int

    var isSuccessful: Boolean

    @Ignore
    var reason: DownloadError? = DownloadError.ERROR_NOT_FOUND
        get() = fromCode(reasonCode)
        set(value) {
            field = value
            reasonCode = field?.code ?: DownloadError.ERROR_NOT_FOUND.code
        }
    var reasonCode: Int = 0

    var completionTime: Long = 0L
    var reasonDetailed: String

    constructor(feedId: Long, title: String, reason: DownloadError?, successful: Boolean, reasonDetailed: String, feedfileType: Int = RequestType.FEED.code, completionDate: Long = nowInMillis()) {
        this.title = title
        this.feedfileId = feedId
        this.isSuccessful = successful
        this.feedfileType = feedfileType
        this.reason = reason
        this.completionTime = completionDate
        this.reasonDetailed = reasonDetailed
    }

    constructor(feed: Feed, reason: DownloadError?, successful: Boolean, reasonDetailed: String, completionDate: Long = nowInMillis())
            : this(feed.id, feed.title?:"no title", reason, successful, reasonDetailed, RequestType.FEED.code, completionDate)

    constructor(feed: FeedIPC, reason: DownloadError?, successful: Boolean, reasonDetailed: String, completionDate: Long = nowInMillis())
            : this(feed.id, feed.title?:"no title", reason, successful, reasonDetailed, RequestType.FEED.code, completionDate)

    constructor() : this(0L, "", DownloadError.ERROR_NOT_FOUND, false, "") {}

    override fun toString(): String {
        return ("DownloadStatus [id=$id, title=$title, reason=$reason, reasonDetailed=$reasonDetailed, successful=$isSuccessful, completionDate=$completionTime, feedfileId=$feedfileId, feedfileType=$feedfileType]")
    }

    fun setId() {
        if (idCounter < 0) idCounter = nowInMillis()
        id = idCounter++
    }

    fun setSuccessful() {
        this.isSuccessful = true
        this.reason = DownloadError.SUCCESS
    }

    fun addDetail(detail: String) {
        if (reasonDetailed.isNotBlank()) reasonDetailed += "\n"
        reasonDetailed += detail
    }

    companion object {
        private const val TAG = "DownloadResult"
        const val SIZE_UNKNOWN: Int = -1

        var idCounter: Long = -1

        fun getFeedDownloadLogs(feedId: Long): List<DownloadResult> {
            Logd(TAG, "getFeedDownloadLog() called with: $feedId")
            val dlog = realm.query(DownloadResult::class).query("feedfileId == $0", feedId).find().toMutableList()
            dlog.sortWith { lhs, rhs ->  (rhs.completionTime - lhs.completionTime).toInt() }
            return realm.copyFromRealm(dlog)
        }

        suspend fun logDownloadResult(status: DownloadResult) {
            if (status.id == 0L) status.setId()
            upsert(status) {}
        }
    }
}