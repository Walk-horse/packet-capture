package com.ht.stream.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 可启动的应用条目（用于窗口化选择进程） */
data class LaunchApp(val label: String, val pkg: String, val uid: Int, val icon: Bitmap?)

/** 列出带启动入口的已安装应用（排除自身），按名称排序 */
fun loadLaunchableApps(ctx: Context): List<LaunchApp> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
    val seen = HashSet<String>()
    val out = ArrayList<LaunchApp>()
    for (ri in resolved) {
        val pkg = ri.activityInfo?.packageName ?: continue
        if (pkg == ctx.packageName) continue
        if (!seen.add(pkg)) continue
        val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
        val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pkg)
        val icon = runCatching { pickerDrawableToBitmap(ai.loadIcon(pm), 96) }.getOrNull()
        out.add(LaunchApp(label, pkg, ai.uid, icon))
    }
    return out.sortedBy { it.label }.also { list ->
        // 便于排查「已安装应用不在列表里」：确认包可见性与是否有 launcher 入口
        android.util.Log.d("AppPicker", "visible launcher apps=${list.size} pkgs=${list.joinToString(", ") { it.pkg }}")
    }
}

private fun pickerDrawableToBitmap(d: Drawable, size: Int): Bitmap {
    val w = d.intrinsicWidth.let { if (it > 0) it else size }
    val h = d.intrinsicHeight.let { if (it > 0) it else size }
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
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

/** 窗口化：选择要抓包的进程（可启动的应用，支持按应用名 / 包名搜索） */
@Composable
fun WindowPickScreen(
    onBack: () -> Unit,
    onPick: (LaunchApp) -> Unit
) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<LaunchApp>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(ctx.applicationContext) }
    }

    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        StreamTopBar(title = "选择进程", onBack = onBack, backLabel = "总览")

        // 搜索框：按应用名 / 包名过滤
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(StreamColors.BgGray)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Search, contentDescription = null,
                    tint = StreamColors.SubText, modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).padding(vertical = 7.dp),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF1A1A1A)),
                    cursorBrush = SolidColor(StreamColors.Blue),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text("搜索应用名 / 包名", fontSize = 14.sp, color = StreamColors.SubText)
                        }
                        inner()
                    }
                )
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.Close, contentDescription = "清空",
                        tint = StreamColors.SubText,
                        modifier = Modifier.size(16.dp).clickable { query = "" }
                    )
                }
            }
        }
        HorizontalDivider(color = StreamColors.Divider)

        val list = apps
        val filtered = remember(list, query) {
            val src = list.orEmpty()
            val q = query.trim()
            if (q.isEmpty()) src
            else src.filter { it.label.contains(q, true) || it.pkg.contains(q, true) }
        }
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载应用列表…", color = StreamColors.SubText, fontSize = 13.sp)
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("未找到可启动的应用", color = StreamColors.SubText, fontSize = 13.sp)
            }
            else -> LazyColumn(Modifier.fillMaxSize().background(Color.White)) {
                if (query.isBlank()) {
                    item {
                        Text(
                            "选择后：桌面出现悬浮图标，并打开该应用；点击悬浮图标可半屏查看该进程的抓包详情。",
                            fontSize = 12.sp,
                            color = StreamColors.SubText,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "未找到匹配「${query.trim()}」的应用",
                            fontSize = 13.sp,
                            color = StreamColors.SubText,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    items(filtered, key = { it.pkg }) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(app) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (app.icon != null) {
                                Image(
                                    bitmap = app.icon.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(9.dp))
                                )
                            } else {
                                Box(Modifier.size(40.dp).background(StreamColors.Divider, RoundedCornerShape(9.dp)))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, fontSize = 15.sp, color = Color(0xFF1A1A1A), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.pkg, fontSize = 12.sp, color = StreamColors.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 68.dp), color = StreamColors.Divider)
                    }
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }
}
