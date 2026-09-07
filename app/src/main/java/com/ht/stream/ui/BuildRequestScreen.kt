package com.ht.stream.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

private data class HeaderPair(var key: String, var value: String)

private class RespState {
    var status by mutableStateOf("")
    var headers by mutableStateOf("")
    var body by mutableStateOf("")
}

private data class ParsedCurl(
    val method: String?,
    val url: String?,
    val headers: List<Pair<String, String>>,
    val body: String?
)

/** 解析 curl 命令：支持 -X/-H/-d/--data-raw/--data-binary/-A/-b/--compressed 等常见参数 */
private fun parseCurl(input: String): ParsedCurl? {
    val text = input.trim()
        .replace("\\\r\n", " ").replace("\\\n", " ")
        .replace("\r\n", " ").replace("\n", " ")
    if (!text.startsWith("curl")) return null
    val tokens = tokenizeShell(text)
    if (tokens.isEmpty()) return null

    val valueFlags = setOf(
        "-X", "--request", "-H", "--header", "-d", "--data", "--data-raw",
        "--data-binary", "--data-ascii", "--data-urlencode", "-A", "--user-agent",
        "-b", "--cookie", "-e", "--referer", "-o", "--output", "-u", "--user"
    )
    var method: String? = null
    var url: String? = null
    var body: String? = null
    var userAgent: String? = null
    var cookie: String? = null
    val headers = mutableListOf<Pair<String, String>>()

    var i = 1 // 跳过 curl
    while (i < tokens.size) {
        val t = tokens[i]
        when {
            t == "-X" || t == "--request" -> {
                method = tokens.getOrNull(++i)?.uppercase()
            }
            t == "-H" || t == "--header" -> {
                tokens.getOrNull(++i)?.let { h ->
                    val idx = h.indexOf(':')
                    if (idx > 0) headers.add(h.substring(0, idx).trim() to h.substring(idx + 1).trim())
                }
            }
            t in listOf("-d", "--data", "--data-raw", "--data-binary", "--data-ascii") -> {
                tokens.getOrNull(++i)?.let { body = if (body == null) it else "$body&$it" }
            }
            t == "-A" || t == "--user-agent" -> userAgent = tokens.getOrNull(++i)
            t == "-b" || t == "--cookie" -> cookie = tokens.getOrNull(++i)
            t.startsWith("-") -> {
                // 带值参数跳过其值，无值参数直接跳过
                if (t in valueFlags) i++
            }
            url == null -> url = t
        }
        i++
    }
    userAgent?.let { ua -> if (headers.none { it.first.equals("User-Agent", true) }) headers.add("User-Agent" to ua) }
    cookie?.let { c -> if (headers.none { it.first.equals("Cookie", true) }) headers.add("Cookie" to c) }
    if (method == null && body != null) method = "POST"
    if (url == null) return null
    return ParsedCurl(method, url, headers, body)
}

/** 按 shell 规则分词（处理单双引号与转义） */
private fun tokenizeShell(s: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote: Char? = null
    var i = 0
    fun flush() {
        if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
    }
    while (i < s.length) {
        val c = s[i]
        when {
            quote == null && (c == '\'' || c == '"') -> quote = c
            quote != null && c == quote -> quote = null
            quote == null && c.isWhitespace() -> flush()
            quote == '"' && c == '\\' && i + 1 < s.length && s[i + 1] in "\"\\$`" -> { cur.append(s[i + 1]); i++ }
            else -> cur.append(c)
        }
        i++
    }
    flush()
    return out
}

/** 无边框紧凑输入框 */
@Composable
private fun PlainInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    mono: Boolean = false
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        textStyle = TextStyle(
            fontSize = 14.sp,
            color = Color(0xFF1C1C1E),
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
        ),
        cursorBrush = SolidColor(StreamColors.Blue),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, color = StreamColors.SubText, fontSize = 14.sp)
                inner()
            }
        }
    )
}

