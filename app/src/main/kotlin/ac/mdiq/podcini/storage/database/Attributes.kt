package ac.mdiq.podcini.storage.database

import ac.mdiq.podcini.PodciniApp.Companion.appIOScope
import ac.mdiq.podcini.shared.ProxyConfig
import ac.mdiq.podcini.storage.model.AppAttribs
import ac.mdiq.podcini.storage.model.AppPrefs
import ac.mdiq.podcini.storage.model.SleepPrefs
import ac.mdiq.podcini.storage.model.SyncPrefs
import ac.mdiq.podcini.utils.Logd
import io.github.xilinjia.krdb.notifications.DeletedObject
import io.github.xilinjia.krdb.notifications.InitialObject
import io.github.xilinjia.krdb.notifications.PendingObject
import io.github.xilinjia.krdb.notifications.SingleQueryChange
import io.github.xilinjia.krdb.notifications.UpdatedObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.Proxy
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "AppPrefs"

var appPrefsFlow: StateFlow<AppPrefs>? = null

var appAttribsFlow: StateFlow<AppAttribs>? = null

var syncPrefs: SyncPrefs = SyncPrefs()

var sleepPrefs: SleepPrefs = SleepPrefs()
    private set

var sleepPrefsJob: Job? = null

var syncPrefsJob: Job? = null

@OptIn(ExperimentalUuidApi::class)
fun initAppPrefs() {
    val initialAppPrefs = realm.query(AppPrefs::class).first().find() ?: upsertBlk(AppPrefs()) {}
    if (appPrefsFlow == null) appPrefsFlow = realm.query(AppPrefs::class).query("id == 0").asFlow().map { change -> change.list.firstOrNull()?: initialAppPrefs }
        .stateIn(scope = appIOScope, started = SharingStarted.Eagerly, initialValue = initialAppPrefs)

    var initialAttribs = realm.query(AppAttribs::class).first().find() ?: upsertBlk(AppAttribs()) {}
    if (initialAttribs.uniqueId.isEmpty()) initialAttribs = upsertBlk(initialAttribs) { it.uniqueId = Uuid.random().toString() }
    if (appAttribsFlow == null) appAttribsFlow = realm.query(AppAttribs::class).query("id == 0").asFlow().map { change -> change.list.firstOrNull()?: initialAttribs }
        .stateIn(scope = appIOScope, started = SharingStarted.Eagerly, initialValue = initialAttribs)

    if (sleepPrefsJob == null) {
        sleepPrefs = realm.query(SleepPrefs::class).query("id == 0").first().find() ?: upsertBlk(SleepPrefs()) {}
        sleepPrefsJob = CoroutineScope(Dispatchers.IO).launch {
            val flow = realm.query(SleepPrefs::class).query("id == 0").first().asFlow()
            flow.collect { changes: SingleQueryChange<SleepPrefs> ->
                Logd(TAG, "sleepPrefsJob flow.collect")
                when (changes) {
                    is UpdatedObject -> sleepPrefs = changes.obj
                    is InitialObject -> sleepPrefs = changes.obj
                    is DeletedObject -> {}
                    is PendingObject -> {}
                }
            }
        }
    }
    if (syncPrefsJob == null) {
        syncPrefs = realm.query(SyncPrefs::class).query("id == 0").first().find() ?: upsertBlk(SyncPrefs()) {}
        syncPrefsJob = CoroutineScope(Dispatchers.IO).launch {
            val flow = realm.query(SyncPrefs::class).query("id == 0").first().asFlow()
            flow.collect { changes: SingleQueryChange<SyncPrefs> ->
                Logd(TAG, "syncPrefsJob flow.collect")
                when (changes) {
                    is UpdatedObject -> syncPrefs = changes.obj
                    is InitialObject -> syncPrefs = changes.obj
                    is DeletedObject -> {}
                    is PendingObject -> {}
                }
            }
        }
    }
}

fun cancelAppPrefs() {
    sleepPrefsJob?.cancel()
    syncPrefsJob?.cancel()
}

const val EPISODE_CACHE_SIZE_UNLIMITED: Int = 0

var isSkipSilence: Boolean
    get() = appPrefsFlow!!.value.skipSilence
    set(value) {
        upsertBlk(appPrefsFlow!!.value) { it.skipSilence = value }
    }

var speedforwardSpeed: Float
    get() = appPrefsFlow!!.value.speedforwardSpeed
    set(speed) {
        upsertBlk(appPrefsFlow!!.value) { it.speedforwardSpeed = speed }
    }

var skipforwardSpeed: Float
    get() = appPrefsFlow!!.value.skipforwardSpeed
    set(speed) {
        upsertBlk(appPrefsFlow!!.value) { it.skipforwardSpeed = speed }
    }

var fallbackSpeed: Float
    get() = appPrefsFlow!!.value.fallbackSpeed
    set(speed) {
        upsertBlk(appPrefsFlow!!.value) { it.fallbackSpeed = speed }
    }

var fastForwardSecs: Int
    get() = appPrefsFlow!!.value.fastForwardSecs
    set(secs) {
        upsertBlk(appPrefsFlow!!.value) { it.fastForwardSecs = secs }
    }

var rewindSecs: Int
    get() = appPrefsFlow!!.value.rewindSecs
    set(secs) {
        upsertBlk(appPrefsFlow!!.value) { it.rewindSecs = secs }
    }

var streamingCacheSizeMB: Int
    get() = appPrefsFlow!!.value.streamingCacheSizeMB
    set(size) {
        val size_ = if (size < 10) 10 else size
        upsertBlk(appPrefsFlow!!.value) { it.streamingCacheSizeMB = size_ }
    }

var proxyConfig: ProxyConfig
    get() {
        val type = Proxy.Type.valueOf(appPrefsFlow!!.value.proxyType)
        val host = appPrefsFlow!!.value.proxyHost
        val port = appPrefsFlow!!.value.proxyPort
        val username = appPrefsFlow!!.value.proxyUser
        val password = appPrefsFlow!!.value.proxyPassword
        return ProxyConfig(type, host, port, username, password)
    }
    set(config) {
        upsertBlk(appPrefsFlow!!.value) {
            it.proxyType = config.type.name
            it.proxyHost = if (config.host.isNullOrEmpty()) null else config.host
            it.proxyPort = if (config.port !in 1..65535) 0 else config.port
            it.proxyUser = if (config.username.isNullOrEmpty()) null else config.username
            it.proxyPassword = if (config.password.isNullOrEmpty()) null else config.password
        }
    }

var prefStreamOverDownload: Boolean
    get() = appPrefsFlow!!.value.streamOverDownload
    set(stream) {
        upsertBlk(appPrefsFlow!!.value) { it.streamOverDownload = stream }
    }
