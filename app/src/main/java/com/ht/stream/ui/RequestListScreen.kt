package com.ht.stream.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.stream.data.HttpExchange
import com.ht.stream.data.PassthroughRec
import com.ht.stream.data.RequestStore

/** 二级页类型：域名明细 / 进程明细 / 透传域名明细 */
private sealed interface Sub {
    /** 按域名 tab：某域名下的各次请求 */
    data class Domain(val host: String) : Sub

    /** 按进程 tab：某进程（uid）发出的各次请求 */
    data class Process(val uid: Int) : Sub

    /** 透传 tab：某域名下的未解密连接明细 */
    data class PassHost(val host: String) : Sub
}

/** 全部请求列表：sessionId 为 null 表示全部（含进行中）。全部请求平铺，其余 tab 一级聚合 → 点击进入二级明细 */
@Composable
fun RequestListScreen(
    sessionId: String?,
    onBack: () -> Unit,
    onOpen: (HttpExchange) -> Unit
) {
    val all by RequestStore.exchanges.collectAsState()
    RequestStore.tick.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var searchOn by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    // 资源类型筛选（全部 / Fetch·XHR / 文档 / CSS / JS / 图片 / Wasm / 其他）
    var typeSel by remember { mutableStateOf(ReqType.ALL) }
    // 当前二级页（域名 / 进程 / 透传域名），null 表示主 tabs 区
    var sub by remember { mutableStateOf<Sub?>(null) }
    val listState = rememberLazyListState()
    // 切换 tab / 切换类型筛选 / 进入或退出二级页时回到顶部
    LaunchedEffect(tab, typeSel, sub) { listState.scrollToItem(0) }
    val ctx = LocalContext.current
    val appInfo = remember(ctx) { AppInfoResolver(ctx.applicationContext) }

    val base = remember(all, sessionId) {
        if (sessionId == null) all else all.filter { it.sessionId == sessionId }
    }
    val filtered = remember(base, filter, typeSel) {
        base.filter { e ->
            (filter.isBlank() ||
                e.host.contains(filter, true) || e.path.contains(filter, true) || e.method.contains(filter, true)) &&
                (typeSel == ReqType.ALL || classifyType(e) == typeSel)
        }
    }
    // 未解密（透传）连接：与请求共用同一作用域（全部 / 单个 session）
    val allPass by RequestStore.passthrough.collectAsState()
    val basePass = remember(allPass, sessionId) {
        if (sessionId == null) allPass else allPass.filter { it.sessionId == sessionId }
    }
    val filteredPass = remember(basePass, filter) {
        if (filter.isBlank()) basePass
        else basePass.filter { it.host.contains(filter, true) }
    }

    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        val cur = sub
        StreamTopBar(
            title = when (cur) {
                is Sub.Domain -> cur.host
                is Sub.Process -> appInfo.info(cur.uid).label
                is Sub.PassHost -> cur.host
                null -> "全部请求"
            },
            onBack = { if (cur != null) sub = null else onBack() },
            backLabel = when (cur) {
                is Sub.Domain -> "按域名"
                is Sub.Process -> "按进程"
                is Sub.PassHost -> "透传"
                null -> if (sessionId == null) "总览" else "抓包历史"
            },
            actions = {
                if (cur == null) {
                    IconButton(onClick = {
                        searchOn = !searchOn
                        if (!searchOn) { filter = ""; typeSel = ReqType.ALL }
                    }) {
                        Icon(
                            if (searchOn) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "搜索",
                            tint = Color.White
                        )
                    }
                }
            }
        )

        when (cur) {
            is Sub.Domain -> {
                val rows = remember(base, cur, typeSel) {
                    base.filter { it.host == cur.host && (typeSel == ReqType.ALL || classifyType(it) == typeSel) }
                }
                ExchangeDetailList(rows, "该域名暂无请求", listState, onOpen, typeSel, { typeSel = it })
            }
            is Sub.Process -> {
                val rows = remember(base, cur, typeSel) {
                    base.filter { it.uid == cur.uid && (typeSel == ReqType.ALL || classifyType(it) == typeSel) }
                }
                ExchangeDetailList(rows, "该进程暂无请求", listState, onOpen, typeSel, { typeSel = it })
            }
            is Sub.PassHost -> {
                val rows = remember(basePass, cur) { basePass.filter { it.host == cur.host } }
                PassthroughDetailList(rows, "该域名暂无未解密连接", listState)
            }
            null -> {
                if (searchOn) {
                    // 紧凑过滤面板：搜索框 + 资源类型分类（透传 tab 无分类）
                    Column(Modifier.fillMaxWidth().background(Color.White)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFF0F1F3))
                                .padding(horizontal = 10.dp, vertical = 0.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Search, contentDescription = null,
                                tint = StreamColors.SubText, modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            BasicTextField(
                                value = filter,
                                onValueChange = { filter = it },
                                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 13.sp, color = Color(0xFF1A1A1A)),
                                cursorBrush = SolidColor(StreamColors.Blue),
                                decorationBox = { inner ->
                                    if (filter.isEmpty()) {
                                        Text("按 host / path / method 过滤", fontSize = 13.sp, color = StreamColors.SubText)
                                    }
                                    inner()
                                }
                            )
                            if (filter.isNotEmpty()) {
                                Icon(
                                    Icons.Default.Close, contentDescription = "清空", tint = StreamColors.SubText,
                                    modifier = Modifier.size(16.dp).clickable { filter = "" }
                                )
                            }
                        }
                        if (tab != 3) {
                            TypeFilterRow(typeSel) { typeSel = it }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactTabs(
                        items = listOf("全部请求", "按域名", "按进程", "透传"),
                        selected = tab,
                        onSelect = { tab = it }
                    )
                }

                Text(
                    when (tab) {
                        1 -> "共 ${filtered.groupBy { it.host }.size} 个域名 · ${filtered.size} 个请求"
                        2 -> "共 ${filtered.groupBy { it.uid }.size} 个进程 · ${filtered.size} 个请求"
                        3 -> "共 ${filteredPass.groupBy { it.host }.size} 个域名 · ${filteredPass.size} 条连接"
                        else -> "共 ${filtered.size} 个请求"
                    },
                    fontSize = 12.sp,
                    color = StreamColors.SubText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                LazyColumn(
                    Modifier.fillMaxSize().background(Color.White),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    when (tab) {
                        0 -> {
                            // 全部请求：平铺每次请求（主标题接口名，副标题域名）
                            if (filtered.isEmpty()) {
                                item { EmptyHint("暂无请求") }
                            } else {
                                items(filtered, key = { it.id }) { e ->
                                    ExchangeRow(e) { onOpen(e) }
                                }
                            }
                        }
                        1 -> {
                            // 按域名：域名聚合 → 二级为请求明细
                            if (filtered.isEmpty()) {
                                item { EmptyHint("暂无请求") }
                            } else {
                                val hosts = filtered.groupBy { it.host }.toList()
                                    .sortedByDescending { it.second.size }
                                items(hosts, key = { "host_${it.first}" }) { (h, list) ->
                                    DomainRow(
                                        host = h,
                                        count = list.size,
                                        downBytes = list.sumOf { it.responseBody.size.toLong() },
                                        onClick = { sub = Sub.Domain(h) }
                                    )
                                }
                            }
                        }
                        2 -> {
                            // 按进程：进程（uid）聚合 → 二级为该进程的请求明细
                            if (filtered.isEmpty()) {
                                item { EmptyHint("暂无请求") }
                            } else {
                                val procs = filtered.groupBy { it.uid }.toList()
                                    .sortedByDescending { it.second.size }
                                items(procs, key = { "proc_${it.first}" }) { (uid, list) ->
                                    val info = appInfo.info(uid)
                                    ProcessRow(
                                        info = info,
                                        count = list.size,
                                        downBytes = list.sumOf { it.responseBody.size.toLong() },
                                        onClick = { sub = Sub.Process(uid) }
                                    )
                                }
                            }
                        }
                        else -> {
                            // 透传：按域名聚合
                            if (filteredPass.isEmpty()) {
                                item {
                                    Text(
                                        "暂无未解密连接（App 拒绝证书 / 抓包模式排除 / 非 HTTP 流量 时会在此记录域名层元数据）",
                                        fontSize = 12.sp,
                                        color = StreamColors.SubText,
                                        lineHeight = 18.sp,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            } else {
                                val hosts = filteredPass.groupBy { it.host }.toList()
                                    .sortedByDescending { it.second.size }
                                items(hosts, key = { "ph_${it.first}" }) { (h, list) ->
                                    DomainPassRow(
                                        host = h,
                                        count = list.size,
                                        down = list.sumOf { it.downBytes.get() },
                                        up = list.sumOf { it.upBytes.get() },
                                        onClick = { sub = Sub.PassHost(h) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 请求明细列表（域名/进程二级页共用）：顶部计数 + 类型筛选（可选） + 请求行 */
@Composable
private fun ExchangeDetailList(
    rows: List<HttpExchange>,
    emptyText: String,
    listState: LazyListState,
    onOpen: (HttpExchange) -> Unit,
    typeSel: ReqType = ReqType.ALL,
    onTypeSelect: ((ReqType) -> Unit)? = null
) {
    if (onTypeSelect != null) {
        TypeFilterRow(typeSel) { onTypeSelect(it) }
    }
    Text(
        "共 ${rows.size} 个请求",
        fontSize = 12.sp,
        color = StreamColors.SubText,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
    LazyColumn(
        Modifier.fillMaxSize().background(Color.White),
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        if (rows.isEmpty()) {
            item { EmptyHint(emptyText) }
        } else {
            items(rows, key = { it.id }) { e ->
                ExchangeRow(e) { onOpen(e) }
            }
        }
    }
}

/** 透传连接明细列表（透传域名二级页）：顶部计数 + 未解密连接行 */
@Composable
private fun PassthroughDetailList(
    rows: List<PassthroughRec>,
    emptyText: String,
    listState: LazyListState
) {
    Text(
        "共 ${rows.size} 条未解密连接",
        fontSize = 12.sp,
        color = StreamColors.SubText,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
    LazyColumn(
        Modifier.fillMaxSize().background(Color.White),
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        if (rows.isEmpty()) {
            item { EmptyHint(emptyText) }
        } else {
            items(rows, key = { it.id }) { r ->
                PassthroughRow(r)
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = StreamColors.SubText,
        modifier = Modifier.padding(16.dp)
    )
}

/** 资源类型筛选行（仿 DevTools）：横向滚动胶囊，选中高亮 */
@Composable
private fun TypeFilterRow(selected: ReqType, onSelect: (ReqType) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ReqType.entries.forEach { t ->
            val active = t == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) StreamColors.Blue else Color.White)
                    .clickable { onSelect(t) }
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(
                    t.label,
                    fontSize = 12.sp,
                    color = if (active) Color.White else Color(0xFF546E7A),
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

/** uid → 应用信息解析器（图标/名称/包名），带内存缓存 */
private class AppInfoResolver(private val ctx: Context) {
    private val pm = ctx.packageManager
    private val cache = LruCache<Int, AppInfo>(128)

    data class AppInfo(val label: String, val pkg: String?, val icon: Bitmap?)

    fun info(uid: Int): AppInfo {
        cache.get(uid)?.let { return it }
        val resolved = if (uid >= 0) {
            runCatching {
                val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
                pkg?.let { it to pm.getApplicationInfo(it, 0) }
            }.getOrNull()
        } else null
        val (pkg, app) = resolved ?: (null to null)
        val label = when {
            app != null -> app.loadLabel(pm).toString()
            uid == 0 -> "Root"
            uid < 0 -> "未知进程"
            uid < 10000 -> "系统进程 (uid $uid)"
            else -> "UID $uid"
        }
        val icon = try {
            if (app != null) drawableToBitmap(app.loadIcon(pm), 96) else null
        } catch (_: Exception) {
            null
        }
        return AppInfo(label, pkg, icon).also { cache.put(uid, it) }
    }
}

/** 把 Drawable 按等比缩放绘制进固定尺寸位图 */
private fun drawableToBitmap(d: Drawable, size: Int): Bitmap {
    val w = d.intrinsicWidth.let { if (it > 0) it else size }
    val h = d.intrinsicHeight.let { if (it > 0) it else size }
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val scale = size.toFloat() / maxOf(w, h).toFloat()
    val nw = (w * scale).toInt().coerceAtLeast(1)
    val nh = (h * scale).toInt().coerceAtLeast(1)
    d.setBounds(0, 0, nw, nh)
    canvas.save()
    canvas.translate((size - nw) / 2f, (size - nh) / 2f)
    d.draw(canvas)
    canvas.restore()
    return bmp
}

/** 进程聚合行（按进程 tab）：应用图标 + 名称 + 请求数/下行，点击进入该进程请求明细 */
@Composable
private fun ProcessRow(
    info: AppInfoResolver.AppInfo,
    count: Int,
    downBytes: Long,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (info.icon != null) {
            Image(
                bitmap = info.icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFECEFF1)),
                contentAlignment = Alignment.Center
            ) {
                Text(info.label.take(1), fontSize = 15.sp, color = StreamColors.SubText)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                info.label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    if (!info.pkg.isNullOrEmpty()) {
                        append(info.pkg); append(" · ")
                    }
                    append("$count 个请求 · ↓"); append(formatBytes(downBytes))
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("›", fontSize = 22.sp, color = Color(0xFFBDBDBD))
    }
}

/** 域名行：点击进入该域名下的接口列表 */
@Composable
private fun DomainRow(host: String, count: Int, downBytes: Long, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                host, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "$count 个请求 · 下行 ↓${formatBytes(downBytes)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("›", fontSize = 22.sp, color = Color(0xFFBDBDBD))
    }
}

/** 透传域名聚合行（透传 tab）：域名 + 连接数与上下行汇总，点击进入该域名连接明细 */
@Composable
private fun DomainPassRow(host: String, count: Int, down: Long, up: Long, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                host, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "$count 条连接 · ↓${formatBytes(down)} ↑${formatBytes(up)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("›", fontSize = 22.sp, color = Color(0xFFBDBDBD))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExchangeRow(e: HttpExchange, onClick: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MethodBadge(e.method)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val title = remember(e.path) { breakAnywhere(e.path.ifEmpty { "/" }) }
            Text(
                title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                lineHeight = 18.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                    onLongClick = {
                        clipboard.setText(AnnotatedString(e.path.ifEmpty { "/" }))
                        Toast.makeText(context, "接口名已复制", Toast.LENGTH_SHORT).show()
                    }
                )
            )
            Text(
                e.host,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            when (e.state) {
                HttpExchange.State.PENDING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                HttpExchange.State.FAILED -> Text("ERR", color = Color(0xFFC62828), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                HttpExchange.State.COMPLETE -> Text(
                    "${e.statusCode}",
                    color = statusColor(e.statusCode),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }
            Text(
                "${formatSize(e.responseBody.size)} · ${formatDuration(e.durationMs)}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PassthroughRow(r: PassthroughRec) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .background(if (r.tls) Color(0xFF455A64) else Color(0xFF78909C), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text("未解密", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                r.host, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "${r.reason} · :${r.port}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "↓${formatBytes(r.downBytes.get())} ↑${formatBytes(r.upBytes.get())}",
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = if (r.endTime == 0L) StreamColors.Blue else Color(0xFF37474F)
            )
            Text(
                if (r.endTime == 0L) "连接中…" else formatDuration(r.durationMs),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MethodBadge(method: String) {
    val color = when (method.uppercase()) {
        "GET" -> Color(0xFF2E7D32)
        "POST" -> Color(0xFF1565C0)
        "PUT" -> Color(0xFFEF6C00)
        "DELETE" -> Color(0xFFC62828)
        "PATCH" -> Color(0xFF6A1B9A)
        else -> Color(0xFF546E7A)
    }
    Box(
        Modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            method.ifEmpty { "?" }, color = Color.White, fontSize = 10.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
        )
    }
}

fun statusColor(code: Int): Color = when {
    code in 200..299 -> Color(0xFF2E7D32)
    code in 300..399 -> Color(0xFF1565C0)
    code in 400..499 -> Color(0xFFEF6C00)
    code >= 500 -> Color(0xFFC62828)
    else -> Color(0xFF546E7A)
}
