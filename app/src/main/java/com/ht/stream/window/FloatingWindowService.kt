package com.ht.stream.window

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color as AColor
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets as AndroidWindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ht.stream.MainActivity
import com.ht.stream.R
import com.ht.stream.capture.CaptureVpnService
import com.ht.stream.data.HttpExchange
import com.ht.stream.data.RequestStore
import com.ht.stream.ui.BodyPanel
import com.ht.stream.ui.StreamColors
import com.ht.stream.ui.StreamTheme
import com.ht.stream.ui.buildCurl
import com.ht.stream.ui.formatDuration
import com.ht.stream.ui.formatFullTime
import com.ht.stream.ui.formatSize
import com.ht.stream.ui.statusColor
import kotlinx.coroutines.delay

/**
 * 窗口化抓包服务：
 * - 在桌面上显示一个可拖动的悬浮图标（本应用最小化后的入口）；
 * - 点击悬浮图标 → 在屏幕底部弹出半屏抓包详情面板（仅显示所选进程的记录）；
 * - 长按悬浮图标 → 退出窗口化。
 */
class FloatingWindowService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        const val ACTION_START = "com.ht.stream.window.START"
        const val ACTION_STOP = "com.ht.stream.window.STOP"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_UID = "uid"
        const val EXTRA_LABEL = "label"

        private const val CHANNEL_ID = "window"
        private const val NOTIF_ID = 2001

        /** 面板高度比例：初始 / 最小 / 最大（最大 = 整屏，不设上限） */
        private const val PANEL_INIT_RATIO = 0.5f
        private const val PANEL_MIN_RATIO = 0.25f
        private const val PANEL_MAX_RATIO = 1.0f

        /** 窗口化会话进行中：悬浮图标已挂载，且已绑定目标进程（pkg/uid/label） */
        @Volatile
        private var active = false
        val isActive: Boolean get() = active

        private fun markActive(v: Boolean) {
            active = v
        }

        fun start(ctx: Context, pkg: String, uid: Int, label: String) {
            val i = Intent(ctx, FloatingWindowService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_UID, uid)
                .putExtra(EXTRA_LABEL, label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        /** 结束窗口化：移除悬浮图标与面板，并释放对目标进程的绑定 */
        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, FloatingWindowService::class.java)) }
        }
    }

    private val lifecycleRegistry by lazy { LifecycleRegistry(this) }
    private val savedStateController by lazy { SavedStateRegistryController.create(this) }
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var wm: WindowManager
    private var iconView: View? = null
    private var panelView: ComposeView? = null

    private var pkg = ""
    private var uid = -1
    private var label = ""

    /** 面板当前高度（px）。可通过顶部把手上下拖拽调整，范围 25% ~ 80% 屏高 */
    private var panelHeightPx = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                removeAll()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                pkg = intent.getStringExtra(EXTRA_PKG) ?: ""
                uid = intent.getIntExtra(EXTRA_UID, -1)
                label = intent.getStringExtra(EXTRA_LABEL)?.takeIf { it.isNotBlank() } ?: pkg
                startForegroundNotif()
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                if (iconView == null) addIcon()
                if (intent.getBooleanExtra("auto_panel", false)) addPanel()
                markActive(true)
            }
            else -> {
                if (iconView == null && pkg.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeAll()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    // ---------- 通知 ----------

    private fun startForegroundNotif() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "窗口化抓包", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("窗口化抓包")
            .setContentText(label)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ---------- 悬浮图标 ----------

    private fun addIcon() {
        val size = dp(54)
        val iv = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AColor.WHITE)
                setStroke(dp(1), 0x22000000)
            }
            elevation = dp(6).toFloat()
            contentDescription = "抓包悬浮图标"
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW() - size - dp(12)
            y = screenH() / 3
        }
        attachDrag(iv, lp)
        iv.setOnClickListener { togglePanel() }
        iv.setOnLongClickListener {
            Toast.makeText(this, "已退出窗口化", Toast.LENGTH_SHORT).show()
            removeAll()
            stopSelf()
            true
        }
        runCatching { wm.addView(iv, lp) }
        iconView = iv
    }

    private fun attachDrag(v: View, lp: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    runCatching { wm.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    // ---------- 半屏面板 ----------

    private fun togglePanel() {
        if (panelView != null) removePanel() else addPanel()
    }

    private fun addPanel() {
        if (panelHeightPx <= 0) panelHeightPx = (screenH() * PANEL_INIT_RATIO).toInt()
        val cv = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingWindowService)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowService)
            setContent {
                StreamTheme {
                    CapturePanelOverlay(
                        uid = uid,
                        label = label,
                        onResizeDrag = { dy -> resizePanel(dy) },
                        onClose = { removePanel() }
                    )
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            panelHeightPx,
            overlayType(),
            // 可获焦（否则面板内输入框弹不出输入法）；NOT_TOUCH_MODAL 保证面板外的触摸仍落到下层 App
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            // 输入法弹出时把面板整体上移，避免盖住输入框
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 只避开顶部（状态栏 / 刘海），底部不避让：
                // 否则导航条 inset 会把窗口底边顶到导航条上方，露出一条缝。
                // 避让手势条交给面板内容自己（windowInsetsPadding(navigationBars)）。
                setFitInsetsSides(
                    AndroidWindowInsets.Side.TOP or
                        AndroidWindowInsets.Side.LEFT or AndroidWindowInsets.Side.RIGHT
                )
            } else {
                @Suppress("DEPRECATION")
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 刘海屏：允许面板延伸到显示切口区域，保持贴底
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching { wm.addView(cv, lp) }
        panelView = cv
    }

    /** 拖拽把手调整面板高度：[dy] 为本次拖拽的垂直位移（向上为负 = 变高） */
    private fun resizePanel(dy: Float) {
        val v = panelView ?: return
        val maxH = (screenH() * PANEL_MAX_RATIO).toInt()
        val minH = (screenH() * PANEL_MIN_RATIO).toInt()
        val next = (panelHeightPx - dy.toInt()).coerceIn(minH, maxH)
        if (next == panelHeightPx) return
        panelHeightPx = next
        runCatching {
            val lp = v.layoutParams as WindowManager.LayoutParams
            lp.height = next
            wm.updateViewLayout(v, lp)
        }
    }

    private fun removePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
    }

    private fun removeAll() {
        markActive(false)
        pkg = ""
        uid = -1
        label = ""
        removePanel()
        iconView?.let { runCatching { wm.removeView(it) } }
        iconView = null
    }

    // ---------- 工具 ----------

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun screenW(): Int = resources.displayMetrics.widthPixels
    private fun screenH(): Int = resources.displayMetrics.heightPixels
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

/**
 * 底部半屏抓包面板：仅展示所选进程（uid）的记录。
 * - 点击条目 → 面板内查看详情（总览 / 请求 / 响应）；
 * - 顶部「清屏」→ 只隐藏当前已显示的记录，底层抓包历史保留（与抓包页一致）。
 */
@Composable
private fun CapturePanelOverlay(
    uid: Int,
    label: String,
    onResizeDrag: (Float) -> Unit,
    onClose: () -> Unit
) {
    val running by CaptureVpnService.running.collectAsState()
    val startedAt by CaptureVpnService.startedAt.collectAsState()
    val all by RequestStore.exchanges.collectAsState()
    RequestStore.tick.collectAsState()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    // 被点开的记录（HttpExchange 是可变对象，靠 tick 驱动刷新）
    var detail by remember { mutableStateOf<HttpExchange?>(null) }
    // 清屏：本轮被隐藏的记录 id（不清除底层历史）
    var clearedIds by remember { mutableStateOf(emptySet<String>()) }
    // 列表过滤词（host / path / method）
    var query by remember { mutableStateOf("") }

    val scoped = remember(all, uid) { all.filter { it.uid == uid } }
    val rows = remember(scoped, clearedIds, query) {
        val q = query.trim()
        scoped.filter { it.id !in clearedIds && (q.isEmpty() ||
            it.host.contains(q, true) || it.path.contains(q, true) || it.method.contains(q, true)) }
    }
    LaunchedEffect(scoped.size, uid) {
        android.util.Log.d("CapturePanel", "bound uid=$uid rows=${scoped.size} total=${all.size}")
    }
    val elapsed = if (running && startedAt > 0) formatElapsed(now - startedAt) else "—"

    // 抓包中：有新记录进入时自动回到最顶部（新记录插在列表头部）
    val listState = rememberLazyListState()
    val newestId = scoped.firstOrNull()?.id
    LaunchedEffect(newestId, running) {
        if (running && rows.isNotEmpty()) listState.animateScrollToItem(0)
    }

    val current = detail
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(PanelBg)
            // 窗口已贴到屏幕物理底部，这里单独让内容避开手势条（背景仍铺满到底）
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        if (current != null) {
            PanelDetail(
                e = current,
                label = label,
                onResizeDrag = onResizeDrag,
                onBack = { detail = null },
                onClose = onClose
            )
            return@Column
        }

        PanelDragHandle(onResizeDrag)
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (running) Color(0xFF34C759) else Color(0xFF8E8E93), RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label.ifBlank { "进程抓包" },
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(elapsed, color = PanelSub, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.Delete, contentDescription = "清屏",
                tint = if (rows.isNotEmpty()) PanelSub else Color(0x44FFFFFF),
                modifier = Modifier
                    .size(18.dp)
                    .clickable(enabled = rows.isNotEmpty()) {
                        clearedIds = clearedIds + scoped.filter { it.id !in clearedIds }.map { it.id }
                    }
            )
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Default.Close, contentDescription = "关闭",
                tint = PanelSub, modifier = Modifier.size(18.dp).clickable { onClose() }
            )
        }

        // 过滤输入框：按 host / path / method 过滤
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x1FFFFFFF))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search, contentDescription = null,
                tint = PanelSub, modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = Color.White),
                cursorBrush = SolidColor(Color.White),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("过滤域名 / 路径", fontSize = 12.sp, color = PanelSub)
                    inner()
                }
            )
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Default.Close, contentDescription = "清空过滤",
                    tint = PanelSub, modifier = Modifier.size(14.dp).clickable { query = "" }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = Color(0x22FFFFFF))

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        query.isNotBlank() -> "未找到匹配「${query.trim()}」的请求"
                        !running -> "抓包未开始"
                        scoped.isNotEmpty() -> "已清屏，新请求将显示在此"
                        else -> "该进程暂无请求"
                    },
                    color = PanelSub, fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(rows.take(300), key = { it.id }) { e ->
                    PanelRow(e) { detail = e }
                }
            }
        }
    }
}

