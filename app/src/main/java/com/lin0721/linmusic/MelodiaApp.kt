package com.lin0721.linmusic

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import com.lin0721.linmusic.core.ui.components.LoginBottomSheet
import com.lin0721.linmusic.core.ui.components.ProfileSidebar
import com.lin0721.linmusic.core.ui.components.ToastManager
import com.lin0721.linmusic.core.ui.components.WebViewLoginScreen
import com.lin0721.linmusic.core.ui.interaction.pressable
import com.lin0721.linmusic.core.ui.theme.MelodiaPress
import com.lin0721.linmusic.core.ui.theme.BackgroundDark
import com.lin0721.linmusic.core.update.UpdateManager
import com.lin0721.linmusic.core.update.UpdateUiState
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import com.lin0721.linmusic.feature.home.ui.HomeViewModel
import com.lin0721.linmusic.feature.settings.ui.UpdateDialog
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private val SidebarWidth = 310.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MelodiaApp() {
    val viewModel: HomeViewModel = koinViewModel()
    val settingsPreferences: SettingsPreferences = koinInject()
    val showCreateEntry by settingsPreferences.showCreateEntry.collectAsStateWithLifecycle(initialValue = true)
    val currentTrack by viewModel.playerManager.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.playerManager.isPlaying.collectAsStateWithLifecycle()
    val currentPositionState = viewModel.playerManager.currentPosition.collectAsStateWithLifecycle()
    val currentPositionProvider = { currentPositionState.value }
    val duration by viewModel.playerManager.duration.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()

    val playerSheet = rememberMelodiaPlayerSheetState()
    val navigation = rememberMelodiaNavigationState()
    val sidebar = rememberMelodiaSidebarState(SidebarWidth)

    var showCreateSheet by remember { mutableStateOf(false) }
    var createLoginState by remember { mutableStateOf(CreateLoginFlowState()) }
    var createLoginSyncJob by remember { mutableStateOf<Job?>(null) }
    var createLoginRequestSequence by remember { mutableStateOf(0L) }
    var createDialogRequestSequence by remember { mutableStateOf(0L) }
    var openCreateDialogRequest by remember { mutableStateOf<Long?>(null) }
    // 网页登录界面可见性状态
    var isLoginScreenVisible by remember { mutableStateOf(false) }
    // MV 播放页是否处于全屏态：全屏时隐藏底部导航栏/悬浮播放条，避免盖住视频
    var isMvFullscreen by remember { mutableStateOf(false) }
    // 悬浮播放卡片 + 导航栏的实际高度，下发给各页面用作列表底部留白
    var bottomOverlayHeight by remember { mutableStateOf(0.dp) }

    val hazeState = remember { HazeState() }
    val density = LocalDensity.current

    val toastMessage = rememberGlobalToastMessage()

    val cancelCreateLoginFlow: () -> Unit = {
        createLoginSyncJob?.cancel()
        createLoginSyncJob = null
        createLoginState = createLoginState.cancel()
        openCreateDialogRequest = null
    }

    // 页面离开组合时终止创建流程的资料同步，避免旧页面回调修改新页面状态。
    DisposableEffect(Unit) {
        onDispose {
            createLoginSyncJob?.cancel()
        }
    }

    // 系统返回键与侧滑返回拦截：按优先级关闭浮层或返回上一级
    val isAnyOverlayOpen = playerSheet.isOpen ||
            createLoginState.surface != CreateLoginSurface.None ||
            sidebar.isOpen ||
            showCreateSheet ||
            navigation.canNavigateBack

    BackHandler(enabled = isAnyOverlayOpen) {
        when {
            playerSheet.isOpen -> playerSheet.animateTo(false, 0f)
            createLoginState.surface != CreateLoginSurface.None -> {
                cancelCreateLoginFlow()
            }
            sidebar.isOpen -> sidebar.close()
            showCreateSheet -> {
                showCreateSheet = false
                openCreateDialogRequest = null
            }
            navigation.canNavigateBack -> navigation.navigateBack()
        }
    }

    val isDrawerDraggable = userProfile != null && (sidebar.isOpen || sidebar.isTouchStartingAtEdge)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { playerSheet.onScreenSizeChanged(it.height.toFloat()) }
            .pointerInput(sidebar.isOpen) {
                val edgeWidthPx = with(density) { 32.dp.toPx() }
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val down = event.changes.firstOrNull { it.changedToDown() }
                        if (down != null) {
                            sidebar.onPointerDown(down.position.x, edgeWidthPx)
                        }
                        if (event.changes.all { !it.pressed }) {
                            sidebar.onPointerUp()
                        }
                    }
                }
            }
            .anchoredDraggable(
                state = sidebar.draggableState,
                orientation = Orientation.Horizontal,
                enabled = isDrawerDraggable
            )
            .background(BackgroundDark)
    ) {
        // 1. 侧边栏层 (位于最底层或同步移动)
        userProfile?.let { profile ->
            Box(
                modifier = Modifier
                    .width(SidebarWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = sidebar.offsetX - sidebar.widthPx
                        alpha = 0.5f + (0.5f * sidebar.progress)
                    }
            ) {
                ProfileSidebar(
                    userProfile = profile,
                    onLogout = {
                        viewModel.logout()
                        sidebar.close()
                    },
                    onDismiss = { sidebar.close() },
                    onNavigateToRecentPlay = { navigation.openRecentPlay() },
                    onNavigateToListenData = { navigation.openListenData() },
                    onNavigateToCloud = { navigation.openCloud() },
                    onViewProfile = { sidebar.close(); navigation.openAccount() },
                    onNavigateToSettings = { navigation.navigateTo(Screen.Settings) }
                )
            }
        }

        // 2. 主页面内容层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = sidebar.offsetX
                    clip = true
                    shape = RoundedCornerShape((sidebar.progress * 32).dp)
                    shadowElevation = (sidebar.progress * 30f)
                }
                .background(BackgroundDark)
        ) {
            CompositionLocalProvider(LocalBottomOverlayInset provides bottomOverlayHeight) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (playerSheet.isOpen) Modifier.haze(hazeState) else Modifier)
                ) {
                    MelodiaNavHost(
                        currentScreen = navigation.currentScreen,
                        homeViewModel = viewModel,
                        activePlaylistId = navigation.activePlaylistId,
                        activePlaylistIsAlbum = navigation.activePlaylistIsAlbum,
                        activeArtistId = navigation.activeArtistId,
                        activeRadioId = navigation.activeRadioId,
                        activeMvId = navigation.activeMvId,
                        activeMvName = navigation.activeMvName,
                        activePlaylistCategory = navigation.activePlaylistCategory,
                        homeTab = navigation.homeTab,
                        showMusicNewWorks = navigation.showMusicNewWorks,
                        searchAutoFocus = navigation.searchAutoFocus,
                        onOpenSidebar = { sidebar.open() },
                        onLoginScreenVisibilityChanged = { isLoginScreenVisible = it },
                        onNavigateToPlaylist = { id, isAlbum -> navigation.openPlaylist(id, isAlbum) },
                        onNavigateToArtist = { id -> navigation.openArtist(id) },
                        onNavigateToRadio = { id -> navigation.openRadio(id) },
                        onNavigateToMv = { id, name -> navigation.openMvPlayer(id, name) },
                        onMvFullscreenChanged = { isMvFullscreen = it },
                        onNavigateToPlaylistCategory = { category -> navigation.openPlaylistCategory(category) },
                        onHomeTabSelected = { navigation.selectHomeTab(it) },
                        onShowMusicNewWorksChanged = { navigation.updateShowMusicNewWorks(it) },
                        onNavigateToSearch = { navigation.openSearch(autoFocus = true) },
                        onBack = { navigation.navigateBack() }
                    )

                    // 创建菜单遮罩
                    if (showCreateSheet) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.4f))
                                .pressable(MelodiaPress.None) {
                                    showCreateSheet = false
                                    openCreateDialogRequest = null
                                }
                        )
                    }

                    // 放置在应用了平移 graphicsLayer 的主 Box 内部的底部
                    MelodiaBottomOverlay(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        currentScreen = navigation.currentScreen,
                        showCreateSheet = showCreateSheet,
                        isLoginScreenVisible = isLoginScreenVisible ||
                                createLoginState.surface == CreateLoginSurface.Web ||
                                createLoginState.surface == CreateLoginSurface.Syncing,
                        isMvFullscreen = isMvFullscreen,
                        currentTrack = currentTrack,
                        isPlaying = isPlaying,
                        currentPositionProvider = currentPositionProvider,
                        duration = duration,
                        hazeState = hazeState,
                        onTogglePlay = { viewModel.togglePlayPause() },
                        onNext = { viewModel.playerManager.playNext() },
                        onMiniPlayerClick = { playerSheet.animateTo(true, 0f) },
                        onMiniPlayerDrag = { delta -> playerSheet.onDrag(delta) },
                        onMiniPlayerDragEnd = { velocity -> playerSheet.onDragEnd(velocity) },
                        onCreateDismiss = {
                            showCreateSheet = false
                            openCreateDialogRequest = null
                        },
                        onNavigate = { navigation.openTab(it) },
                        onCreateClick = {
                            showCreateSheet = !showCreateSheet
                            if (!showCreateSheet) openCreateDialogRequest = null
                        },
                        onLoginRequest = {
                            createLoginRequestSequence += 1L
                            createLoginState = createLoginState.requestLogin(createLoginRequestSequence)
                        },
                        openCreateDialogRequest = openCreateDialogRequest,
                        // 请求在表单打开时由 CreatePopupMenu 记住，表单关闭时
                        // 再清除 token，避免重组或返回操作重复打开表单。
                        onCreateDialogRequestConsumed = {},
                        onCreateDialogClosed = { openCreateDialogRequest = null },
                        showCreateEntry = showCreateEntry,
                        onOverlayHeightChanged = { bottomOverlayHeight = it }
                    )

                    // 侧边栏打开时的遮罩与点击收起事件
                    if (sidebar.progress > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.4f * sidebar.progress))
                                .pressable(
                                    style = MelodiaPress.None,
                                    enabled = sidebar.isOpen,
                                    onClick = { sidebar.close() }
                                )
                        )
                    }
                }
            }
        }

        MelodiaFullPlayerOverlay(
            currentTrack = currentTrack,
            screenHeightPx = playerSheet.screenHeightPx,
            isPlayerOpen = playerSheet.isOpen,
            playerOffsetY = playerSheet.offsetY,
            isPlaying = isPlaying,
            currentPositionProvider = currentPositionProvider,
            duration = duration,
            onTogglePlay = { viewModel.togglePlayPause() },
            onSeek = { viewModel.playerManager.seekTo(it) },
            onClose = { playerSheet.animateTo(false, 0f) },
            onDragClose = { offset, velocity -> playerSheet.animateTo(false, velocity, offset) },
            onArtistClick = { artistId ->
                playerSheet.animateTo(false, 0f)
                navigation.openArtist(artistId)
            },
            onAlbumClick = { albumId ->
                playerSheet.animateTo(false, 0f)
                navigation.openPlaylist(albumId, isAlbum = true)
            }
        )

        // 创建歌单触发的登录流程在应用层托管，这样登录完成后可以回到
        // 同一个创建流程，同时不影响当前页面和底栏导航栈。
        if (createLoginState.surface == CreateLoginSurface.Choice) {
            LoginBottomSheet(
                onDismiss = {
                    cancelCreateLoginFlow()
                },
                onWebLogin = {
                    createLoginState = createLoginState.openWebLogin()
                }
            )
        }

        if (createLoginState.surface == CreateLoginSurface.Web) {
            val webLoginRequestId = createLoginState.requestId
            WebViewLoginScreen(
                onClose = {
                    cancelCreateLoginFlow()
                },
                onLoginSuccess = { cookies ->
                    val shouldSync = createLoginState.surface == CreateLoginSurface.Web &&
                            createLoginState.ownsPendingRequest(webLoginRequestId) &&
                            !createLoginState.loginSuccessReported
                    if (shouldSync) {
                        val requestId = webLoginRequestId
                        createLoginState = createLoginState.reportLoginSuccess()
                        createLoginSyncJob = viewModel.handleLoginSuccess(cookies) { syncSucceeded ->
                            if (syncSucceeded && createLoginState.ownsPendingRequest(requestId)) {
                                createLoginSyncJob = null
                                // 仅在资料同步成功后恢复创建流程；消费后即清除，
                                // 通过一次性 request token 直接打开新建歌单表单。
                                createLoginState = createLoginState.consumePendingCreate()
                                showCreateSheet = true
                                createDialogRequestSequence += 1L
                                openCreateDialogRequest = createDialogRequestSequence
                            } else if (!syncSucceeded && createLoginState.ownsPendingRequest(requestId)) {
                                createLoginSyncJob = null
                                // 登录 Cookie 有效但账号资料未同步时，放弃本次
                                // 待办，避免下一次无关登录误打开创建菜单。
                                createLoginState = createLoginState.cancel()
                                ToastManager.showToast("登录信息同步失败，请重试")
                            }
                        }
                    }
                }
            )
        }

        if (createLoginState.surface == CreateLoginSurface.Syncing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BackgroundDark)
                    .pressable(MelodiaPress.None) {},
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "正在同步账号信息", color = Color.White)
                    TextButton(
                        onClick = cancelCreateLoginFlow
                    ) {
                        Text(text = "取消", color = Color.White)
                    }
                }
            }
        }

        // 4. 全局自定义 Toast 提示
        MelodiaToastHost(toastMessage = toastMessage)

        // 5. 全局更新弹窗，任意页面均可弹出
        val updateManager: UpdateManager = koinInject()
        val updateState by updateManager.uiState.collectAsStateWithLifecycle()
        if (updateState !is UpdateUiState.Idle) {
            UpdateDialog(
                state = updateState,
                onDismiss = { updateManager.dismiss() },
                onIgnore = { updateManager.ignoreCurrentVersion() },
                onStartDownload = { updateManager.startDownload() },
                onInstall = { updateManager.retryInstall() }
            )
        }
    }
}

// 订阅全局 Toast 事件，展示 2 秒后自动清空
@Composable
private fun rememberGlobalToastMessage(): String? {
    var message by remember { mutableStateOf<String?>(null) }
    var trigger by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        ToastManager.toastFlow.collect { msg ->
            message = msg
            trigger++
        }
    }

    LaunchedEffect(trigger) {
        if (message != null) {
            kotlinx.coroutines.delay(2000)
            message = null
        }
    }

    return message
}
