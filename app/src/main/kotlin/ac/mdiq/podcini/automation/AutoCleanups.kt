package ac.mdiq.podcini.automation

import ac.mdiq.podcini.sourcing.download.EpisodeAdrDLManager
import ac.mdiq.podcini.storage.database.EPISODE_CACHE_SIZE_UNLIMITED
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.deleteMedia
import ac.mdiq.podcini.storage.database.getEpisodes
import ac.mdiq.podcini.storage.database.getEpisodesCount
import ac.mdiq.podcini.storage.database.inQueueEpisodeIdSet
import ac.mdiq.podcini.storage.database.removeFromAllQueues
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.specs.EpisodeFilter
import ac.mdiq.podcini.storage.specs.EpisodeSortOrder
import ac.mdiq.podcini.storage.specs.EpisodeState
import ac.mdiq.podcini.storage.specs.Rating
import ac.mdiq.podcini.ui.screens.prefscreens.EpisodeCleanupOptions
import ac.mdiq.podcini.utils.Logt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private const val TAG: String = "AutoCleanups"

fun cleanupAlgorithm(): EpisodeCleanupAlgorithm {
    if (!appPrefsFlow!!.value.enableAutoDl) return APNullCleanupAlgorithm()
    val cleanupValue = appPrefsFlow!!.value.episodeCleanup.toIntOrNull() ?: EpisodeCleanupOptions.Never.num
    return when (cleanupValue) {
        EpisodeCleanupOptions.ExceptFavorites.num -> ExceptFavoriteCleanupAlgorithm()
        EpisodeCleanupOptions.NotInQueue.num -> APQueueCleanupAlgorithm()
        EpisodeCleanupOptions.Never.num -> APNullCleanupAlgorithm()
        else -> APCleanupAlgorithm(cleanupValue)
    }
}

class ExceptFavoriteCleanupAlgorithm : EpisodeCleanupAlgorithm() {
    private val candidates: List<Episode>
        get() {
            val candidates: MutableList<Episode> = mutableListOf()
            val downloadedItems = getEpisodes(EpisodeFilter(EpisodeFilter.States.downloaded.name), EpisodeSortOrder.DATE_DESC)
            for (item in downloadedItems) if (item.downloaded && item.rating < Rating.GOOD.code) candidates.add(item)
            return candidates
        }
    override fun getReclaimableItems(): Int {
        return candidates.size
    }

    public override suspend fun performCleanup(numToRemove: Int): Int {
        var candidates = candidates
        // in the absence of better data, we'll sort by item publication date
        candidates = candidates.sortedWith { lhs: Episode, rhs: Episode ->
            val l = lhs.pubDate
            val r = rhs.pubDate
            if (l != r) return@sortedWith l.compareTo(r)
            else return@sortedWith lhs.id.compareTo(rhs.id)  // No date - compare by id which should be always incremented
        }
        return cleanup(candidates, numToRemove)
    }
    public override fun getDefaultCleanupParameter(): Int {
        val cacheSize = appPrefsFlow!!.value.episodeCacheSize
        if (cacheSize > EPISODE_CACHE_SIZE_UNLIMITED) {
            val downloadedEpisodes = getEpisodesCount(EpisodeFilter(EpisodeFilter.States.downloaded.name))
            if (downloadedEpisodes > cacheSize) return downloadedEpisodes - cacheSize
        }
        return 0
    }
}

class APQueueCleanupAlgorithm : EpisodeCleanupAlgorithm() {
    private val candidates: List<Episode>
        get() {
            val candidates: MutableList<Episode> = mutableListOf()
            val downloadedItems = getEpisodes(EpisodeFilter(EpisodeFilter.States.downloaded.name), EpisodeSortOrder.DATE_DESC)
            val idsInQueues = inQueueEpisodeIdSet()
            for (item in downloadedItems) if (item.downloaded && !idsInQueues.contains(item.id) && item.rating < Rating.GOOD.code) candidates.add(item)
            return candidates
        }
    override fun getReclaimableItems(): Int {
        return candidates.size
    }
    public override suspend fun performCleanup(numToRemove: Int): Int {
        var candidates = candidates
        // in the absence of better data, we'll sort by item publication date
        candidates = candidates.sortedWith { lhs: Episode, rhs: Episode ->
            val l = lhs.pubDate
            val r = rhs.pubDate
            l.compareTo(r)
        }
        return cleanup(candidates, numToRemove)
    }
    public override fun getDefaultCleanupParameter(): Int {
        return getNumEpisodesToCleanup(0)
    }
}

