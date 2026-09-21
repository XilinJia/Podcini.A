package ac.mdiq.podcini.config

import ac.mdiq.podcini.sourcing.ssl.SslProviderInstaller
import ac.mdiq.podcini.utils.NetworkUtils.networkChangedDetected
import ac.mdiq.podcini.utils.NetworkUtils.networkMonitor
import ac.mdiq.podcini.playback.releaseAController
import ac.mdiq.podcini.shared.PodciniHttpClient.configProxy
import ac.mdiq.podcini.sourcing.AppGatewayRegistry
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.cancelAppPrefs
import ac.mdiq.podcini.storage.database.cancelMonitorFeeds
import ac.mdiq.podcini.storage.database.cancelQueuesJob
import ac.mdiq.podcini.storage.database.getRealmInstance
import ac.mdiq.podcini.storage.database.initAppPrefs
import ac.mdiq.podcini.storage.database.initQueues
import ac.mdiq.podcini.storage.database.monitorFeeds
import ac.mdiq.podcini.storage.database.proxyConfig
import ac.mdiq.podcini.storage.model.cancelMonitorVolumes
import ac.mdiq.podcini.storage.model.monitorVolumes
import ac.mdiq.podcini.storage.utils.setupStorage
import ac.mdiq.podcini.utils.timeIt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch


object AppConfig {

    val isInitialized =  MutableStateFlow(false)
    private var initializing = false

    var nmJob: Job? = null

    private val initLock = Any()

    @Synchronized
    fun initialize() {
        synchronized(initLock) {
            if (isInitialized.value || initializing) return
            initializing = true
        }

//        if (isInitialized.value) {
//            if (appPrefsFlow?.value?.loadExternalApp == true && sourceClients.isEmpty())
//                AppGatewayRegistry.initialize(appPrefsFlow!!.value.loadExternalApp, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
//            return
//        }
        try {
            getRealmInstance()
            initAppPrefs()
            AppGatewayRegistry.initialize(appPrefsFlow!!.value.loadExternalApp, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

            if (nmJob == null) nmJob = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { networkMonitor.networkFlow.collect { isConnected -> networkChangedDetected(isConnected) } }

            setupStorage()

            timeIt("ClientConfigurator Init started ")

            monitorFeeds()
            monitorVolumes()
            initQueues()

            SslProviderInstaller.install()
            configProxy(proxyConfig)
            createNotificationChannels()

            timeIt("ClientConfigurator Init ends ")

            isInitialized.value = true
        } finally { synchronized(initLock) { initializing = false } }
    }

    fun destroy() {
        nmJob?.cancel()
        nmJob = null
        releaseAController()
        cancelQueuesJob()
        cancelMonitorFeeds()
        cancelMonitorVolumes()
        cancelAppPrefs()
    }
}