/** 构建请求：方法 + 链接 + 头部 + body，右上角 ▶ 执行；支持粘贴 curl 解析；replay 非空时预填为重放 */
@Composable
fun BuildRequestScreen(onBack: () -> Unit, replay: com.ht.stream.data.HttpExchange? = null) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var tab by remember { mutableIntStateOf(0) }
    var method by remember { mutableStateOf(replay?.method?.ifEmpty { "GET" } ?: "GET") }
    var url by remember { mutableStateOf(replay?.url ?: "") }
    val headers = remember {
        mutableStateListOf<HeaderPair>().apply {
            if (replay != null && replay.requestHeaders.isNotEmpty()) {
                replay.requestHeaders
                    .filter { !it.first.equals("Accept-Encoding", true) && !it.first.equals("Connection", true) }
                    .forEach { add(HeaderPair(it.first, it.second)) }
            } else {
                add(HeaderPair("User-Agent", "PacketCapture/0.1 Android"))
            }
        }
    }
    var body by remember {
        mutableStateOf(
            replay?.let { decodeBodyPreview(it, request = true) }
                ?.takeIf { it.isNotEmpty() && !it.startsWith("[") } ?: ""
        )
    }
    var running by remember { mutableStateOf(false) }
    var showMethodPicker by remember { mutableStateOf(false) }
    var showCurlDialog by remember { mutableStateOf(false) }
    var curlInput by remember { mutableStateOf("") }
    val resp = remember { RespState() }

    val methods = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD")

    if (showMethodPicker) {
        AlertDialog(
            onDismissRequest = { showMethodPicker = false },
            title = { Text("选择方法") },
            text = {
                Column {
                    methods.forEach { m ->
                        TextButton(onClick = { method = m; showMethodPicker = false }) {
                            Text(m, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showCurlDialog) {
        AlertDialog(
            onDismissRequest = { showCurlDialog = false },
            title = { Text("粘贴 curl 命令") },
            text = {
                Column {
                    OutlinedTextField(
                        value = curlInput,
                        onValueChange = { curlInput = it },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        placeholder = { Text("curl 'https://…' -H '…'", fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                        textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    )
                    TextButton(onClick = {
                        curlInput = clipboard.getText()?.text ?: ""
                    }) { Text("从剪贴板读取") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val parsed = parseCurl(curlInput)
                    if (parsed == null) {
                        Toast.makeText(context, "解析失败：不是有效的 curl 命令", Toast.LENGTH_SHORT).show()
                    } else {
                        parsed.method?.let { method = it }
                        parsed.url?.let { url = it }
                        if (parsed.headers.isNotEmpty()) {
                            headers.clear()
                            parsed.headers.forEach { headers.add(HeaderPair(it.first, it.second)) }
                        }
                        parsed.body?.let { body = it }
                        showCurlDialog = false
                        curlInput = ""
                        Toast.makeText(context, "已解析并填充", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("解析") }
            },
            dismissButton = {
                TextButton(onClick = { showCurlDialog = false }) { Text("取消") }
            }
        )
    }

    fun execute() {
        if (url.isBlank()) {
            Toast.makeText(context, "请输入链接", Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        resp.status = ""; resp.headers = ""; resp.body = ""
        val m = method; val u = url.trim()
        val hdrs = headers.map { it.key to it.value }.filter { it.first.isNotBlank() }
        val reqBody = body
        Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    requestMethod = m
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    hdrs.forEach { (k, v) -> setRequestProperty(k, v) }
                    if (m !in listOf("GET", "HEAD") && reqBody.isNotEmpty()) {
                        doOutput = true
                        outputStream.use { it.write(reqBody.toByteArray(Charsets.UTF_8)) }
                    }
                }
                val code = conn.responseCode
                resp.status = "$code ${conn.responseMessage ?: ""}"
                resp.headers = conn.headerFields
                    .filter { it.key != null }
                    .entries.joinToString("\n") { (k, v) -> "$k: ${v.joinToString()}" }
                val stream = try {
                    if (code >= 400) conn.errorStream else conn.inputStream
                } catch (_: Exception) { null }
                val bytes = stream?.let { BufferedInputStream(it).readBytes() } ?: ByteArray(0)
                resp.body = if (bytes.isEmpty()) "(空响应体)"
                else String(bytes, 0, minOf(bytes.size, 256 * 1024), Charsets.UTF_8)
            } catch (e: Exception) {
                resp.status = "请求失败"
                resp.body = e.message ?: e.javaClass.simpleName
            } finally {
                conn?.disconnect()
                running = false
            }
        }.start()
        tab = 1
    }

    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        StreamTopBar(
            title = "构建请求",
            onBack = onBack,
            actions = {
                TextButton(onClick = {
                    curlInput = clipboard.getText()?.text?.takeIf { it.trim().startsWith("curl") } ?: ""
                    showCurlDialog = true
                }) {
                    Text("curl", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                }
                if (running) {
                    CircularProgressIndicator(
                        Modifier.padding(end = 16.dp).size(22.dp),
                        color = Color.White, strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = { execute() }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "执行", tint = Color.White)
                    }
                }
            }
        )

        Box(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 48.dp, vertical = 8.dp)) {
            CompactTabs(
                items = listOf("请求", "响应"),
                selected = tab,
                onSelect = { tab = it }
            )
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (tab == 0) {
                SectionHeader("请求行")
                Group {
                    CellRow("方法", onClick = { showMethodPicker = true }, trailing = {
                        Text(method, color = StreamColors.SubText, fontFamily = FontFamily.Monospace)
                    })
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("链接", fontSize = 15.sp)
                        Spacer(Modifier.width(16.dp))
                        PlainInput(
                            value = url,
                            onValueChange = { url = it },
                            placeholder = "请输入链接",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 请求头部：标题行右侧放「添加」
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("请求头部", fontSize = 13.sp, color = StreamColors.SubText, modifier = Modifier.weight(1f))
                    Row(
                        Modifier.clickable { headers.add(HeaderPair("", "")) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = StreamColors.Blue, modifier = Modifier.size(16.dp))
                        Text("添加", fontSize = 13.sp, color = StreamColors.Blue)
                    }
                }
                Group {
                    if (headers.isEmpty()) {
                        CellRow("暂无请求头", showDivider = false)
                    }
                    headers.forEachIndexed { i, h ->
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PlainInput(
                                    value = h.key,
                                    onValueChange = { headers[i] = h.copy(key = it) },
                                    placeholder = "字段",
                                    modifier = Modifier.weight(2f)
                                )
                                Spacer(Modifier.width(8.dp))
                                PlainInput(
                                    value = h.value,
                                    onValueChange = { headers[i] = h.copy(value = it) },
                                    placeholder = "值",
                                    modifier = Modifier.weight(3f)
                                )
                                IconButton(onClick = { headers.removeAt(i) }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = StreamColors.RedText, modifier = Modifier.size(18.dp))
                                }
                            }
                            if (i < headers.size - 1) {
                                HorizontalDivider(Modifier.padding(start = 16.dp), color = StreamColors.Divider)
                            }
                        }
                    }
                }

                if (method !in listOf("GET", "HEAD")) {
                    SectionHeader("请求体")
                    Group {
                        PlainInput(
                            value = body,
                            onValueChange = { body = it },
                            placeholder = "请求体内容",
                            singleLine = false,
                            mono = true,
                            modifier = Modifier.fillMaxWidth().padding(16.dp).height(110.dp)
                        )
                    }
                }
            } else {
                // 响应页：样式对齐抓包详情（状态行 + headers + Body 复制/搜索/JSON 美化）
                val prettyBody = prettyJsonIfPossible(resp.body.ifEmpty { null })
                var query by remember { mutableStateOf("") }
                val matchCount = countMatches(prettyBody, query)
                val displayBody = when {
                    prettyBody == null -> AnnotatedString("-")
                    query.isEmpty() -> AnnotatedString(prettyBody)
                    else -> highlightText(prettyBody, query)
                }

                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .padding(16.dp)
                ) {
                    Text(
                        resp.status.ifEmpty { "尚未执行" },
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                    )
                    if (resp.headers.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(resp.headers, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Body",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = StreamColors.SubText,
                            modifier = Modifier.weight(1f)
                        )
                        if (!prettyBody.isNullOrEmpty()) {
                            Text(
                                "复制",
                                fontSize = 12.sp,
                                color = StreamColors.Blue,
                                modifier = Modifier.clickable {
                                    clipboard.setText(AnnotatedString(prettyBody))
                                    Toast.makeText(context, "Body 已复制", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                    if (!prettyBody.isNullOrEmpty()) {
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
                                Text("$matchCount 处匹配", fontSize = 11.sp, color = StreamColors.SubText)
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
            Spacer(Modifier.height(32.dp))
        }
    }
}
