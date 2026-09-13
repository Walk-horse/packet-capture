package com.ht.streamdesk

import androidx.compose.ui.graphics.Color
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// MARK: - 格式化（对齐 mac 版 Formatting.swift）

fun fmtSize(n: Int): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", n / 1024.0)
    else -> String.format(Locale.US, "%.2f MB", n / 1024.0 / 1024.0)
}

fun fmtSize(n: Long): String = fmtSize(n.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())

fun fmtDuration(ms: Long): String = when {
    ms < 0 -> "进行中"
    ms < 1000 -> "$ms ms"
    else -> String.format(Locale.US, "%.2f s", ms / 1000.0)
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private val fullFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

fun fmtTime(epochMillis: Long): String = timeFmt.format(Date(epochMillis))
fun fmtFullTime(epochMillis: Long): String = fullFmt.format(Date(epochMillis))

// 中国用户习惯：请求/响应状态色沿用 mac 版口径（成功绿、重定向蓝、客户端错橙、服务端错红）
fun methodColor(m: String): Color = when (m.uppercase(Locale.ROOT)) {
    "GET" -> Color(0xFF1E88E5)
    "POST" -> Color(0xFFF57C00)
    "PUT" -> Color(0xFF8E24AA)
    "DELETE" -> Color(0xFFE53935)
    "PATCH" -> Color(0xFFD81B60)
    "HEAD" -> Color(0xFF00897B)
    else -> Color(0xFF757575)
}

fun statusColor(code: Int): Color = when (code) {
    in 200..299 -> Color(0xFF2E7D32)
    in 300..399 -> Color(0xFF1E88E5)
    in 400..499 -> Color(0xFFF57C00)
    in 500..599 -> Color(0xFFE53935)
    else -> Color(0xFF757575)
}

// MARK: - body 解析

/** 十六进制转储（默认只转前 64KB，避免大 body 卡顿） */
fun hexDump(data: ByteArray, limit: Int = 64 * 1024): String {
    val n = minOf(limit, data.size)
    val sb = StringBuilder(n * 4)
    var offset = 0
    while (offset < n) {
        val end = minOf(offset + 16, n)
        val hex = StringBuilder(48)
        val ascii = StringBuilder(16)
        for (i in offset until end) {
            val b = data[i].toInt() and 0xFF
            hex.append(String.format(Locale.US, "%02x", b))
            if (i != end - 1) hex.append(' ')
            ascii.append(if (b in 0x20..0x7E) b.toChar() else '.')
        }
        while (hex.length < 47) hex.append(' ')
        sb.append(String.format(Locale.US, "%08x  %s  |%s|", offset, hex, ascii)).append('\n')
        offset = end
    }
    if (data.size > limit) {
        sb.append("\n… 仅显示前 ${fmtSize(limit)}（共 ${fmtSize(data.size)}）")
    }
    return sb.toString()
}

/** 严格 UTF-8 解码，失败返回 null（不静默替换为 U+FFFD） */
private fun strictUtf8(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String? =
    try {
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        dec.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

/** 可打印字符占比（用于判定「是不是可读文本」） */
private fun printableRatio(s: String): Double {
    if (s.isEmpty()) return 1.0
    var ok = 0
    for (c in s) {
        val bad = c == '\uFFFD' || (c.code in 0..8) || (c.code in 0x0E..0x1F && c != '\t' && c != '\n' && c != '\r')
        if (!bad) ok++
    }
    return ok.toDouble() / s.length
}

/**
 * 本地兜底解码：UTF-16(BOM) → 严格 UTF-8 → 可读性判定。
 *
 * 与 mac 版有一处**有意的改进**：mac 版最后会退到 Latin-1，而 Latin-1 对任意字节都成功，
 * 于是二进制 body 会被当成乱码文本（并在 cURL 里生成乱码 --data-raw）。
 * 这里改为「严格 UTF-8 失败后按可打印占比判定」，不可读返回 null，交由 HEX 视图处理。
 */
fun decodeText(data: ByteArray): String? {
    if (data.isEmpty()) return ""
    if (data.size >= 2) {
        val b0 = data[0].toInt() and 0xFF
        val b1 = data[1].toInt() and 0xFF
        if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) {
            val body = data.copyOfRange(2, data.size)
            val cs = if (b0 == 0xFE) Charsets.UTF_16BE else Charsets.UTF_16LE
            val dec = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val s = runCatching { dec.decode(ByteBuffer.wrap(body)).toString() }.getOrNull()
            if (s != null && printableRatio(s) >= 0.9) return s
        }
    }
    strictUtf8(data)?.let { return it }
    // 非 UTF-8：单字节文本（GBK/Latin-1 等）里可打印字符占比通常很高，二进制则很低
    val latin = String(data, Charsets.ISO_8859_1)
    return if (printableRatio(latin) >= 0.9) latin else null
}

private val prettyJson = Json { prettyPrint = true; isLenient = true; explicitNulls = false }
private val jsonParser = Json { isLenient = true; ignoreUnknownKeys = true }

/** JSON 美化（用于详情 body 展示开关）；非 JSON 返回 null */
fun prettyJSON(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val first = trimmed.first()
    if (first != '{' && first != '[') return null
    return runCatching {
        val element = jsonParser.parseToJsonElement(trimmed)
        prettyJson.encodeToString(JsonElement.serializer(), element)
    }.getOrNull()
}

// MARK: - cURL

/** 单引号包裹并转义内部单引号，保证可安全粘贴到 shell */
fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/** 由抓包记录还原 cURL 命令（headers 来自请求行，body 用手机端已解压文本） */
fun curlCommand(e: ExchangeRecord): String {
    val method = if (e.method.isEmpty()) "GET" else e.method
    val sb = StringBuilder("curl")
    if (!method.equals("GET", ignoreCase = true)) sb.append(" -X ").append(method)
    sb.append(' ').append(shellQuote(e.url))

    val decompressed = !e.reqEncoding.isNullOrEmpty()
    val skip = if (decompressed) setOf("content-length", "content-encoding") else setOf("content-length")

    for ((name, value) in e.requestHeaderPairs) {
        if (skip.contains(name.lowercase(Locale.ROOT))) continue
        sb.append(" \\\n  -H ").append(shellQuote("$name: $value"))
    }

    val data = e.requestData
    if (data.isNotEmpty()) {
        val text = decodeText(data)
        if (text != null) {
            sb.append(" \\\n  --data-raw ").append(shellQuote(text))
        } else {
            sb.append(" \\\n  --data-binary @<文件路径>（二进制 body，请用「保存」导出后替换）")
        }
    }
    if (decompressed) {
        sb.append(" \\\n  # 原始 body 为 ").append(e.reqEncoding!!.uppercase(Locale.ROOT))
            .append(" 压缩，此处已写入解压后的明文")
    }
    return sb.toString()
}