/** 面板顶部把手：垂直拖拽调整面板高度（列表页与详情页共用） */
@Composable
private fun PanelDragHandle(onResizeDrag: (Float) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    onResizeDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(width = 40.dp, height = 4.dp).background(Color(0x66FFFFFF), RoundedCornerShape(2.dp)))
    }
}

/** 面板内详情：深色头部 + 浅色内容区（复用 BodyPanel 展示请求/响应体） */
@Composable
private fun PanelDetail(
    e: HttpExchange,
    label: String,
    onResizeDrag: (Float) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PanelDragHandle(onResizeDrag)
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ArrowBack, contentDescription = "返回",
                tint = Color.White, modifier = Modifier.size(20.dp).clickable { onBack() }
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    e.host, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${e.method} ${e.path.ifEmpty { "/" }}",
                    color = PanelSub, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.Close, contentDescription = "关闭",
                tint = PanelSub, modifier = Modifier.size(18.dp).clickable { onClose() }
            )
        }
        PanelTabs(listOf("总览", "请求", "响应"), tab) { tab = it }
        HorizontalDivider(color = Color(0x22FFFFFF))
        Box(Modifier.fillMaxSize().background(Color.White)) {
            when (tab) {
                0 -> PanelOverview(e, label)
                1 -> PanelMessage(e, request = true)
                else -> PanelMessage(e, request = false)
            }
        }
    }
}

