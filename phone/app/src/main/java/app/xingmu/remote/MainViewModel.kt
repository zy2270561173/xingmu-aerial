package app.xingmu.remote

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.xingmu.remote.data.CommandReceipt
import app.xingmu.remote.data.ClientRelease
import app.xingmu.remote.data.InstalledTvApp
import app.xingmu.remote.data.TvDevice
import app.xingmu.remote.data.User
import app.xingmu.remote.data.hasEntitlement
import app.xingmu.remote.data.toTvDevice
import app.xingmu.remote.media.MediaShareManager
import app.xingmu.remote.network.ControlSocket
import app.xingmu.remote.network.DlnaController
import app.xingmu.remote.network.DlnaDevice
import app.xingmu.remote.network.Endpoint
import app.xingmu.remote.network.P2PControlChannel
import app.xingmu.remote.network.JpegTcpClient
import app.xingmu.remote.data.VipPlan
import app.xingmu.remote.data.VipOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CopyOnWriteArraySet

enum class AppSection { DEVICES, REMOTE, CAST, APPS, APPROVALS, AGENT, PROFILE }
enum class ControlChannelMode { AUTO, P2P, RELAY }

data class CommandStatus(
    val commandId: Long,
    val label: String,
    val state: String,
    val success: Boolean?,
)

data class EntitlementPrompt(
    val key: String,
    val title: String,
    val message: String,
)

