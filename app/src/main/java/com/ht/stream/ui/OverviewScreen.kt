package com.ht.stream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.stream.Screen
import com.ht.stream.capture.CaptureVpnService
import com.ht.stream.data.RequestStore
import kotlinx.coroutines.delay

/** 总览页：开始抓包/抓包历史 + 流量统计 + 工具/设置宫格 */
@Composable
fun OverviewScreen(
    running: Boolean,
    onToggle: () -> Unit,
    nav: (Screen) -> Unit
) {
    RequestStore.tick.collectAsState()
    val startedAt by CaptureVpnService.startedAt.collectAsState()
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (true) {
            nowTick = System.currentTimeMillis()
            delay(1000)
        }
    }
    val up = RequestStore.uploadBytes.get()
    val down = RequestStore.downloadBytes.get()
    val reqCount = RequestStore.currentSession?.let { RequestStore.sessionRequestCount(it.id) }
    val elapsedText = if (running && startedAt > 0) formatElapsed(nowTick - startedAt) else null

    Column(
        Modifier
            .fillMaxSize()
            .background(StreamColors.BgGray)
            .verticalScroll(rememberScrollState())
    ) {
        // 蓝色头部 + 悬浮卡片
        Box {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(StreamColors.Blue)
            ) {
                Text(
                    "总览",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 14.dp)
                )
            }
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 100.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onToggle,
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (running) Color(0xFF34C759) else StreamColors.Orange
                            )
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (running) "停止抓包" else "开始抓包", fontSize = if (running) 12.sp else 15.sp)
                                if (elapsedText != null) {
                                    Text(elapsedText, fontSize = 10.sp, color = Color.White.copy(alpha = 0.9f), lineHeight = 12.sp)
                                }
                            }
                        }
                        Button(
                            onClick = { nav(Screen.History) },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StreamColors.ButtonBlue)
                        ) {
                            Text("抓包历史", fontSize = 15.sp)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    ) {
                        StatItem("上传流量", formatByteSize(up), Modifier.weight(1f))
                        StatItem("下载流量", formatByteSize(down), Modifier.weight(1f))
                        StatItem("请求数", reqCount?.toString() ?: "-", Modifier.weight(1f))
                    }
                }
            }
        }

        // 工具
        SectionHeader("工具")
        IconGrid(
            listOf(
                GridItem("构建请求", Icons.Default.Create, StreamColors.Blue) { nav(Screen.BuildRequest()) },
                GridItem("Hosts 设置", Icons.Default.Info, StreamColors.Blue) { nav(Screen.Hosts) },
                GridItem("收藏请求", Icons.Default.Star, StreamColors.Blue) { nav(Screen.Favorites) },
                GridItem("常用工具", Icons.Default.Build, StreamColors.Blue) { nav(Screen.Tools) }
            )
        )

        // 设置
        SectionHeader("设置")
        IconGrid(
            listOf(
                GridItem("HTTPS 抓包", Icons.Default.Lock, StreamColors.Teal) { nav(Screen.Https) },
                GridItem("设置抓包模式", Icons.Default.Settings, StreamColors.Teal) { nav(Screen.CaptureMode) },
                GridItem("日志", Icons.Default.List, StreamColors.Teal) { nav(Screen.Logs) },
                GridItem("教程", Icons.Default.Info, StreamColors.Blue) { nav(Screen.Tutorial) },
                GridItem("关于", Icons.Default.Star, StreamColors.Blue) { nav(Screen.About) }
            )
        )
        Spacer(Modifier.height(32.dp))
        @Suppress("UNUSED_EXPRESSION") nowTick // 驱动统计与计时刷新
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = totalSec % 3600 / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 12.sp, color = StreamColors.SubText)
    }
}

private data class GridItem(
    val label: String,
    val icon: ImageVector,
    val bg: Color,
    val onClick: () -> Unit
)

@Composable
private fun IconGrid(items: List<GridItem>, columns: Int = 3) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        items.chunked(columns).forEach { rowItems ->
            Row(Modifier.fillMaxWidth()) {
                rowItems.forEach { item ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable(onClick = item.onClick)
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier
                                .size(72.dp)
                                .background(item.bg, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(item.icon, contentDescription = item.label, tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(item.label, fontSize = 12.sp)
                    }
                }
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

fun formatByteSize(n: Long): String = when {
    n < 1024 -> "$n Byte"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024.0)
    else -> "%.2f GB".format(n / 1024.0 / 1024.0 / 1024.0)
}
