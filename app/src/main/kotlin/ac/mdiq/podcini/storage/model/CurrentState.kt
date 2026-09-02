package ac.mdiq.podcini.storage.model

import io.github.xilinjia.krdb.types.RealmObject
import io.github.xilinjia.krdb.types.annotations.PrimaryKey

class CurrentState : RealmObject {
    @PrimaryKey
    var id: Long = 0L

    var curMediaId: Long = 0

    var curIsVideo: Boolean = false

    constructor() {}

    companion object {
        const val SPEED_USE_GLOBAL: Float = -1f
    }
}