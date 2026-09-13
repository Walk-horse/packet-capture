package com.ht.streamdesk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun RequestListView(
    client: SyncClient,
    panel: Panel,
    selectedId: String?,
    search: String,
    onSearchChange: (String) -> Unit,
    onSelect: (String?) -> Unit,
) {
    val exchanges by client.exchanges.collectAsState()
    var typeSel by remember { mutableStateOf(ReqType.All) }

    // 一次遍历同时得到「搜索结果 / 各类型计数 / 当前类型结果」，避免重复分类
    val prepared = remember(exchanges, panel, search, typeSel) {
        val base = when (panel) {
            Panel.All -> exchanges
            is Panel.Session -> exchanges.filter { it.sessionId == panel.id }
            is Panel.Domain -> exchanges.filter { it.host == panel.host }
            Panel.Passthrough -> emptyList()
        }
        val q = search.trim()
        val searched = if (q.isEmpty()) base else base.filter { e ->
            e.host.contains(q, ignoreCase = true) ||
                e.path.contains(q, ignoreCase = true) ||
                e.method.contains(q, ignoreCase = true) ||
                e.statusCode.toString().contains(q)
        }
        val counts = HashMap<ReqType, Int>()
        val rows = ArrayList<ExchangeRecord>(searched.size)
        for (e in searched) {
            val t = classifyType(e)
            counts[t] = (counts[t] ?: 0) + 1
            if (typeSel == ReqType.All || t == typeSel) rows.add(e)
        }
        Triple(searched, counts, rows)
    }
    val (searched, counts, rows) = prepared

    Column(Modifier.width(432.dp).fillMaxHeight().background(Color.White)) {
        if (panel is Panel.Domain) {
            val host = panel.host
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF3F5F8))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StreamIcon(StreamIconKind.Globe, size = 13.dp, tint = Accent)
                Spacer(Modifier.width(6.dp))
                Text(
                    host.ifEmpty { "(无域名)" },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("${searched.size} 个请求", fontSize = 11.sp, color = SubText)
            }
        }

        // 搜索
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StreamIcon(StreamIconKind.Search, size = 13.dp, tint = SubText)
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF2F3F5))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (search.isEmpty()) {
                    Text("搜索 host / path / method / 状态码", fontSize = 11.sp, color = SubText)
                }
                BasicTextField(
                    value = search,
                    onValueChange = onSearchChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 11.sp, color = Color(0xFF1F2328)),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (search.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "清空",
                    fontSize = 11.sp,
                    color = Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onSearchChange("") }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text("${rows.size} 条", fontSize = 11.sp, color = SubText)
            if (panel !is Panel.Passthrough) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "清屏",
                    fontSize = 11.sp,
                    color = Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { client.clearScreen() }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }

        // 类型过滤
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ReqType.filterable.forEach { t ->
                TypeChip(
                    title = t.label,
                    count = if (t == ReqType.All) null else (counts[t] ?: 0),
                    selected = typeSel == t,
                    onClick = { typeSel = t },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = HairLine)

        when {
            panel is Panel.Passthrough -> PassListView(client)
            rows.isEmpty() -> EmptyState(
                title = if (typeSel == ReqType.All || searched.isEmpty()) "暂无请求" else "当前类型下无请求",
                hint = if (exchanges.isEmpty()) "填写手机同步地址后点击「手动同步」" else null,
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { e ->
                    ExchangeRow(
                        e = e,
                        selected = e.id == selectedId,
                        onClick = { onSelect(e.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TypeChip(title: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent else ChipBg)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 11.sp, color = if (selected) Color.White else Color(0xFF1F2328))
        if (count != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                "$count",
                fontSize = 11.sp,
                color = if (selected) Color.White.copy(alpha = 0.85f) else SubText,
            )
        }
    }
}

@Composable
private fun ExchangeRow(e: ExchangeRecord, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) SelectedBg else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .width(52.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(methodColor(e.method))
                .padding(vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (e.method.isEmpty()) "?" else e.method,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.displayPath,
                fontSize = 12.sp,
                color = Color(0xFF1F2328),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                e.host,
                fontSize = 11.sp,
                color = SubText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.width(84.dp), horizontalAlignment = Alignment.End) {
            when {
                e.isFailed -> Text("失败", fontSize = 11.sp, color = Color(0xFFE53935), fontWeight = FontWeight.SemiBold)
                e.statusCode == 0 -> Text("进行中", fontSize = 11.sp, color = SubText)
                else -> Text(
                    "${e.statusCode}",
                    fontSize = 11.sp,
                    color = statusColor(e.statusCode),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(fmtDuration(e.durationMs), fontSize = 10.sp, color = SubText)
            Text(fmtTime(e.startTime), fontSize = 10.sp, color = SubText)
        }
    }
}

@Composable
private fun PassListView(client: SyncClient) {
    val list by client.passthrough.collectAsState()
    if (list.isEmpty()) {
        EmptyState("暂无透传连接", null)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.width(20.dp)) {
                    StreamIcon(
                        if (p.tls) StreamIconKind.LockSlash else StreamIconKind.Swap,
                        size = 14.dp,
                        tint = Color(0xFFF57C00),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text("${p.host}:${p.port}", fontSize = 12.sp, color = Color(0xFF1F2328))
                    Text("原因：${p.reason}", fontSize = 11.sp, color = SubText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.width(100.dp), horizontalAlignment = Alignment.End) {
                    Text(
                        "↑${fmtSize(p.upBytes)} ↓${fmtSize(p.downBytes)}",
                        fontSize = 10.sp,
                        color = Color(0xFF1F2328),
                    )
                    Text(fmtDuration(p.durationMs), fontSize = 10.sp, color = SubText)
                    Text(fmtTime(p.startTime), fontSize = 10.sp, color = SubText)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, hint: String?) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StreamIcon(StreamIconKind.Search, size = 26.dp, tint = Color(0xFFB9BEC6))
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 12.sp, color = SubText)
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, fontSize = 11.sp, color = SubText)
        }
    }
}
