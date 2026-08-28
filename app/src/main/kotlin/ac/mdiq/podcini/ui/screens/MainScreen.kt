package ac.mdiq.podcini.ui.screens

import ac.mdiq.podcini.R
import ac.mdiq.podcini.playback.base.theatres
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.storage.database.appAttribsFlow
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.ui.compose.CommonConfirmDialog
import ac.mdiq.podcini.ui.compose.CommonToast
import ac.mdiq.podcini.ui.compose.LargePoster
import ac.mdiq.podcini.ui.compose.commonConfirms
import ac.mdiq.podcini.ui.compose.commonMessage
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logt
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "MainScreen"

var playerMinHeight by mutableIntStateOf(100)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context by rememberUpdatedState(LocalContext.current)
    val lcScope = rememberCoroutineScope()
    val appPrefs by appPrefsFlow!!.collectAsStateWithLifecycle()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            Logd(TAG, "DisposableEffect LifecycleEventObserver: $event")
            when (event) {
                Lifecycle.Event.ON_CREATE -> {
                    if (appAttribsFlow!!.value.restoreLastScreen) {
                        val restored: List<NavKey> = Json.decodeFromString(appAttribsFlow!!.value.backstack)
                        if (restored.isNotEmpty()) backStack.addAll(restored.take(10))
                    }
                }
                Lifecycle.Event.ON_START -> {}
                Lifecycle.Event.ON_RESUME -> {}
                Lifecycle.Event.ON_STOP -> {}
                Lifecycle.Event.ON_DESTROY -> {}
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {}
    }

    val sheetState = rememberBottomSheetScaffoldState(bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = false))
    val player0 by theatres[0].mPlayerFlow.collectAsStateWithLifecycle()
    val curMedia0 by player0?.curMediaFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }

    LaunchedEffect(Unit) { snapshotFlow { sheetState.bottomSheetState.targetValue }.distinctUntilChanged().collect { targetValue ->
        val state = PSState.fromSheet(targetValue)
        if (psState != state) psState = state
    } }

    LaunchedEffect(curMedia0?.id, psState) {
        if ((curMedia0?.id ?: -1L) <= 0) {
            if (sheetState.bottomSheetState.targetValue != SheetValue.Hidden) sheetState.bottomSheetState.hide()
            return@LaunchedEffect
        }
        val targetSheetValue = when (psState) {
            PSState.Expanded -> SheetValue.Expanded
            PSState.PartiallyExpanded -> SheetValue.PartiallyExpanded
            PSState.Hidden -> SheetValue.Hidden
        }
        if (sheetState.bottomSheetState.targetValue != targetSheetValue) {
            when (targetSheetValue) {
                SheetValue.Expanded -> sheetState.bottomSheetState.expand()
                SheetValue.PartiallyExpanded -> sheetState.bottomSheetState.partialExpand()
                SheetValue.Hidden -> sheetState.bottomSheetState.hide()
            }
        }
    }

    val bottomInsets = WindowInsets.ime.union(WindowInsets.navigationBars)
    val bottomInsetPadding = bottomInsets.asPaddingValues().calculateBottomPadding()
    val dynamicBottomPadding by remember {
        derivedStateOf {
            when (sheetState.bottomSheetState.currentValue) {
                SheetValue.Expanded -> bottomInsetPadding + 300.dp
                SheetValue.PartiallyExpanded -> bottomInsetPadding + playerMinHeight.dp
                else -> bottomInsetPadding
            }
        }
    }
    var savedDrawerValue by rememberSaveable { mutableStateOf(DrawerValue.Closed) }

    val drawerState = rememberDrawerState(initialValue = savedDrawerValue)

    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.orientation) {
        Logd(TAG, "LaunchedEffect(configuration.orientation)")
        withFrameNanos { }
        drawerState.snapTo(savedDrawerValue)
    }

    LaunchedEffect(drawerState.currentValue) { savedDrawerValue = drawerState.currentValue }
    val drawerCtrl = remember {
        object : DrawerController {
            override fun isOpen() = drawerState.isOpen
            override fun open() {
                lcScope.launch { drawerState.open() }
            }
            override fun close() {
                lcScope.launch { drawerState.close() }
            }
            override fun toggle() {
                lcScope.launch {
                    if (drawerState.isOpen) drawerState.close()
                    else drawerState.open()
                }
            }
        }
    }

    CommonToast(onDismiss = { })
    if (commonConfirms.isNotEmpty()) CommonConfirmDialog(commonConfirms[0])
    if (commonMessage != null) LargePoster(commonMessage!!)

    var lastLogTime by remember { mutableLongStateOf(0L) }
    if (appPrefs.customFolderUnavailable) {
        val currentTime = nowInMillis()
        if (currentTime - lastLogTime > 60000L) {
            Loge(TAG, stringResource(R.string.custum_folder_warning))
            lastLogTime = currentTime
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { backStack.toList() }.debounce(200.milliseconds).collect { stack ->
            val json = Json.encodeToString(stack)
            withContext(Dispatchers.IO) { upsert(appAttribsFlow!!.value) { it.backstack = json } }
        }
    }

    val windowInfo = LocalWindowInfo.current
    val screenWidth = windowInfo.containerSize.width.dp
//    Logd(TAG, "before CompositionLocalProvider")
    CompositionLocalProvider(LocalDrawerController provides drawerCtrl, LocalDrawerState provides drawerState) {
        ModalNavigationDrawer(drawerState = drawerState, modifier = Modifier.fillMaxHeight(), drawerContent = { NavDrawerScreen() }) {
            BottomSheetScaffold(sheetContent = { AVPlayerScreen() }, scaffoldState = sheetState, sheetMaxWidth = screenWidth, sheetPeekHeight = bottomInsetPadding + playerMinHeight.dp, sheetDragHandle = {}, sheetShape = RectangleShape, topBar = {}) { paddingValues ->
                Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface).fillMaxSize().padding(top = paddingValues.calculateTopPadding(), bottom = dynamicBottomPadding)) {
                    NavDisplay(backStack = backStack, onBack = { navBack() }, entryProvider = myEntryProvider, entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()))
                    if ((curMedia0?.id ?: -1L) > 0 && psState == PSState.Hidden) Text(stringResource(R.string.player_in_drawer), color = Color.Black, style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(Color.LightGray).align(Alignment.BottomCenter))
//                    if () Text(stringResource(R.string.player_in_drawer), color = Color.Red, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.background(Color.LightGray).align(Alignment.BottomCenter))
                }
            }
        }
    }

    BackHandler(enabled = handleBackSubScreens.isEmpty()) {
        Logd(TAG, "BackHandler isBSExpanded: $psState")
        val openDrawer = appPrefs.backButtonOpensDrawer
        val defPage = defaultNavKey
        Logd(TAG, "BackHandler curruntRoute0: defPage: $defPage")
        when {
            drawerState.isOpen -> drawerCtrl.close()
            psState == PSState.Expanded -> psState = PSState.PartiallyExpanded
            backStack.size > 1 -> {
                Logd(TAG, "BackHandler nav to back")
                navBack()
            }
            backStack.size == 1 && defPage != backStack[0] -> {
                Logd(TAG, "BackHandler nav to defPage: $defPage")
                navTo(defPage)
            }
            openDrawer -> drawerCtrl.open()
            else -> Logt(TAG, context.getString(R.string.no_more_screens_back))
        }
    }
}
