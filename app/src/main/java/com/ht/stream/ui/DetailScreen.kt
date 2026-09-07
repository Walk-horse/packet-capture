package com.ht.stream.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.ht.stream.data.HttpExchange
import com.ht.stream.data.RequestStore
import java.io.File

/** 抓包详情（iOS Stream 风格）：总览 / 请求 / 响应 / 时间 + 收藏 + 更多操作 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(exchange: HttpExchange, onBack: () -> Unit, onReplay: (HttpExchange) -> Unit) {
    RequestStore.tick.collectAsState() // 请求完成后自动刷新
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("总览", "请求", "响应", "时间")
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showMore by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        StreamTopBar(
            title = "抓包详情",
            onBack = onBack,
            backLabel = "返回",
            actions = {
                IconButton(onClick = {
                    RequestStore.toggleFavorite(exchange.id)
                    RequestStore.notifyChanged()
                }) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "收藏",
                        tint = if (exchange.favorite) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.75f)
                    )
                }
                IconButton(onClick = { showMore = true }) {
                    Icon(Icons.Default.Share, contentDescription = "更多操作", tint = Color.White)
                }
            }
        )

        CompactTabs(
            items = tabs,
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )

        when (tab) {
            0 -> OverviewTab(exchange)
            1 -> MessageTab(exchange, request = true)
            2 -> MessageTab(exchange, request = false, searchable = true)
            3 -> TimeTab(exchange)
        }
    }

    if (showMore) {
        ModalBottomSheet(onDismissRequest = { showMore = false }) {
            Text(
                "更多操作",
                fontSize = 13.sp,
                color = StreamColors.SubText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
            MoreAction("分享请求和响应") {
                showMore = false
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, buildShareText(exchange))
                }
                context.startActivity(Intent.createChooser(intent, "分享请求和响应"))
            }
            MoreAction("获取 cURL 命令") {
                showMore = false
                clipboard.setText(AnnotatedString(buildCurl(exchange)))
                Toast.makeText(context, "curl 已复制", Toast.LENGTH_SHORT).show()
            }
            MoreAction("编辑重放请求") {
                showMore = false
                onReplay(exchange)
            }
            MoreAction("导出 HAR") {
                showMore = false
                try {
                    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                    val file = File(dir, "${exchange.host}_${exchange.startTime}.har")
                    file.writeText(buildHar(exchange))
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "导出 HAR"))
                } catch (e: Exception) {
                    Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            MoreAction("取消", showDivider = false) { showMore = false }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MoreAction(label: String, showDivider: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White).clickable(onClick = onClick)) {
        Text(
            label,
            fontSize = 16.sp,
            color = StreamColors.Blue,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
        )
        if (showDivider) HorizontalDivider(color = StreamColors.Divider)
    }
}

@Composable
private fun KVRow(label: String, value: String, mono: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$label：", fontSize = 14.sp, color = StreamColors.SubText)
        SelectionContainer {
            Text(
                value,
                fontSize = 14.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
            )
        }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = StreamColors.Divider)
}

@Composable
private fun OverviewTab(e: HttpExchange) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("域名")
        Group { SelectionContainer { Text(e.host, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 12.dp)) } }

        SectionHeader("流量")
        Group {
            KVRow("上行流量", formatSize(e.requestBody.size))
            KVRow("下行流量", formatSize(e.responseBody.size))
        }

        SectionHeader("地址")
        Group {
            KVRow("本地地址", "127.0.0.1", mono = true)
            KVRow("远程地址", e.remoteIp ?: e.host, mono = true)
        }

        SectionHeader("状态")
        Group {
            KVRow("连接状态", when (e.state) {
                HttpExchange.State.PENDING -> "进行中"
                HttpExchange.State.COMPLETE -> "已完成（${e.statusCode} ${e.statusText}）"
                HttpExchange.State.FAILED -> "失败：${e.error ?: "-"}"
            })
            KVRow("请求时间", formatFullTime(e.startTime), mono = true)
        }

        SectionHeader("标识符")
        Group {
            SelectionContainer {
                Text(
                    e.id.uppercase(),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TimeTab(e: HttpExchange) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("时间")
        Group {
            KVRow("请求时间", formatFullTime(e.startTime), mono = true)
            KVRow("完成时间", if (e.endTime > 0) formatFullTime(e.endTime) else "—", mono = true)
            KVRow("总耗时", formatDuration(e.durationMs))
        }
        SectionHeader("流量")
        Group {
            KVRow("上行流量", formatSize(e.requestBody.size))
            KVRow("下行流量", formatSize(e.responseBody.size))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MessageTab(e: HttpExchange, request: Boolean, searchable: Boolean = false) {
    val headers = if (request) e.requestHeaders else e.responseHeaders
    val startLine = if (request) {
        "${e.method} ${e.path.ifEmpty { "/" }} HTTP/1.1"
    } else {
        if (e.statusCode > 0) "HTTP/1.1 ${e.statusCode} ${e.statusText}" else "（尚无响应）"
    }
    val rawBody = decodeBodyPreview(e, request)
    val body = prettyJsonIfPossible(rawBody)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    val matchCount = remember(body, query) {
        if (query.isEmpty() || body == null) 0 else {
            var c = 0
            var i = 0
            while (true) {
                val idx = body.indexOf(query, i, ignoreCase = true)
                if (idx < 0) break
                c++
                i = idx + query.length
            }
            c
        }
    }
    val displayBody = remember(body, query) {
        if (query.isEmpty() || body == null) AnnotatedString(body ?: "（无内容）")
        else highlightMatches(body, query)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SelectionContainer {
            Column {
                Text(startLine, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                headers.forEach { (k, v) ->
                    Row {
                        Text("$k: ", fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                        Text(v, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Body",
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (!body.isNullOrEmpty()) {
                Text(
                    "复制",
                    fontSize = 12.sp,
                    color = StreamColors.Blue,
                    modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString(body))
                        Toast.makeText(context, "Body 已复制", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
        if (searchable && !body.isNullOrEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF2F2F7), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Search, contentDescription = null,
                    tint = StreamColors.SubText, modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("搜索 Body 内容", fontSize = 13.sp, color = StreamColors.SubText)
                        inner()
                    }
                )
                if (query.isNotEmpty()) {
                    Text(
                        "$matchCount 处匹配",
                        fontSize = 11.sp,
                        color = StreamColors.SubText
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.Close, contentDescription = "清除",
                        tint = StreamColors.SubText,
                        modifier = Modifier.size(15.dp).clickable { query = "" }
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(
                displayBody,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            )
        }
    }
}

/** 不区分大小写高亮所有匹配片段 */
private fun highlightMatches(text: String, query: String): AnnotatedString {
    val builder = AnnotatedString.Builder(text)
    val hl = SpanStyle(background = Color(0xFFFFD54F), color = Color(0xFF3E2723))
    var i = 0
    while (i <= text.length - query.length) {
        val idx = text.indexOf(query, i, ignoreCase = true)
        if (idx < 0) break
        builder.addStyle(hl, idx, idx + query.length)
        i = idx + query.length
    }
    return builder.toAnnotatedString()
}