@Composable
private fun PanelTabs(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        items.forEachIndexed { i, s ->
            val active = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) Color(0x33FFFFFF) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    s,
                    color = if (active) Color.White else PanelSub,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun PanelOverview(e: HttpExchange, label: String) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        PanelKV("请求地址", e.url, mono = true)
        PanelKV(
            "连接状态",
            when (e.state) {
                HttpExchange.State.PENDING -> "进行中"
                HttpExchange.State.COMPLETE -> "${e.statusCode} ${e.statusText}".trim()
                HttpExchange.State.FAILED -> "失败：${e.error ?: "-"}"
            }
        )
        PanelKV("所属进程", label.ifBlank { "UID ${e.uid}" })
        PanelKV("远程地址", e.remoteIp ?: e.host, mono = true)
        PanelKV("上行 / 下行", "${formatSize(e.requestBody.size)} / ${formatSize(e.responseBody.size)}")
        PanelKV("总耗时", formatDuration(e.durationMs))
        PanelKV("请求时间", formatFullTime(e.startTime), mono = true)
        PanelKV("完成时间", if (e.endTime > 0) formatFullTime(e.endTime) else "—", mono = true)
        PanelKV("标识符", e.id.uppercase(), mono = true)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PanelKV(k: String, v: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(k, color = Color(0xFF8A9099), fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        SelectionContainer {
            Text(
                v.ifBlank { "-" },
                color = Color(0xFF1A1A1A), fontSize = 13.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
            )
        }
    }
}

@Composable
private fun PanelMessage(e: HttpExchange, request: Boolean) {
    val headers = if (request) e.requestHeaders else e.responseHeaders
    val startLine = if (request) {
        "${e.method} ${e.path.ifEmpty { "/" }} HTTP/1.1"
    } else {
        if (e.statusCode > 0) "HTTP/1.1 ${e.statusCode} ${e.statusText}" else "（尚无响应）"
    }
    val fileName = remember(e, request) {
        "${e.host.replace(Regex("[^A-Za-z0-9._-]"), "_")}_${if (request) "req" else "resp"}"
    }
    // body 上方信息（起始行 + 头部）默认折叠，避免头部过长挤占 body
    var infoExpanded by remember(e, request) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // 折叠条：整行可点，收起时单行显示起始行 + 头部数量
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFF2F2F7))
                .clickable { infoExpanded = !infoExpanded }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                startLine, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, color = Color(0xFF1A1A1A),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!infoExpanded) {
                Spacer(Modifier.width(6.dp))
                Text(
                    if (headers.isEmpty()) "无头部" else "${headers.size} 个头部",
                    fontSize = 10.sp, color = Color(0xFF8A9099)
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                if (infoExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (infoExpanded) "收起" else "展开",
                tint = Color(0xFF8A9099),
                modifier = Modifier.size(16.dp)
            )
        }
        if (infoExpanded) {
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Column {
                    if (headers.isEmpty()) {
                        Text("（无头部）", fontSize = 11.sp, color = Color(0xFF8A9099))
                    } else {
                        headers.forEach { (k, v) ->
                            Row {
                                Text(
                                    "$k: ", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium, color = Color(0xFF1A1A1A)
                                )
                                Text(
                                    v, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF1A1A1A)
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = Color(0xFFE0E0E0))
        Spacer(Modifier.height(10.dp))
        BodyPanel(
            rawBytes = if (request) e.requestBody else e.responseBody,
            contentType = if (request) e.requestContentType else e.responseContentType,
            contentEncoding = e.headerValue(headers, "Content-Encoding"),
            searchable = true,
            fileName = fileName,
            // 仅请求页：在「复制」左侧提供 cURL 复制
            actionSlot = if (request) {
                { CurlCopyAction(e) }
            } else null
        )
    }
}

/**
 * 请求页的「复制 cURL」按钮：放在 Body 头部「复制」左侧。
 * 面板运行在后台服务里，Toast 可能被系统拦截，因此用按钮内联文字反馈。
 */
@Composable
private fun CurlCopyAction(e: HttpExchange) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(e) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val accent = if (copied) Color(0xFF34C759) else StreamColors.Blue
    Text(
        if (copied) "已复制" else "cURL",
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = accent,
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(accent.copy(alpha = 0.12f))
            .clickable {
                clipboard.setText(AnnotatedString(buildCurl(e)))
                copied = true
            }
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

private val PanelBg = Color(0xFF121419)
private val PanelSub = Color(0xFF9AA0AB)

@Composable
private fun PanelRow(e: HttpExchange, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val mc = when (e.method.uppercase()) {
            "GET" -> Color(0xFF2E7D32)
            "POST" -> Color(0xFF1565C0)
            "PUT" -> Color(0xFFEF6C00)
            "DELETE" -> Color(0xFFC62828)
            "PATCH" -> Color(0xFF6A1B9A)
            else -> Color(0xFF546E7A)
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(mc)
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text(e.method.ifEmpty { "?" }, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.host, color = Color(0xFFE8EAED), fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (e.path.isNotEmpty()) {
                Text(
                    e.path.substringBefore('?'), color = PanelSub, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (e.statusCode > 0) e.statusCode.toString() else "…",
            color = if (e.statusCode > 0) statusColor(e.statusCode) else PanelSub,
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = totalSec % 3600 / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
