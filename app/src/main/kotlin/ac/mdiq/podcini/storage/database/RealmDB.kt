package ac.mdiq.podcini.storage.database

import ac.mdiq.podcini.BuildConfig
import ac.mdiq.podcini.PodciniApp.Companion.appIOScope
import ac.mdiq.podcini.storage.model.AppAttribs
import ac.mdiq.podcini.storage.model.AppPrefs
import ac.mdiq.podcini.storage.model.AutoDLEQ
import ac.mdiq.podcini.storage.model.Chapter
import ac.mdiq.podcini.storage.model.CurrentState
import ac.mdiq.podcini.storage.model.DownloadResult
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.FacetsPrefs
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.storage.model.PAFeed
import ac.mdiq.podcini.storage.model.PlayQueue
import ac.mdiq.podcini.storage.model.QueueEntry
import ac.mdiq.podcini.storage.model.ShareLog
import ac.mdiq.podcini.storage.model.SleepPrefs
import ac.mdiq.podcini.storage.model.SubscriptionLog
import ac.mdiq.podcini.storage.model.SubscriptionsPrefs
import ac.mdiq.podcini.storage.model.SyncPrefs
import ac.mdiq.podcini.storage.model.Timer
import ac.mdiq.podcini.storage.model.Todo
import ac.mdiq.podcini.storage.model.Volume
import ac.mdiq.podcini.storage.specs.FeedType
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Logs
import android.util.Log
import io.github.xilinjia.krdb.MutableRealm
import io.github.xilinjia.krdb.Realm
import io.github.xilinjia.krdb.RealmConfiguration
import io.github.xilinjia.krdb.UpdatePolicy
import io.github.xilinjia.krdb.dynamic.getNullableValue
import io.github.xilinjia.krdb.dynamic.getValue
import io.github.xilinjia.krdb.ext.isManaged
import io.github.xilinjia.krdb.ext.realmListOf
import io.github.xilinjia.krdb.notifications.InitialObject
import io.github.xilinjia.krdb.notifications.SingleQueryChange
import io.github.xilinjia.krdb.notifications.UpdatedObject
import io.github.xilinjia.krdb.types.EmbeddedRealmObject
import io.github.xilinjia.krdb.types.RealmObject
import io.github.xilinjia.krdb.types.TypedRealmObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor

private const val TAG: String = "RealmDB"

val config: RealmConfiguration by lazy {
    RealmConfiguration.Builder(schema = setOf(
        Volume::class,
        Feed::class,
        AutoDLEQ::class,
        Episode::class,
        CurrentState::class,
        PlayQueue::class,
        QueueEntry::class,
        DownloadResult::class,
        ShareLog::class,
        SubscriptionLog::class,
        Chapter::class,
        Todo::class,
        Timer::class,
        PAFeed::class,
        AppAttribs::class,
        AppPrefs::class,
        SubscriptionsPrefs::class,
        FacetsPrefs::class,
        SleepPrefs::class,
        SyncPrefs::class,
    )).name("Podcini.realm").schemaVersion(158)
        .migration({ mContext ->
            val oldRealm = mContext.oldRealm // old realm using the previous schema
            val newRealm = mContext.newRealm // new realm using the new schema
            if (oldRealm.schemaVersion() < 150) {
                Log.d(TAG, "migrating DB from below 150")
                var feeds = newRealm.query("Feed").find().toList()
                for (f in feeds) {
                    val type = f.getNullableValue<String>("type")
                    if (type in listOf(FeedType.RSS.name, FeedType.ATOM.name)) f.set("episodesDownloadable", true)
                }
            }
            if (oldRealm.schemaVersion() < 157) {
                Log.d(TAG, "migrating DB from below 157")
                var feeds = oldRealm.query("Feed").find().toList()
                for (f in feeds) {
                    val id = f.getValue<Long>("id")
                    Log.d(TAG, "migrating feed: $id")
                    val fNew = newRealm.query("Feed", "id == $id").first().find()
                    if (fNew != null) {
                        val dleq = AutoDLEQ()
                        if (dleq != null) {
                            val filterStringADL = f.getValue<String>("filterStringADL")
                            dleq.filterStringADL = filterStringADL
                            val durationFloorADL = f.getValue<Long>("durationFloorADL")
                            dleq.durationFloorADL = durationFloorADL.toInt()
                            val durationCeilingADL = f.getValue<Long>("durationCeilingADL")
                            dleq.durationCeilingADL = durationCeilingADL.toInt()
                            val sortOrderCodeADL = f.getValue<Long>("sortOrderCodeADL")
                            dleq.sortOrderCodeADL = sortOrderCodeADL.toInt()
                            val autoDLInclude = f.getNullableValue<String>("autoDLInclude")
                            dleq.autoDLInclude = autoDLInclude
                            val autoDLExclude = f.getNullableValue<String>("autoDLExclude")
                            dleq.autoDLExclude = autoDLExclude
                            val autoDLMinDuration = f.getValue<Long>("autoDLMinDuration")
                            dleq.autoDLMinDuration = autoDLMinDuration.toInt()
                            val autoDLMaxDuration = f.getValue<Long>("autoDLMinDuration")
                            dleq.autoDLMaxDuration = autoDLMaxDuration.toInt()
                            val markExcludedPlayed = f.getValue<Boolean>("markExcludedPlayed")
                            dleq.markExcludedPlayed = markExcludedPlayed
                            val autoDLPolicyCode = f.getValue<Long>("autoDLPolicyCode")
                            dleq.autoDLPolicyCode = autoDLPolicyCode.toInt()
                            val autoDLPolicyReplace = f.getValue<Boolean>("autoDLPolicyReplace")
                            dleq.autoDLPolicyReplace = autoDLPolicyReplace
                            val dleqs = realmListOf(dleq)
                            fNew.set("autoDLEQs", dleqs)
                        } else Log.d(TAG, "dleq is null")
                    } else Log.d(TAG, "fNew is null")
                }
            }
        }).build()
}

