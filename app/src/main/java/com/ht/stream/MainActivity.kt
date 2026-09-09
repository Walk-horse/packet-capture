package com.ht.stream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ht.stream.capture.CaptureVpnService
import com.ht.stream.data.RequestStore
import com.ht.stream.sync.SyncPrefs
import com.ht.stream.sync.SyncServer
import com.ht.stream.ui.BuildRequestScreen
import com.ht.stream.ui.DetailScreen
import com.ht.stream.ui.FavoritesScreen
import com.ht.stream.ui.HistoryScreen
import com.ht.stream.ui.HostsScreen
import com.ht.stream.ui.HttpsScreen
import com.ht.stream.ui.LogsScreen
import com.ht.stream.ui.ModeScreen
import com.ht.stream.ui.OverviewScreen
import com.ht.stream.ui.RequestListScreen
import com.ht.stream.ui.StreamTheme
import com.ht.stream.ui.ToolsScreen

/** 页面导航模型（栈式） */
sealed interface Screen {
    data object Overview : Screen
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
    val stack = remember { mutableStateListOf<Screen>(Screen.Overview) }
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

    val toggleCapture: () -> Unit = {
        if (running) {
            context.startService(Intent(context, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_STOP))
        } else {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) vpnPermission.launch(prepareIntent) else startCapture(context)
        }
    }

    when (val s = stack.last()) {
        Screen.Overview -> OverviewScreen(running, toggleCapture, nav)
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
