package com.ht.streamdesk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val detailTabs = listOf("请求头", "请求体", "响应头", "响应体")

@Composable
fun DetailView(client: SyncClient, id: String?) {
    val exchanges by client.exchanges.collectAsState()
    val record = remember(id, exchanges) { id?.let { i -> exchanges.firstOrNull { it.id == i } } }
    // 默认停在「响应体」，与 mac 版一致
    var tab by remember(record?.id) { mutableIntStateOf(3) }

    if (record == null) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StreamIcon(StreamIconKind.Doc, size = 30.dp, tint = Color(0xFFB9BEC6))
            Spacer(Modifier.height(10.dp))
            Text("在中间列表选择一个请求查看详情", fontSize = 12.sp, color = SubText)
        }
        return
    }

    val e = record
    Column(Modifier.fillMaxSize().background(Color.White)) {
        HeaderBlock(e)
        HorizontalDivider(color = HairLine)
        Box(Modifier.padding(10.dp)) {
            Segmented(
                items = detailTabs,
                selectedIndex = tab,
                onSelect = { tab = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HorizontalDivider(color = HairLine)
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> HeaderTable(e.requestHeaderPairs)
                1 -> BodyView(
                    bodyKey = "${e.id}-req-${e.requestBodyB64.length}",
                    data = e.requestData,
                    encoding = e.reqEncoding,
                    contentType = e.reqType,
                    truncated = e.requestBodyTruncated,
                )
                2 -> HeaderTable(e.responseHeaderPairs)
                else -> BodyView(
                    bodyKey = "${e.id}-resp-${e.responseBodyB64.length}",
                    data = e.responseData,
                    encoding = e.respEncoding,
                    contentType = e.respType,
                    truncated = e.responseBodyTruncated,
                )
            }
        }
    }
}

@Composable
private fun HeaderBlock(e: ExchangeRecord) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(methodColor(e.method))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    e.method,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                e.url,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF1F2328),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            LinkText("复制 URL") { clipboard.setText(AnnotatedString(e.url)) }
            CopyCurlButton(e)
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Meta("状态", statusSummary(e), statusColor(e.statusCode))
            Meta("耗时", fmtDuration(e.durationMs), Color(0xFF1F2328))
            Meta("时间", fmtFullTime(e.startTime), Color(0xFF1F2328))
            Meta("请求体", fmtSize(e.requestSize), Color(0xFF1F2328))
            Meta("响应体", fmtSize(e.responseSize), Color(0xFF1F2328))
            e.remoteIp?.let { Meta("远端 IP", it, Color(0xFF1F2328)) }
            if (e.uid >= 0) Meta("UID", "${e.uid}", Color(0xFF1F2328))
            if (e.favorite) Meta("收藏", "★", Color(0xFFF9A825))
        }

        if (e.error != null) {
            Spacer(Modifier.height(6.dp))
            Text("错误：${e.error}", fontSize = 11.sp, color = Color(0xFFE53935))
        }
    }
}

private fun statusSummary(e: ExchangeRecord): String = when {
    e.isFailed -> "失败"
    e.statusCode == 0 -> "未完成"
    else -> "${e.statusCode} ${e.statusText}"
}

@Composable
private fun Meta(title: String, value: String, color: Color) {
    Row {
        Text(title, fontSize = 11.sp, color = SubText)
        Spacer(Modifier.width(3.dp))
        Text(value, fontSize = 11.sp, color = color)
    }
}

@Composable
private fun CopyCurlButton(e: ExchangeRecord) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(e.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    LinkText(if (copied) "已复制 ✓" else "复制 cURL") {
        clipboard.setText(AnnotatedString(curlCommand(e)))
        copied = true
    }
}

@Composable
private fun HeaderTable(pairs: List<Pair<String, String>>) {
    if (pairs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("无 Header", fontSize = 12.sp, color = SubText)
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        pairs.forEachIndexed { i, (name, value) ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1F2328),
                    modifier = Modifier.width(180.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    value,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF1F2328),
                    modifier = Modifier.weight(1f),
                )
            }
            if (i != pairs.lastIndex) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = Color(0xFFF0F1F3))
                Spacer(Modifier.height(4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
