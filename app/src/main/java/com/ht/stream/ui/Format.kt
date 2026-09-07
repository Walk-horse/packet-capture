package com.ht.stream.ui

import com.ht.stream.data.HttpExchange
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

fun formatSize(n: Int): String = when {
    n < 1024 -> "${n}B"
    n < 1024 * 1024 -> "%.1fK".format(n / 1024.0)
    else -> "%.1fM".format(n / 1024.0 / 1024.0)
}

fun formatTime(ms: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(ms))

fun formatFullTime(ms: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(ms))

private fun iso8601(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(ms))

fun formatDuration(ms: Long): String = if (ms < 0) "…" else "${ms}ms"

/** 尝试按文本解码 body（支持 gzip/deflate），失败返回 null */
fun decodeBodyPreview(e: HttpExchange, request: Boolean, limit: Int = 64 * 1024): String? {
    val raw = if (request) e.requestBody else e.responseBody
    if (raw.isEmpty()) return ""
    val headers = if (request) e.requestHeaders else e.responseHeaders
    val encoding = e.headerValue(headers, "Content-Encoding")?.lowercase()
    var bytes = raw
    try {
        when (encoding) {
            "gzip" -> bytes = GZIPInputStream(ByteArrayInputStream(raw)).readBytes()
            "deflate" -> bytes = InflaterInputStream(ByteArrayInputStream(raw)).readBytes()
        }
    } catch (_: Exception) {
        return "[解码失败：$encoding，原始 ${raw.size} 字节]"
    }
    val contentType = (if (request) e.requestContentType else e.responseContentType)?.lowercase() ?: ""
    val textual = contentType.isEmpty() || contentType.startsWith("text") ||
        contentType.contains("json") || contentType.contains("xml") ||
        contentType.contains("javascript") || contentType.contains("urlencoded") ||
        contentType.contains("html") || contentType.contains("svg")
    if (!textual) return "[二进制内容：$contentType，${formatSize(bytes.size)}]"
    return try {
        val s = String(bytes, 0, minOf(bytes.size, limit), Charsets.UTF_8)
        if (bytes.size > limit) "$s\n\n…[截断，共 ${formatSize(bytes.size)}]" else s
    } catch (_: Exception) {
        "[无法以 UTF-8 解码，${formatSize(bytes.size)}]"
    }
}

/** 若文本是 JSON 则美化（缩进 2），否则原样返回 */
fun prettyJsonIfPossible(text: String?): String? {
    if (text.isNullOrBlank()) return text
    val t = text.trim()
    if (!t.startsWith("{") && !t.startsWith("[")) return text
    return try {
        when (t[0]) {
            '{' -> org.json.JSONObject(t).toString(2)
            '[' -> org.json.JSONArray(t).toString(2)
            else -> text
        }
    } catch (_: Exception) {
        text
    }
}

/** 生成 curl 命令（body 做 gzip 解码 + 单引号转义） */
fun buildCurl(e: HttpExchange): String {
    val sb = StringBuilder("curl -X ${e.method.ifEmpty { "GET" }} '${e.url}'")
    e.requestHeaders.forEach { (k, v) ->
        // 跳过代理层强制重写的头，导出时按真实重写值给出
        if (k.equals("Accept-Encoding", true) || k.equals("Connection", true)) return@forEach
        sb.append(" \\\n  -H '${k}: ${v.replace("'", "'\\''")}'")
    }
    if (!e.requestContentType.isNullOrEmpty() &&
        e.requestHeaders.none { it.first.equals("Content-Type", true) }
    ) {
        sb.append(" \\\n  -H 'Content-Type: ${e.requestContentType}'")
    }
    if (e.requestBody.isNotEmpty()) {
        val bodyText = decodeBodyPreview(e, request = true, limit = 256 * 1024)
            ?.replace(Regex("\n\n…\\[截断.*$"), "")
        if (!bodyText.isNullOrEmpty() && !bodyText.startsWith("[解码失败") &&
            !bodyText.startsWith("[二进制") && !bodyText.startsWith("[无法")
        ) {
            sb.append(" \\\n  --data-raw '${bodyText.replace("'", "'\\''")}'")
        }
    }
    return sb.toString()
}

