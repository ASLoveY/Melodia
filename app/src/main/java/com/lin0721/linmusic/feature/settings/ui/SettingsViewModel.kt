package com.lin0721.linmusic.feature.settings.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin0721.linmusic.BuildConfig
import com.lin0721.linmusic.R
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.feature.settings.data.UserBindingItem
import com.lin0721.linmusic.feature.settings.data.UserLevelData
import com.lin0721.linmusic.feature.settings.data.VipInfoData
import com.lin0721.linmusic.core.auth.AuthRepository
import com.lin0721.linmusic.feature.settings.data.SettingsRepository
import com.lin0721.linmusic.core.player.AudioCacheManager
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.network.ResourceProvider
import com.lin0721.linmusic.core.network.toUserMessage
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SettingsViewModel"

@OptIn(FlowPreview::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val settingsPreferences: SettingsPreferences,
    private val userPreferences: UserPreferences,
    private val authRepository: AuthRepository,
    private val resourceProvider: ResourceProvider
) : ViewModel() {

    // DataStore 的偏好流统一以相同策略转为 StateFlow，避免每项重复五行样板
    private fun <T> Flow<T>.asState(initial: T): StateFlow<T> = stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = initial
    )

    // 偏好写入均为即发即忘，无需回传结果
    private fun launchSave(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    // ─── 本地偏好设置对外状态流 ───
    val wifiQuality = settingsPreferences.wifiQuality.asState("lossless")

    val mobileQuality = settingsPreferences.mobileQuality.asState("standard")

    val useRealIp = settingsPreferences.useRealIp.asState(false)

    val realIpValue = settingsPreferences.realIpValue.asState("")

    val defaultPlaylistPrivate = settingsPreferences.defaultPlaylistPrivate.asState(false)

    val streamCacheEnabled = settingsPreferences.streamCacheEnabled.asState(true)

    val audioCacheMaxSize = settingsPreferences.audioCacheMaxSize.asState(512 * 1024 * 1024L)

    val autoPlayNext = settingsPreferences.autoPlayNext.asState(true)

    val wifiOnlyPlay = settingsPreferences.wifiOnlyPlay.asState(false)

    val mobileAlert = settingsPreferences.mobileAlert.asState(true)

    val useProxy = settingsPreferences.useProxy.asState(false)

    val showDesktopLrc = settingsPreferences.showDesktopLrc.asState(false)

    val showLockscreen = settingsPreferences.showLockscreen.asState(true)

    val carMode = settingsPreferences.carMode.asState(false)

    val showCreateEntry = settingsPreferences.showCreateEntry.asState(true)

    val lyricTextSize = settingsPreferences.lyricTextSize.asState(14)

    val lyricTextColor = settingsPreferences.lyricTextColor.asState("#FFFFFF")

    val logLevel = settingsPreferences.logLevel.asState(if (BuildConfig.DEBUG) "DEBUG" else "WARN")

    val autoCheckUpdateEnabled = settingsPreferences.autoCheckUpdateEnabled.asState(true)

    val allowPrereleaseChannel = settingsPreferences.allowPrereleaseChannel.asState(false)

    // ─── 服务端拉取数据状态 ───
    private val _userLevel = MutableStateFlow<UserLevelData?>(null)
    val userLevel = _userLevel.asStateFlow()

    private val _vipInfo = MutableStateFlow<VipInfoData?>(null)
    val vipInfo = _vipInfo.asStateFlow()

    private val _userBindings = MutableStateFlow<List<UserBindingItem>>(emptyList())
    val userBindings = _userBindings.asStateFlow()

    // 加载中状态与消息事件
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _isCacheOperationRunning = MutableStateFlow(false)
    val isCacheOperationRunning = _isCacheOperationRunning.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent = _toastEvent.asSharedFlow()

    // 昵称重名实时检查防抖状态
    private val _nicknameInput = MutableStateFlow("")
    val nicknameInput = _nicknameInput.asStateFlow()

    private val _isNicknameDuplicated = MutableStateFlow<Boolean?>(null)
    val isNicknameDuplicated = _isNicknameDuplicated.asStateFlow()

    init {
        // 昵称输入实时防抖查重 (500ms 延迟)
        _nicknameInput
            .debounce(500)
            .filter { it.isNotBlank() }
            .distinctUntilChanged()
            .onEach { name ->
                val currentName = userPreferences.userProfile.first()?.nickname
                if (name == currentName) {
                    _isNicknameDuplicated.value = false
                    return@onEach
                }
                settingsRepository.checkNickname(name).collect { result ->
                    result.onSuccess { duplicated ->
                        _isNicknameDuplicated.value = duplicated
                    }.onFailure {
                        _isNicknameDuplicated.value = null
                    }
                }
            }
            .launchIn(viewModelScope)

        // 初始化拉取远端账号数据
        fetchRemoteSettingsData()
    }

    // ─── 核心设置修改方法 ───

    fun updateWifiQuality(quality: String) = launchSave { settingsPreferences.saveWifiQuality(quality) }

    fun updateMobileQuality(quality: String) = launchSave { settingsPreferences.saveMobileQuality(quality) }

    fun updateUseRealIp(enabled: Boolean) = launchSave { settingsPreferences.saveUseRealIp(enabled) }

    fun updateRealIpValue(ip: String) = launchSave { settingsPreferences.saveRealIpValue(ip) }

    fun updateDefaultPlaylistPrivate(private: Boolean) = launchSave { settingsPreferences.saveDefaultPlaylistPrivate(private) }

    fun updateStreamCacheEnabled(enabled: Boolean) = launchSave { settingsPreferences.saveStreamCacheEnabled(enabled) }

    fun updateAudioCacheMaxSize(context: Context, size: Long) {
        runCacheOperation(context) { appContext ->
            withContext(Dispatchers.IO) {
                require(size > 0)
                val previousSize = settingsPreferences.audioCacheMaxSize.first()
                try {
                    AudioCacheManager.updateMaxSize(appContext, size)
                    settingsPreferences.saveAudioCacheMaxSize(size)
                } catch (error: Exception) {
                    try {
                        AudioCacheManager.updateMaxSize(appContext, previousSize)
                    } catch (rollbackError: Exception) {
                        error.addSuppressed(rollbackError)
                    }
                    throw error
                }
            }
            appContext.getString(R.string.cache_limit_updated)
        }
    }

    // 持久化并立即让 AppLogger 生效，无需重启 App
    fun updateLogLevel(level: AppLogger.LogLevel) {
        viewModelScope.launch {
            settingsPreferences.saveLogLevel(level.name)
            AppLogger.setLevel(level)
        }
    }

    fun updateAutoPlayNext(enabled: Boolean) = launchSave { settingsPreferences.saveAutoPlayNext(enabled) }

    fun updateWifiOnlyPlay(enabled: Boolean) = launchSave { settingsPreferences.saveWifiOnlyPlay(enabled) }

    fun updateMobileAlert(enabled: Boolean) = launchSave { settingsPreferences.saveMobileAlert(enabled) }

    fun updateUseProxy(enabled: Boolean) = launchSave { settingsPreferences.saveUseProxy(enabled) }

    fun updateShowDesktopLrc(enabled: Boolean) = launchSave { settingsPreferences.saveShowDesktopLrc(enabled) }

    fun updateShowLockscreen(enabled: Boolean) = launchSave { settingsPreferences.saveShowLockscreen(enabled) }

    fun updateCarMode(enabled: Boolean) = launchSave { settingsPreferences.saveCarMode(enabled) }

    fun updateShowCreateEntry(enabled: Boolean) = launchSave { settingsPreferences.saveShowCreateEntry(enabled) }

    fun updateLyricTextSize(size: Int) = launchSave { settingsPreferences.saveLyricTextSize(size) }

    fun updateLyricTextColor(color: String) = launchSave { settingsPreferences.saveLyricTextColor(color) }

    fun updateAutoCheckUpdateEnabled(enabled: Boolean) = launchSave { settingsPreferences.saveAutoCheckUpdateEnabled(enabled) }

    fun updateAllowPrereleaseChannel(enabled: Boolean) = launchSave { settingsPreferences.saveAllowPrereleaseChannel(enabled) }

    fun onNicknameInputChanged(name: String) {
        _nicknameInput.value = name
        if (name.isBlank()) {
            _isNicknameDuplicated.value = null
        }
    }

    // ─── 服务端接口数据加载 ───

    fun fetchRemoteSettingsData() {
        viewModelScope.launch {
            _isLoading.value = true
            val profile = userPreferences.userProfile.first() ?: return@launch

            // 并发获取用户等级、VIP 信息和绑定第三方账号信息
            launch {
                settingsRepository.getUserLevel().collect { res ->
                    res.onSuccess { _userLevel.value = it }
                }
            }
            launch {
                settingsRepository.getVipInfo().collect { res ->
                    res.onSuccess { _vipInfo.value = it }
                }
            }
            launch {
                settingsRepository.getUserBindings(profile.uid).collect { res ->
                    res.onSuccess { _userBindings.value = it }
                }
            }
            _isLoading.value = false
        }
    }

    // 每日签到
    fun executeDailySignin(type: Int = 0) {
        viewModelScope.launch {
            settingsRepository.dailySignin(type).collect { res ->
                res.onSuccess { point ->
                    if (point > 0) {
                        _toastEvent.emit("签到成功！积分+$point")
                    } else {
                        _toastEvent.emit("今天已经签到过了")
                    }
                }.onFailure {
                    _toastEvent.emit(it.toUserMessage(resourceProvider))
                }
            }
        }
    }

    // 更换头像
    fun uploadUserAvatar(file: java.io.File) {
        viewModelScope.launch {
            _isLoading.value = true
            settingsRepository.uploadAvatar(file).collect { res ->
                res.onSuccess { newUrl ->
                    // 更新本地用户元数据
                    val currentProfile = userPreferences.userProfile.first()
                    if (currentProfile != null) {
                        userPreferences.saveUserProfile(currentProfile.copy(avatarUrl = newUrl))
                    }
                    _toastEvent.emit("头像更新成功")
                }.onFailure {
                    _toastEvent.emit(it.toUserMessage(resourceProvider))
                }
                _isLoading.value = false
            }
        }
    }

    // 退出登录，注销远端会话并清空本地 preferences 缓存
    fun executeLogout(onFinished: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout().collect { }
            userPreferences.clearUserProfile()
            _toastEvent.emit("已成功退出登录")
            onFinished()
        }
    }

    // ─── 缓存清理：各缓存由所属组件管理，保留日志、WebView 等活动目录 ───

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearApplicationCache(context: Context) {
        runCacheOperation(context) { appContext ->
            val imageLoader = coil.Coil.imageLoader(appContext)
            imageLoader.memoryCache?.clear()
            withContext(Dispatchers.IO) {
                AudioCacheManager.clearCache(appContext, settingsPreferences.audioCacheMaxSize.first())
                imageLoader.diskCache?.clear()
            }
            appContext.getString(R.string.cache_cleared)
        }
    }

    private fun runCacheOperation(context: Context, operation: suspend (Context) -> String) {
        if (_isCacheOperationRunning.value) return
        _isCacheOperationRunning.value = true
        val appContext = context.applicationContext
        viewModelScope.launch {
            try {
                _toastEvent.emit(operation(appContext))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLogger.e(TAG, "缓存操作失败", error)
                _toastEvent.emit(appContext.getString(R.string.cache_operation_failed))
            } finally {
                _isCacheOperationRunning.value = false
            }
        }
    }
}
