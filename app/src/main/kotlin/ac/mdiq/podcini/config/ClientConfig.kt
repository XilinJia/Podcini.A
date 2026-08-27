package ac.mdiq.podcini.config

import ac.mdiq.podcini.net.ssl.SslProviderInstaller
import ac.mdiq.podcini.net.utils.NetworkUtils.networkChangedDetected
import ac.mdiq.podcini.net.utils.NetworkUtils.networkMonitor
import ac.mdiq.podcini.playback.base.releaseAController
import ac.mdiq.podcini.shared.PodciniHttpClient.configProxy
import ac.mdiq.podcini.sources.AppGatewayRegistry
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
import ac.mdiq.podcini.storage.utils.initStorage
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.timeIt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


object ClientConfig {
    private var initialized = false
    var nmJob: Job? = null

    @Synchronized
    fun initialize() {
        if (initialized) return

        getRealmInstance()
        initAppPrefs()
        AppGatewayRegistry.initialize(appPrefsFlow!!.value.loadExternalApp, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

        if (nmJob == null) nmJob = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { networkMonitor.networkFlow.collect { isConnected -> networkChangedDetected(isConnected) } }

        initStorage()

        Logd("ClientConfigurator", "initialize")
        timeIt("ClientConfigurator Init started ")

        monitorFeeds()
        monitorVolumes()
        initQueues()

        SslProviderInstaller.install()
        configProxy(proxyConfig)
        createNotificationChannels()

        timeIt("ClientConfigurator Init ends ")
        initialized = true
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