/** 不区分大小写高亮所有匹配片段（Compose AnnotatedString） */
fun highlightText(text: String, query: String): androidx.compose.ui.text.AnnotatedString {
    val builder = androidx.compose.ui.text.AnnotatedString.Builder(text)
    val hl = androidx.compose.ui.text.SpanStyle(
        background = androidx.compose.ui.graphics.Color(0xFFFFD54F),
        color = androidx.compose.ui.graphics.Color(0xFF3E2723)
    )
    var i = 0
    while (i <= text.length - query.length) {
        val idx = text.indexOf(query, i, ignoreCase = true)
        if (idx < 0) break
        builder.addStyle(hl, idx, idx + query.length)
        i = idx + query.length
    }
    return builder.toAnnotatedString()
}

/** 匹配次数（不区分大小写） */
fun countMatches(text: String?, query: String): Int {
    if (text == null || query.isEmpty()) return 0
    var c = 0
    var i = 0
    while (true) {
        val idx = text.indexOf(query, i, ignoreCase = true)
        if (idx < 0) break
        c++
        i = idx + query.length
    }
    return c
}

/** 分享用纯文本：请求 + 响应全文 */
fun buildShareText(e: HttpExchange): String {
    val sb = StringBuilder()
    sb.append("${e.method} ${e.url}\n")
    e.requestHeaders.forEach { (k, v) -> sb.append("$k: $v\n") }
    val reqBody = decodeBodyPreview(e, request = true)
    if (!reqBody.isNullOrEmpty()) sb.append("\n$reqBody\n")
    sb.append("\n----------------------------------------\n\n")
    if (e.statusCode > 0) sb.append("HTTP/1.1 ${e.statusCode} ${e.statusText}\n")
    e.responseHeaders.forEach { (k, v) -> sb.append("$k: $v\n") }
    val respBody = decodeBodyPreview(e, request = false)
    if (!respBody.isNullOrEmpty()) sb.append("\n$respBody\n")
    return sb.toString()
}

private fun org.json.JSONObject.putHeaders(headers: List<Pair<String, String>>): org.json.JSONObject {
    val arr = org.json.JSONArray()
    headers.forEach { (k, v) -> arr.put(org.json.JSONObject().put("name", k).put("value", v)) }
    return put("headers", arr)
}

/** 导出 HAR 1.2 */
fun buildHar(e: HttpExchange): String {
    val req = org.json.JSONObject()
        .put("method", e.method)
        .put("url", e.url)
        .put("httpVersion", "HTTP/1.1")
        .putHeaders(e.requestHeaders)
        .put("bodySize", e.requestBody.size)
    val reqText = decodeBodyPreview(e, request = true)
    if (!reqText.isNullOrEmpty()) {
        req.put("postData", org.json.JSONObject()
            .put("mimeType", e.requestContentType ?: "")
            .put("text", reqText))
    }
    val respContent = org.json.JSONObject()
        .put("mimeType", e.responseContentType ?: "")
        .put("size", e.responseBody.size)
    decodeBodyPreview(e, request = false)?.let { respContent.put("text", it) }
    val resp = org.json.JSONObject()
        .put("status", e.statusCode)
        .put("statusText", e.statusText)
        .put("httpVersion", "HTTP/1.1")
        .putHeaders(e.responseHeaders)
        .put("bodySize", e.responseBody.size)
        .put("content", respContent)
    val entry = org.json.JSONObject()
        .put("startedDateTime", iso8601(e.startTime))
        .put("time", if (e.durationMs >= 0) e.durationMs else 0)
        .put("request", req)
        .put("response", resp)
    val log = org.json.JSONObject()
        .put("version", "1.2")
        .put("creator", org.json.JSONObject().put("name", "Packet capture").put("version", "1.0"))
        .put("entries", org.json.JSONArray().put(entry))
    return org.json.JSONObject().put("log", log).toString(2)
}
