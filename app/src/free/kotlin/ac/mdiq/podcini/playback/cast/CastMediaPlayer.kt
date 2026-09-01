package ac.mdiq.podcini.playback.cast

import android.content.Context
import ac.mdiq.podcini.playback.base.MediaPlayerBase
import androidx.media3.common.Player

object CastMediaPlayer {
    fun buildCastPlayer(exoPlayer: Player): Player = exoPlayer
}
