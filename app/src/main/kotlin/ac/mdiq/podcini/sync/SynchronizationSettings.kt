package ac.mdiq.podcini.sync

import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.syncPrefs
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.sync.queue.SynchronizationQueueSink

object SynchronizationSettings {
    var username: String?
        get() = syncPrefs.PREF_USERNAME
        set(username) {
            runOnIOScope { upsert(syncPrefs) { it.PREF_USERNAME = username } }
        }

    var password: String?
        get() = syncPrefs.PREF_PASSWORD
        set(password) {
            runOnIOScope { upsert(syncPrefs) { it.PREF_PASSWORD = password } }
        }

    var deviceID: String?
        get() = syncPrefs.PREF_DEVICEID
        set(deviceID) {
            runOnIOScope { upsert(syncPrefs) { it.PREF_DEVICEID = deviceID } }
        }

    var hosturl: String?
        get() = syncPrefs.PREF_HOSTNAME
        set(value) {
            runOnIOScope { upsert(syncPrefs) { it.PREF_HOSTNAME = value } }
        }

    var hostport: Int
        get() = syncPrefs.PREF_HOSTPORT
        set(value) {
            runOnIOScope { upsert(syncPrefs) { it.PREF_HOSTPORT = value } }
        }

    @Synchronized
    fun clear() {
        username = null
        password = null
        deviceID = null
        SynchronizationQueueSink.clearQueue()
        runOnIOScope { upsert(appPrefsFlow!!.value) { it.gpodnet_notifications = true } }
    }

    val isSyncProviderConnected: Boolean
        get() = syncPrefs.SELECTED_SYNC_PROVIDER != null

    fun resetTimestamps() {
        runOnIOScope {
            upsert(syncPrefs) {
                it.LAST_SUBSCRIPTION_SYNC_TIMESTAMP = 0L
                it.LAST_EPISODE_ACTIONS_SYNC_TIMESTAMP = 0L
                it.LAST_SYNC_ATTEMPT_TIMESTAMP = 0L
            }
        }
    }

    fun setSelectedSyncProvider(provider: SynchronizationProviderViewData?) {
        runOnIOScope { upsert(syncPrefs) { it.SELECTED_SYNC_PROVIDER = provider?.identifier } }
    }

    fun setWifiSyncEnabled(stat: Boolean) {
        runOnIOScope { upsert(syncPrefs) { it.WIFI_SYNC_ENABLED = stat } }
    }

    fun updateLastSynchronizationAttempt() {
        runOnIOScope {  upsert(syncPrefs) { it.LAST_SYNC_ATTEMPT_TIMESTAMP = nowInMillis() } }
    }

    fun setLastSynchronizationAttemptSuccess(isSuccess: Boolean) {
        runOnIOScope { upsert(syncPrefs) { it.LAST_SYNC_ATTEMPT_SUCCESS = isSuccess } }
    }

    fun setLastSubscriptionSynchronizationAttemptTimestamp(newTimeStamp: Long) {
        runOnIOScope { upsert(syncPrefs) { it.LAST_SUBSCRIPTION_SYNC_TIMESTAMP = newTimeStamp } }
    }

    fun setLastEpisodeActionSynchronizationAttemptTimestamp(timestamp: Long) {
        runOnIOScope { upsert(syncPrefs) { it.LAST_EPISODE_ACTIONS_SYNC_TIMESTAMP = timestamp } }
    }
}
