package ac.mdiq.podcini.sources

import ac.mdiq.podcini.PodciniApp.Companion.appIOScope
import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.net.searcher.PodcastSearcherRegistry.searcherInfos
import ac.mdiq.podcini.playback.forcePlaybackReset
import ac.mdiq.podcini.shared.EpisodeIPC
import ac.mdiq.podcini.shared.FeedSearchResult
import ac.mdiq.podcini.shared.FeedSearcher
import ac.mdiq.podcini.shared.MediaSearcher
import ac.mdiq.podcini.shared.PROVIDER_API_VERSION
import ac.mdiq.podcini.shared.ProviderAttrs
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.storage.specs.FeedType
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logt
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "GatewayClient"

const val EPISODE_BATCH_SIZE = 100

val sourceClients = mutableListOf<SourceGatewayClient>()

val typeClientMap = mutableMapOf<String, SourceGatewayClient>()

fun clientByFeed(feed: Feed): SourceGatewayClient? {
    if (feed.type.isNullOrBlank()) return null
    return typeClientMap[feed.type!!]
}

fun clientByEpisode(episode: Episode): SourceGatewayClient? {
    if (!episode.feedType.isNullOrBlank()) return typeClientMap[episode.feedType!!]
    if (!episode.feed?.type.isNullOrBlank()) return typeClientMap[episode.feed!!.type!!]
    val client = sourceClients.firstOrNull { it.withProviderBlocking { p-> p.canHandleUrl(episode.downloadUrl) == 1 } == true }
    return client
}

fun clientBySearcher(name: String?): SourceGatewayClient? {
    if (name.isNullOrBlank()) return null
    return sourceClients.firstOrNull { it.feedSearcher?.name == name }
}

fun isExtFeed(feed: Feed?): Boolean {
    if (feed?.type.isNullOrBlank()) return false
    return typeClientMap[feed.type!!] != null
}

fun clientsHaveMultiQ(): Boolean {
    for (client in sourceClients) if (client.attributes?.hasMultiQualities == true) return true
    return false
}

fun clientsHaveSeprateAVs(): Boolean {
    for (client in sourceClients) if (client.attributes?.hasSeparateAVs == true) return true
    return false
}

fun clientshaveViewCounts(): Boolean {
    for (client in sourceClients) if (client.attributes?.hasViewCount == true) return true
    return false
}
fun clientshaveLikeCounts(): Boolean {
    for (client in sourceClients) if (client.attributes?.hasLikeCount == true) return true
    return false
}

object AppGatewayRegistry {
    sealed interface GatewayState {
        object Initializing : GatewayState
        data class Ready(val clients: List<SourceGatewayClient>) : GatewayState
        data class Failed(val error: Throwable? = null) : GatewayState
    }

    private val _state = MutableStateFlow<GatewayState>(GatewayState.Initializing)
    val state: StateFlow<GatewayState> = _state.asStateFlow()

    @Volatile
    private var readyDeferred = CompletableDeferred<List<SourceGatewayClient>>()
    private val mutex = Mutex()
    private var isInitializing = false

    fun initialize(loadExternal: Boolean, scope: CoroutineScope) {
        scope.launch {
            Logd(TAG, "initialize loadExternal: $loadExternal")
            var currentDeferred: CompletableDeferred<List<SourceGatewayClient>>
            mutex.withLock {
                if (readyDeferred.isCompleted) readyDeferred.complete(emptyList())
                if (isInitializing) return@launch
                isInitializing = true
                _state.value = GatewayState.Initializing

                if (readyDeferred.isCompleted) readyDeferred = CompletableDeferred()
                currentDeferred = readyDeferred
            }
            try {
                sourceClients.forEach { it.disconnect() }
                sourceClients.clear()
                if (loadExternal) {
                    val cs = getSourceClients()
                    if (cs.isNotEmpty()) sourceClients.addAll(cs)
                }
                forcePlaybackReset = true
                if (sourceClients.isNotEmpty()) {
                    _state.value = GatewayState.Ready(sourceClients)
                    currentDeferred.complete(sourceClients)
                } else {
                    _state.value = GatewayState.Failed()
                    currentDeferred.complete(emptyList())
                }
            } catch (e: Exception) {
                _state.value = GatewayState.Failed(e)
                currentDeferred.complete(emptyList())
            } finally { mutex.withLock { isInitializing = false } }
        }
    }

