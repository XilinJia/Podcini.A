package ac.mdiq.podcini.sync.queue

import ac.mdiq.podcini.sync.LockingAsyncExecutor
import ac.mdiq.podcini.sync.model.EpisodeAction
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.sync.SynchronizationSettings

object SynchronizationQueueSink {
    // To avoid a dependency loop of every class to SyncService, and from SyncService back to every class.
    private var serviceStarterImpl = {}

    fun setServiceStarterImpl(serviceStarter: ()->Unit) {
        serviceStarterImpl = serviceStarter
    }

    private fun syncNow() {
        serviceStarterImpl()
    }

    fun syncNowIfNotSyncedRecently() {
        if (nowInMillis() - SynchronizationSettings.lastSyncAttempt > 1000 * 60 * 10) syncNow()
    }

    fun clearQueue() {
        LockingAsyncExecutor.executeLockedAsync { SynchronizationQueueStorage().clearQueue() }
    }

    fun enqueueFeedAddedIfSyncActive(downloadUrl: String) {
        if (!SynchronizationSettings.isSyncProviderConnected) return
        LockingAsyncExecutor.executeLockedAsync {
            SynchronizationQueueStorage().enqueueFeedAdded(downloadUrl)
            syncNow()
        }
    }

    fun enqueueFeedRemovedIfSyncActive(downloadUrl: String) {
        if (!SynchronizationSettings.isSyncProviderConnected) return
        LockingAsyncExecutor.executeLockedAsync {
            SynchronizationQueueStorage().enqueueFeedRemoved(downloadUrl)
            syncNow()
        }
    }

    fun enqueueEpisodeActionIfSyncActive(action: EpisodeAction) {
        if (!SynchronizationSettings.isSyncProviderConnected) return
        LockingAsyncExecutor.executeLockedAsync {
            SynchronizationQueueStorage().enqueueEpisodeAction(action)
            syncNow()
        }
    }

    fun enqueueEpisodePlayedIfSyncActive(media: Episode, completed: Boolean) {
        if (!SynchronizationSettings.isSyncProviderConnected) return
        if (media.feed?.isLocal == true) return
        if (media.startPosition < 0 || (!completed && media.startPosition >= media.position)) return
        val action = EpisodeAction.Builder(media, EpisodeAction.PLAY)
            .currentTimestamp()
            .started(media.startPosition / 1000)
            .position((if (completed) media.duration else media.position) / 1000)
            .total(media.duration / 1000)
            .build()
        enqueueEpisodeActionIfSyncActive(action)
    }
}
