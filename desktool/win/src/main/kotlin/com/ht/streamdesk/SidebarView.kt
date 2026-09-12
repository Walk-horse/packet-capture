package com.ht.streamdesk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SidebarView(client: SyncClient, panel: Panel, onSelect: (Panel) -> Unit) {
    val exchanges by client.exchanges.collectAsState()
    val sessions by client.sessions.collectAsState()
    val passthrough by client.passthrough.collectAsState()
    var showAllDomains by remember { mutableStateOf(false) }

    val domains = remember(exchanges) {
        val map = HashMap<String, Int>()
        for (e in exchanges) map[e.host] = (map[e.host] ?: 0) + 1
        map.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
    }

    val domainLimit = 20
    val visibleDomains = if (showAllDomains) domains else domains.take(domainLimit)

    Column(Modifier.width(246.dp).fillMaxHeight().background(Color(0xFFFAFBFC))) {
        LazyColumn(Modifier.weight(1f)) {
            item { SectionTitle("会话") }
            item {
                SidebarRow(
                    icon = StreamIconKind.Tray,
                    title = "全部请求",
                    badge = exchanges.size,
                    selected = panel == Panel.All,
                    onClick = { onSelect(Panel.All) },
                )
            }
            items(sessions, key = { it.id }) { s ->
                SidebarRow(
                    icon = if (s.endTime == 0L) StreamIconKind.Pulse else StreamIconKind.Doc,
                    iconTint = if (s.endTime == 0L) Color(0xFFE53935) else SubText,
                    title = fmtFullTime(s.startTime),
                    subtitle = "${fmtDuration(s.durationSec * 1000)} · ${s.requestCount} 请求",
                    badge = s.requestCount,
                    selected = panel == Panel.Session(s.id),
                    onClick = { onSelect(Panel.Session(s.id)) },
                )
            }

            item { SectionTitle("域名") }
            if (visibleDomains.isEmpty()) {
                item {
                    Text("暂无域名", fontSize = 11.sp, color = SubText, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
            items(visibleDomains, key = { "d:" + it.first }) { (host, count) ->
                SidebarRow(
                    icon = StreamIconKind.Globe,
                    iconTint = Accent,
                    title = host.ifEmpty { "(无域名)" },
                    badge = count,
                    selected = panel == Panel.Domain(host),
                    onClick = { onSelect(Panel.Domain(host)) },
                )
            }
            if (domains.size > domainLimit && !showAllDomains) {
                item {
                    Text(
                        "显示全部 ${domains.size} 个域名",
                        fontSize = 11.sp,
                        color = Accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAllDomains = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            item { SectionTitle("其他") }
            item {
                SidebarRow(
                    icon = StreamIconKind.LockSlash,
                    iconTint = Color(0xFFF57C00),
                    title = "透传连接",
                    badge = passthrough.size,
                    selected = panel == Panel.Passthrough,
                    onClick = { onSelect(Panel.Passthrough) },
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        StatsBar(client)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = SubText,
        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SidebarRow(
    icon: StreamIconKind,
    title: String,
    iconTint: Color = SubText,
    subtitle: String? = null,
    badge: Int? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) SelectedBg else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StreamIcon(icon, size = 13.dp, tint = iconTint)
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 12.sp,
                color = Color(0xFF1F2328),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, fontSize = 10.sp, color = SubText, maxLines = 1)
            }
        }
        if (badge != null) {
            Spacer(Modifier.width(6.dp))
            Text("$badge", fontSize = 11.sp, color = SubText)
        }
    }
}

@Composable
private fun StatsBar(client: SyncClient) {
    val stats by client.stats.collectAsState()
    val exchanges by client.exchanges.collectAsState()

    Column(Modifier.fillMaxWidth().background(StatsBg).padding(horizontal = 12.dp, vertical = 8.dp)) {
        HorizontalDivider(color = HairLine, modifier = Modifier.padding(bottom = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (stats.capturing) Color(0xFF2E7D32) else Color(0xFF9E9E9E))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (stats.capturing) "手机正在抓包" else "未在抓包",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(6.dp))
        StatRow("请求 / 透传", "${stats.requests} / ${stats.passthrough}")
        StatRow("上传 / 下载", "${fmtSize(stats.upload)} / ${fmtSize(stats.download)}")
        StatRow("本地已同步", "${exchanges.size} 条")
    }
}

@Composable
private fun StatRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(title, fontSize = 10.sp, color = SubText, modifier = Modifier.weight(1f))
        Text(value, fontSize = 10.sp, color = Color(0xFF1F2328))
    }
}
