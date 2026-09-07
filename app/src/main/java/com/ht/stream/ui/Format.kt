package com.ht.stream.ui

import com.ht.stream.data.HttpExchange
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

fun formatBytes(n: Long): String = when {
    n < 1024 -> "${n}B"
    n < 1024 * 1024 -> "%.1fK".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1fM".format(n / 1024.0 / 1024.0)
    else -> "%.2fG".format(n / 1024.0 / 1024.0 / 1024.0)
}

fun formatTime(ms: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(ms))

fun formatFullTime(ms: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(ms))

private fun iso8601(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(ms))

fun formatDuration(ms: Long): String = if (ms < 0) "…" else "${ms}ms"

/** 尝试按文本解码 body（支持 gzip/deflate/brotli/zstd），失败/二进制返回描述性提示 */
fun decodeBodyPreview(e: HttpExchange, request: Boolean, limit: Int = 64 * 1024): String? {
    val raw = if (request) e.requestBody else e.responseBody
    if (raw.isEmpty()) return ""
    val headers = if (request) e.requestHeaders else e.responseHeaders
    val encoding = e.headerValue(headers, "Content-Encoding")?.lowercase()
    val contentType = (if (request) e.requestContentType else e.responseContentType)?.lowercase() ?: ""
    val body = decompressBody(encoding, raw)
    if (body == null) {
        return "[解码失败：$encoding，原始 ${formatSize(raw.size)} 字节]"
    }
    return bodyToText(body, contentType, limit)
}

/**
 * 识别出的真实文件格式（魔数）。用于二进制 body 的提示、导出扩展名。
 */
data class DetectedFormat(val display: String, val ext: String, val mime: String)

fun sniffFormat(b: ByteArray): DetectedFormat? {
    if (b.size < 4) return null
    fun eq(offset: Int, s: String): Boolean {
        if (b.size < offset + s.length) return false
        for (i in s.indices) if (b[offset + i] != s[i].code.toByte()) return false
        return true
    }
    return when {
        eq(0, "\u0089PNG") -> DetectedFormat("PNG 图片", "png", "image/png")
        b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> DetectedFormat("JPEG 图片", "jpg", "image/jpeg")
        eq(0, "GIF8") -> DetectedFormat("GIF 图片", "gif", "image/gif")
        eq(0, "RIFF") && eq(8, "WEBP") -> DetectedFormat("WebP 图片", "webp", "image/webp")
        eq(0, "BM") -> DetectedFormat("BMP 图片", "bmp", "image/bmp")
        eq(0, "%PDF") -> DetectedFormat("PDF 文档", "pdf", "application/pdf")
        eq(0, "PK\u0003\u0004") -> DetectedFormat("ZIP 压缩包", "zip", "application/zip")
        eq(0, "\u001f\u008b") -> DetectedFormat("GZIP 压缩包", "gz", "application/gzip")
        b[0] == 0x28.toByte() && b[1] == 0xB5.toByte() && b[2] == 0x2F.toByte() && b[3] == 0xFD.toByte() ->
            DetectedFormat("Zstandard 压缩", "zst", "application/zstd")
        eq(4, "ftyp") -> DetectedFormat("MP4 视频", "mp4", "video/mp4")
        eq(0, "ID3") || (b[0] == 0xFF.toByte() && b[1] in setOf(0xFB.toByte(), 0xF3.toByte(), 0xF2.toByte())) ->
            DetectedFormat("MP3 音频", "mp3", "audio/mpeg")
        eq(0, "7z\u00bc\u00af'\u001c") -> DetectedFormat("7z 压缩包", "7z", "application/x-7z-compressed")
        eq(0, "Rar!") -> DetectedFormat("RAR 压缩包", "rar", "application/vnd.rar")
        else -> null
    }
}

/**
 * 解压 body 字节。无/未知编码返回原文；已知编码（gzip/deflate/brotli/zstd）失败返回 null。
 */