    private fun PackageManager.queryIntentServicesCompat(intent: Intent, flags: Int): List<ResolveInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            queryIntentServices(intent, flags)
        }
    }

    private suspend fun getSourceClients(): List<SourceGatewayClient> {
        val context = getAppContext()
        searcherInfos.clear()
        val intent = Intent("ac.mdiq.podcini.action.PODCINI_GATEWAY")
        val resolveInfos = context.packageManager.queryIntentServicesCompat(intent, PackageManager.MATCH_ALL)
        if (resolveInfos.isEmpty()) {
            Loge(TAG, "No external source provider is available. Setting '${context.getString(R.string.pref_use_external_apps)}' is turned off")
            upsert(appPrefsFlow!!.value) { p-> p.loadExternalApp = false }
            return listOf()
        }

        val clients = mutableListOf<SourceGatewayClient>()

        suspend fun bindSingleClient(explicitIntent: Intent): SourceGatewayClient? = suspendCancellableCoroutine { continuation ->
            val client = SourceGatewayClient()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    try {
                        val remote = IPodciniGateway.Stub.asInterface(service)
                        val attr = remote.attributes
                        Logd(TAG, "onServiceConnected name: ${attr.name} type: ${attr.feedType} api: ${attr.apiVersion} $PROVIDER_API_VERSION")
                        searcherInfos.clear()
                        val recognized = attr.feedType in FeedType.entries.map { it.name }
                        val versionMatched = attr.apiVersion == PROVIDER_API_VERSION
                        if (recognized && versionMatched) {
                            client.attributes = attr
                            client.gateway = remote
                            client.connection = this
                            val aidlSearchProvider = client.gateway?.searchProvider
                            if (aidlSearchProvider != null) client.feedSearcher = GatewaySearcherAdapter(aidlSearchProvider)
                            val aidlMediaSearcher = client.gateway?.mediaSearcher
                            if (aidlMediaSearcher != null) client.mediaSearcher = GatewayMediaSearcherAdapter(aidlMediaSearcher)

                            typeClientMap[attr.feedType] = client
                            Logt(TAG, "External service ${attr.name} connected")
                        } else {
                            if (recognized) Loge(TAG, "External service ${attr.name} is not a compatible version, rejected.")
                            else Loge(TAG, "External service ${attr.name} not qualified, rejected.")
                            clients.remove(client)
                        }
                    } catch (e: Exception) {
                        Loge(TAG, e, "External service bind error")
                        clients.remove(client)
                    }
                    if (continuation.isActive) continuation.resumeWith(Result.success(client))
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Logt(TAG, "Service ${client.attributes?.name} disconnected")
                    searcherInfos.clear()
                    appIOScope.launch { client.disconnect() }
                    clients.remove(client)
                }
                override fun onBindingDied(name: ComponentName?) {
                    Logt(TAG, "${client.attributes?.name} binding died, trying to rebind service")
                    searcherInfos.clear()
                    appIOScope.launch { client.disconnect() }
                    clients.remove(client)
                    if (continuation.isActive) continuation.resumeWith(Result.success(null))
                }
                override fun onNullBinding(name: ComponentName?) {
                    Logt(TAG, "Service ${client.attributes?.name} not bond: null binding, trying to rebind")
                    searcherInfos.clear()
                    appIOScope.launch { client.disconnect() }
                    clients.remove(client)
                    if (continuation.isActive) continuation.resumeWith(Result.success(null))
                }
            }
            Logd(TAG, "bindSingleClient before bind")
            val success = try {
                context.bindService(explicitIntent, connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
            } catch (e: Exception) {
                Loge(TAG, e, "Failed to bind external service")
                false
            }
            Logd(TAG, "bindSingleClient after bind")

            if (!success && continuation.isActive) continuation.resumeWith(Result.success(null))

            continuation.invokeOnCancellation { runCatching { context.unbindService(connection) } }
        }

        for (resolveInfo in resolveInfos) {
            val serviceInfo = resolveInfo.serviceInfo
            Logd(TAG, "getSourceClients exported=${serviceInfo.exported}")
            Logd(TAG, "getSourceClients permission=${serviceInfo.permission}")
            Logd(TAG, "getSourceClients Targeting Package: ${serviceInfo.packageName}")
            Logd(TAG, "getSourceClients Targeting Class: ${serviceInfo.name}")
            val explicitIntent = Intent("ac.mdiq.podcini.action.PODCINI_GATEWAY").apply { component = ComponentName(serviceInfo.packageName, serviceInfo.name) }

            Logd(TAG, "getSourceClients before bindSingleClient")
            var client = bindSingleClient(explicitIntent)
            Logd(TAG, "getSourceClients after bindSingleClient")
            if (client == null) client = bindSingleClient(explicitIntent)
            if (client != null) clients.add(client)
        }
        return clients
    }

    suspend fun awaitReadyClients(): List<SourceGatewayClient> {
        return withTimeoutOrNull(10000.milliseconds) { readyDeferred.await() } ?: listOf()
    }

    fun getClientsOrNull(): List<SourceGatewayClient>? {
        return (state.value as? GatewayState.Ready)?.clients
    }

    private fun queryGatewayServices(context: Context): List<ResolveInfo> {
        val intent = Intent("ac.mdiq.podcini.action.PODCINI_GATEWAY")
        return context.packageManager.queryIntentServices(intent, 0)
    }
}

