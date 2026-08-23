package ac.mdiq.podcini.storage.model

import io.github.xilinjia.krdb.types.EmbeddedRealmObject

private const val TAG = "Timer"

class Timer: EmbeddedRealmObject {
    var episodeId: Long = 0L

    var alarmId: Int = 0

    var triggerTime: Long = 0L

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Timer

        if (episodeId != other.episodeId) return false
        if (alarmId != other.alarmId) return false
        if (triggerTime != other.triggerTime) return false

        return true
    }

    override fun hashCode(): Int {
        var result = episodeId.hashCode()
        result = 31 * result + alarmId
        result = 31 * result + triggerTime.hashCode()
        return result
    }
}