class APNullCleanupAlgorithm : EpisodeCleanupAlgorithm() {
    public override suspend fun performCleanup(numToRemove: Int): Int {
        // never clean anything up
        Logt(TAG, "performCleanup: Not removing anything")
        return 0
    }
    public override fun getDefaultCleanupParameter(): Int {
        return 0
    }
    override fun getReclaimableItems(): Int {
        return 0
    }
}

/** the number of days after playback to wait before an item is eligible to be cleaned up.
 * Fractional for number of hours, e.g., 0.5 = 12 hours, 0.0416 = 1 hour.   */
class APCleanupAlgorithm( val numberOfHoursAfterPlayback: Int) : EpisodeCleanupAlgorithm() {
    private val candidates: List<Episode>
        get() {
            val candidates: MutableList<Episode> = mutableListOf()
            val downloadedItems = getEpisodes(EpisodeFilter(EpisodeFilter.States.downloaded.name), EpisodeSortOrder.DATE_DESC)
            val idsInQueues = inQueueEpisodeIdSet()
            val mostRecentDateForDeletion = Clock.System.now().minusHours(numberOfHoursAfterPlayback).toEpochMilliseconds()
            for (item in downloadedItems) {
                if (item.downloaded && !idsInQueues.contains(item.id) && item.playState >= EpisodeState.PLAYED.code && item.rating < Rating.GOOD.code) {
                    // make sure this candidate was played at least the proper amount of days prior to now
                    if (item.playbackCompletionTime < mostRecentDateForDeletion) candidates.add(item)
                }
            }
            return candidates
        }
    override fun getReclaimableItems(): Int {
        return candidates.size
    }
    public override suspend fun performCleanup(numToRemove: Int): Int {
        val candidates = candidates.toMutableList()
        candidates.sortWith { lhs: Episode, rhs: Episode ->
            val l = lhs.playbackCompletionTime
            val r = rhs.playbackCompletionTime
            l.compareTo(r)
        }
        return cleanup(candidates, numToRemove)
    }

    fun Instant.minusHours(count: Int): Instant = this.minus(count.hours)

    public override fun getDefaultCleanupParameter(): Int = getNumEpisodesToCleanup(0)
}

abstract class EpisodeCleanupAlgorithm {
    protected abstract suspend fun performCleanup(numToRemove: Int): Int

    protected suspend fun cleanup(candidates: List<Episode>, numToRemove: Int): Int {
        val toDelete = if (candidates.size > numToRemove) candidates.subList(0, numToRemove) else candidates
        for (episode in toDelete) {
            if (episode.feed != null && !episode.feed!!.isLocal) {
                EpisodeAdrDLManager.manager.cancel(episode)
                if (episode.downloaded) deleteMedia(episode)
            }
        }
        if (appPrefsFlow!!.value.deleteRemovesFromQueue) removeFromAllQueues(toDelete)
        val counter = toDelete.size
        Logt(TAG, "Auto-delete deleted $counter episodes ($numToRemove requested)")
        return counter
    }

    protected abstract fun getDefaultCleanupParameter(): Int
    suspend fun makeRoomForEpisodes(amountOfRoomNeeded: Int): Int {
        val numToRemove = getNumEpisodesToCleanup(amountOfRoomNeeded)
        Logt("EpisodeCleanupAlgorithm", "makeRoomForEpisodes: $numToRemove")
        if (numToRemove <= 0) return 0
        return performCleanup(numToRemove)
    }
    abstract fun getReclaimableItems(): Int
    fun getNumEpisodesToCleanup(amountOfRoomNeeded: Int): Int {
        if (amountOfRoomNeeded >= 0 && appPrefsFlow!!.value.episodeCacheSize > EPISODE_CACHE_SIZE_UNLIMITED) {
            val downloadedEpisodes = getEpisodesCount(EpisodeFilter(EpisodeFilter.States.downloaded.name))
            if (downloadedEpisodes + amountOfRoomNeeded >= appPrefsFlow!!.value.episodeCacheSize) return (downloadedEpisodes + amountOfRoomNeeded - appPrefsFlow!!.value.episodeCacheSize)
        }
        return 0
    }
}

