package ac.mdiq.podcini.activity

import ac.mdiq.podcini.BuildConfig
import ac.mdiq.podcini.R
import ac.mdiq.podcini.sourcing.feed.FeedUpdateManager
import ac.mdiq.podcini.sourcing.feed.FeedUpdateManager.runOnceOrAsk
import ac.mdiq.podcini.sourcing.feed.FeedUpdateManager.scheduleUpdateTaskOnce
import ac.mdiq.podcini.sync.SyncService
import ac.mdiq.podcini.sync.queue.SynchronizationQueueSink
import ac.mdiq.podcini.playback.base.TTSEngine.closeTTS
import ac.mdiq.podcini.playback.cast.BaseActivity
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.sourcing.AppGatewayRegistry
import ac.mdiq.podcini.sourcing.sourceClients
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.realm
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.specs.EpisodeState
import ac.mdiq.podcini.storage.utils.autoBackup
import ac.mdiq.podcini.ui.compose.CommonConfirmAttrib
import ac.mdiq.podcini.ui.compose.PodciniTheme
import ac.mdiq.podcini.ui.compose.commonConfirms
import ac.mdiq.podcini.ui.screens.Facets
import ac.mdiq.podcini.ui.screens.FeedDetails
import ac.mdiq.podcini.ui.screens.FindFeeds
import ac.mdiq.podcini.ui.screens.Library
import ac.mdiq.podcini.ui.screens.MainScreen
import ac.mdiq.podcini.ui.screens.OnlineFeed
import ac.mdiq.podcini.ui.screens.PSState
import ac.mdiq.podcini.ui.screens.Queues
import ac.mdiq.podcini.ui.screens.QuickAccess
import ac.mdiq.podcini.ui.screens.Search
import ac.mdiq.podcini.ui.screens.Statistics
import ac.mdiq.podcini.ui.screens.navTo
import ac.mdiq.podcini.ui.screens.psState
import ac.mdiq.podcini.ui.screens.searchFeedsOnline
import ac.mdiq.podcini.ui.screens.setSearchTerms
import ac.mdiq.podcini.utils.CrashReportWriter.Companion.crashLogFile
import ac.mdiq.podcini.utils.EventFlow
import ac.mdiq.podcini.utils.FlowEvent
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Logt
import ac.mdiq.podcini.utils.timeIt
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.StrictMode
import android.provider.Settings
import android.view.View
import android.view.Window
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : BaseActivity() {
    private var intentState by mutableStateOf<Intent?>(null)

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        Logt(TAG, getString(R.string.notification_permission_text))
        if (isGranted) {
            checkAndRequestUnrestrictedBackgroundActivity()
            return@registerForActivityResult
        }
        commonConfirms.add(CommonConfirmAttrib(
            title = getString(R.string.notification_check_permission),
            message = getString(R.string.notification_permission_text),
            confirmRes = android.R.string.ok,
            cancelRes = R.string.cancel_label,
            onConfirm = { checkAndRequestUnrestrictedBackgroundActivity() },
            onCancel = { checkAndRequestUnrestrictedBackgroundActivity() }))
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun postForNotificationPermission() {
        commonConfirms.add(CommonConfirmAttrib(
            title = getString(R.string.notification_check_permission),
            message = getString(R.string.notification_permission_text),
            confirmRes = android.R.string.ok,
            cancelRes = R.string.cancel_label,
            onConfirm = { requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            onCancel = { checkAndRequestUnrestrictedBackgroundActivity() }))
    }

    private var showUnrestrictedBackgroundPermissionDialog by mutableStateOf(false)

    private var hasFeedUpdateObserverStarted = false

    private var hasInitialized = mutableStateOf(false)


    public override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        window.requestFeature(Window.FEATURE_ACTION_MODE_OVERLAY)
        enableEdgeToEdge(window)

        if (BuildConfig.DEBUG) {
            val builder = StrictMode.ThreadPolicy.Builder()
                .detectAll()  // Enable all detections
                .penaltyLog()  // Log violations to the console
                .penaltyDropBox()
            StrictMode.setThreadPolicy(builder.build())
        }

        super.onCreate(savedInstanceState)
        handleNavIntent()

        timeIt("$TAG after handleNavIntent")
        intentState = intent

//        if (savedInstanceState != null) hasInitialized.value = savedInstanceState.getBoolean(INIT_KEY, false)
//        if (!hasInitialized.value) hasInitialized.value = true

        title = "Podcini.MainActivity"

        setContent { PodciniTheme { intentState?.let {
            if (showUnrestrictedBackgroundPermissionDialog) UnrestrictedBackgroundPermissionDialog { showUnrestrictedBackgroundPermissionDialog = false }
            MainScreen()
        } } }

        timeIt("$TAG after setContent")

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) postForNotificationPermission()
        else checkAndRequestUnrestrictedBackgroundActivity()

        if (savedInstanceState == null) {
            timeIt("$TAG after checking permission")
            val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
            if (currentVersion != appPrefsFlow!!.value.lastVersion) {
                runOnIOScope {
                    upsert(appPrefsFlow!!.value) { it.lastVersion = currentVersion }
                    crashLogFile.delete()
                }
            }

            SynchronizationQueueSink.setServiceStarterImpl { SyncService.sync() }
            scheduleUpdateTaskOnce(replace = false)
        }

        runOnIOScope { SynchronizationQueueSink.syncNowIfNotSyncedRecently() }

        WorkManager.getInstance(this).getWorkInfosByTagLiveData(FeedUpdateManager.WORK_TAG_FEED_UPDATE).observe(this) { workInfos: List<WorkInfo> ->
            if (!hasFeedUpdateObserverStarted) {
                hasFeedUpdateObserverStarted = true
                return@observe
            }
            var isRefreshingFeeds = false
            for (workInfo in workInfos) {
                when (workInfo.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> isRefreshingFeeds = true
                    else -> {}
                }
            }
            EventFlow.postStickyEvent(FlowEvent.FeedUpdatingEvent(isRefreshingFeeds))
        }

        timeIt("$TAG end of onCreate")
    }

    @Composable
    fun UnrestrictedBackgroundPermissionDialog(onDismiss: () -> Unit) {
        var dontAskAgain by remember { mutableStateOf(false) }
        AlertDialog(modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.extraLarge), onDismissRequest = onDismiss, title = { Text("Permission Required") },
            text = {
                Column {
                    Text(stringResource(R.string.unrestricted_background_permission_text))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dontAskAgain, onCheckedChange = { dontAskAgain = it })
                        Text(stringResource(R.string.checkbox_do_not_show_again))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dontAskAgain) upsertBlk(appPrefsFlow!!.value) { it.dont_ask_again_unrestricted_background = true }
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = "package:$packageName".toUri() }
                    this@MainActivity.startActivity(intent)
                    onDismiss()
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_label)) } }
        )
    }

    private fun checkAndRequestUnrestrictedBackgroundActivity() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(packageName)
        val dontAskAgain = appPrefsFlow!!.value.dont_ask_again_unrestricted_background
        if (!isIgnoringBatteryOptimizations && !dontAskAgain) showUnrestrictedBackgroundPermissionDialog = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(Extras.generated_view_id.name, View.generateViewId())
        outState.putBoolean(INIT_KEY, hasInitialized.value)
    }

    override fun onDestroy() {
        Logd(TAG, "onDestroy")
        WorkManager.getInstance(this).pruneWork()
        WorkManager.getInstance(applicationContext).pruneWork()
        closeTTS()
        super.onDestroy()
    }

    public override fun onStart() {
        super.onStart()
        procFlowEvents()
        timeIt("$TAG end of onStart")
    }

    override fun onStop() {
        super.onStop()
        cancelFlowEvents()
    }

    private var firstStart = true
    override fun onResume() {
        super.onResume()
        autoBackup()
        if (!firstStart && appPrefsFlow?.value?.loadExternalApp == true && sourceClients.isEmpty()) {
            commonConfirms.add(CommonConfirmAttrib(title = getString(R.string.reconnect_external_apps), message = getString(R.string.reconnect_external_apps_sum),
                confirmRes = R.string.reconnect, cancelRes = R.string.setting_off,
                onConfirm = { AppGatewayRegistry.initialize(true, CoroutineScope(Dispatchers.Default)) },
                onCancel = {
                    upsertBlk(appPrefsFlow!!.value) { p-> p.loadExternalApp = false}
                    Logt(TAG, getString(R.string.pref_use_external_apps) + " " + getString(R.string.setting_off))
                }
            ))
        }
        firstStart = false
        val curTime = nowInMillis()
        Logd(TAG, "onResume curTime: $curTime postRepeatsTime: ${appPrefsFlow!!.value.postRepeatsTime}")
        if ((curTime - appPrefsFlow!!.value.postRepeatsTime) > 3600000L * 24)
            runOnIOScope {
                val count = realm.query(Episode::class).query("playState == ${EpisodeState.AGAIN.code} OR playState == ${EpisodeState.FOREVER.code}").query("repeatTime <= $curTime").count().find()
                upsert(appPrefsFlow!!.value) { it.postRepeatsTime = curTime }
                if (count > 0) withContext(Dispatchers.Main) {
                    commonConfirms.add(CommonConfirmAttrib(title = getString(R.string.repeats_past_due), message = getString(R.string.repeats_past_due_sum, count), confirmRes = R.string.OK, cancelRes = R.string.no,
                        onConfirm = { navTo(Facets(modeName = QuickAccess.Due.name)) }))
                }
            }

        timeIt("$TAG end of onResume")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
//        lastTheme = getNoTitleTheme(this) // Don't recreate activity when a result is pending
    }

    private var eventSink: Job?     = null
    private var eventStickySink: Job? = null
    private fun cancelFlowEvents() {
        eventSink?.cancel()
        eventSink = null
        eventStickySink?.cancel()
        eventStickySink = null
    }
    private fun procFlowEvents() {
        if (eventSink == null) eventSink = lifecycleScope.launch {
            EventFlow.events.collectLatest { event ->
                Logd(TAG, "Received event: ${event.TAG}")
                when (event) {
                    is FlowEvent.MessageEvent -> {
                        if (event.action != null)
                            commonConfirms.add(CommonConfirmAttrib(
                                title = event.message,
                                message = event.actionText ?: "",
                                confirmRes = R.string.confirm_label,
                                cancelRes = R.string.no,
                                onConfirm = { event.action(this@MainActivity) }))
                        else Logt(TAG, event.message)
                    }
                    else -> {}
                }
            }
        }
        if (eventStickySink == null) eventStickySink = lifecycleScope.launch {
            EventFlow.stickyEvents.collectLatest { event -> Logd(TAG, "Received sticky event: ${event.TAG}") }
        }
    }

    private fun handleNavIntent() {
        Logd(TAG, "handleNavIntent()")
        when {
            intent.hasExtra(Extras.feed_id.name) -> {
                val feedId = intent.getLongExtra(Extras.feed_id.name, 0)
                Logd(TAG, "handleNavIntent: feedId: $feedId")
                if (feedId > 0) navTo(FeedDetails(feedId = feedId))
                psState = PSState.PartiallyExpanded
            }
            intent.hasExtra(Extras.queue_id.name) -> {
                val queueId = intent.getLongExtra(Extras.queue_id.name, 0)
                Logd(TAG, "handleNavIntent: queueId: $queueId")
                if (queueId >= 0) navTo(Queues(id = queueId))
                psState = PSState.PartiallyExpanded
            }
            intent.hasExtra(Extras.facet_name.name) -> {
                val facetName = intent.getStringExtra(Extras.facet_name.name)
                Logd(TAG, "handleNavIntent: facetName: $facetName")
                if (!facetName.isNullOrEmpty()) QuickAccess.entries.find { it.name == facetName }?.let { navTo(Facets(modeName = it.name)) }
                psState = PSState.PartiallyExpanded
            }
            intent.hasExtra(Extras.feed_url.name) -> {
                val feedurl = intent.getStringExtra(Extras.feed_url.name)
                val isShared = intent.getBooleanExtra(Extras.isShared.name, false)
                val source = intent.getStringExtra(Extras.source.name) ?: ""
                Logd(TAG, "handleNavIntent feedurl: $feedurl")
                if (!feedurl.isNullOrBlank()) navTo(OnlineFeed(url = feedurl, source = source, shared = isShared))
            }
            intent.hasExtra(Extras.search_string.name) -> {
                searchFeedsOnline(query = intent.getStringExtra(Extras.search_string.name))
                navTo(FindFeeds)
            }
            intent.getBooleanExtra(Extras.open_player.name, false) -> psState = PSState.Expanded
            intent.hasExtra("shortcut_route") -> {
                val route = intent.getStringExtra("shortcut_route")
                Logd(TAG, "intent.hasExtra(shortcut_route) route $route")
                val screen = when (route) {
                    "Queues" -> Queues()
                    "Facets" -> Facets()
                    "library" -> Library
                    "FindFeeds" -> FindFeeds
                    "Statistics" -> Statistics
                    else -> Library
                }
                navTo(screen)
            }
            else -> {
                // deeplink
                val uri = intent.data
                if (uri?.path == null) return
                Logd(TAG, "Handling deeplink: $uri")
                when (uri.path) {
                    "/deeplink/search" -> {
                        val query = uri.getQueryParameter("query") ?: return
                        setSearchTerms(query)
                        navTo(Search)
                    }
                    "/deeplink/main" -> {
                        val feature = uri.getQueryParameter("page") ?: return
                        when (feature) {
                            "FACETS" -> navTo(Facets())
                            "QUEUES" -> navTo(Queues())
                            "LIBRARY" -> navTo(Library)
                            "STATISTCS" -> navTo(Statistics)
                            else -> Logt(TAG, getString(R.string.app_action_not_found) + feature)
                        }
                    }
                    else -> {}
                }
            }
        }
        if (intent.getBooleanExtra(Extras.refresh_on_start.name, false)) runOnceOrAsk()

        // to avoid handling the intent twice when the configuration changes    TODO: this is not a good way
//        setIntent(Intent(this@MainActivity, MainActivity::class.java))
    }

    @SuppressLint("MissingSuperCall")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavIntent()
    }

    @Suppress("EnumEntryName")
    enum class Extras {
        lastVersion,
        queue_id,
        facet_name,
        feed_id,
        feed_url,
        refresh_on_start,
        generated_view_id,
        search_string,
        isShared,
        source,
        open_player
    }

    companion object {
        private val TAG: String = MainActivity::class.simpleName ?: "Anonymous"  // have to keep, otherwise release build may fail?!

        private const val INIT_KEY = "app_init_state"

        fun Context.findActivity(): Activity? {
            var context = this
            while (context is ContextWrapper) {
                if (context is Activity) return context
                context = context.baseContext
            }
            return null
        }
    }
}
