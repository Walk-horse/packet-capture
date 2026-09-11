package com.ht.stream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ht.stream.capture.CaptureVpnService
import com.ht.stream.data.RequestStore
import com.ht.stream.sync.SyncPrefs
import com.ht.stream.sync.SyncServer
import com.ht.stream.ui.BuildRequestScreen
import com.ht.stream.ui.CaptureScreen
import com.ht.stream.ui.DetailScreen
import com.ht.stream.ui.FavoritesScreen
import com.ht.stream.ui.HistoryScreen
import com.ht.stream.ui.HostsScreen
import com.ht.stream.ui.HttpsScreen
import com.ht.stream.ui.LaunchApp
import com.ht.stream.ui.LogsScreen
import com.ht.stream.ui.ModeScreen
import com.ht.stream.ui.OverviewScreen
import com.ht.stream.ui.RequestListScreen
import com.ht.stream.ui.StreamTheme
import com.ht.stream.ui.ToolsScreen
import com.ht.stream.ui.WindowPickScreen
import com.ht.stream.window.FloatingWindowService

/** 页面导航模型（栈式） */
sealed interface Screen {
    data object Overview : Screen
    data object Capture : Screen
    data object WindowPick : Screen
    data object History : Screen
    data class RequestList(val sessionId: String?) : Screen
    data class Detail(val id: String) : Screen
    data object Favorites : Screen
    data class BuildRequest(val replayId: String? = null) : Screen
    data object Hosts : Screen
    data object Tools : Screen
    data object Https : Screen
    data object CaptureMode : Screen
    data object Logs : Screen
    data object Tutorial : Screen
    data object About : Screen
    data class LogView(val fileName: String) : Screen
}

class MainActivity : ComponentActivity() {

    /**
     * 进入窗口化时的时间戳。本 Activity 启动窗口化后会立即退到后台，
     * 用时间窗规避「退后台瞬间又收到 onResume」的竞态，避免刚开就自己关掉。
     */
    private var windowLaunchedAt = 0L

    /** 由 Root 在启动窗口化时调用 */
    fun markWindowLaunched() {
        windowLaunchedAt = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        // 切回抓包应用：关闭悬浮图标，并释放对目标进程的绑定
        if (!FloatingWindowService.isActive) return
        if (System.currentTimeMillis() - windowLaunchedAt < 2000L) return
        FloatingWindowService.stop(this)
        Toast.makeText(this, "已退出窗口化", Toast.LENGTH_SHORT).show()
    }

    override fun onStop() {
        super.onStop()
        // 已经退到后台（窗口化已成功切走），此后任意时刻回到本应用都应结束窗口化
        windowLaunchedAt = 0L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 调试通道：am start --ez autostart true 自动开始抓包（需已授予 VPN 权限）
        if (intent.getBooleanExtra("autostart", false) && VpnService.prepare(this) == null) {
            startCapture(this)
        }
        // 调试通道：am start --ez sync_on true 直接开启桌面同步服务（无需点 UI）
        if (intent.getBooleanExtra("sync_on", false)) {
            SyncPrefs.setOn(this, true)
            SyncServer.start()
        }
        setContent { StreamTheme { Root() } }
    }
}

@Composable
fun Root() {
    val context = LocalContext.current
    val running by CaptureVpnService.running.collectAsState()
    val stack = remember {
        mutableStateListOf<Screen>(Screen.Overview).apply {
            // 调试通道：am start --ez picker true 直接进入「选择进程」页（本机 adb input 被限制时用于验证）
            if ((context as? Activity)?.intent?.getBooleanExtra("picker", false) == true) add(Screen.WindowPick)
        }
    }
    val nav: (Screen) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.size - 1) }

    BackHandler(enabled = stack.size > 1) { back() }

    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startCapture(context)
        }
    }

    // ---------- 窗口化 ----------
    val pendingWindow = remember { mutableStateOf<LaunchApp?>(null) }
    val ensureHolder = remember { arrayOfNulls<((LaunchApp) -> Unit)>(1) }

    fun launchWindowNow(app: LaunchApp) {
        (context as? MainActivity)?.markWindowLaunched()
        FloatingWindowService.start(context, app.pkg, app.uid, app.label)
        context.packageManager.getLaunchIntentForPackage(app.pkg)?.let { li ->
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(li) }
        }
        (context as? Activity)?.moveTaskToBack(true)
    }

    val overlayPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        pendingWindow.value?.let { app -> ensureHolder[0]?.invoke(app) }
    }

    val windowVpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startCapture(context)
            pendingWindow.value?.let { launchWindowNow(it) }
        }
        pendingWindow.value = null
    }

    // 选择进程后：悬浮窗权限 → 确保抓包已启动 → 进入窗口化
    fun ensureAndLaunch(app: LaunchApp) {
        if (!Settings.canDrawOverlays(context)) {
            pendingWindow.value = app
            runCatching {
                overlayPermission.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
            return
        }
        if (!CaptureVpnService.running.value) {
            val prep = VpnService.prepare(context)
            if (prep != null) {
                pendingWindow.value = app
                windowVpnPermission.launch(prep)
                return
            }
            startCapture(context)
        }
        launchWindowNow(app)
    }
    ensureHolder[0] = ::ensureAndLaunch

    val toggleCapture: () -> Unit = {
        if (running) {
            context.startService(Intent(context, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_STOP))
        } else {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) vpnPermission.launch(prepareIntent) else startCapture(context)
        }
    }

    when (val s = stack.last()) {
        Screen.Overview -> OverviewScreen(onEnterCapture = { nav(Screen.Capture) }, nav = nav)
        Screen.Capture -> CaptureScreen(
            running = running,
            onToggle = toggleCapture,
            onBack = back,
            nav = nav
        )
        Screen.WindowPick -> WindowPickScreen(
            onBack = back,
            onPick = { app ->
                back()
                ensureHolder[0]?.invoke(app)
            }
        )
        Screen.History -> HistoryScreen(
            onBack = back,
            onOpenSession = { nav(Screen.RequestList(it)) },
            onOpenCurrent = { nav(Screen.RequestList(null)) }
        )
        is Screen.RequestList -> RequestListScreen(
            sessionId = s.sessionId,
            onBack = back,
            onOpen = { nav(Screen.Detail(it.id)) }
        )
        is Screen.Detail -> {
            val exchange = RequestStore.find(s.id)
            if (exchange == null) {
                back()
            } else {
                DetailScreen(
                    exchange = exchange,
                    onBack = back,
                    onReplay = { nav(Screen.BuildRequest(it.id)) }
                )
            }
        }
        Screen.Favorites -> FavoritesScreen(onBack = back, onOpen = { nav(Screen.Detail(it.id)) })
        is Screen.BuildRequest -> BuildRequestScreen(
            onBack = back,
            replay = s.replayId?.let { RequestStore.find(it) }
        )
        Screen.Hosts -> HostsScreen(onBack = back)
        Screen.Tools -> ToolsScreen(onBack = back)
        Screen.Https -> HttpsScreen(onBack = back)
        Screen.CaptureMode -> ModeScreen(onBack = back)
        Screen.Logs -> LogsScreen(onBack = back, onOpen = { nav(Screen.LogView(it)) })
        Screen.Tutorial -> com.ht.stream.ui.TutorialScreen(onBack = back)
        Screen.About -> com.ht.stream.ui.AboutScreen(onBack = back)
        is Screen.LogView -> com.ht.stream.ui.LogViewScreen(fileName = s.fileName, onBack = back)
    }
}

private fun startCapture(context: Context) {
    val intent = Intent(context, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_START)
    context.startForegroundService(intent)
}
