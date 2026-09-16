package ac.mdiq.podcini.storage.specs

import ac.mdiq.podcini.R
import ac.mdiq.podcini.utils.Loge

enum class VolumeAdaptionSetting(val value: Int,  val adaptionFactor: Float, val resId: Int) {
    OFF(0, 1.0f, R.string.no_adaptation),
    LIGHT_REDUCTION(1, 0.5f, R.string.light_reduction),
    HEAVY_REDUCTION(2, 0.2f, R.string.heavy_reduction),
    LIGHT_BOOST(3, 1.6f, R.string.light_boost),
    MEDIUM_BOOST(4, 2.4f, R.string.medium_boost),
    HEAVY_BOOST(5, 3.6f, R.string.heavy_boost);

    companion object {
        fun fromInteger(value: Int): VolumeAdaptionSetting {
            val vs = VolumeAdaptionSetting.entries.firstOrNull { it.value == value }
            if (vs == null) {
                Loge("VolumeAdaptionSetting", "Cannot map value to VolumeAdaptionSetting: $value resort to OFF")
                return OFF
            }
            return vs
        }
    }
}