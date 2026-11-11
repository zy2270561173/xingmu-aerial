package app.xingmu.remote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.xingmu.remote.ui.BingBackdrop
import app.xingmu.remote.ui.JpegPreviewView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xingmu.remote.update.MobileUpdateInstaller
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            persistReadPermission(it)
            viewModel.castLocalVideo(it)
        }
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach(::persistReadPermission)
        if (uris.isNotEmpty()) viewModel.castImages(uris, pendingSlideshowSeconds)
    }
    private val apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            persistReadPermission(it)
            viewModel.uploadAndInstall(it)
        }
    }
    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(viewModel::claimPairingQr)
    }
    private val avatarPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            persistReadPermission(it)
            viewModel.uploadAvatar(it)
        }
    }
    private var pendingSlideshowSeconds = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StarlightTheme {
                var splashVisible by rememberSaveable { mutableStateOf(true) }
                LaunchedEffect(Unit) { delay(1250); splashVisible = false }
                // Wallpaper behind every screen, phone and splash alike: a black void was the one
                // thing that made this look unlike Aerial.
                Box(Modifier.fillMaxSize()) {
                    BingBackdrop(Modifier.matchParentSize(), scrimTop = 0.55f, scrimBottom = 0.9f)
                    if (splashVisible) BrandSplash() else StarlightApp(
                    viewModel = viewModel,
                    pickVideo = { videoPicker.launch(arrayOf("video/*", "application/vnd.apple.mpegurl")) },
                    pickImages = { seconds ->
                        pendingSlideshowSeconds = seconds
                        imagePicker.launch(arrayOf("image/*"))
                    },
                    pickApk = { apkPicker.launch(arrayOf("application/vnd.android.package-archive")) },
                    pickAvatar = { avatarPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) },
                    scanQr = {
                        qrScanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("扫描电视上的“手机 App 扫码绑定”二维码")
                                .setBeepEnabled(false)
                                .setCaptureActivity(PortraitCaptureActivity::class.java)
                                .setOrientationLocked(true),
                        )
                    },
                )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setAppForeground(true)
    }

    override fun onStop() {
        viewModel.setAppForeground(false)
        super.onStop()
    }

    private fun persistReadPermission(uri: android.net.Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

@Composable
private fun BrandSplash() {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val scale by animateFloatAsState(if (started) 1f else 0.72f, tween(650), label = "logo-scale")
    val alpha by animateFloatAsState(if (started) 1f else 0f, tween(500), label = "logo-alpha")
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale, alpha = alpha)) {
            Surface(Modifier.size(92.dp), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primary) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Icon(Icons.Default.Tv, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Text("星投屏", Modifier.padding(top = 18.dp), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("安徽光秒网络科技有限公司", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One tab of the floating bar: the selected one is a white plate with dark content, the rest are
 * plain glyphs on the glass. Sizes are deliberately small so the plate stays a pill, not a banner.
 */
@Composable
private fun TabItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val plate = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(plate)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(21.dp), tint = tint)
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}
@Composable
private fun StarlightTheme(content: @Composable () -> Unit) {
    app.xingmu.remote.ui.theme.AerialTheme(content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StarlightApp(
    viewModel: MainViewModel,
    pickVideo: () -> Unit,
    pickImages: (Int) -> Unit,
    pickApk: () -> Unit,
    pickAvatar: () -> Unit,
    scanQr: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        if (state.message.isNotBlank()) {
            snackbar.showSnackbar(state.message)
            viewModel.dismissMessage()
        }
    }
    LaunchedEffect(state.readyUpdatePath) {
        val path = state.readyUpdatePath ?: return@LaunchedEffect
        val opened = (context as? Activity)?.let { MobileUpdateInstaller.open(it, path) } == true
        viewModel.consumeUpdateInstaller(opened)
    }
    if (state.restoring) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (!state.authenticated) {
        // Bing's daily photo instead of an empty black screen; the gradient keeps white type legible.
        Box(Modifier.fillMaxSize()) {
            BingBackdrop(Modifier.matchParentSize())
            LoginScreen(state, viewModel::login, viewModel::register, viewModel::enterOfflineMode)
        }
        return
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // Transparent so the Bing backdrop shows through; cards and bars stay opaque on top.
        containerColor = Color.Transparent,
        snackbarHost = {
            // Aerial toast: dark glass pill. Material's default snackbar uses inverseSurface, which
            // is a light slab on a black app and was the big pale bar at the bottom of the screen.
            SnackbarHost(snackbar) { data ->
                androidx.compose.material3.Snackbar(
                    snackbarData = data,
                    containerColor = app.xingmu.remote.ui.theme.AerialPalette.Glass,
                    contentColor = app.xingmu.remote.ui.theme.AerialPalette.Label,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    Column {
                        Text(
                            when (state.section) {
                                AppSection.DEVICES -> "电视设备"
                                AppSection.REMOTE -> if (state.offlineMode) "局域网遥控器" else "远程控制"
                                AppSection.CAST -> "投屏中心"
                                AppSection.APPS -> "电视应用"
                                AppSection.APPROVALS -> "审批设备"
                                AppSection.AGENT -> "Agent 详情"
                                AppSection.PROFILE -> "我的"
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                        )
                        Text(
                            if (state.offlineMode) {
                                state.lanDevices.firstOrNull { it.host == state.selectedLanHost }?.name
                                    ?: "离线局域网模式"
                            } else state.primaryDevice?.name ?: "未选择电视",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (!state.offlineMode) {
                        IconButton(onClick = { viewModel.setSection(AppSection.APPROVALS) }) {
                            Box {
                                Icon(Icons.Default.FactCheck, "审批设备，${state.pendingApprovals.size} 台待处理")
                                if (state.pendingApprovals.isNotEmpty()) {
                                    Surface(
                                        modifier = Modifier.size(16.dp).align(Alignment.TopEnd).offset(x = 6.dp, y = (-5).dp),
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.error,
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(state.pendingApprovals.size.coerceAtMost(9).toString(), color = Color.White, fontSize = 9.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    IconButton(onClick = { viewModel.refreshDevices(true) }) {
                        Icon(Icons.Default.Refresh, "刷新设备")
                    }
                },
            )
        },
        bottomBar = {
            // Floating glass tab bar: a rounded plate inset from the edges, so the wallpaper shows
            // around it. Aerial's surfaces are plates, never a full-width opaque bar.
            Box(
                Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    color = Color(0xCC141416),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TabItem(Icons.Default.Tv, "设备", state.section == AppSection.DEVICES) {
                            viewModel.setSection(AppSection.DEVICES)
                        }
                        TabItem(Icons.Default.PowerSettingsNew, "遥控", state.section == AppSection.REMOTE) {
                            viewModel.setSection(AppSection.REMOTE)
                        }
                        TabItem(Icons.Default.Cast, "投屏", state.section == AppSection.CAST) {
                            viewModel.setSection(AppSection.CAST)
                        }
                        if (!state.offlineMode) {
                            TabItem(Icons.Default.Apps, "应用", state.section == AppSection.APPS) {
                                viewModel.setSection(AppSection.APPS)
                            }
                            TabItem(
                                Icons.Default.Person,
                                "我的",
                                state.section in setOf(AppSection.PROFILE, AppSection.AGENT, AppSection.APPROVALS),
                            ) { viewModel.setSection(AppSection.PROFILE) }
                        }
                    }
                }
            }
        },
    ) { padding ->
        when (state.section) {
            AppSection.DEVICES -> if (state.offlineMode) OfflineDevicesScreen(state, viewModel, padding) else DevicesScreen(state, viewModel, padding, scanQr)
            AppSection.REMOTE -> if (state.offlineMode) OfflineRemoteScreen(state, viewModel, padding) else RemoteScreen(state, viewModel, padding)
            AppSection.APPS -> TvAppsScreen(state, viewModel, padding)
            AppSection.CAST -> CastScreen(state, viewModel, padding, pickVideo, pickImages, pickApk)
            AppSection.APPROVALS -> ApprovalsScreen(state, viewModel, padding)
            AppSection.AGENT -> AgentScreen(state, viewModel, padding)
            AppSection.PROFILE -> ProfileScreen(state, viewModel, padding, pickAvatar, scanQr)
        }
    }
    state.entitlementPrompt?.let { prompt ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            onDismissRequest = viewModel::dismissEntitlementPrompt,
            title = { Text(prompt.title) },
            text = { Text(prompt.message) },
            confirmButton = {
                Button(onClick = viewModel::openMembershipCenter) { Text("查看套餐与权益") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissEntitlementPrompt) { Text("暂不处理") }
            },
        )
    }
    state.availableUpdate?.let { release ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            onDismissRequest = viewModel::dismissMobileUpdate,
            title = { Text("发现新版本 ${release.versionName}") },
            text = {
                Column {
                    Text(release.releaseNotes.ifBlank { "稳定性与体验优化" })
                    Text(
                        "安装包 ${formatSize(release.size)} · 当前 ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            },
            confirmButton = {
                Button(onClick = viewModel::downloadMobileUpdate, enabled = !state.updateDownloading) {
                    if (state.updateDownloading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(if (state.updateDownloading) "下载中" else "下载并安装")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissMobileUpdate, enabled = !state.updateDownloading) {
                    Text("稍后")
                }
            },
        )
    }
}

@Composable
private fun LoginScreen(
    state: AppUiState,
    login: (String, String, String) -> Unit,
    register: (String, String, String, String, String) -> Unit,
    offline: () -> Unit,
) {
    var baseUrl by rememberSaveable { mutableStateOf(state.baseUrl) }
    var username by rememberSaveable { mutableStateOf(state.username) }
    var password by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var registering by rememberSaveable { mutableStateOf(false) }
    var developerTaps by rememberSaveable { mutableStateOf(0) }
    var lastDeveloperTapAt by rememberSaveable { mutableStateOf(0L) }
    var showBackendSettings by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Cast, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(18.dp))
        Text("连接控制中心", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (registering) "创建家庭账号后扫码绑定电视" else "使用账号或手机号登录",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 22.dp),
        )
        Text(
            "开发者：慕沄",
            modifier = Modifier.clickable {
                val now = android.os.SystemClock.elapsedRealtime()
                developerTaps = if (now - lastDeveloperTapAt > 5_000L) 1 else developerTaps + 1
                lastDeveloperTapAt = now
                if (developerTaps >= 10) {
                    developerTaps = 0
                    showBackendSettings = true
                }
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showBackendSettings) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("开发者后端设置") },
                placeholder = { Text(BuildConfig.DEFAULT_BACKEND_URL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(if (registering) "登录账号" else "账号或手机号") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (registering) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("昵称") },
                placeholder = { Text("例如 爸爸") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.filter { character -> character.isDigit() || character == '+' } },
                label = { Text("手机号") },
                placeholder = { Text("可用于登录和显示设备归属") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                if (registering) register(baseUrl, username, password, nickname, phone)
                else login(baseUrl, username, password)
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(if (registering) "注册并登录" else "登录")
        }
        TextButton(
            onClick = { registering = !registering },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (registering) "已有账号？返回登录" else "没有账号？注册")
        }
        if (!registering) {
            OutlinedButton(onClick = offline, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Tv, null)
                Spacer(Modifier.width(8.dp))
                Text("离线使用")
            }
            Text(
                "仅提供投屏中心、局域网设备发现和局域网播放遥控；不连接后端，也不显示电视实时画面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (state.message.isNotBlank()) {
            Text(
                state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun OfflineDevicesScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    padding: PaddingValues,
) {
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("离线局域网模式", fontWeight = FontWeight.Bold)
                Text("只发现同一 Wi-Fi 下的投屏设备，不读取账号、Agent 或电视画面。", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("局域网设备", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            FilledTonalButton(onClick = viewModel::discoverLanDevices, enabled = !state.lanDiscovering) {
                if (state.lanDiscovering) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(6.dp)); Text("刷新")
            }
        }
        if (state.lanDevices.isEmpty() && !state.lanDiscovering) {
            EmptyState(Modifier.fillMaxWidth(), "未发现局域网电视", "确认手机与电视连接同一 Wi-Fi，并在电视开启投屏接收。", viewModel::discoverLanDevices)
        }
        state.lanDevices.forEach { device ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.selectLanDevice(device.host) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.selectedLanHost == device.host, onClick = { viewModel.selectLanDevice(device.host) })
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(device.name, fontWeight = FontWeight.SemiBold)
                        Text(device.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.Cast, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        OutlinedButton(onClick = viewModel::exitOfflineMode, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Logout, null); Spacer(Modifier.width(8.dp)); Text("退出离线模式并登录")
        }
    }
}

@Composable
private fun OfflineRemoteScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    padding: PaddingValues,
) {
    val device = state.lanDevices.firstOrNull { it.host == state.selectedLanHost }
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Default.Tv, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
        Text(device?.name ?: "尚未选择电视", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            if (device?.remoteControlUrl?.isNotBlank() == true)
                "已连接星投屏局域网遥控入口。方向键、返回、主页、音量和播放按键均直接发送到电视，不经过后端。"
            else "当前设备只提供 DLNA 播放控制。更新并运行星投屏 TV 后，重新搜索即可使用完整局域网按键遥控。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        QuickKeys(viewModel::sendKey)
        DirectionPad(viewModel::sendKey)
        MediaKeys(viewModel::sendKey)
        NumberKeys(viewModel::sendKey)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { viewModel.sendLanTransport("play") }, enabled = device != null, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PlayArrow, null); Text("播放") }
            FilledTonalButton(onClick = { viewModel.sendLanTransport("pause") }, enabled = device != null, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Pause, null); Text("暂停") }
        }
        Button(onClick = { viewModel.sendLanTransport("stop") }, enabled = device != null, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Stop, null); Spacer(Modifier.width(7.dp)); Text("停止投屏")
        }
    }
}

@Composable
private fun DevicesScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    padding: PaddingValues,
    scanQr: () -> Unit,
) {
    if (state.devices.isEmpty() && !state.busy) {
        EmptyState(
            modifier = Modifier.padding(padding),
            title = "还没有电视设备",
            detail = "先在电视上打开星投屏并完成后台审核",
            onRefresh = { viewModel.refreshDevices(true) },
            secondaryLabel = "扫码绑定电视",
            onSecondary = scanQr,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // The screen title already sits in the top bar; repeating it here made every
                // screen start with the same words twice.
                Text(
                    "已选择 ${state.selectedDeviceIds.size} 台，遥控和投屏可批量发送",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.busy || state.lanDiscovering) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    FilledTonalButton(
                        onClick = scanQr,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(Icons.Default.QrCodeScanner, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("扫码绑定")
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = viewModel::discoverLanDevices,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(Icons.Default.Cast, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("局域网搜索")
                    }
                }
            }
        }
        items(state.devices, key = { it.deviceId }) { device ->
            DeviceItem(
                device = device,
                selected = device.deviceId in state.selectedDeviceIds,
                primary = device.deviceId == state.primaryDeviceId,
                lanDirect = state.lanDevices.any { it.host == device.lanIp },
                onToggle = { viewModel.toggleDevice(device.deviceId) },
                onPrimary = { viewModel.makePrimary(device.deviceId) },
                onDetails = { viewModel.showDeviceDetails(device.deviceId) },
            )
        }
        if (state.lanDevices.isNotEmpty()) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("局域网发现", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            items(state.lanDevices, key = { "lan:${it.udn}:${it.location}" }) { device ->
                val bound = state.devices.any { it.lanIp == device.host }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(38.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Icon(
                                Icons.Default.Cast,
                                null,
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            device.host,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (bound) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (bound) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Text(
                            if (bound) "已匹配" else "未绑定",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun DeviceItem(
    device: app.xingmu.remote.data.TvDevice,
    selected: Boolean,
    primary: Boolean,
    lanDirect: Boolean,
    onToggle: () -> Unit,
    onPrimary: () -> Unit,
    onDetails: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDetails),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            // Solid on purpose: a translucent selected fill would let the wallpaper show through.
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
        ),
        border = androidx.compose.foundation.BorderStroke(
            if (primary) 1.5.dp else 1.dp,
            if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Default.Circle,
                        null,
                        Modifier.size(9.dp),
                        tint = if (device.online) app.xingmu.remote.ui.theme.AerialPalette.Green else MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    listOf(device.model, device.lanIp, "v${device.appVersion}").filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Text(
                    deviceStatus(device),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (device.controllable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text(
                    "遥控：${controlModeLabel(device.controlMode)} · 采集：${captureModeLabel(device.captureMode)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                if (lanDirect) {
                    Text("局域网直连可用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                Text(
                    "点开查看 Agent 完整上报",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (selected) {
                if (primary) {
                    Icon(Icons.Default.CheckCircle, "当前主控", tint = MaterialTheme.colorScheme.primary)
                } else {
                    OutlinedButton(onClick = onPrimary, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text("设为主控")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceDetailDialog(
    device: app.xingmu.remote.data.TvDevice,
    busy: Boolean,
    apps: List<app.xingmu.remote.data.InstalledTvApp>,
    appsLoading: Boolean,
    onDismiss: () -> Unit,
    onRemote: () -> Unit,
    onSettings: (String) -> Unit,
    onDelete: () -> Unit,
    onRefreshApps: () -> Unit,
    onLaunchApp: (app.xingmu.remote.data.InstalledTvApp) -> Unit,
    onForeground: () -> Unit,
) {
    var confirmingDelete by remember(device.deviceId) { mutableStateOf(false) }
    var appSearch by remember(device.deviceId) { mutableStateOf("") }
    val filteredApps = remember(apps, appSearch) {
        val query = appSearch.trim().lowercase()
        if (query.isBlank()) apps else apps.filter {
            it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
        }
    }
    val visibleApps = filteredApps.take(120)
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(device.name)
                Text(device.deviceId, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                DetailRow("连接", if (device.online) "Agent 在线" else "Agent 离线")
                DetailRow("审核", deviceStatus(device))
                DetailRow("型号", device.model.ifBlank { "未上报" })
                DetailRow("系统", if (device.androidApi > 0) "Android ${device.androidRelease} / API ${device.androidApi}" else "未上报")
                DetailRow("Agent", device.appVersion.ifBlank { "未上报" })
                DetailRow("局域网", device.lanIp.ifBlank { "未上报" })
                DetailRow("NAT", device.natType.ifBlank { "未上报" })
                DetailRow("当前应用", device.foregroundPackage.ifBlank { device.currentActivity.ifBlank { "未上报" } })
                DetailRow("遥控", controlModeLabel(device.controlMode))
                DetailRow("画面采集", captureModeLabel(device.captureMode))
                systemMemoryLabel(device)?.let { DetailRow("内存", it.removePrefix("内存 ")) }
                if (device.storageAvailableBytes >= 0 && device.storageTotalBytes > 0) {
                    DetailRow("存储", "${formatGb(device.storageAvailableBytes)} 可用 / ${formatGb(device.storageTotalBytes)}")
                }
                if (device.agentMetadata.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text("Agent 原始上报", fontWeight = FontWeight.SemiBold)
                    device.agentMetadata.toSortedMap().forEach { (key, value) ->
                        DetailRow(metadataLabel(key), value.ifBlank { "未上报" })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("盒子控制权限", fontWeight = FontWeight.SemiBold)
                Text(
                    "新版电视端在 Android 13 以上使用系统级方向键，无需 Root。若厂商隐藏无线调试，普通 App 不能强开，只能使用无障碍、厂商工程菜单或刷入允许调试的固件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp),
                )
                OutlinedButton(
                    onClick = { onSettings("accessibility") },
                    enabled = device.controllable,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) { Icon(Icons.Default.Settings, null); Spacer(Modifier.width(7.dp)); Text("打开电视无障碍设置") }
                OutlinedButton(
                    onClick = { onSettings("wireless_debugging") },
                    enabled = device.controllable,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Icon(Icons.Default.Settings, null); Spacer(Modifier.width(7.dp)); Text("打开电视无线调试 / 开发者选项") }
                Button(
                    onClick = onForeground,
                    enabled = device.controllable,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Icon(Icons.Default.Home, null); Spacer(Modifier.width(7.dp)); Text("唤醒星投屏前台") }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Apps, null, tint = MaterialTheme.colorScheme.primary)
                    Text("电视应用（${apps.size}）", Modifier.weight(1f).padding(start = 7.dp), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onRefreshApps, enabled = device.controllable && !appsLoading) {
                        if (appsLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, "重新读取应用")
                    }
                }
                OutlinedTextField(
                    value = appSearch,
                    onValueChange = { appSearch = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    placeholder = { Text("搜索名称或包名") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                )
                if (filteredApps.isEmpty()) {
                    Text(
                        if (appsLoading) "正在读取电视应用…" else "暂无应用清单，点击刷新后由 Agent 上报",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Empty states read as a centred aside, the way Aerial places its hints.
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                } else {
                    if (filteredApps.size > visibleApps.size) {
                        Text(
                            "共匹配 ${filteredApps.size} 个，当前显示前 ${visibleApps.size} 个；输入名称或包名可继续筛选",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    visibleApps.forEach { app ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ApplicationIcon(app)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(
                                    app.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    listOfNotNull(
                                        app.versionName.takeIf { it.isNotBlank() }?.let { "版本 $it" }
                                            ?: app.versionCode.takeIf { it > 0 }?.let { "版本码 $it" },
                                        if (app.system) "系统应用" else "用户应用",
                                        if (!app.enabled) "已停用" else if (!app.launchable) "无启动入口" else null,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FilledTonalIconButton(
                                onClick = { onLaunchApp(app) },
                                enabled = device.controllable && app.enabled && app.launchable,
                            ) { Icon(Icons.Default.PlayArrow, "在电视打开") }
                        }
                        HorizontalDivider()
                    }
                }
                Button(
                    onClick = onRemote,
                    enabled = device.controllable,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) { Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(7.dp)); Text("查看快照画面并遥控") }
                TextButton(
                    onClick = { confirmingDelete = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(7.dp)); Text("删除这台旧设备") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
    if (confirmingDelete) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除 ${device.name}？") },
            text = { Text("设备记录、历史指令和上报日志会一起删除。仍安装着 Agent 的电视可重新登记。") },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }, enabled = !busy) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun TvAppsScreen(state: AppUiState, viewModel: MainViewModel, padding: PaddingValues) {
    val device = state.primaryDevice
    var search by rememberSaveable(device?.deviceId) { mutableStateOf("") }
    val source = if (state.installedAppsDeviceId == device?.deviceId) state.installedApps else emptyList()
    val filtered = remember(source, search) {
        val query = search.trim().lowercase()
        if (query.isBlank()) source else source.filter {
            it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        device?.let { "${it.name} · ${source.size} 个应用" } ?: "请先在设备页选择主控电视",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { device?.let { viewModel.refreshInstalledApps(it.deviceId) } },
                    enabled = device?.controllable == true && !state.appsLoading,
                ) {
                    if (state.appsLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, "重新读取电视应用")
                }
            }
        }
        if (device == null) {
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
                    Text("没有主控电视，请先返回设备页选择一台在线电视。", Modifier.padding(18.dp))
                }
            }
        } else {
            item {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    // Solid tint: a translucent container would let the wallpaper bleed through.
                    color = if (device.controllable) Color(0xFF16251B) else Color(0xFF2A1512),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tv, null)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(if (device.controllable) "Agent 在线，可远程打开应用" else "Agent 当前不可控制", fontWeight = FontWeight.SemiBold)
                            Text(
                                "${controlModeLabel(device.controlMode)} · ${captureModeLabel(device.captureMode)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { viewModel.openTvForeground(device.deviceId) }, enabled = device.controllable) {
                            Text("唤醒")
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索应用名称或包名") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                )
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        if (state.appsLoading) "正在读取电视应用…" else "暂无应用清单，点击右上角刷新",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(filtered, key = { it.packageName }) { app ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ApplicationIcon(app)
                        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                            Text(app.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(
                                    app.versionName.takeIf { it.isNotBlank() }?.let { "版本 $it" }
                                        ?: app.versionCode.takeIf { it > 0 }?.let { "版本码 $it" },
                                    if (app.system) "系统应用" else "用户应用",
                                    if (!app.enabled) "已停用" else if (!app.launchable) "无启动入口" else "可打开",
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        FilledTonalIconButton(
                            onClick = { viewModel.launchInstalledApp(device.deviceId, app) },
                            enabled = device.controllable && app.enabled && app.launchable,
                        ) { Icon(Icons.Default.PlayArrow, "在电视打开 ${app.label}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApplicationIcon(app: app.xingmu.remote.data.InstalledTvApp) {
    val bitmap = remember(app.iconDataUri) {
        runCatching {
            val encoded = app.iconDataUri.substringAfter(',', "")
            require(encoded.isNotBlank() && encoded.length <= 100_000)
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    Surface(
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (bitmap != null) {
            Image(bitmap, app.label, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Apps, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProfileScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    padding: PaddingValues,
    pickAvatar: () -> Unit,
      scanQr: () -> Unit,
) {
    val user = state.user ?: return
    val primaryDevice = state.primaryDevice
    var editOpen by rememberSaveable { mutableStateOf(false) }
    var passwordOpen by rememberSaveable { mutableStateOf(false) }
    var unbindOpen by rememberSaveable { mutableStateOf(false) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    var vipOpen by rememberSaveable { mutableStateOf(false) }
    var selectedPaymentPlan by remember { mutableStateOf<app.xingmu.remote.data.VipPlan?>(null) }
    var redeemCode by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable(user.id, user.nickname) { mutableStateOf(user.nickname) }
    var phone by rememberSaveable(user.id, user.phone) { mutableStateOf(user.phone) }
    var oldPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.membershipCenterRequest) {
        if (state.membershipCenterRequest > 0L) vipOpen = true
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = pickAvatar),
                    contentAlignment = Alignment.Center,
                ) {
                    if (user.avatarUrl.isNotBlank()) {
                        AsyncImage(
                            model = user.avatarUrl,
                            contentDescription = "用户头像",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Text(
                            (user.nickname.ifBlank { user.username }).take(1).uppercase(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(user.nickname.ifBlank { user.username }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (user.vipActive) {
                        Surface(color = vipColor(user.vipTagStyle), shape = RoundedCornerShape(5.dp), modifier = Modifier.padding(top = 5.dp)) {
                            Text(user.vipPlanName.ifBlank { "VIP 会员" }, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("@${user.username}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (user.phone.isNotBlank()) Text(user.phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { editOpen = true }) { Icon(Icons.Default.ManageAccounts, "编辑个人资料") }
            }
        }

        Text("快捷入口", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProfileShortcut(Icons.Default.Settings, "Agent 详情", Modifier.weight(1f)) { viewModel.setSection(AppSection.AGENT) }
            ProfileShortcut(Icons.Default.FactCheck, "审批设备${if (state.pendingApprovals.isNotEmpty()) " ${state.pendingApprovals.size}" else ""}", Modifier.weight(1f)) { viewModel.setSection(AppSection.APPROVALS) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProfileShortcut(Icons.Default.Tv, "设备管理", Modifier.weight(1f)) { viewModel.setSection(AppSection.DEVICES) }
            ProfileShortcut(Icons.Default.Apps, "电视应用", Modifier.weight(1f)) { viewModel.setSection(AppSection.APPS) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProfileShortcut(Icons.Default.Badge, "会员中心", Modifier.weight(1f)) { vipOpen = true; viewModel.refreshVip() }
            ProfileShortcut(Icons.Default.QrCodeScanner, "扫码兑换", Modifier.weight(1f)) { scanQr() }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column {
                ProfileRow(Icons.Default.Badge, "个人资料", user.phone.ifBlank { "未填写手机号" }) { editOpen = true }
                HorizontalDivider()
                ProfileRow(Icons.Default.Security, "更换密码", "需验证旧密码") { passwordOpen = true }
                if (primaryDevice != null) {
                    HorizontalDivider()
                    ProfileRow(Icons.Default.LinkOff, "解除设备绑定", primaryDevice.name) { unbindOpen = true }
                }
                HorizontalDivider()
                ProfileRow(Icons.Default.Info, "关于星投屏", "${BuildConfig.VERSION_NAME} · 安徽光秒网络科技有限公司") { aboutOpen = true }
            }
        }
        OutlinedButton(onClick = viewModel::logout, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Logout, null)
            Spacer(Modifier.width(8.dp))
            Text("退出登录")
        }
        Spacer(Modifier.height(8.dp))
    }

    if (editOpen) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            onDismissRequest = { editOpen = false },
            title = { Text("编辑个人资料") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(nickname, { nickname = it }, label = { Text("昵称") }, singleLine = true)
                    OutlinedTextField(
                        phone,
                        { phone = it.filter { character -> character.isDigit() || character == '+' } },
                        label = { Text("手机号") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                    )
                    TextButton(onClick = pickAvatar) { Text("更换头像") }
                }
            },
            confirmButton = { Button(onClick = { viewModel.updateProfile(nickname, phone); editOpen = false }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editOpen = false }) { Text("取消") } },
        )
    }
    if (passwordOpen) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            onDismissRequest = { passwordOpen = false },
            title = { Text("更换密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PasswordField(oldPassword, { oldPassword = it }, "旧密码")
                    PasswordField(newPassword, { newPassword = it }, "新密码")
                    PasswordField(confirmPassword, { confirmPassword = it }, "再次输入新密码")
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.changePassword(oldPassword, newPassword, confirmPassword)
                    passwordOpen = false
                    oldPassword = ""; newPassword = ""; confirmPassword = ""
                }) { Text("确认更换") }
            },
            dismissButton = { TextButton(onClick = { passwordOpen = false }) { Text("取消") } },
        )
    }
    if (unbindOpen && primaryDevice != null) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            onDismissRequest = { unbindOpen = false },
            title = { Text("解除设备绑定") },
            text = { Text("${primaryDevice.name} 将从当前账号移除，并回到待绑定状态。") },
            confirmButton = {
                Button(
                    onClick = { viewModel.unbindDevice(primaryDevice.deviceId); unbindOpen = false },
                    colors = ButtonDefaults.buttonColors(
        // Aerial destructive style: a red-tinted plate with red type, not a solid red slab.
        containerColor = Color(0x33FF453A),
        contentColor = MaterialTheme.colorScheme.error,
    ),
                ) { Text("解除绑定") }
            },
            dismissButton = { TextButton(onClick = { unbindOpen = false }) { Text("取消") } },
        )
    }
    if (aboutOpen) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            onDismissRequest = { aboutOpen = false },
            title = { Text("关于星投屏") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("版本 ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.SemiBold)
                    Text("安徽光秒网络科技有限公司 开发")
                    Text("App 开发：慕沄")
                    Text("为不在家时也能便捷地帮助家人操作电视而开发，让不熟悉电视操作的老人也能及时得到远程协助。")
                }
            },
            confirmButton = { Button(onClick = { aboutOpen = false }) { Text("知道了") } },
        )
    }
    if (vipOpen) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            onDismissRequest = { vipOpen = false },
            title = { Text("会员中心") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        color = vipColor(user.vipTagStyle).copy(alpha = 0.10f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(user.membershipName, color = vipColor(user.vipTagStyle), fontWeight = FontWeight.Bold)
                            Text(
                                when {
                                    user.role == "admin" -> "超级管理员账号，已开放全部权益"
                                    user.vipActive && user.vipExpiresAt > 0L -> "有效期至 ${java.text.DateFormat.getDateInstance().format(java.util.Date(user.vipExpiresAt * 1000))}"
                                    else -> "当前为免费会员，可使用基础局域网功能"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "生效套餐：${user.vipActivePlans.ifEmpty { listOf("免费会员") }.joinToString(" + ")}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text("最多 ${user.vipDeviceLimit} 台设备", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("当前套餐内容", fontWeight = FontWeight.Bold)
                    user.vipBenefits.forEach { benefit ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = app.xingmu.remote.ui.theme.AerialPalette.Green)
                            Text(benefit, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("功能权益明细", fontWeight = FontWeight.Bold)
                    user.entitlementCatalog.forEach { capability ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                            Icon(
                                if (capability.enabled) Icons.Default.CheckCircle else Icons.Default.Lock,
                                null,
                                Modifier.padding(top = 2.dp).size(18.dp),
                                tint = if (capability.enabled) app.xingmu.remote.ui.theme.AerialPalette.Green else MaterialTheme.colorScheme.outline,
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(capability.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text(capability.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                if (capability.enabled) "已开通" else "需套餐",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (capability.enabled) app.xingmu.remote.ui.theme.AerialPalette.Green else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    OutlinedTextField(redeemCode, { redeemCode = it.uppercase() }, label = { Text("兑换码") }, singleLine = true)
                    Button(onClick = { viewModel.redeemVipCode(redeemCode) }, enabled = redeemCode.length >= 8, modifier = Modifier.fillMaxWidth()) { Text("立即兑换") }
                    HorizontalDivider()
                    state.vipPlans.filter { it.active }.forEach { plan ->
                        Surface(color = vipColor(plan.tagStyle).copy(alpha = 0.10f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(plan.name, fontWeight = FontWeight.Bold); Text("${plan.days} 天 · ${plan.description}", style = MaterialTheme.typography.bodySmall) }
                                Button(
                                    onClick = {
                                        vipOpen = false
                                        selectedPaymentPlan = plan
                                    },
                                    enabled = state.vipSalesEnabled && state.vipPaymentEnabled && state.vipPaymentMethods.isNotEmpty(),
                                ) { Text("¥${"%.2f".format(plan.priceCents / 100.0)}") }
                            }
                        }
                    }
                    if (!state.vipPaymentEnabled) Text("在线支付暂未开启，可使用兑换码。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.vipOrders.isNotEmpty()) {
                        HorizontalDivider(); Text("最近订单", fontWeight = FontWeight.Bold)
                        state.vipOrders.take(8).forEach { order -> Text("${order.planName} · ${order.orderNo} · ${when (order.status) { "paid" -> "已生效"; "cancelled" -> "已取消"; else -> "待确认" }}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = { Button(onClick = { vipOpen = false }) { Text("完成") } },
        )
    }
    selectedPaymentPlan?.let { plan ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            onDismissRequest = { selectedPaymentPlan = null },
            title = { Text("选择支付方式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${plan.name} · ${plan.days} 天 · ¥${"%.2f".format(plan.priceCents / 100.0)}")
                    Text("订单金额和权益由后端套餐快照生成，支付成功仅以后端验签回调为准。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if ("alipay" in state.vipPaymentMethods) Button(onClick = { viewModel.startVipPayment(plan.id, "alipay"); selectedPaymentPlan = null }, modifier = Modifier.fillMaxWidth()) { Text("支付宝") }
                    if ("wxpay" in state.vipPaymentMethods) Button(onClick = { viewModel.startVipPayment(plan.id, "wxpay"); selectedPaymentPlan = null }, modifier = Modifier.fillMaxWidth()) { Text("微信支付") }
                    // 在线支付不可用或用户不想线上付时的兜底：提交订单，管理员后台确认后发放。
                    TextButton(onClick = { viewModel.createVipOrder(plan.id); selectedPaymentPlan = null }, modifier = Modifier.fillMaxWidth()) { Text("提交订单（等待管理员确认）") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { selectedPaymentPlan = null }) { Text("取消") } },
        )
    }
    state.activeVipPayment?.let { order ->
        val qr = remember(order.paymentTarget) { paymentQr(order.paymentTarget, 720) }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            onDismissRequest = viewModel::dismissVipPayment,
            title = { Text(if (order.status == "paid") "充值成功" else "扫码支付") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("${order.planName} · ¥${"%.2f".format(order.amountCents / 100.0)}", fontWeight = FontWeight.Bold)
                    qr?.let { Image(it.asImageBitmap(), "支付二维码", Modifier.fillMaxWidth().aspectRatio(1f)) }
                    Text(if (order.status == "paid") "后端已确认收款并叠加套餐权益" else "${if (order.paymentMethod == "wxpay") "微信" else "支付宝"}扫码后请保持此页面，App 正在轮询后端支付结果", style = MaterialTheme.typography.bodySmall)
                    Text("订单号 ${order.orderNo}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(onClick = viewModel::dismissVipPayment) { Text(if (order.status == "paid") "完成" else "关闭") } },
        )
    }
}

private fun paymentQr(value: String, size: Int): android.graphics.Bitmap? = runCatching {
    if (value.isBlank()) error("empty payment target")
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        pixels[y * size + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}.getOrNull()

private fun vipColor(style: String) = when (style) {
    "mint" -> Color(0xFF2BD4A8)
    "gold" -> Color(0xFFFFC15E)
    "rose" -> Color(0xFFFF7A9C)
    "violet" -> Color(0xFFB49CFF)
    else -> Color(0xFF0A84FF)
}

@Composable
private fun ProfileShortcut(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.height(76.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        // Dark plate, not FilledTonalButton: that follows secondaryContainer, which Aerial reserves
        // for the focus plate, so the shortcuts used to render as white cards with black type.
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, Modifier.size(22.dp))
            Spacer(Modifier.height(5.dp))
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ProfileRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
    )
}

@Composable
private fun ApprovalsScreen(state: AppUiState, viewModel: MainViewModel, padding: PaddingValues) {
    // Approving hands the TV to the approver unless an owner is sent, so the second tap confirms it.
    var confirmApprove by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state.user?.role == "admin") "全部设备审批" else "我的设备审批",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "电视扫码绑定后会出现在这里，批准后才开放账号权限",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalIconButton(
                    onClick = viewModel::refreshApprovals,
                    enabled = !state.approvalsLoading,
                ) {
                    if (state.approvalsLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, "刷新待审批设备")
                }
            }
        }
        if (state.pendingApprovals.isEmpty() && !state.approvalsLoading) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FactCheck, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("当前没有待审批设备", Modifier.padding(top = 10.dp), fontWeight = FontWeight.SemiBold)
                        Text("请先扫描电视上的绑定二维码", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        items(state.pendingApprovals, key = { it.deviceId }) { device ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tv, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(device.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOf(device.model, device.lanIp).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "设备信息待上报" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = if (device.online) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(if (device.online) "在线" else "离线", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(device.deviceId, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        OutlinedButton(onClick = { viewModel.reviewApproval(device.deviceId, false) }, enabled = !state.busy) { Text("拒绝") }
                        Button(onClick = { if (confirmApprove == device.deviceId) { confirmApprove = ""; viewModel.reviewApproval(device.deviceId, true) } else confirmApprove = device.deviceId }, enabled = !state.busy) { Text(if (confirmApprove == device.deviceId) "确认归属到我" else "批准添加") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentScreen(state: AppUiState, viewModel: MainViewModel, padding: PaddingValues) {
    var developerTaps by rememberSaveable { mutableStateOf(0) }
    var lastDeveloperTapAt by rememberSaveable { mutableStateOf(0L) }
    var showBackendSettings by rememberSaveable { mutableStateOf(false) }
    var backendAddress by rememberSaveable(state.baseUrl) { mutableStateOf(state.baseUrl) }
    var confirmingDelete by remember(state.primaryDeviceId) { mutableStateOf(false) }
    val device = state.primaryDevice
    val scrollState = rememberLazyListState()
    Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            state = scrollState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 24.dp, bottom = 16.dp),
        ) {
            item {
                LaunchedEffect(device?.deviceId) { viewModel.loadDeviceHistory(device?.deviceId) }
            }
            if (state.deviceEvents.isNotEmpty() || state.deviceCommands.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("后端历史", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            if (state.deviceEvents.isNotEmpty()) {
                                Text("最近事件 ${state.deviceEvents.size} 条", style = MaterialTheme.typography.bodySmall)
                                state.deviceEvents.take(3).forEach { event ->
                                    Text("· ${event.type}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (state.deviceCommands.isNotEmpty()) {
                                Text("最近指令 ${state.deviceCommands.size} 条", style = MaterialTheme.typography.bodySmall)
                                state.deviceCommands.take(3).forEach { command ->
                                    Text("· ${command.type} · ${command.status}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        device?.let { "${it.name} · ${if (it.online) "在线" else "离线"}" }
                            ?: "请先在电视页选择一台设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (device != null) {
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(16.dp)) {
                        Text("电视 Agent 详情", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        DetailRow("设备 ID", device.deviceId)
                        DetailRow("型号", device.model.ifBlank { "未上报" })
                        DetailRow("系统", if (device.androidApi > 0) "Android ${device.androidRelease} / API ${device.androidApi}" else "未上报")
                        DetailRow("Agent", device.appVersion.ifBlank { "未上报" })
                        DetailRow("网络环境", networkEnvironmentLabel(device))
                        DetailRow("局域网 IP", device.lanIp.ifBlank { "未上报" })
                        DetailRow("当前应用", device.foregroundPackage.ifBlank { device.currentActivity.ifBlank { "未上报" } })
                        DetailRow("遥控", controlModeLabel(device.controlMode))
                        DetailRow("画面采集", captureModeLabel(device.captureMode))
                        DetailRow("心跳", "丢失 ${device.missedHeartbeats} 次 · ${device.powerState.ifBlank { "unknown" }}")
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text("完整上报", fontWeight = FontWeight.SemiBold)
                        device.agentMetadata.toSortedMap().forEach { (key, value) ->
                            DetailRow(metadataLabel(key), value.ifBlank { "未上报" })
                        }
                        OutlinedButton(
                            onClick = { viewModel.openTvSettings(device.deviceId, "accessibility") },
                            enabled = device.controllable,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) { Icon(Icons.Default.Settings, null); Spacer(Modifier.width(7.dp)); Text("打开电视权限设置") }
                        Button(
                            onClick = { viewModel.openTvForeground(device.deviceId) },
                            enabled = device.controllable,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) { Icon(Icons.Default.Home, null); Spacer(Modifier.width(7.dp)); Text("唤醒星投屏前台") }
                        OutlinedButton(
                            onClick = { confirmingDelete = true },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(7.dp)); Text("删除这台设备") }
                    }
                }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp)) {
                    Text("关于星投屏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "开发者：慕沄",
                        modifier = Modifier.clickable {
                            val now = android.os.SystemClock.elapsedRealtime()
                            developerTaps = if (now - lastDeveloperTapAt > 5_000L) 1 else developerTaps + 1
                            lastDeveloperTapAt = now
                            if (developerTaps >= 10) {
                                developerTaps = 0
                                showBackendSettings = true
                            }
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "星投屏是为了让家人操作电视更便捷：即使不在家，也能从手机看到电视画面，及时帮助不熟悉电视操作的老人打开应用、调整设置和处理播放。",
                        modifier = Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (showBackendSettings) {
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("开发者后端设置", fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = backendAddress,
                            onValueChange = { backendAddress = it },
                            label = { Text("控制中心地址") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("保存后会退出当前账号并使用新地址。", style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = { viewModel.changeBackendAddress(backendAddress) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("保存并重新登录") }
                    }
                }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.padding(14.dp)) {
                    Text("Agent 状态检测", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (device == null) "尚未选择主控电视"
                        else "${device.name} · ${if (device.online) "在线" else if (device.powerState == "assumed_off") "疑似关机" else "正在重连"}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    if (device != null) {
                        Text(
                            "${controlModeLabel(device.controlMode)} · ${captureModeLabel(device.captureMode)} · 丢失心跳 ${device.missedHeartbeats} 次",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        if (state.socketConnected) "手机实时控制通道已连接" else "手机实时控制通道正在重连",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("星投屏手机端 ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.SemiBold)
                        Text("自动检查新版本并校验安装包", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { viewModel.checkMobileUpdate(true) }) { Text("检查") }
                }
            }
        }
        }
        AgentScrollIndicator(
            state = scrollState,
            modifier = Modifier.align(Alignment.CenterEnd).padding(vertical = 12.dp, horizontal = 6.dp),
        )
    }
    if (confirmingDelete && device != null) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除 ${device.name}？") },
            text = { Text("设备记录、历史指令和上报日志会一起删除。仍安装着 Agent 的电视可重新登记。") },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; viewModel.deleteDevice(device.deviceId) }) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun AgentScrollIndicator(
    state: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    val layout = state.layoutInfo
    val totalItems = layout.totalItemsCount
    val visibleItems = layout.visibleItemsInfo
    if (totalItems == 0 || (!state.canScrollBackward && !state.canScrollForward)) return

    BoxWithConstraints(
        modifier = modifier
            .width(5.dp)
            .fillMaxHeight()
            .background(
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                RoundedCornerShape(3.dp),
            ),
    ) {
        val first = visibleItems.firstOrNull()
        val itemProgress = if (first == null || first.size <= 0) 0f
        else (-first.offset).toFloat() / first.size.toFloat()
        val visibleCount = visibleItems.size.coerceAtLeast(1)
        val thumbFraction = (visibleCount.toFloat() / totalItems.toFloat()).coerceIn(0.12f, 0.58f)
        val scrollableItems = (totalItems - visibleCount).coerceAtLeast(1).toFloat()
        val scrollProgress = ((state.firstVisibleItemIndex + itemProgress) / scrollableItems)
            .coerceIn(0f, 1f)
        val thumbOffset = maxHeight * (1f - thumbFraction) * scrollProgress
        Box(
            Modifier
                .offset(y = thumbOffset)
                .fillMaxWidth()
                .fillMaxHeight(thumbFraction)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    // Long values (udp_p2p_transport and friends) used to wrap mid-token in the narrow value
    // column; give them the full width and put the label above instead.
    if (value.length > 16 || label.length > 12) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
            Text(
                label,
                Modifier.width(92.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RemoteScreen(state: AppUiState, viewModel: MainViewModel, padding: PaddingValues) {
    var confirmingPowerOff by rememberSaveable { mutableStateOf(false) }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("电视画面", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        state.p2pConnected -> "P2P 直连 · UDP 画面与遥控已连接"
                        state.lanStreamConnected -> "局域网直连 · TCP JPEG 画面"
                        state.socketConnected -> "后端转发 · 画面与遥控已连接"
                        else -> "画面预览通道未连接"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.socketConnected || state.p2pConnected || state.lanStreamConnected) app.xingmu.remote.ui.theme.AerialPalette.Green else MaterialTheme.colorScheme.error,
                )
            }
            IconButton(
                onClick = { viewModel.openTvForeground() },
                enabled = state.primaryDevice?.controllable == true,
            ) { Icon(Icons.Default.Home, "唤醒电视端星投屏") }
            Text("同步", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Switch(checked = state.liveScreen, onCheckedChange = viewModel::setLiveScreen)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                ControlChannelMode.AUTO to "自动",
                ControlChannelMode.P2P to "P2P 直连",
                ControlChannelMode.RELAY to "后端转发",
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = state.controlChannelMode == mode,
                    onClick = { viewModel.setControlChannelMode(mode) },
                    label = { Text(label) },
                    enabled = true,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        state.primaryDevice?.let { device ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = if (device.controlMode == "unavailable" || device.captureMode == "unavailable")
                    MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        "遥控：${controlModeLabel(device.controlMode)} · 画面：${captureModeLabel(device.captureMode)}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        listOfNotNull(
                            device.foregroundPackage.takeIf { it.isNotBlank() }?.let { "当前应用 $it" },
                            if (device.androidApi > 0) "Android ${device.androidRelease} / API ${device.androidApi}" else null,
                            systemMemoryLabel(device),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (device.controlMode == "unavailable" || device.captureMode == "unavailable") {
                        Text(
                            "请在电视端开启星投屏无障碍服务；Android 10 及以下采集其他应用还需 Root/ADB 级权限",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Surface(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable { fullScreen = true },
            shape = RoundedCornerShape(22.dp),
            color = app.xingmu.remote.ui.theme.AerialPalette.GlassSoft,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (!fullScreen && state.liveScreen) {
                    LiveJpegSurface(viewModel, Modifier.fillMaxSize())
                }
                if (!state.hasPreviewFrame || !state.liveScreen) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Videocam, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (!state.liveScreen) "画面同步已暂停"
                            else state.snapshotError.ifBlank { "正在建立实时画面" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                if (state.hasPreviewFrame) {
                    Icon(Icons.Default.Fullscreen, "全屏遥控", Modifier.align(Alignment.TopEnd).padding(8.dp), tint = Color.White)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        QuickKeys(viewModel::sendKey)
        Spacer(Modifier.height(14.dp))
        DirectionPad(viewModel::sendKey)
        Spacer(Modifier.height(14.dp))
        MediaKeys(viewModel::sendKey)
        Spacer(Modifier.height(14.dp))
        DecoderKeys(viewModel::switchDecoder)
        Spacer(Modifier.height(14.dp))
        NumberKeys(viewModel::sendKey)
        Spacer(Modifier.height(18.dp))
        OutlinedButton(
            onClick = { confirmingPowerOff = true },
            enabled = state.selectedDevices.any { it.online },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Default.PowerSettingsNew, null)
            Spacer(Modifier.width(8.dp))
            Text("远程关闭电视")
        }
        Spacer(Modifier.height(24.dp))
    }
    if (confirmingPowerOff) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            onDismissRequest = { confirmingPowerOff = false },
            title = { Text("确认关闭电视？") },
            text = { Text("将向已选择的在线电视发送关机指令。关机后只能在电视旁重新开机，并且电视必须已授权 ADB 或 Root。") },
            confirmButton = {
                TextButton(onClick = { confirmingPowerOff = false; viewModel.shutdownTvs() }) {
                    Text("确认关机", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingPowerOff = false }) { Text("取消") } },
        )
    }
    if (fullScreen) {
        Dialog(
            onDismissRequest = { fullScreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                LiveJpegSurface(viewModel, Modifier.fillMaxSize())
                IconButton(
                    onClick = { fullScreen = false },
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(10.dp)
                        .background(Color(0xB3000000), CircleShape),
                ) { Icon(Icons.Default.Close, "退出全屏", tint = Color.White) }
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xD91A1E22),
                ) {
                    Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FullScreenKey(Icons.Default.Home, "主页") { viewModel.sendKey("HOME") }
                            FullScreenKey(Icons.AutoMirrored.Filled.ArrowBack, "返回") { viewModel.sendKey("BACK") }
                            FullScreenKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "左") { viewModel.sendKey("DPAD_LEFT") }
                            FullScreenKey(Icons.Default.KeyboardArrowUp, "上") { viewModel.sendKey("DPAD_UP") }
                            FullScreenKey(Icons.Default.CheckCircle, "确定") { viewModel.sendKey("DPAD_CENTER") }
                            FullScreenKey(Icons.Default.KeyboardArrowDown, "下") { viewModel.sendKey("DPAD_DOWN") }
                            FullScreenKey(Icons.AutoMirrored.Filled.KeyboardArrowRight, "右") { viewModel.sendKey("DPAD_RIGHT") }
                            FullScreenKey(Icons.Default.VolumeDown, "音量减") { viewModel.sendKey("VOLUME_DOWN") }
                            FullScreenKey(Icons.Default.VolumeUp, "音量加") { viewModel.sendKey("VOLUME_UP") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveJpegSurface(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preview = remember(context) { JpegPreviewView(context) }
    val consumer: (ByteArray) -> Unit = remember(viewModel, preview) { preview::offer }
    DisposableEffect(viewModel, preview) {
        viewModel.addFrameListener(consumer)
        onDispose {
            viewModel.removeFrameListener(consumer)
            preview.release()
        }
    }
    AndroidView(
        factory = { preview },
        modifier = modifier,
    )
}

@Composable
private fun FullScreenKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(46.dp)) {
        Icon(icon, label)
    }
}

@Composable
private fun QuickKeys(send: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        LabeledIconButton(Icons.Default.Home, "主页") { send("HOME") }
        LabeledIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回") { send("BACK") }
        LabeledIconButton(Icons.Default.VolumeDown, "音量减") { send("VOLUME_DOWN") }
        LabeledIconButton(Icons.Default.VolumeUp, "音量加") { send("VOLUME_UP") }
    }
}

@Composable
private fun DirectionPad(send: (String) -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(210.dp)) {
        Box(Modifier.fillMaxSize()) {
            RemoteIconButton(Icons.Default.KeyboardArrowUp, "上", Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) { send("DPAD_UP") }
            RemoteIconButton(Icons.Default.KeyboardArrowDown, "下", Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) { send("DPAD_DOWN") }
            RemoteIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "左", Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) { send("DPAD_LEFT") }
            RemoteIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "右", Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) { send("DPAD_RIGHT") }
            FilledIconButton(onClick = { send("DPAD_CENTER") }, modifier = Modifier.align(Alignment.Center).size(70.dp)) {
                Text("OK", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MediaKeys(send: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        LabeledIconButton(Icons.Default.PlayArrow, "播放") { send("MEDIA_PLAY") }
        LabeledIconButton(Icons.Default.Pause, "暂停") { send("MEDIA_PAUSE") }
        LabeledIconButton(Icons.Default.Stop, "停止") { send("MEDIA_STOP") }
    }
}

@Composable
private fun DecoderKeys(switch: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("播放内核", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AssistChip(onClick = { switch("AUTO") }, label = { Text("自动") })
            AssistChip(onClick = { switch("EXO_HARDWARE") }, label = { Text("Exo") })
            AssistChip(onClick = { switch("GSY_PLAYER") }, label = { Text("GSY") })
            AssistChip(onClick = { switch("IJK_HARDWARE") }, label = { Text("IJK") })
            AssistChip(onClick = { switch("IJK_SOFTWARE") }, label = { Text("软解") })
            AssistChip(onClick = { switch("ART_PLAYER") }, label = { Text("ArtPlayer") })
            AssistChip(onClick = { switch("PLDROID_PLAYER") }, label = { Text("七牛") })
        }
    }
}

@Composable
private fun NumberKeys(send: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("数字键", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Icon(if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown, null)
        }
        if (expanded) {
            (1..9).chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { number ->
                        OutlinedButton(
                            onClick = { send("DIGIT_$number") },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) { Text(number.toString()) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(
                onClick = { send("DIGIT_0") },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text("0") }
        }
    }
}

@Composable
private fun CastScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    padding: PaddingValues,
    pickVideo: () -> Unit,
    pickImages: (Int) -> Unit,
    pickApk: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var interval by rememberSaveable { mutableFloatStateOf(5f) }
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text(
            if (state.offlineMode) "投到 ${state.lanDevices.firstOrNull { it.host == state.selectedLanHost }?.name ?: "未选择电视"}"
            else "投到 ${state.selectedDeviceIds.size} 台电视",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        )
        Text(
            if (state.offlineMode) "离线模式由手机在局域网直接提供内容，不经过后端"
            else "网络视频可选电视原生内核或后端 DPlayer；本地文件仍由手机局域网直接提供",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("网络视频或 M3U8", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            placeholder = { Text("https://example.com/live/index.m3u8") },
            singleLine = true,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(
                onClick = { viewModel.castUrl(url) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Tv, null)
                Spacer(Modifier.width(6.dp))
                Text("电视播放器")
            }
            if (!state.offlineMode) {
                Button(
                    onClick = { viewModel.castHostedUrl(url) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Cast, null)
                    Spacer(Modifier.width(6.dp))
                    Text("后端 DPlayer")
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        Text("手机本地文件", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = pickVideo, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Movie, null)
                Spacer(Modifier.width(6.dp))
                Text("视频")
            }
            FilledTonalButton(onClick = { pickImages(interval.toInt()) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.AddPhotoAlternate, null)
                Spacer(Modifier.width(6.dp))
                Text("图片")
            }
        }
        Text(
            "幻灯片间隔 ${interval.toInt()} 秒",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 14.dp),
        )
        Slider(value = interval, onValueChange = { interval = it }, valueRange = 2f..60f, steps = 57)
        if (!state.offlineMode) {
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            Text("远程安装", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "已授权 ADB 或 Root 时自动安装，否则由电视打开系统安装确认页面",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            OutlinedButton(onClick = pickApk, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CloudUpload, null)
                Spacer(Modifier.width(8.dp))
                Text("选择并推送安装包")
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.sendMediaAction("play") }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PlayArrow, "播放")
                Spacer(Modifier.width(6.dp))
                Text("播放")
            }
            OutlinedButton(onClick = { viewModel.sendMediaAction("pause") }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Pause, "暂停")
                Spacer(Modifier.width(6.dp))
                Text("暂停")
            }
        }
        Button(
            onClick = viewModel::stopCasting,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            colors = ButtonDefaults.buttonColors(
        // Aerial destructive style: a red-tinted plate with red type, not a solid red slab.
        containerColor = Color(0x33FF453A),
        contentColor = MaterialTheme.colorScheme.error,
    ),
        ) {
            Icon(Icons.Default.Stop, null)
            Spacer(Modifier.width(8.dp))
            Text("终止投屏并退出播放器")
        }
        if (state.busy) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("正在处理…", Modifier.padding(start = 10.dp))
            }
        }
        if (state.recentCommands.isNotEmpty()) {
            Text("最近指令", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
            state.recentCommands.forEach { command ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.Circle,
                        null,
                        Modifier.padding(top = 4.dp).size(9.dp),
                        tint = when (command.success) {
                            true -> app.xingmu.remote.ui.theme.AerialPalette.Green
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.tertiary
                        },
                    )
                    Text(command.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LabeledIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(52.dp)) { Icon(icon, label) }
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RemoteIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier.size(54.dp)) { Icon(icon, label) }
}

@Composable
private fun EmptyState(
    modifier: Modifier,
    title: String,
    detail: String,
    onRefresh: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Tv, null, Modifier.size(50.dp), tint = MaterialTheme.colorScheme.outline)
        Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        OutlinedButton(onClick = onRefresh, modifier = Modifier.padding(top = 14.dp)) {
            Icon(Icons.Default.Refresh, null)
            Spacer(Modifier.width(6.dp))
            Text("刷新")
        }
        if (secondaryLabel != null && onSecondary != null) {
            Button(onClick = onSecondary, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Default.QrCodeScanner, null)
                Spacer(Modifier.width(6.dp))
                Text(secondaryLabel)
            }
        }
    }
}

private fun deviceStatus(device: app.xingmu.remote.data.TvDevice): String = when {
    device.status == "pending" -> "等待后台审核"
    device.status == "rejected" -> "审核未通过"
    device.status == "revoked" -> "授权已撤销"
    !device.enabled -> "设备已停用"
    !device.online -> "离线"
    else -> "在线可控"
}

private fun controlModeLabel(value: String): String = when (value) {
    "accessibility" -> "无障碍全局"
    "adb" -> "ADB TCP"
    "root" -> "Root"
    "player" -> "播放器内"
    else -> "未就绪"
}

private fun captureModeLabel(value: String): String = when (value) {
    "accessibility" -> "无障碍全屏"
    "adb" -> "ADB TCP 全屏"
    "root" -> "Root 全屏"
    "app_window" -> "应用内"
    else -> "未就绪"
}

private fun networkEnvironmentLabel(device: app.xingmu.remote.data.TvDevice): String {
    val environment = device.agentMetadata["network_environment"].orEmpty()
    val transport = device.agentMetadata["network_transport"]
        .orEmpty().ifBlank { device.agentMetadata["network_type"].orEmpty() }.lowercase()
    val environmentLabel = if (environment == "lan" || device.lanIp.startsWith("192.168.") ||
        device.lanIp.startsWith("10.") || device.lanIp.matches(Regex("172\\.(1[6-9]|2[0-9]|3[01])\\..*"))) {
        "局域网"
    } else {
        "网络待识别"
    }
    val transportLabel = when {
        "wifi" in transport -> "Wi-Fi"
        "ethernet" in transport -> "网线"
        transport.isNotBlank() && transport != "unknown" -> transport.uppercase()
        else -> "连接方式未上报"
    }
    val natLabel = when (device.natType) {
        "", "unknown", "not_tested" -> "NAT 未执行 STUN/ICE 探测"
        else -> "NAT ${device.natType}"
    }
    return "$environmentLabel · $transportLabel · $natLabel"
}

private fun systemMemoryLabel(device: app.xingmu.remote.data.TvDevice): String? {
    if (device.memoryAvailableBytes < 0 || device.memoryTotalBytes <= 0) return null
    fun gb(bytes: Long) = String.format(java.util.Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    return "内存 ${gb(device.memoryAvailableBytes)} 可用 / ${gb(device.memoryTotalBytes)}"
}

private fun formatGb(bytes: Long): String =
    String.format(java.util.Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)

private fun formatSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

private fun metadataLabel(key: String): String = when (key) {
    "capabilities_version" -> "能力协议版本"
    "android_api" -> "Android API"
    "android_release" -> "Android 版本"
    "security_patch" -> "安全补丁"
    "manufacturer" -> "制造商"
    "brand" -> "品牌"
    "product" -> "产品"
    "device" -> "设备代号"
    "hardware" -> "硬件平台"
    "board" -> "主板"
    "build_display" -> "固件版本"
    "build_id" -> "构建 ID"
    "build_incremental" -> "增量版本"
    "build_type" -> "构建类型"
    "build_tags" -> "系统签名标签"
    "build_fingerprint" -> "系统指纹"
    "abis" -> "CPU ABI"
    "cpu_cores" -> "CPU 核心"
    "locale" -> "系统语言"
    "timezone" -> "时区"
    "agent_pid" -> "Agent 进程 PID"
    "agent_threads" -> "Agent 线程数"
    "runtime_max_memory_bytes" -> "Agent 最大堆内存"
    "runtime_free_memory_bytes" -> "Agent 空闲堆内存"
    "screen_width_px" -> "屏幕宽度"
    "screen_height_px" -> "屏幕高度"
    "screen_refresh_hz" -> "刷新率"
    "memory_available_bytes" -> "可用内存"
    "memory_total_bytes" -> "总内存"
    "memory_low" -> "低内存状态"
    "low_ram_device" -> "低内存设备"
    "storage_available_bytes" -> "可用存储"
    "storage_total_bytes" -> "总存储"
    "external_storage_state" -> "外部存储状态"
    "screen_density_dpi" -> "屏幕密度"
    "screen_orientation" -> "屏幕方向"
    "screen_on" -> "屏幕点亮"
    "network_type" -> "网络类型"
    "network_connected" -> "网络连接"
    "network_environment" -> "网络环境"
    "network_transport" -> "连接方式"
    "nat_discovery" -> "NAT 探测"
    "network_subtype" -> "网络子类型"
    "network_state" -> "网络状态"
    "network_roaming" -> "网络漫游"
    "network_metered" -> "计费网络"
    "ip_addresses" -> "本机 IP"
    "wifi_rssi_dbm" -> "Wi-Fi 信号"
    "wifi_link_speed_mbps" -> "Wi-Fi 速率"
    "wifi_ssid" -> "Wi-Fi 名称"
    "gateway_ipv4" -> "网关"
    "dns1_ipv4" -> "DNS 1"
    "dns2_ipv4" -> "DNS 2"
    "battery_percent" -> "电量"
    "battery_charging" -> "充电状态"
    "battery_temperature_tenths_c" -> "电池温度"
    "battery_voltage_mv" -> "电池电压"
    "battery_health" -> "电池健康代码"
    "battery_technology" -> "电池技术"
    "power_source" -> "供电来源代码"
    "battery_whitelist" -> "保活白名单"
    "foreground_package" -> "前台应用"
    "current_activity" -> "当前页面"
    "control_mode" -> "控制方式"
    "capture_mode" -> "采集方式"
    "accessibility_active" -> "无障碍服务"
    "accessibility_enabled" -> "无障碍已启用"
    "root_binary" -> "Root 程序"
    "root_access" -> "Root 权限"
    "adb_tcp_port" -> "ADB 连接端口"
    "adb_endpoint" -> "ADB 连接地址"
    "adb_port_source" -> "ADB 端口来源"
    "adb_requires_manual_enable" -> "ADB 需手动开启"
    "adb_status" -> "ADB 连接状态"
    "adb_connected" -> "ADB 已连接"
    "system_uptime_ms" -> "系统运行毫秒"
    "agent_uptime_ms" -> "Agent 运行毫秒"
    "capture_scope" -> "采集范围"
    "preview_max_width" -> "预览最大宽度"
    "preview_max_height" -> "预览最大高度"
    "preview_jpeg_quality" -> "预览 JPEG 质量"
    "last_capture_duration_ms" -> "最近截图耗时"
    "last_capture_payload_chars" -> "最近截图传输字符"
    "background_agent" -> "后台 Agent"
    "boot_autostart" -> "开机自启"
    "agent_heartbeat_seconds" -> "心跳间隔"
    "agent_reconnect_seconds" -> "重连间隔"
    "global_capture_requires" -> "全屏采集条件"
    "control_features" -> "遥控能力"
    "app_inventory" -> "应用清单"
    "remote_app_launch" -> "远程启动应用"
    "remote_power_off" -> "远程关机"
    "playback_state" -> "播放状态"
    "player_route" -> "播放器入口"
    "external_player_active" -> "外部播放器活动"
    "duration_ms" -> "媒体总时长"
    "volume_percent" -> "音量"
    else -> key
}
