package com.ht.stream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.stream.data.HttpExchange
import com.ht.stream.data.RequestStore

/** 全部请求列表：sessionId 为 null 表示全部（含进行中） */
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
    val listState = rememberLazyListState()
    // 切换 全部请求/按域名 时回到顶部
    LaunchedEffect(tab) { listState.scrollToItem(0) }

    val base = remember(all, sessionId) {
        if (sessionId == null) all else all.filter { it.sessionId == sessionId }
    }
    val filtered = remember(base, filter) {
        if (filter.isBlank()) base
        else base.filter {
            it.host.contains(filter, true) || it.path.contains(filter, true) || it.method.contains(filter, true)
        }
    }

    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        StreamTopBar(
            title = "全部请求",
            onBack = onBack,
            backLabel = if (sessionId == null) "总览" else "抓包历史",
            actions = {
                IconButton(onClick = { searchOn = !searchOn; if (!searchOn) filter = "" }) {
                    Icon(
                        if (searchOn) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = Color.White
                    )
                }
            }
        )

        if (searchOn) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 6.dp),
                placeholder = { Text("按 host / path / method 过滤", fontSize = 13.sp) },
                singleLine = true
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactTabs(
                items = listOf("全部请求", "按域名"),
                selected = tab,
                onSelect = { tab = it }
            )
        }

        Text(
            "共 ${filtered.size} 个请求",
            fontSize = 12.sp,
            color = StreamColors.SubText,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        LazyColumn(
            Modifier.fillMaxSize().background(Color.White),
            state = listState,
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (tab == 0) {
                items(filtered, key = { it.id }) { e ->
                    ExchangeRow(e) { onOpen(e) }
                }
            } else {
                val grouped = filtered.groupBy { it.host }.toList().sortedByDescending { it.second.size }
                grouped.forEach { (host, list) ->
                    item(key = "h_$host") {
                        Text(
                            "$host（${list.size}）",
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = StreamColors.Blue,
                            modifier = Modifier.fillMaxWidth()
                                .background(StreamColors.BgGray)
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                    items(list, key = { it.id }) { e ->
                        ExchangeRow(e) { onOpen(e) }
                    }
                }
            }
        }
    }
}

@Composable
fun ExchangeRow(e: HttpExchange, onClick: () -> Unit) {
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
            Text(
                e.host, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                e.path.ifEmpty { "/" },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
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