data class AppUiState(
    val restoring: Boolean = true,
    val authenticated: Boolean = false,
    val offlineMode: Boolean = false,
    val baseUrl: String = BuildConfig.DEFAULT_BACKEND_URL,
    val username: String = "admin",
    val user: User? = null,
    val devices: List<TvDevice> = emptyList(),
    val selectedDeviceIds: Set<String> = emptySet(),
    val primaryDeviceId: String? = null,
    val detailDeviceId: String? = null,
    val section: AppSection = AppSection.DEVICES,
    val busy: Boolean = false,
    val message: String = "",
    val socketConnected: Boolean = false,
    val snapshot: Bitmap? = null,
    val hasPreviewFrame: Boolean = false,
    val snapshotError: String = "",
    val liveScreen: Boolean = true,
    val lastSnapshotAt: Long = 0,
    val recentCommands: List<CommandStatus> = emptyList(),
    val deviceEvents: List<app.xingmu.remote.data.DeviceEvent> = emptyList(),
    val deviceCommands: List<app.xingmu.remote.data.CommandReceipt> = emptyList(),
    val lanDevices: List<DlnaDevice> = emptyList(),
    val lanDiscovering: Boolean = false,
    val selectedLanHost: String? = null,
    val installedApps: List<InstalledTvApp> = emptyList(),
    val installedAppsDeviceId: String? = null,
    val appsLoading: Boolean = false,
    val pendingApprovals: List<TvDevice> = emptyList(),
    val approvalsLoading: Boolean = false,
    val availableUpdate: ClientRelease? = null,
    val updateDownloading: Boolean = false,
    val readyUpdatePath: String? = null,
    val p2pConnected: Boolean = false,
    val lanStreamConnected: Boolean = false,
    val vipPlans: List<VipPlan> = emptyList(),
    val vipOrders: List<VipOrder> = emptyList(),
    val vipSalesEnabled: Boolean = false,
    val vipPaymentEnabled: Boolean = false,
    val vipPaymentMethods: List<String> = emptyList(),
    val activeVipPayment: VipOrder? = null,
    val controlChannelMode: ControlChannelMode = ControlChannelMode.AUTO,
    val entitlementPrompt: EntitlementPrompt? = null,
    val membershipCenterRequest: Long = 0L,
) {
    val selectedDevices: List<TvDevice>
        get() = devices.filter { it.deviceId in selectedDeviceIds }
    val primaryDevice: TvDevice?
        get() = devices.firstOrNull { it.deviceId == primaryDeviceId }
    val detailDevice: TvDevice?
        get() = devices.firstOrNull { it.deviceId == detailDeviceId }

    fun entitlementEnabled(key: String): Boolean = user?.hasEntitlement(key) == true

    val remoteChannelEnabled: Boolean
        get() = entitlementEnabled("p2p_access") || entitlementEnabled("relay_access")
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StarlightMobileApplication
    private val tokenStore = app.tokenStore
    private val backend = app.backendClient
    private val dlna = DlnaController(application)
    private val mutableState = MutableStateFlow(
        AppUiState(
            baseUrl = tokenStore.baseUrl,
            username = tokenStore.username,
            controlChannelMode = runCatching {
                ControlChannelMode.valueOf(tokenStore.controlChannelMode)
            }.getOrDefault(ControlChannelMode.AUTO),
        ),
    )
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    private var token: String? = null
    private var controlSocket: ControlSocket? = null
    private var connectedDeviceId: String? = null
    private var refreshJob: Job? = null
    private var snapshotJob: Job? = null
    private var reconnectJob: Job? = null
    private var p2pRenewJob: Job? = null
    private var vipPaymentJob: Job? = null
    private var offlineSlideshowJob: Job? = null
    private var appVisible = false
    private var snapshotAwaiting = false
    private var lastSnapshotRequestAt = 0L
    private val connectionGeneration = AtomicLong(0)
    private var p2pChannel: P2PControlChannel? = null
    private var jpegTcpClient: JpegTcpClient? = null
    private var p2pSession: app.xingmu.remote.data.P2PSession? = null
    private val frameListeners = CopyOnWriteArraySet<(ByteArray) -> Unit>()
    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var udpConnected = false
    @Volatile private var tcpConnected = false

    init {
        backend.setUnauthorizedListener {
            viewModelScope.launch {
                if (token != null) clearSession("登录已过期，请重新登录")
            }
        }
        restoreSession()
    }

    fun login(baseUrl: String, username: String, password: String) {
        if (password.isBlank()) {
            updateMessage("请输入密码")
            return
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, message = "正在登录…")
            runCatching {
                val normalized = Endpoint.normalize(baseUrl)
                val result = backend.login(normalized, username, password)
                tokenStore.saveSession(normalized, result.user.username, result.accessToken)
                token = result.accessToken
                mutableState.value = mutableState.value.copy(
                    restoring = false,
                    authenticated = true,
                    baseUrl = normalized,
                    username = result.user.username,
                    user = result.user,
                    busy = false,
                    message = "登录成功",
                )
                refreshDevices(showBusy = true)
                startRefreshLoop()
                checkMobileUpdate()
            }.onFailure { fail(it) }
        }
    }

    fun register(
        baseUrl: String,
        username: String,
        password: String,
        nickname: String,
        phone: String,
    ) {
        if (!username.trim().matches(Regex("[A-Za-z0-9_.-]{3,64}"))) {
            updateMessage("账号限 3-64 位字母、数字、点、横线或下划线")
            return
        }
        if (password.length < 8) {
            updateMessage("密码至少 8 位")
            return
        }
        if (phone.isNotBlank() && !phone.trim().matches(Regex("\\+?[0-9]{6,20}"))) {
            updateMessage("请输入正确的手机号")
            return
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, message = "正在注册…")
            runCatching {
                val normalized = Endpoint.normalize(baseUrl)
                val result = backend.register(normalized, username, password, nickname, phone)
                tokenStore.saveSession(normalized, result.user.username, result.accessToken)
                token = result.accessToken
                mutableState.value = mutableState.value.copy(
                    restoring = false,
                    authenticated = true,
                    baseUrl = normalized,
                    username = result.user.username,
                    user = result.user,
                    busy = false,
                    message = "注册成功，请扫描电视绑定码",
                )
                refreshDevices(showBusy = true)
                startRefreshLoop()
                checkMobileUpdate()
            }.onFailure { fail(it) }
        }
    }

    fun claimPairingQr(contents: String) {
        val credential = token ?: return
        val uri = runCatching { Uri.parse(contents.trim()) }.getOrNull()
        if (uri?.scheme == "starlight-tvcast" && uri.host == "vip") {
            val server = uri.getQueryParameter("server").orEmpty()
            val code = uri.getQueryParameter("code").orEmpty()
            if (runCatching { Endpoint.normalize(server) }.getOrNull() != state.value.baseUrl) {
                updateMessage("兑换码属于其他控制中心")
            } else redeemVipCode(code)
            return
        }
        if (uri?.scheme != "starlight-tvcast" || uri.host != "pair") {
            updateMessage("这不是星投屏电视绑定二维码")
            return
        }
        val server = uri.getQueryParameter("server").orEmpty()
        val code = uri.getQueryParameter("code").orEmpty()
        val normalizedServer = runCatching { Endpoint.normalize(server) }.getOrNull()
        if (normalizedServer == null || code.length < 20) {
            updateMessage("电视绑定二维码内容不完整，请在电视刷新")
            return
        }
        if (normalizedServer != state.value.baseUrl) {
            updateMessage("二维码属于 $normalizedServer，请先用该后端的账号登录")
            return
        }
        viewModelScope.launch {
            mutableState.value = state.value.copy(busy = true, message = "正在绑定电视…")
            runCatching { backend.claimPairing(normalizedServer, credential, code) }
                .onSuccess { device ->
                    mutableState.value = state.value.copy(
                        busy = false,
                        message = "${device.name} 已提交绑定申请，请在审批设备中确认添加",
                        section = AppSection.DEVICES,
                    )
                    refreshDevices()
                }
                .onFailure { fail(it) }
        }
    }

    fun logout() {
        if (state.value.offlineMode) {
            exitOfflineMode()
            return
        }
        clearSession("已退出登录")
    }

    fun enterOfflineMode() {
        refreshJob?.cancel()
        disconnectControl()
        token = null
        mutableState.value = AppUiState(
            restoring = false,
            authenticated = true,
            offlineMode = true,
            baseUrl = tokenStore.baseUrl,
            username = tokenStore.username,
            section = AppSection.DEVICES,
            message = "已进入离线局域网模式",
        )
        discoverLanDevices()
    }

    fun exitOfflineMode() {
        offlineSlideshowJob?.cancel()
        MediaShareManager.stop(getApplication())
        mutableState.value = AppUiState(
            restoring = false,
            baseUrl = tokenStore.baseUrl,
            username = tokenStore.username,
            message = "已退出离线模式",
        )
    }

    private fun clearSession(message: String) {
        refreshJob?.cancel()
        vipPaymentJob?.cancel()
        offlineSlideshowJob?.cancel()
        disconnectControl()
        MediaShareManager.stop(getApplication())
        token = null
        tokenStore.clearSession()
        mutableState.value = AppUiState(
            restoring = false,
            baseUrl = tokenStore.baseUrl,
            username = tokenStore.username,
            message = message,
        )
    }

    fun changeBackendAddress(value: String) {
        runCatching { Endpoint.normalize(value) }
            .onSuccess { normalized ->
                refreshJob?.cancel()
                disconnectControl()
                MediaShareManager.stop(getApplication())
                token = null
                tokenStore.clearSession()
                tokenStore.saveEndpoint(normalized)
                mutableState.value = AppUiState(
                    restoring = false,
                    baseUrl = normalized,
                    username = state.value.username,
                    message = "控制中心已更新，请重新登录",
                )
            }
            .onFailure { updateMessage(friendly(it)) }
    }

    fun setSection(section: AppSection) {
        if (state.value.offlineMode && section !in setOf(AppSection.DEVICES, AppSection.REMOTE, AppSection.CAST)) {
            return
        }
        if (!state.value.offlineMode && section == AppSection.APPS
            && !requireEntitlement("remote_control", "远程管理电视应用")) return
        val previous = state.value.section
        val resetPreview = previous != section &&
            (previous == AppSection.REMOTE || section == AppSection.REMOTE)
        if (resetPreview) {
            latestJpeg = null
            lastFrameAt = 0L
        }
        mutableState.value = mutableState.value.copy(
            section = section,
            hasPreviewFrame = if (resetPreview) false else state.value.hasPreviewFrame,
            snapshotError = if (resetPreview) "" else state.value.snapshotError,
        )
        restartSnapshotLoop()
        if (section == AppSection.APPROVALS) refreshApprovals()
        if (section == AppSection.APPS) {
            state.value.primaryDeviceId?.let {
                mutableState.value = state.value.copy(
                    installedAppsDeviceId = it,
                    installedApps = emptyList(),
                    appsLoading = true,
                )
                loadInstalledApps(it)
            }
        }
    }

    fun updateProfile(nickname: String, phone: String) {
        val credential = token ?: return
        if (phone.isNotBlank() && !phone.trim().matches(Regex("\\+?[0-9]{6,20}"))) {
            updateMessage("请输入正确的手机号")
            return
        }
        viewModelScope.launch {
            mutableState.value = state.value.copy(busy = true, message = "正在保存资料…")
            runCatching {
                backend.updateProfile(state.value.baseUrl, credential, nickname, phone)
            }.onSuccess { user ->
                mutableState.value = state.value.copy(
                    user = user,
                    username = user.username,
                    busy = false,
                    message = "个人资料已更新",
                )
            }.onFailure { fail(it) }
        }
    }

    fun uploadAvatar(uri: Uri) {
        val credential = token ?: return
        viewModelScope.launch {
            mutableState.value = state.value.copy(busy = true, message = "正在上传头像…")
            runCatching { backend.uploadAvatar(state.value.baseUrl, credential, uri) }
                .onSuccess { user ->
                    mutableState.value = state.value.copy(user = user, busy = false, message = "头像已更新")
                }
                .onFailure { fail(it) }
        }
    }

    fun changePassword(oldPassword: String, newPassword: String, confirmPassword: String) {
        val credential = token ?: return
        if (oldPassword.isBlank()) { updateMessage("请输入旧密码"); return }
        if (newPassword.length < 8) { updateMessage("新密码至少 8 位"); return }
        if (newPassword != confirmPassword) { updateMessage("两次新密码不一致"); return }
        viewModelScope.launch {
            mutableState.value = state.value.copy(busy = true, message = "正在更换密码…")
            runCatching {
                backend.changePassword(
                    state.value.baseUrl, credential, oldPassword, newPassword, confirmPassword,
                )
            }.onSuccess {
                mutableState.value = state.value.copy(busy = false, message = "密码已更换")
            }.onFailure { fail(it) }
        }
    }

    fun unbindDevice(deviceId: String) {
        val credential = token ?: return
        viewModelScope.launch {
            mutableState.value = state.value.copy(busy = true, message = "正在解除设备绑定…")
            runCatching { backend.unbindDevice(state.value.baseUrl, credential, deviceId) }
                .onSuccess {
                    mutableState.value = state.value.copy(
                        detailDeviceId = null,
                        selectedDeviceIds = state.value.selectedDeviceIds - deviceId,
                        primaryDeviceId = state.value.primaryDeviceId?.takeIf { it != deviceId },
                        busy = false,
                        message = "设备已解除绑定，可重新扫码绑定其他账号",
                    )
                    refreshDevices(showBusy = true)
                }
                .onFailure { fail(it) }
        }
    }

    fun refreshApprovals() {
        val credential = token ?: return
        viewModelScope.launch {
            mutableState.value = state.value.copy(approvalsLoading = true)
            runCatching { backend.listApprovals(state.value.baseUrl, credential) }
                .onSuccess { approvals ->
                    mutableState.value = state.value.copy(
                        pendingApprovals = approvals,
                        approvalsLoading = false,
                    )
                }
                .onFailure {
                    mutableState.value = state.value.copy(
                        approvalsLoading = false,
                        message = friendly(it),
                    )
                }
        }
    }

    /** Pulls the backend-side history for the open device: events timeline and recent commands. */
    fun loadDeviceHistory(deviceId: String?) {
        val id = deviceId ?: return
        val credential = token ?: return
        viewModelScope.launch {
            runCatching { backend.listEvents(state.value.baseUrl, credential, id) }
                .onSuccess { events -> mutableState.value = state.value.copy(deviceEvents = events) }
            runCatching { backend.listCommands(state.value.baseUrl, credential, id) }
                .onSuccess { commands -> mutableState.value = state.value.copy(deviceCommands = commands) }
        }
    }
    fun reviewApproval(deviceId: String, approve: Boolean, ownerUserId: Int? = null) {
        val credential = token ?: return
        viewModelScope.launch {
            mutableState.value = state.value.copy(busy = true)
            runCatching {
                backend.reviewApproval(state.value.baseUrl, credential, deviceId, approve, ownerUserId)
            }.onSuccess {
                mutableState.value = state.value.copy(
                    pendingApprovals = state.value.pendingApprovals.filterNot { it.deviceId == deviceId },
                    busy = false,
                    message = if (approve) "App 审批已批准，电视二维码页将自动关闭" else "设备已拒绝",
                )
                refreshDevices()
            }.onFailure { fail(it) }
        }
    }

    fun checkMobileUpdate(manual: Boolean = false) {
        if (!state.value.authenticated) return
        val now = System.currentTimeMillis()
        if (!manual && now - tokenStore.lastUpdateCheck < UPDATE_CHECK_INTERVAL_MS) return
        viewModelScope.launch {
            runCatching {
                backend.latestMobileRelease(state.value.baseUrl, BuildConfig.VERSION_CODE)
            }.onSuccess { check ->
                tokenStore.lastUpdateCheck = System.currentTimeMillis()
                val release = check.release?.takeIf {
                    check.updateAvailable && it.versionCode > BuildConfig.VERSION_CODE
                }
                mutableState.value = state.value.copy(availableUpdate = release)
                if (manual && release == null) updateMessage("手机 App 当前已是最新版本")
            }.onFailure {
                if (manual) updateMessage(friendly(it))
            }
        }
    }

    fun dismissMobileUpdate() {
        mutableState.value = state.value.copy(availableUpdate = null)
    }

    fun downloadMobileUpdate() {
        val release = state.value.availableUpdate ?: return
        if (state.value.updateDownloading) return
        viewModelScope.launch {
            mutableState.value = state.value.copy(updateDownloading = true, message = "正在下载手机 App 更新…")
            runCatching {
                withTimeout(MOBILE_UPDATE_TIMEOUT_MS) {
                    val apk = backend.downloadRelease(state.value.baseUrl, release)
                    val packageInfo = getApplication<Application>().packageManager
                        .getPackageArchiveInfo(apk.absolutePath, 0)
                    val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageInfo?.longVersionCode ?: 0L
                    } else {
                        @Suppress("DEPRECATION")
                        (packageInfo?.versionCode ?: 0).toLong()
                    }
                    if (packageInfo?.packageName != BuildConfig.APPLICATION_ID
                        || archiveVersion <= BuildConfig.VERSION_CODE
                    ) {
                        apk.delete()
                        throw IllegalStateException("更新包不是更高版本的星投屏手机 App")
                    }
                    apk
                }
            }.onSuccess { apk ->
                    mutableState.value = state.value.copy(
                        updateDownloading = false,
                        readyUpdatePath = apk.absolutePath,
                        message = "更新包已校验，正在打开系统安装器",
                    )
                }
                .onFailure { error ->
                    val message = if (error is TimeoutCancellationException) {
                        "更新包下载超时，请检查网络后重试"
                    } else {
                        friendly(error)
                    }
                    mutableState.value = state.value.copy(
                        updateDownloading = false,
                        readyUpdatePath = null,
                        message = message,
                    )
                }
        }
    }

    fun consumeUpdateInstaller(opened: Boolean) {
        mutableState.value = state.value.copy(
            updateDownloading = false,
            readyUpdatePath = null,
            availableUpdate = if (opened) null else state.value.availableUpdate,
            message = if (opened) state.value.message else "系统没有可用的 APK 安装器",
        )
    }

    fun refreshDevices(showBusy: Boolean = false) {
        if (state.value.offlineMode) {
            discoverLanDevices()
            return
        }
        val credential = token ?: return
        viewModelScope.launch {
            if (showBusy) mutableState.value = mutableState.value.copy(busy = true)
            runCatching { backend.listDevices(state.value.baseUrl, credential) }
                .onSuccess { devices ->
                    val existing = state.value.selectedDeviceIds.intersect(devices.map { it.deviceId }.toSet())
                    val selection = if (existing.isEmpty()) {
                        devices.firstOrNull { it.controllable }?.let { setOf(it.deviceId) } ?: emptySet()
                    } else existing
                    val primary = state.value.primaryDeviceId?.takeIf { it in selection }
                        ?: selection.firstOrNull()
                    mutableState.value = mutableState.value.copy(
                        devices = devices,
                        selectedDeviceIds = selection,
                        primaryDeviceId = primary,
                        busy = false,
                    )
                    connectControl(primary)
                    refreshLanDevicesSilently()
                    if (state.value.section == AppSection.APPS && primary != null
                        && state.value.installedAppsDeviceId != primary
                    ) {
                        mutableState.value = state.value.copy(
                            installedAppsDeviceId = primary,
                            installedApps = emptyList(),
                            appsLoading = true,
                        )
                        loadInstalledApps(primary)
                    }
                }
                .onFailure { error ->
                    if (showBusy) fail(error) else mutableState.value = mutableState.value.copy(message = friendly(error))
                }
        }
    }

    fun toggleDevice(deviceId: String) {
        val current = state.value.selectedDeviceIds
        val updated = if (deviceId in current) current - deviceId else current + deviceId
        val primary = state.value.primaryDeviceId?.takeIf { it in updated } ?: updated.firstOrNull()
        mutableState.value = state.value.copy(selectedDeviceIds = updated, primaryDeviceId = primary)
        connectControl(primary)
        if (state.value.section == AppSection.APPS && primary != null
            && state.value.installedAppsDeviceId != primary
        ) {
            mutableState.value = state.value.copy(
                installedAppsDeviceId = primary,
                installedApps = emptyList(),
                appsLoading = true,
            )
            loadInstalledApps(primary)
        }
    }

    fun makePrimary(deviceId: String) {
        if (deviceId !in state.value.selectedDeviceIds) return
        mutableState.value = state.value.copy(primaryDeviceId = deviceId)
        connectControl(deviceId)
        if (state.value.section == AppSection.APPS) {
            mutableState.value = state.value.copy(
                installedAppsDeviceId = deviceId,
                installedApps = emptyList(),
                appsLoading = true,
            )
            loadInstalledApps(deviceId)
        }
    }

    fun showDeviceDetails(deviceId: String) {
        val selected = state.value.selectedDeviceIds + deviceId
        mutableState.value = state.value.copy(
            detailDeviceId = deviceId,
            selectedDeviceIds = selected,
            primaryDeviceId = deviceId,
            section = AppSection.AGENT,
        )
        connectControl(deviceId)
        if (state.value.user?.role == "admin") refreshApprovals()
    }

    fun hideDeviceDetails() {
        mutableState.value = state.value.copy(
            detailDeviceId = null,
        )
    }

    fun showDeviceRemote(deviceId: String) {
        val selected = state.value.selectedDeviceIds + deviceId
        mutableState.value = state.value.copy(
            selectedDeviceIds = selected,
            primaryDeviceId = deviceId,
            detailDeviceId = null,
            section = AppSection.REMOTE,
        )
        connectControl(deviceId)
    }

    fun deleteDevice(deviceId: String) {
        val credential = token ?: return
        viewModelScope.launch {
            mutableState.value = state.value.copy(busy = true, message = "正在删除设备…")
            runCatching { backend.deleteDevice(state.value.baseUrl, credential, deviceId) }
                .onSuccess {
                    val remaining = state.value.devices.filterNot { it.deviceId == deviceId }
                    val selection = state.value.selectedDeviceIds - deviceId
                    val primary = state.value.primaryDeviceId?.takeIf { it != deviceId && it in selection }
                        ?: selection.firstOrNull()
                    mutableState.value = state.value.copy(
                        devices = remaining,
                        selectedDeviceIds = selection,
                        primaryDeviceId = primary,
                        detailDeviceId = null,
                        busy = false,
                        message = "旧设备及其历史数据已删除",
                    )
                    connectControl(primary)
                }
                .onFailure { fail(it) }
        }
    }

    fun openTvSettings(deviceId: String, target: String) {
        val device = state.value.devices.firstOrNull { it.deviceId == deviceId }
        if (device == null || !device.controllable) {
            updateMessage("电视需在线、启用并已审核，才能远程打开设置")
            return
        }
        viewModelScope.launch {
            sendToTargets(
                listOf(device),
                "settings",
                JSONObject().put("target", target),
                "打开电视设置",
            )
        }
    }

    fun refreshInstalledApps(deviceId: String) {
        val credential = token ?: return
        val device = state.value.devices.firstOrNull { it.deviceId == deviceId }
        if (device == null || !device.controllable) {
            updateMessage("电视需在线、启用并已审核，才能读取应用")
            return
        }
        viewModelScope.launch {
            mutableState.value = state.value.copy(appsLoading = true, installedAppsDeviceId = deviceId)
            runCatching {
                backend.sendCommand(
                    state.value.baseUrl,
                    credential,
                    deviceId,
                    "app",
                    JSONObject().put("action", "refresh"),
                )
            }.onSuccess { receipt ->
                receiptStatus(receipt, "读取电视应用")
                delay(1_200)
                loadInstalledAppsNow(deviceId, credential)
            }.onFailure {
                mutableState.value = state.value.copy(appsLoading = false, message = friendly(it))
            }
        }
    }

    fun launchInstalledApp(deviceId: String, app: InstalledTvApp) {
        val credential = token ?: return
        val device = state.value.devices.firstOrNull { it.deviceId == deviceId }
        if (device == null || !device.controllable || !app.enabled || !app.launchable) {
            updateMessage("该应用当前不能远程打开")
            return
        }
        viewModelScope.launch {
            runCatching {
                backend.sendCommand(
                    state.value.baseUrl,
                    credential,
                    deviceId,
                    "app",
                    JSONObject().put("action", "launch").put("package_name", app.packageName),
                )
            }.onSuccess { receipt ->
                receiptStatus(receipt, "打开 ${app.label}")
                updateMessage("已通知电视打开 ${app.label}")
            }.onFailure { updateMessage(friendly(it)) }
        }
    }

    fun openTvForeground(deviceId: String? = state.value.primaryDeviceId) {
        val credential = token ?: return
        val device = state.value.devices.firstOrNull { it.deviceId == deviceId }
        if (device == null || !device.controllable) {
            updateMessage("电视需在线、启用并已审核，才能唤醒星投屏")
            return
        }
        viewModelScope.launch {
            runCatching {
                backend.sendCommand(
                    state.value.baseUrl,
                    credential,
                    device.deviceId,
                    "app",
                    JSONObject().put("action", "foreground"),
                )
            }.onSuccess { receipt ->
                receiptStatus(receipt, "唤醒星投屏")
                updateMessage("已通知电视显示星投屏前台")
            }.onFailure { updateMessage(friendly(it)) }
        }
    }

    fun setLiveScreen(enabled: Boolean) {
        if (enabled) {
            if (!requireEntitlement("screen_view", "查看远程电视画面")) return
            if (!state.value.remoteChannelEnabled) {
                showEntitlementPrompt("p2p_access", "建立远程画面通道")
                return
            }
        }
        mutableState.value = state.value.copy(liveScreen = enabled)
        restartSnapshotLoop()
    }

    fun addFrameListener(listener: (ByteArray) -> Unit) {
        frameListeners.add(listener)
        latestJpeg?.let(listener)
    }

    fun removeFrameListener(listener: (ByteArray) -> Unit) {
        frameListeners.remove(listener)
    }

    fun setAppForeground(visible: Boolean) {
        appVisible = visible
        restartSnapshotLoop()
    }

    fun discoverLanDevices() {
        viewModelScope.launch {
            mutableState.value = state.value.copy(lanDiscovering = true, message = "正在搜索局域网电视…")
            runCatching { dlna.discover(force = true) }
                .onSuccess { found ->
                    mutableState.value = state.value.copy(
                        lanDevices = found,
                        selectedLanHost = state.value.selectedLanHost
                            ?.takeIf { host -> found.any { it.host == host } }
                            ?: found.firstOrNull()?.host,
                        lanDiscovering = false,
                        message = if (found.isEmpty()) "局域网内没有发现 DLNA 电视" else "发现 ${found.size} 台局域网电视",
                    )
                }
                .onFailure {
                    mutableState.value = state.value.copy(lanDiscovering = false, message = friendly(it))
                }
        }
    }

    fun selectLanDevice(host: String) {
        mutableState.value = state.value.copy(selectedLanHost = host)
    }

    fun sendLanTransport(action: String) {
        val host = state.value.selectedLanHost
        if (host.isNullOrBlank()) {
            updateMessage("请先选择局域网电视")
            return
        }
        if (action == "stop") {
            offlineSlideshowJob?.cancel()
            MediaShareManager.stop(getApplication())
        }
        viewModelScope.launch {
            runCatching { dlna.transport(host, action) }
                .onSuccess { ok -> updateMessage(if (ok) "局域网播放指令已发送" else "电视暂不支持该播放指令") }
                .onFailure { updateMessage(friendly(it)) }
        }
    }

    fun sendKey(key: String) {
        if (state.value.offlineMode) {
            sendLanKey(key)
            return
        }
        when (state.value.controlChannelMode) {
            ControlChannelMode.RELAY -> {
                if (!requireEntitlement("remote_control", "公网远程遥控")) {
                    return
                } else if (!state.value.entitlementEnabled("relay_access")) {
                    showEntitlementPrompt("relay_access", "使用后端转发遥控")
                } else {
                    sendBatchCommand("key", JSONObject().put("key", key), "后端转发遥控")
                }
            }
            ControlChannelMode.P2P -> {
                if (!requireEntitlement("remote_control", "公网远程遥控")) {
                    return
                } else if (!state.value.entitlementEnabled("p2p_access")) {
                    showEntitlementPrompt("p2p_access", "使用 P2P 直连遥控")
                } else if (state.value.selectedDevices.size != 1 || p2pChannel?.sendKey(key) != true) {
                    updateMessage("P2P 直连尚未建立；请选择一台在线电视后重试")
                } else {
                    updateMessage("P2P 遥控指令已发送")
                }
            }
            ControlChannelMode.AUTO -> sendAutomaticKey(key)
        }
    }

    private fun sendAutomaticKey(key: String) {
        val selected = state.value.selectedDevices.singleOrNull()
        viewModelScope.launch {
            val lanHost = selected?.lanIp?.trim().orEmpty()
            if (lanHost.isNotBlank()) {
                val lanResult = runCatching { dlna.sendRemoteKey(lanHost, key) }.getOrNull()
                if (lanResult?.success == true) {
                    updateMessage("局域网直连遥控成功：${lanResult.message}")
                    return@launch
                }
            }

            if (!state.value.entitlementEnabled("remote_control")) {
                showEntitlementPrompt("remote_control", "公网远程遥控")
                return@launch
            }
            if (state.value.entitlementEnabled("p2p_access")
                && state.value.selectedDevices.size == 1
                && p2pChannel?.sendKey(key) == true
            ) {
                updateMessage("局域网不可用，已通过 P2P 发送遥控指令")
                return@launch
            }
            if (state.value.entitlementEnabled("relay_access")) {
                sendBatchCommand("key", JSONObject().put("key", key), "后端回退遥控")
            } else {
                showEntitlementPrompt("relay_access", "P2P 失败后自动回退")
            }
        }
    }

    private fun sendLanKey(key: String) {
        val host = state.value.selectedLanHost
        if (host.isNullOrBlank()) {
            updateMessage("请先选择局域网电视")
            return
        }
        viewModelScope.launch {
            runCatching { dlna.sendRemoteKey(host, key) }
                .onSuccess { result ->
                    updateMessage(
                        when {
                            !result.supported -> result.message
                            result.success -> "局域网遥控成功：${result.message}"
                            else -> "电视未执行：${result.message}"
                        },
                    )
                }
                .onFailure { updateMessage("局域网遥控失败：${friendly(it)}") }
        }
    }

    private fun refreshLanDevicesSilently() {
        viewModelScope.launch {
            runCatching { dlna.discover(force = false) }.onSuccess { found ->
                if (!state.value.offlineMode) {
                    mutableState.value = state.value.copy(
                        lanDevices = found,
                        selectedLanHost = state.value.selectedLanHost
                            ?.takeIf { host -> found.any { it.host == host } }
                            ?: found.firstOrNull()?.host,
                    )
                }
            }
        }
    }

    fun setControlChannelMode(mode: ControlChannelMode) {
        if (mode == state.value.controlChannelMode) return
        if (mode == ControlChannelMode.P2P && !state.value.entitlementEnabled("p2p_access")) {
            showEntitlementPrompt("p2p_access", "切换到 P2P 直连通道")
            return
        }
        if (mode == ControlChannelMode.RELAY && !state.value.entitlementEnabled("relay_access")) {
            showEntitlementPrompt("relay_access", "切换到后端转发通道")
            return
        }
        tokenStore.controlChannelMode = mode.name
        val deviceId = state.value.primaryDeviceId
        mutableState.value = state.value.copy(controlChannelMode = mode, message = when (mode) {
            ControlChannelMode.AUTO -> "已切换为自动通道：优先局域网，其次 P2P，失败时使用后端"
            ControlChannelMode.P2P -> "已切换为仅 P2P 直连"
            ControlChannelMode.RELAY -> "已切换为仅后端转发"
        })
        disconnectControl()
        if (deviceId != null) connectControl(deviceId)
    }

    fun refreshVip() {
        val credential = token ?: return
        viewModelScope.launch {
            runCatching {
                Triple(
                    backend.getVipStatus(state.value.baseUrl, credential),
                    backend.listVipPlans(state.value.baseUrl, credential),
                    backend.listVipOrders(state.value.baseUrl, credential),
                )
            }.onSuccess { (status, plans, orders) -> mutableState.value = state.value.copy(
                user = status.user, vipPlans = plans, vipOrders = orders,
                vipSalesEnabled = status.salesEnabled, vipPaymentEnabled = status.paymentEnabled,
                vipPaymentMethods = status.paymentMethods,
            ) }
                .onFailure { updateMessage(friendly(it)) }
        }
    }

    fun createVipOrder(planId: Int) {
        val credential = token ?: return
        viewModelScope.launch { runCatching { backend.createVipOrder(state.value.baseUrl, credential, planId) }
            .onSuccess { updateMessage("订单已提交，等待管理员确认"); refreshVip() }
            .onFailure { updateMessage(friendly(it)) } }
    }

    fun startVipPayment(planId: Int, paymentMethod: String) {
        val credential = token ?: return
        if (paymentMethod !in state.value.vipPaymentMethods) {
            updateMessage("该支付方式未在后台启用")
            return
        }
        vipPaymentJob?.cancel()
        viewModelScope.launch {
            mutableState.value = state.value.copy(busy = true, message = "正在向后端创建支付订单…")
            runCatching {
                backend.createVipPayment(state.value.baseUrl, credential, planId, paymentMethod)
            }.onSuccess { order ->
                mutableState.value = state.value.copy(
                    busy = false, activeVipPayment = order,
                    message = "订单已创建，请扫描二维码完成支付",
                )
                pollVipPayment(order.orderNo, credential)
            }.onFailure { fail(it) }
        }
    }

    fun dismissVipPayment() {
        vipPaymentJob?.cancel()
        vipPaymentJob = null
        mutableState.value = state.value.copy(activeVipPayment = null)
    }

    private fun pollVipPayment(orderNo: String, credential: String) {
        vipPaymentJob?.cancel()
        vipPaymentJob = viewModelScope.launch {
            while (true) {
                delay(2_500L)
                val order = runCatching {
                    backend.getVipPayment(state.value.baseUrl, credential, orderNo)
                }.getOrNull() ?: continue
                mutableState.value = state.value.copy(activeVipPayment = order)
                if (order.status == "paid") {
                    updateMessage("支付成功，套餐时长和权益已经叠加")
                    refreshVip()
                    break
                }
                if (order.status == "cancelled" || order.paymentStatus == "failed") {
                    updateMessage("订单已取消或支付失败")
                    break
                }
            }
        }
    }

    fun redeemVipCode(code: String) {
        val credential = token ?: return
        viewModelScope.launch { runCatching { backend.redeemVipCode(state.value.baseUrl, credential, code) }
            .onSuccess { user -> mutableState.value = state.value.copy(user = user, message = "兑换成功"); refreshVip() }
            .onFailure { updateMessage(friendly(it)) } }
    }

    fun shutdownTvs() = sendBatchCommand(
        "power",
        JSONObject().put("action", "shutdown"),
        "远程关机",
    )

    fun sendMediaAction(action: String) {
        if (state.value.offlineMode) {
            sendLanTransport(action)
            return
        }
        if (action == "stop") {
            stopCasting()
        } else {
            sendBatchCommand("media", JSONObject().put("action", action), "播放控制")
        }
    }

    fun stopCasting() {
        if (state.value.offlineMode) {
            sendLanTransport("stop")
            return
        }
        sendBatchCommand("media", JSONObject().put("action", "stop"), "终止投屏") {
            MediaShareManager.stop(getApplication())
        }
    }

    fun switchDecoder(mode: String) =
        if (!requireEntitlement("remote_control", "远程切换电视播放内核")) {
            Unit
        } else {
            sendBatchCommand("switch", JSONObject().put("mode", mode), "切换解码")
        }

    fun castUrl(url: String) {
        val source = url.trim()
        val parsed = runCatching { Uri.parse(source) }.getOrNull()
        if (parsed?.scheme !in setOf("http", "https") || parsed?.host.isNullOrBlank()) {
            updateMessage("请输入正确的 HTTP、HTTPS 或 M3U8 地址")
            return
        }
        val mime = if (source.substringBefore('?').endsWith(".m3u8", true)) {
            "application/vnd.apple.mpegurl"
        } else ""
        if (state.value.offlineMode) {
            val host = state.value.selectedLanHost
            if (host.isNullOrBlank()) {
                updateMessage("请先选择局域网电视")
                return
            }
            viewModelScope.launch {
                runCatching { dlna.playUrl(host, source, mime.ifBlank { "video/mp4" }) }
                    .onSuccess { ok -> updateMessage(if (ok) "已发送到局域网电视" else "电视当前不可用") }
                    .onFailure { updateMessage(friendly(it)) }
            }
            return
        }
        if (!requireEntitlement("remote_cast", "公网远程投屏")) return
        sendBatchCommand(
            "media",
            JSONObject().put("action", "play_url").put("url", source).put("metadata", mime),
            "网络视频投屏",
        )
    }

    fun castHostedUrl(url: String) {
        val source = url.trim()
        val parsed = runCatching { Uri.parse(source) }.getOrNull()
        if (parsed?.scheme !in setOf("http", "https") || parsed?.host.isNullOrBlank()) {
            updateMessage("请输入正确的 HTTP、HTTPS 或 M3U8 地址")
            return
        }
        if (!requireEntitlement("remote_cast", "使用后端 DPlayer 投屏")) return
        if (!requireEntitlement("relay_access", "使用后端 DPlayer 投屏")) return
        val credential = token ?: return
        val targets = controllableTargets() ?: return
        val mime = if (source.substringBefore('?').endsWith(".m3u8", true)) {
            "application/vnd.apple.mpegurl"
        } else ""
        viewModelScope.launch {
            mutableState.value = state.value.copy(busy = true, message = "正在创建 DPlayer 播放会话…")
            runCatching {
                backend.createHostedPlayerSession(
                    state.value.baseUrl, credential, source, mime,
                )
            }.onSuccess { session ->
                val payload = JSONObject()
                    .put("action", "play_hosted")
                    .put("player_path", session.playerPath)
                sendToTargets(targets, "media", payload, "后端 DPlayer 投屏")
                mutableState.value = state.value.copy(busy = false)
            }.onFailure { fail(it) }
        }
    }

    fun castLocalVideo(uri: Uri) {
        shareLocal(listOf(uri), slideshowSeconds = null)
    }

    fun castImages(uris: List<Uri>, slideshowSeconds: Int) {
        if (uris.isEmpty()) {
            updateMessage("请至少选择一张图片")
            return
        }
        shareLocal(uris, slideshowSeconds.coerceIn(2, 60))
    }

    fun uploadAndInstall(uri: Uri) {
        if (!requireEntitlement("app_install", "公网远程安装应用")) return
        val credential = token ?: return
        val targets = controllableTargets() ?: return
        viewModelScope.launch {
            mutableState.value = state.value.copy(busy = true, message = "正在上传安装包…")
            runCatching { backend.uploadApk(state.value.baseUrl, credential, uri) }
                .onSuccess { apk ->
                    val incompatible = if (apk.minSdk > 0) {
                        targets.filter { it.androidApi > 0 && it.androidApi < apk.minSdk }
                    } else {
                        emptyList()
                    }
                    incompatible.forEach { device ->
                        addCommandStatus(CommandStatus(
                            0,
                            "${device.name}：安装包最低需要 API ${apk.minSdk}，电视为 Android ${device.androidRelease} / API ${device.androidApi}",
                            "failed",
                            false,
                        ))
                    }
                    val compatible = targets - incompatible.toSet()
                    if (compatible.isEmpty()) {
                        mutableState.value = state.value.copy(
                            busy = false,
                            message = "安装包与所选电视系统不兼容，未发送安装指令",
                        )
                        return@onSuccess
                    }
                    mutableState.value = state.value.copy(message = "上传完成，正在通知电视安装…")
                    val payload = JSONObject()
                        .put("url", apk.url)
                        .put("sha256", apk.sha256)
                        .put("filename", apk.filename)
                        .put("package_name", apk.packageName)
                        .put("min_sdk", apk.minSdk)
                        .put("size", apk.size)
                    sendToTargets(compatible, "install", payload, "安装 ${apk.filename}")
                    mutableState.value = state.value.copy(busy = false)
                }
                .onFailure { fail(it) }
        }
    }

    fun dismissMessage() {
        mutableState.value = state.value.copy(message = "")
    }

    fun dismissEntitlementPrompt() {
        mutableState.value = state.value.copy(entitlementPrompt = null)
    }

    fun openMembershipCenter() {
        mutableState.value = state.value.copy(
            section = AppSection.PROFILE,
            entitlementPrompt = null,
            membershipCenterRequest = state.value.membershipCenterRequest + 1L,
        )
        refreshVip()
    }

    private fun restoreSession() {
        viewModelScope.launch {
            val savedToken = withContext(Dispatchers.IO) { tokenStore.readToken() }
            if (savedToken == null) {
                mutableState.value = state.value.copy(restoring = false)
                return@launch
            }
            runCatching { backend.getCurrentUser(tokenStore.baseUrl, savedToken) }
                .onSuccess { user ->
                    token = savedToken
                    mutableState.value = state.value.copy(
                        restoring = false,
                        authenticated = true,
                        user = user,
                    )
                    refreshDevices(showBusy = true)
                    startRefreshLoop()
                    checkMobileUpdate()
                }
                .onFailure {
                    tokenStore.clearSession()
                    mutableState.value = state.value.copy(restoring = false, message = "登录已失效，请重新登录")
                }
        }
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                delay(8_000)
                refreshDevices()
                checkMobileUpdate()
            }
        }
    }

    private fun connectControl(deviceId: String?) {
        val credential = token ?: return
        if (deviceId == null) {
            disconnectControl()
            mutableState.value = state.value.copy(socketConnected = false, snapshot = null,
                hasPreviewFrame = false, snapshotError = "")
            return
        }
        if (deviceId == connectedDeviceId && controlSocket != null) return
        disconnectControl()
        val generation = connectionGeneration.incrementAndGet()
        latestJpeg = null
        lastFrameAt = 0L
        mutableState.value = state.value.copy(socketConnected = false, snapshot = null,
            hasPreviewFrame = false, snapshotError = "")
        connectedDeviceId = deviceId
        controlSocket = backend.controlSocket(
            state.value.baseUrl,
            credential,
            deviceId,
            object : ControlSocket.Listener {
                override fun onState(connected: Boolean, message: String) {
                    viewModelScope.launch {
                        if (generation != connectionGeneration.get()) return@launch
                        mutableState.value = state.value.copy(socketConnected = connected, message = message)
                        if (connected) {
                            restartSnapshotLoop()
                        } else {
                            snapshotJob?.cancel()
                            snapshotJob = null
                            snapshotAwaiting = false
                            controlSocket = null
                            connectedDeviceId = null
                            scheduleControlReconnect(generation)
                        }
                    }
                }

                override fun onMessage(message: JSONObject) {
                    viewModelScope.launch { handleSocketMessage(generation, message) }
                }

                override fun onJpeg(bytes: ByteArray) {
                    if (generation == connectionGeneration.get()) publishJpeg(bytes)
                }

                override fun onUnauthorized() {
                    viewModelScope.launch { logout() }
                }
            },
        )
        startP2P(deviceId, generation)
    }

    private fun startP2P(deviceId: String, generation: Long) {
        if (state.value.controlChannelMode == ControlChannelMode.RELAY) return
        if (!state.value.entitlementEnabled("p2p_access")
            || !state.value.entitlementEnabled("screen_view")
            || !state.value.entitlementEnabled("remote_control")) return
        val credential = token ?: return
        viewModelScope.launch {
            val requestedMode = if (state.value.controlChannelMode == ControlChannelMode.P2P) "webrtc" else "auto"
            runCatching { backend.createP2PSession(state.value.baseUrl, credential, deviceId, requestedMode) }
                .onSuccess { session ->
                    if (generation != connectionGeneration.get()) return@onSuccess
                    if (session.selectedMode != "udp_jpeg") {
                        p2pChannel?.close()
                        p2pChannel = null
                        udpConnected = false
                        updateDirectState(generation)
                        if (state.value.controlChannelMode == ControlChannelMode.P2P) {
                            updateMessage("电视当前无法建立 P2P 直连，请检查公网 UDP 18001 和电视在线状态")
                        }
                        return@onSuccess
                    }
                    p2pChannel?.close()
                    jpegTcpClient?.close()
                    jpegTcpClient = null
                    p2pSession = session
                    p2pChannel = P2PControlChannel(object : P2PControlChannel.Listener {
                        override fun onState(connected: Boolean) {
                            udpConnected = connected
                            updateDirectState(generation)
                        }
                        override fun onJpeg(bytes: ByteArray) {
                            if (!tcpConnected && generation == connectionGeneration.get()) publishJpeg(bytes)
                        }
                        override fun onKeyResult(
                            key: String,
                            acknowledged: Boolean,
                            success: Boolean,
                            message: String,
                        ) {
                            if (generation != connectionGeneration.get()) return
                            viewModelScope.launch {
                                if (success) {
                                    addCommandStatus(CommandStatus(0, "P2P 遥控：$message", "completed", true))
                                    updateMessage(message)
                                    return@launch
                                }
                                if (!acknowledged
                                    && state.value.controlChannelMode == ControlChannelMode.AUTO
                                    && state.value.entitlementEnabled("relay_access")
                                ) {
                                    val target = state.value.devices.firstOrNull { it.deviceId == deviceId }
                                    if (target != null) {
                                        addCommandStatus(CommandStatus(0, "$message，正在切换后端回退", "started", null))
                                        sendToTargets(
                                            listOf(target),
                                            "key",
                                            JSONObject().put("key", key),
                                            "后端回退遥控",
                                        )
                                        return@launch
                                    }
                                }
                                addCommandStatus(CommandStatus(0, "P2P 遥控：$message", "failed", false))
                                updateMessage(message)
                            }
                        }
                    }).also {
                        it.start(session)
                        it.setStreaming(shouldStreamPreview())
                    }
                    if (shouldStreamPreview()) startLanStream(session, generation)
                    p2pRenewJob?.cancel()
                    p2pRenewJob = viewModelScope.launch {
                        val renewIn = (session.expiresAt * 1000L - System.currentTimeMillis() - 15_000L)
                            .coerceAtLeast(10_000L)
                        delay(renewIn)
                        if (generation == connectionGeneration.get() && state.value.authenticated) {
                            startP2P(deviceId, generation)
                        }
                    }
                }
                .onFailure { error ->
                    if (generation == connectionGeneration.get()) {
                        val message = friendly(error)
                        if (state.value.controlChannelMode == ControlChannelMode.P2P) {
                            updateMessage("P2P 会话申请失败：$message")
                        } else if (state.value.entitlementEnabled("relay_access")) {
                            updateMessage("P2P 会话申请失败，已保留后端回退通道：$message")
                        }
                    }
                }
        }
    }

    private fun startLanStream(session: app.xingmu.remote.data.P2PSession, generation: Long) {
        jpegTcpClient?.close()
        jpegTcpClient = JpegTcpClient(object : JpegTcpClient.Listener {
            override fun onState(connected: Boolean) {
                tcpConnected = connected
                updateDirectState(generation)
            }

            override fun onJpeg(bytes: ByteArray) {
                if (generation == connectionGeneration.get()) publishJpeg(bytes)
            }
        }).also { it.start(session) }
    }

    private fun updateDirectState(generation: Long) {
        viewModelScope.launch {
            if (generation == connectionGeneration.get()) {
                mutableState.value = state.value.copy(
                    p2pConnected = udpConnected,
                    lanStreamConnected = tcpConnected,
                )
            }
        }
    }

    private fun publishJpeg(bytes: ByteArray) {
        if (bytes.size !in 4..2_000_000) return
        latestJpeg = bytes
        lastFrameAt = SystemClock.elapsedRealtime()
        snapshotAwaiting = false
        frameListeners.forEach { listener -> runCatching { listener(bytes) } }
        if (!state.value.hasPreviewFrame || state.value.snapshotError.isNotEmpty()) {
            viewModelScope.launch {
                mutableState.value = state.value.copy(hasPreviewFrame = true, snapshotError = "")
            }
        }
    }

    private fun disconnectControl() {
        connectionGeneration.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        p2pRenewJob?.cancel()
        p2pRenewJob = null
        snapshotJob?.cancel()
        snapshotJob = null
        snapshotAwaiting = false
        controlSocket?.stopPreview()
        controlSocket?.close()
        controlSocket = null
        connectedDeviceId = null
        p2pChannel?.close()
        p2pChannel = null
        jpegTcpClient?.close()
        jpegTcpClient = null
        p2pSession = null
        udpConnected = false
        tcpConnected = false
        latestJpeg = null
        lastFrameAt = 0L
        mutableState.value = state.value.copy(p2pConnected = false, lanStreamConnected = false)
    }

    private fun restartSnapshotLoop() {
        snapshotJob?.cancel()
        snapshotJob = null
        snapshotAwaiting = false
        if (!shouldStreamPreview()) {
            controlSocket?.stopPreview()
            p2pChannel?.setStreaming(false)
            jpegTcpClient?.close()
            jpegTcpClient = null
            tcpConnected = false
            return
        }
        controlSocket?.startPreview(10)
        p2pChannel?.setStreaming(true)
        if (jpegTcpClient == null) p2pSession?.let { startLanStream(it, connectionGeneration.get()) }
        snapshotJob = viewModelScope.launch {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameAt >= 2_000L) {
                    controlSocket?.startPreview(10)
                    p2pChannel?.setStreaming(true)
                    if (jpegTcpClient == null) {
                        p2pSession?.let { startLanStream(it, connectionGeneration.get()) }
                    }
                }
                delay(1_000)
            }
        }
    }

    private fun shouldStreamPreview(): Boolean =
        appVisible && state.value.liveScreen && state.value.section == AppSection.REMOTE
            && state.value.entitlementEnabled("screen_view") && state.value.remoteChannelEnabled

    private fun scheduleControlReconnect(generation: Long) {
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            delay(3_000)
            if (generation == connectionGeneration.get() && state.value.authenticated) {
                reconnectJob = null
                connectControl(state.value.primaryDeviceId)
            }
        }
    }

    private suspend fun handleSocketMessage(generation: Long, message: JSONObject) {
        if (generation != connectionGeneration.get()) return
        when (message.optString("type")) {
            "device_event" -> {
                message.optJSONObject("device")?.let { incoming ->
                    val id = incoming.optString("device_id")
                    mutableState.value = state.value.copy(
                        devices = state.value.devices.map {
                            if (it.deviceId == id) incoming.toTvDevice() else it
                        },
                    )
                }
                if (message.optString("event") == "snapshot") {
                    val data = message.optJSONObject("data")
                    val encoded = data?.optString("screen_base64").orEmpty()
                    val captureError = data?.optString("capture_error").orEmpty()
                    val decoded = withContext(Dispatchers.Default) { decodeSnapshotBytes(encoded) }
                    if (generation != connectionGeneration.get()) return
                    mutableState.value = if (decoded != null) {
                        publishJpeg(decoded)
                        state.value.copy(hasPreviewFrame = true, snapshotError = "", lastSnapshotAt = System.currentTimeMillis())
                    } else if (state.value.hasPreviewFrame) {
                        state.value
                    } else {
                        state.value.copy(hasPreviewFrame = false, snapshotError = captureError.ifBlank { "电视画面解码失败" })
                    }
                    snapshotAwaiting = false
                } else if (message.optString("event") == "app_inventory") {
                    val appsDeviceId = state.value.installedAppsDeviceId
                    if (appsDeviceId != null && appsDeviceId == message.optJSONObject("device")?.optString("device_id")) {
                        loadInstalledApps(appsDeviceId)
                    }
                }
            }
            "snapshot_ack" -> {
                if (!message.optBoolean("delivered")) snapshotAwaiting = false
            }
            "command_update" -> message.optJSONObject("command")?.let { updateCommand(it) }
            "presence" -> {
                val id = message.optString("device_id")
                val online = message.optBoolean("online")
                mutableState.value = state.value.copy(
                    devices = state.value.devices.map { if (it.deviceId == id) it.copy(online = online) else it },
                )
            }
            "device_deleted" -> {
                val id = message.optString("device_id")
                val remaining = state.value.devices.filterNot { it.deviceId == id }
                val selection = state.value.selectedDeviceIds - id
                val primary = state.value.primaryDeviceId?.takeIf { it != id && it in selection }
                    ?: selection.firstOrNull()
                mutableState.value = state.value.copy(
                    devices = remaining,
                    selectedDeviceIds = selection,
                    primaryDeviceId = primary,
                    detailDeviceId = state.value.detailDeviceId?.takeIf { it != id },
                )
                connectControl(primary)
            }
        }
    }

    private fun updateCommand(command: JSONObject) {
        val id = command.optLong("id")
        val status = command.optString("status")
        val result = command.optJSONObject("result")
        val message = result?.optString("message")?.takeIf { it.isNotBlank() }
            ?: commandStateLabel(status)
        addCommandStatus(CommandStatus(id, message, status, when (status) {
            "completed" -> true
            "failed" -> false
            else -> null
        }))
    }

    private fun loadInstalledApps(deviceId: String) {
        val credential = token ?: return
        viewModelScope.launch { loadInstalledAppsNow(deviceId, credential) }
    }

    private suspend fun loadInstalledAppsNow(deviceId: String, credential: String) {
        runCatching { backend.listInstalledApps(state.value.baseUrl, credential, deviceId) }
            .onSuccess { apps ->
                if (state.value.installedAppsDeviceId == deviceId) {
                    mutableState.value = state.value.copy(installedApps = apps, appsLoading = false)
                }
            }
            .onFailure {
                if (state.value.installedAppsDeviceId == deviceId) {
                    mutableState.value = state.value.copy(appsLoading = false, message = friendly(it))
                }
            }
    }

    private fun decodeSnapshotBytes(dataUri: String): ByteArray? = runCatching {
        require(dataUri.length in 1..2_000_000)
        val raw = dataUri.substringAfter(',', dataUri)
        Base64.decode(raw, Base64.DEFAULT)
    }.getOrNull()

    private fun sendBatchCommand(
        type: String,
        payload: JSONObject,
        label: String,
        after: (() -> Unit)? = null,
    ) {
        val targets = controllableTargets() ?: return
        viewModelScope.launch {
            sendToTargets(targets, type, payload, label)
            after?.invoke()
        }
    }

    private suspend fun sendToTargets(
        targets: List<TvDevice>,
        type: String,
        payload: JSONObject,
        label: String,
    ) {
        val credential = token ?: return
        var successCount = 0
        for (device in targets) {
            val direct = if (type == "media") {
                runCatching { sendMediaDirect(device, payload) }.getOrDefault(false)
            } else false
            if (direct) {
                successCount++
                addCommandStatus(CommandStatus(0, "$label · ${device.name}：局域网直连成功", "completed", true))
                continue
            }
            runCatching {
                backend.sendCommand(state.value.baseUrl, credential, device.deviceId, type, JSONObject(payload.toString()))
            }.onSuccess { receipt ->
                successCount++
                receiptStatus(receipt, "$label · ${device.name}")
            }.onFailure { error ->
                addCommandStatus(CommandStatus(0, "${device.name}：${friendly(error)}", "failed", false))
            }
        }
        mutableState.value = state.value.copy(message = "已向 $successCount/${targets.size} 台电视发送")
    }

    private suspend fun sendMediaDirect(device: TvDevice, payload: JSONObject): Boolean {
        if (device.lanIp.isBlank()) return false
        return when (payload.optString("action")) {
            "play_url" -> dlna.playUrl(
                device.lanIp,
                payload.optString("url"),
                payload.optString("metadata"),
            )
            else -> false
        }
    }

    private fun receiptStatus(receipt: CommandReceipt, label: String) {
        addCommandStatus(
            CommandStatus(
                receipt.id,
                "$label：${commandStateLabel(receipt.status)}",
                receipt.status,
                null,
            ),
        )
    }

    private fun commandStateLabel(status: String) = when (status) {
        "pending" -> "已加入队列"
        "delivered" -> "电视已收到"
        "started" -> "电视正在执行"
        "completed" -> "执行成功"
        "failed" -> "执行失败"
        else -> status
    }

    private fun shareLocal(uris: List<Uri>, slideshowSeconds: Int?) {
        if (state.value.offlineMode) {
            val host = state.value.selectedLanHost
            val device = state.value.lanDevices.firstOrNull { it.host == host }
            if (host.isNullOrBlank() || device == null) {
                updateMessage("请先选择局域网电视")
                return
            }
            offlineSlideshowJob?.cancel()
            offlineSlideshowJob = viewModelScope.launch {
                mutableState.value = state.value.copy(busy = true, message = "正在准备局域网投屏…")
                runCatching {
                    withContext(Dispatchers.IO) {
                        MediaShareManager.share(getApplication(), uris, setOf(host), device.name)
                    }
                }.onSuccess { shared ->
                    mutableState.value = state.value.copy(busy = false)
                    if (slideshowSeconds != null && shared.size > 1) {
                        updateMessage("局域网幻灯片已开始")
                        while (true) {
                            for (item in shared) {
                                dlna.playUrl(host, item.url, item.mimeType)
                                delay(slideshowSeconds * 1000L)
                            }
                        }
                    } else {
                        val item = shared.first()
                        dlna.playUrl(host, item.url, item.mimeType)
                        updateMessage("已发送到 ${device.name}")
                    }
                }.onFailure { fail(it) }
            }
            return
        }
        val targets = controllableTargets() ?: return
        viewModelScope.launch {
            mutableState.value = state.value.copy(busy = true, message = "正在准备局域网投屏…")
            runCatching {
                withContext(Dispatchers.IO) {
                    MediaShareManager.share(
                        getApplication(),
                        uris,
                        targets.map { it.lanIp }.toSet(),
                        if (targets.size == 1) targets.first().name else "${targets.size} 台电视",
                    )
                }
            }.onSuccess { shared ->
                val payload = if (slideshowSeconds != null && shared.size > 1) {
                    JSONObject()
                        .put("action", "play_slideshow")
                        .put("urls", JSONArray(shared.map { it.url }))
                        .put("mime_types", JSONArray(shared.map { it.mimeType }))
                        .put("interval_ms", slideshowSeconds * 1000L)
                } else {
                    val first = shared.first()
                    JSONObject()
                        .put("action", "play_url")
                        .put("url", first.url)
                        .put("metadata", first.mimeType)
                }
                sendToTargets(targets, "media", payload, if (shared.size > 1) "图片幻灯片" else "本地文件投屏")
                mutableState.value = state.value.copy(busy = false)
            }.onFailure { fail(it) }
        }
    }

    private fun controllableTargets(): List<TvDevice>? {
        val selected = state.value.selectedDevices
        if (selected.isEmpty()) {
            updateMessage("请先选择电视")
            return null
        }
        val controllable = selected.filter { it.controllable }
        if (controllable.isEmpty()) {
            updateMessage("所选电视当前离线、停用或尚未审核")
            return null
        }
        return controllable
    }

    private fun addCommandStatus(status: CommandStatus) {
        val old = state.value.recentCommands.filterNot { status.commandId > 0 && it.commandId == status.commandId }
        mutableState.value = state.value.copy(recentCommands = (listOf(status) + old).take(8))
    }

    private fun updateMessage(message: String) {
        mutableState.value = state.value.copy(message = message)
    }

    private fun requireEntitlement(key: String, action: String): Boolean {
        if (state.value.entitlementEnabled(key)) return true
        showEntitlementPrompt(key, action)
        return false
    }

    private fun showEntitlementPrompt(key: String, action: String) {
        val capability = state.value.user?.entitlementCatalog?.firstOrNull { it.key == key }
        val label = capability?.label ?: "对应套餐权益"
        val description = capability?.description ?: "当前账号尚未开通这项功能"
        val requirement = capability?.requirement ?: "请选择包含该权益的套餐"
        mutableState.value = state.value.copy(
            entitlementPrompt = EntitlementPrompt(
                key = key,
                title = "$action 暂不可用",
                message = "缺少权益：$label\n$description\n$requirement。局域网基础投屏可在登录页选择“离线使用”。",
            ),
        )
    }

    private fun fail(error: Throwable) {
        mutableState.value = state.value.copy(busy = false, message = friendly(error))
    }

    private fun friendly(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: "操作失败"

    override fun onCleared() {
        backend.setUnauthorizedListener(null)
        vipPaymentJob?.cancel()
        offlineSlideshowJob?.cancel()
        disconnectControl()
        super.onCleared()
    }

    private companion object {
        const val UPDATE_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
        const val MOBILE_UPDATE_TIMEOUT_MS = 10L * 60L * 1000L
    }
}