class SourceGatewayClient() {
    private val mutex = Mutex()

    var attributes: ProviderAttrs? = null

    var feedSearcher: FeedSearcher? = null

    var mediaSearcher: MediaSearcher? = null

    @Volatile
    var gateway: IPodciniGateway? = null

    @Volatile
    var connection: ServiceConnection? = null

    @Volatile
    private var bindDeferred: CompletableDeferred<IPodciniGateway>? = null

    suspend fun <T> execute(block: suspend (IPodciniGateway) -> T): T? {
        if (gateway == null) return null
        return block(gateway!!)
    }

    fun <T> executeBlocking(block: (IPodciniGateway) -> T): T? {
//        Logd(TAG, "executeBlocking")
        return runBlocking(Dispatchers.IO) {
            if (gateway == null) return@runBlocking null
            withContext(Dispatchers.IO) { block(gateway!!) }
        }
    }

    suspend fun <T> withProvider(block: suspend (Provider) -> T): T? {
        return execute { gateway ->
            val provider = gateway.provider ?: throw IllegalStateException("Extension does not provide Provider support")
            block(provider)
        }
    }

    fun <T> withProviderBlocking(block: (Provider) -> T): T? {
//        Logs(TAG, "withProviderBlocking")
        return executeBlocking { gateway ->
            val provider = gateway.provider ?: throw IllegalStateException("Extension does not provide Provider support")
            block(provider)
        }
    }

    suspend fun disconnect() {
        mutex.withLock {
            if (gateway != null) Logt(TAG, "Disconnecting ${gateway!!.attributes?.name}")
            connection?.let { try { getAppContext().unbindService(it) } catch (_: Exception) { } }
            connection = null
            attributes?.apply { typeClientMap.remove(feedType) }
            attributes = null
            gateway = null
            feedSearcher = null
            mediaSearcher = null
            bindDeferred = null
        }
    }
}

class GatewaySearcherAdapter(private val aidlProvider: IFeedSearchProvider) : FeedSearcher {
    override val name: String?
        get() = try { aidlProvider.name } catch (e: RemoteException) { null }

    override fun urlNeedsLookup(url: String): Boolean {
        return try { aidlProvider.urlNeedsLookup(url) } catch (e: RemoteException) { false }
    }
    override suspend fun search(query: String): List<FeedSearchResult> = withContext(Dispatchers.IO) {
        try { aidlProvider.search(query) ?: emptyList() } catch (e: RemoteException) { emptyList() }
    }
    override suspend fun lookupUrl(url: String): String = withContext(Dispatchers.IO) {
        try { aidlProvider.lookupUrl(url) ?: url } catch (e: RemoteException) { url }
    }
}

class GatewayMediaSearcherAdapter(private val aidlProvider: IMediaSearchProvider) : MediaSearcher {
    override val name: String
        get() = aidlProvider.name

    override suspend fun searchQuick(query: String): List<EpisodeIPC> = withContext(Dispatchers.IO) {
        try { aidlProvider.searchQuick(query) ?: emptyList() } catch (e: RemoteException) { emptyList() }
    }
    override suspend fun search(query: String, limit: Int): List<EpisodeIPC> = withContext(Dispatchers.IO) {
        try { aidlProvider.search(query, limit) ?: emptyList() } catch (e: RemoteException) { emptyList() }
    }
    override suspend fun getMoreItems(): List<EpisodeIPC> = withContext(Dispatchers.IO) {
        try { aidlProvider.getMoreItems() ?: emptyList() } catch (e: RemoteException) { emptyList() }
    }
}