package com.ht.stream.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Body 查看器：解压(gzip/deflate/brotli/zstd) → 文本判定 → 魔数识别。
 * 支持 文本 / UTF-16 / HEX 三种视角，二进制可一键导出文件。
 */
@Composable
fun BodyPanel(
    rawBytes: ByteArray,
    contentType: String?,
    contentEncoding: String?,
    searchable: Boolean,
    fileName: String,
    fallback: String? = null
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // 0=文本 1=UTF-16 2=HEX
    var mode by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }

    val decompressed = remember(rawBytes, contentEncoding) {
        if (rawBytes.isEmpty()) rawBytes else decompressBody(contentEncoding, rawBytes)
    }
    val decompressFailed = decompressed == null
    val body = decompressed ?: ByteArray(0)

    val sniff = remember(body) { sniffFormat(body) }
    val rawText = remember(body, contentType) { bodyToText(body, contentType) }
    val isBinary = body.isNotEmpty() && rawText == null
    val prettyText = remember(rawText) { if (rawText == null) null else prettyJsonIfPossible(rawText) }
    val bodySizeText = formatBytes(body.size.toLong())

    val modeText = when (mode) {
        1 -> utf16Text(body)
        2 -> hexDump(body)
        else -> null
    }
    val displayBody: String? = when {
        decompressFailed -> "[解码失败：$contentEncoding，原始 ${formatBytes(rawBytes.size.toLong())} 字节]"
        body.isEmpty() -> if (fallback != null) fallback else null
        mode == 0 -> prettyText ?: if (isBinary) null else rawText
        else -> modeText
    }

    // 二进制时的引导说明
    val binaryNote = when {
        !isBinary || mode != 0 -> null
        sniff != null -> "已识别为 ${sniff.display}（${bodySizeText}），可用 HEX 查看或「导出」后用对应工具打开"
        else -> "未识别出常见文件格式（${bodySizeText}），可切换 HEX / UTF-16 排查，或「导出」为文件分析"
    }

    val matchCount = countMatches(displayBody, query)
    val shownText = when {
        displayBody == null -> null
        query.isEmpty() -> AnnotatedString(displayBody)
        else -> highlightText(displayBody, query)
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Body",
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (isBinary && body.isNotEmpty() && mode == 0) {
                Text(
                    "导出",
                    fontSize = 12.sp,
                    color = StreamColors.Blue,
                    modifier = Modifier
                        .clickable {
                            val path = exportBodyFile(context, body, fileName, sniff)
                            Toast.makeText(
                                context,
                                if (path != null) "已导出：$path" else "导出失败",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .padding(horizontal = 4.dp)
                )
            }
            if (displayBody != null && displayBody.isNotEmpty()) {
                Text(
                    "复制",
                    fontSize = 12.sp,
                    color = StreamColors.Blue,
                    modifier = Modifier
                        .clickable {
                            clipboard.setText(AnnotatedString(displayBody))
                            Toast.makeText(context, "Body 已复制", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 4.dp)
                )
            }
        }

        // 视角切换：仅当有可查看的字节且未解压失败
        if (!decompressFailed && body.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("文本" to 0, "UTF-16" to 1, "HEX" to 2).forEachIndexed { i, (label, m) ->
                    if (i > 0) Spacer(Modifier.width(6.dp))
                    Text(
                        label,
                        fontSize = 11.sp,
                        color = if (mode == m) Color.White else StreamColors.SubText,
                        fontWeight = if (mode == m) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .background(
                                if (mode == m) StreamColors.Blue else Color(0xFFF2F2F7),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                mode = m
                                if (m != 0) query = ""
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                if (binaryNote != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        binaryNote,
                        fontSize = 10.sp,
                        color = Color(0xFF9E9E9E),
                        lineHeight = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (searchable && mode == 0 && displayBody != null && displayBody.isNotEmpty()) {
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
                shownText ?: AnnotatedString(if (body.isEmpty()) "（空）" else ""),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = if (isBinary && mode == 0 && shownText == null) Color(0xFF9E9E9E) else Color.Unspecified,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            )
        }
    }
}

/** 导出字节为文件到 Download；返回展示路径，失败 null */
fun exportBodyFile(context: Context, bytes: ByteArray, nameBase: String, fmt: DetectedFormat?): String? {
    val safe = nameBase.replace(Regex("[^A-Za-z0-9._\\-]"), "_").take(80)
    val fileName = "$safe.${fmt?.ext ?: "bin"}"
    val mime = fmt?.mime ?: "application/octet-stream"
    return try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
            "Download/$fileName"
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            file.absolutePath
        }
    } catch (e: Exception) {
        null
    }
}