fun decompressBody(encoding: String?, raw: ByteArray): ByteArray? {
    if (encoding.isNullOrBlank()) return raw
    return when (encoding.trim().lowercase()) {
        "gzip", "x-gzip" -> inflateLimited(raw) { GZIPInputStream(it) }
        "deflate" -> inflateLimited(raw) { InflaterInputStream(it) }
        "br" -> inflateLimited(raw) { org.brotli.dec.BrotliInputStream(it) }
        "zstd" -> inflateLimited(raw) { com.github.luben.zstd.ZstdInputStream(it) }
        else -> raw
    }
}

private fun <T : java.io.InputStream> inflateLimited(raw: ByteArray, factory: (ByteArrayInputStream) -> T): ByteArray? {
    try {
        factory(ByteArrayInputStream(raw)).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_DECOMPRESS) return null // 防解压炸弹
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    } catch (_: Exception) {
        return null
    }
}

private const val MAX_DECOMPRESS = 32L * 1024 * 1024

/**
 * body 字节转展示文本：
 *  - 空 → ""
 *  - 符合文本特征 → UTF-8 解码（含截断标注）
 *  - 否则（魔数命中 / NUL 字节 / 明确非文本 Content-Type）→ null（二进制）
 */
fun bodyToText(bytes: ByteArray, contentType: String?, limit: Int = 64 * 1024): String? {
    if (bytes.isEmpty()) return ""
    val ct = contentType?.lowercase() ?: ""
    val textualHint = ct.isEmpty() || ct.startsWith("text") ||
        ct.contains("json") || ct.contains("xml") || ct.contains("javascript") ||
        ct.contains("urlencoded") || ct.contains("html") || ct.contains("svg") ||
        ct.contains("x-www-form-urlencoded") || ct.contains("graphql")
    if (sniffFormat(bytes) != null) return null // 魔数优先，避免 text/plain 里藏图片
    if (!textualHint) return null
    if (bytes.contains(0x00.toByte())) return null // NUL 字节 → 几乎不可能是文本
    val s = String(bytes, 0, minOf(bytes.size, limit), Charsets.UTF_8)
    return if (bytes.size > limit) "$s\n\n…[截断，共 ${formatSize(bytes.size)}]" else s
}

/** UTF-16 解码查看（自动处理 BOM / 大小端） */
fun utf16Text(bytes: ByteArray, limit: Int = 64 * 1024): String {
    if (bytes.isEmpty()) return ""
    fun cap(s: String) = if (s.length > limit) s.substring(0, limit) + "\n\n…[截断，共 ${formatSize(bytes.size)}]" else s
    val withBom = String(bytes, Charsets.UTF_16)
    if (bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
            (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))) {
        return cap(withBom)
    }
    // 无 BOM：先试大端，若满屏 NUL 换小端
    val be = withBom
    return if (be.count { it == '\u0000' } < be.length / 2) cap(be)
    else cap(String(bytes, Charsets.UTF_16LE))
}

/** HEX dump：地址 + 十六进制 + ASCII */
fun hexDump(bytes: ByteArray, maxBytes: Int = 48 * 1024): String {
    if (bytes.isEmpty()) return ""
    val n = minOf(bytes.size, maxBytes)
    val sb = StringBuilder(n * 4 + 64)
    var off = 0
    while (off < n) {
        val end = minOf(off + 16, n)
        sb.append("%08X  ".format(off))
        for (i in off until end) {
            sb.append("%02X ".format(bytes[i].toInt() and 0xFF))
            if (i - off == 7) sb.append(' ')
        }
        for (i in end until off + 16) sb.append("   ")
        sb.append(" |")
        for (i in off until end) {
            val c = bytes[i].toInt()
            sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
        }
        sb.append("|\n")
        off += 16
    }
    if (bytes.size > maxBytes) sb.append("…[已截断，共 ${formatSize(bytes.size)} 字节]\n")
    return sb.toString()
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
