package ac.mdiq.podcini.storage.model

import ac.mdiq.podcini.storage.specs.AutoDLEQPolicy
import ac.mdiq.podcini.storage.specs.EpisodeFilter
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder.Companion.fromCode
import ac.mdiq.podcini.storage.specs.FeedAutoDLEQFilter
import io.github.xilinjia.krdb.types.EmbeddedRealmObject
import io.github.xilinjia.krdb.types.annotations.Ignore

class AutoDLEQ: EmbeddedRealmObject {
    @Ignore
    var episodeFilterADL: EpisodeFilter = EpisodeFilter()
        get() {
            val f = EpisodeFilter(filterStringADL)
            f.durationFloor = durationFloorADL
            f.durationCeiling = durationCeilingADL
            return f
        }
        set(value) {
            field = value
            filterStringADL = value.propertySet.joinToString()
            durationFloorADL = value.durationFloor
            durationCeilingADL = value.durationCeiling
        }
    var filterStringADL: String = ""
    var durationFloorADL: Int = 0
    var durationCeilingADL: Int = Int.MAX_VALUE

    @Ignore
    var episodesSortOrderADL: EpisodeSortOrder = EpisodeSortOrder.DATE_DESC
        get() = fromCode(sortOrderCodeADL)
        set(value) {
            field = value
            sortOrderCodeADL = value.code
        }
    var sortOrderCodeADL: Int = EpisodeSortOrder.DATE_DESC.code

    @Ignore
    var autoDownloadFilter: FeedAutoDLEQFilter? = null
        get() = field ?: FeedAutoDLEQFilter(autoDLInclude, autoDLExclude, autoDLMinDuration, autoDLMaxDuration, markExcludedPlayed)
        set(value) {
            field = value
            autoDLInclude = value?.includeFilterRaw ?: ""
            autoDLExclude = value?.excludeFilterRaw ?: ""
            autoDLMinDuration = value?.minDurationFilter ?: 0
            autoDLMaxDuration = value?.maxDurationFilter ?: 0
            markExcludedPlayed = value?.markExcludedPlayed == true
        }
    var autoDLInclude: String? = ""
    var autoDLExclude: String? = ""
    var autoDLMinDuration: Int = 0
    var autoDLMaxDuration: Int = 0
    var markExcludedPlayed: Boolean = false

    @Ignore
    var autoDLPolicy: AutoDLEQPolicy = AutoDLEQPolicy.ONLY_NEW
        get() {
            val value = AutoDLEQPolicy.fromCode(autoDLPolicyCode)
            value.replace = autoDLPolicyReplace
            return value
        }
        set(value) {
            field = value
            autoDLPolicyCode = value.code
            autoDLPolicyReplace = value.replace
        }
    var autoDLPolicyCode: Int = AutoDLEQPolicy.ONLY_NEW.code
    var autoDLPolicyReplace: Boolean = false

    constructor() {}

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AutoDLEQ

        if (durationFloorADL != other.durationFloorADL) return false
        if (durationCeilingADL != other.durationCeilingADL) return false
        if (sortOrderCodeADL != other.sortOrderCodeADL) return false
        if (autoDLMinDuration != other.autoDLMinDuration) return false
        if (autoDLMaxDuration != other.autoDLMaxDuration) return false
        if (markExcludedPlayed != other.markExcludedPlayed) return false
        if (autoDLPolicyCode != other.autoDLPolicyCode) return false
        if (autoDLPolicyReplace != other.autoDLPolicyReplace) return false
        if (filterStringADL != other.filterStringADL) return false
        if (autoDLInclude != other.autoDLInclude) return false
        if (autoDLExclude != other.autoDLExclude) return false

        return true
    }

    override fun hashCode(): Int {
        var result = durationFloorADL
        result = 31 * result + durationCeilingADL
        result = 31 * result + sortOrderCodeADL
        result = 31 * result + autoDLMinDuration
        result = 31 * result + autoDLMaxDuration
        result = 31 * result + markExcludedPlayed.hashCode()
        result = 31 * result + autoDLPolicyCode
        result = 31 * result + autoDLPolicyReplace.hashCode()
        result = 31 * result + filterStringADL.hashCode()
        result = 31 * result + autoDLInclude.hashCode()
        result = 31 * result + autoDLExclude.hashCode()
        return result
    }
}