lateinit var realm: Realm
    private set

fun getRealmInstance() {
    if (::realm.isInitialized) return
    realm = Realm.open(config)
}

fun <T : TypedRealmObject> unmanaged(entity: T) : T {
    if (BuildConfig.DEBUG) {
        val stackTrace = Thread.currentThread().stackTrace
        val caller = if (stackTrace.size > 3) stackTrace[3] else null
        Logd(TAG, "${caller?.className}.${caller?.methodName} unmanaged: ${entity.javaClass.simpleName}")
    }
    return if (entity.isManaged()) realm.copyFromRealm(entity) else entity
}

suspend fun <T : TypedRealmObject> update(entity: T, block: MutableRealm.(T) -> Unit) : T {
    return realm.write {
        val result: T = findLatest(entity)?.let {
            block(it)
            it
        } ?: entity
        result
    }
}

suspend fun <T : RealmObject> upsert(entity: T, block: MutableRealm.(T) -> Unit) : T {
//    stackTraceShort()
    return realm.write {
        var result: T = entity
        if (entity.isManaged()) {
            result = findLatest(entity)?.let {
                block(it)
                it
            } ?: entity
        } else {
            try {
                result = copyToRealm(entity, UpdatePolicy.ALL).let {
                    block(it)
                    it
                }
            } catch (e: Exception) {
                Logs(TAG, e, "copyToRealm error")
//                showStackTrace()
            }
        }
        result
    }
}

fun <T : RealmObject> upsertBlk(entity: T, block: MutableRealm.(T) -> Unit) : T {
//    if (BuildConfig.DEBUG) {
//        val stackTrace = Thread.currentThread().stackTrace
//        val caller = if (stackTrace.size > 3) stackTrace[3] else null
//        Logd(TAG, "${caller?.className}.${caller?.methodName} upsertBlk: ${entity.javaClass.simpleName}")
//    }
//    stackTraceShort()
    return realm.writeBlocking {
        var result: T = entity
        if (entity.isManaged()) {
            result = findLatest(entity)?.let {
                block(it)
                it
            } ?: entity
        } else {
            try {
                result = copyToRealm(entity, UpdatePolicy.ALL).let {
                    block(it)
                    it
                }
            } catch (e: Exception) {
                Logs(TAG, e, "copyToRealm error")
//                showStackTrace()
            }
        }
        result
    }
}

fun <T : EmbeddedRealmObject> upsertBlkEmb(entity: T, block: MutableRealm.(T) -> Unit) : T {
//    stackTraceShort()
    return realm.writeBlocking {
        val result: T = findLatest(entity)?.let {
                block(it)
                it
            } ?: entity
        result
    }
}


fun runOnIOScope(block: suspend () -> Unit) : Job {
    return appIOScope.launch {
        if (Dispatchers.IO == coroutineContext[ContinuationInterceptor]) block()
        else withContext(Dispatchers.IO) { block() }
    }
}

private val subscriptionMutex = Mutex()

data class MonitorEntity(
    val tag: String,
    val onChanges: suspend (Episode, fields: Array<String>)->Unit,
    val onInit: (suspend (Episode)->Unit)? = null)

class EpisodeMonitors {
    var job: Job? = null
    val entities: MutableSet<MonitorEntity> = mutableSetOf()
}

private val idMonitorsMap: MutableMap<Long, EpisodeMonitors> = mutableMapOf()

private fun episodeMonitor(episode: Episode): Job {
    return CoroutineScope(Dispatchers.IO).launch {
        val item_ = realm.query(Episode::class).query("id == ${episode.id}").first()
        Logd(TAG, "start monitoring episode: ${episode.id} ${episode.title}")
        item_.asFlow().collect { changes: SingleQueryChange<Episode> ->
            //                Logd(TAG, "episodeMonitor in collect subscriptionLock: $subscriptionLock")
            subscriptionMutex.withLock {
                val ms = idMonitorsMap[episode.id] ?: return@collect
                when (changes) {
                    is UpdatedObject -> {
                        Logd(TAG, "episodeMonitor UpdatedObject ${changes.obj.title} ${changes.changedFields.joinToString()}")
                        for (e in ms.entities) {
                            if (episode.id == changes.obj.id) {
                                Logd(TAG, "episodeMonitor onChange callback for ${e.tag} ${episode.title}")
                                e.onChanges(changes.obj, changes.changedFields)
                            }
                        }
                    }
                    is InitialObject -> {
                        Logd(TAG, "episodeMonitor InitialObject ${changes.obj.title}")
                        for (e in ms.entities) {
                            if (episode.id == changes.obj.id) {
                                Logd(TAG, "episodeMonitor onChange callback for ${e.tag} ${episode.title}")
                                e.onInit?.invoke(changes.obj)
                            }
                        }
                    }
                    else -> Logd(TAG, "episodeMonitor other changes: $changes")
                }
            }
        }
    }
}

fun hasSubscribed(episode: Episode, tag: String): Boolean {
    val ms = idMonitorsMap[episode.id] ?: return false
    return ms.entities.firstOrNull { it.tag == tag } != null
}

suspend fun subscribeEpisode(episode: Episode, entity: MonitorEntity) {
    subscriptionMutex.withLock {
        var ms = idMonitorsMap[episode.id]
        if (ms == null) {
            ms = EpisodeMonitors()
            ms.entities.add(entity)
            ms.job = episodeMonitor(episode)
            idMonitorsMap[episode.id] = ms
        } else {
            ms.entities.removeIf { it.tag == entity.tag }
            ms.entities.add(entity)
        }
        Logd(TAG, "subscribeEpisode ${entity.tag} ${episode.id} ${episode.title}")
        Logd(TAG, "subscribeEpisode idMonitorsMap: ${idMonitorsMap.size}")
        for ((k, v) in idMonitorsMap.entries.toList()) for (e in v.entities) Logd(TAG, "subscribeEpisode idMonitorsMap $k tag: ${e.tag} job: ${v.job != null}")
    }
}

fun unsubscribeEpisode(episode: Episode, tag: String) {
    runOnIOScope {
        subscriptionMutex.withLock {
            val ms = idMonitorsMap[episode.id]
            if (ms != null) {
                try {
                    ms.entities.removeIf { it.tag == tag }
                    if (ms.entities.isEmpty()) {
                        ms.job?.cancel()
                        idMonitorsMap.remove(episode.id)
                    }
                } catch (e: Throwable) { Logs(TAG, e, "unsubscribe episode failed $tag ${episode.title}") }
            }
            eraseIfLoose(episode)
            Logd(TAG, "unsubscribeEpisode $tag ${episode.id} ${episode.title}")
            Logd(TAG, "unsubscribeEpisode idMonitorsMap: ${idMonitorsMap.size}")
            for ((k, v) in idMonitorsMap.entries.toList()) for (e in v.entities) Logd(TAG, "unsubscribeEpisode idMonitorsMap $k tag: ${e.tag} job: ${v.job != null}")
        }
    }
}
