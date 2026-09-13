package com.ht.streamdesk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 文本视图渲染上限，超出截断（「复制 / 保存」仍为完整内容） */
private const val RENDER_LIMIT = 1024 * 1024
private const val RENDER_NOTE = "\n\n… 为流畅渲染仅显示前 1 MB（完整内容可用「复制 / 保存」导出）"

private enum class BodyMode(val label: String) { Text("文本"), Json("JSON树"), Hex("HEX") }

/** 匹配高亮淡黄色（对齐 mac 版 NSColor(1.0, 0.98, 0.65)） */
val MatchHighlight = Color(0xFFFFFAA6)

/** 大小写不敏感、不重叠的搜索计数（与 mac 版 searchMatchCount 口径一致，上限 5000） */
fun countMatches(s: String, q: String): Int {
    if (s.isEmpty() || q.isEmpty()) return 0
    var count = 0
    var start = 0
    while (count < 5000) {
        val idx = s.indexOf(q, start, ignoreCase = true)
        if (idx < 0) break
        count++
        start = idx + q.length
    }
    return count
}

@Composable
fun BodyView(
    bodyKey: String,
    data: ByteArray,
    encoding: String?,
    contentType: String?,
    truncated: Boolean,
) {
    val clipboard = LocalClipboardManager.current
    var mode by remember { mutableStateOf(BodyMode.Text) }
    var prettyOn by remember { mutableStateOf(false) }
    var decoded by remember { mutableStateOf<String?>(null) }
    var pretty by remember { mutableStateOf<String?>(null) }
    var hex by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchIndex by remember { mutableStateOf(0) }

    val looksLikeJSON = remember(decoded) {
        val t = decoded?.trim().orEmpty()
        t.startsWith("{") || t.startsWith("[")
    }

    // 切换请求 / body 变化：重置并按需解析（默认文本只做轻量解码，对齐 mac 版）
    LaunchedEffect(bodyKey) {
        decoded = null; pretty = null; hex = null
        query = ""; matchIndex = 0
        decoded = withContext(Dispatchers.Default) { decodeText(data)?.let { clip(it) } }
    }
    // 切换视图：按需解析（已解析则复用）
    LaunchedEffect(mode, bodyKey) {
        if (mode == BodyMode.Hex && hex == null) {
            working = true
            hex = withContext(Dispatchers.Default) { hexDump(data) }
            working = false
        }
    }
    // 「JSON 美化」开关：点开才解析（默认纯文本）
    LaunchedEffect(prettyOn, decoded) {
        if (!prettyOn) {
            pretty = null
        } else if (pretty == null) {
            val t = decoded ?: return@LaunchedEffect
            working = true
            pretty = withContext(Dispatchers.Default) { prettyJSON(t)?.let { clip(it) } }
            working = false
        }
    }
    // 搜索词变化：回到第一个匹配
    LaunchedEffect(query) { matchIndex = 0 }

    val displayText = if (mode == BodyMode.Hex) hex else (pretty ?: decoded)
    val searchBase = if (mode == BodyMode.Hex) hex else (pretty ?: decoded)
    val matchCount = remember(searchBase, query) { countMatches(searchBase.orEmpty(), query) }
    val fullCopyText = if (mode == BodyMode.Hex) (hex ?: "") else (pretty ?: decoded ?: decodeText(data) ?: "")

    Column(Modifier.fillMaxSize()) {
        // 第一行：模式切换 + 复制 / 保存
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Segmented(
                items = BodyMode.entries.map { it.label },
                selectedIndex = mode.ordinal,
                onSelect = { mode = BodyMode.entries[it] },
                modifier = Modifier.width(200.dp),
            )
            Spacer(Modifier.weight(1f))
            LinkText("复制") { if (fullCopyText.isNotEmpty()) clipboard.setText(AnnotatedString(fullCopyText)) }
            Spacer(Modifier.width(10.dp))
            LinkText("保存") { saveBody(data, mode == BodyMode.Hex, fullCopyText) }
        }
        // 第二行：信息（左）+ 搜索框（右）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mode == BodyMode.Text && looksLikeJSON) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = prettyOn,
                        onCheckedChange = { prettyOn = it },
                        modifier = Modifier.height(20.dp),
                        colors = CheckboxDefaults.colors(checkedColor = Accent),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("JSON 美化", fontSize = 11.sp, color = Color(0xFF1F2328))
                }
                Spacer(Modifier.width(10.dp))
            }
            Text("大小 ${fmtSize(data.size)}", fontSize = 11.sp, color = SubText)
            if (!contentType.isNullOrEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(contentType, fontSize = 11.sp, color = SubText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!encoding.isNullOrEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "已解压 ${encoding.uppercase()}",
                    fontSize = 10.sp,
                    color = Color(0xFF9A5B00),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x26F57C00))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            if (truncated) {
                Spacer(Modifier.width(8.dp))
                Text("同步已截断", fontSize = 11.sp, color = Color(0xFFF57C00))
            }
            if (working) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.height(12.dp).width(12.dp), strokeWidth = 1.5.dp, color = Accent)
            }

            Spacer(Modifier.weight(1f))

            // 搜索框（文本 / JSON树 / HEX 通用）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White)
                        .border(1.dp, HairLine, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    StreamIcon(StreamIconKind.Search, size = 14.dp, tint = SubText)
                    Spacer(Modifier.width(4.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 11.sp, color = Color(0xFF1F2328)),
                        cursorBrush = SolidColor(Accent),
                        modifier = Modifier.width(160.dp),
                    )
                }
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (matchCount == 0) "0/0" else "${minOf(matchIndex + 1, matchCount)}/$matchCount",
                        fontSize = 11.sp,
                        color = SubText,
                    )
                    if (mode != BodyMode.Json) {
                        Spacer(Modifier.width(4.dp))
                        StreamIcon(
                            StreamIconKind.ChevronUp, size = 13.dp, tint = SubText,
                            modifier = Modifier.clip(RoundedCornerShape(3.dp)).clickable {
                                if (matchCount > 0) matchIndex = (matchIndex - 1 + matchCount) % matchCount
                            },
                        )
                        Spacer(Modifier.width(2.dp))
                        StreamIcon(
                            StreamIconKind.ChevronDown, size = 13.dp, tint = SubText,
                            modifier = Modifier.clip(RoundedCornerShape(3.dp)).clickable {
                                if (matchCount > 0) matchIndex = (matchIndex + 1) % matchCount
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = HairLine)

        when {
            data.isEmpty() -> Placeholder("空 body")
            mode == BodyMode.Json && decoded != null -> JsonTreeView(decoded.orEmpty(), query)
            mode == BodyMode.Json && !looksLikeJSON && !working ->
                Placeholder("非 JSON 内容（共 ${fmtSize(data.size)}），可切换到「文本」或「HEX」视图查看")
            displayText != null -> LargeTextView(displayText, query = query, matchIndex = matchIndex)
            working -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
            }
            else -> Placeholder("二进制内容（共 ${fmtSize(data.size)}），可切换到 HEX 视图查看")
        }
    }
}

/** 手机端文本兜底已取消（协议不再下发 reqText），本地解码并限长 */
private fun clip(s: String): String {
    if (s.toByteArray(Charsets.UTF_8).size <= RENDER_LIMIT) return s
    // 按字节预算保守截断（多字节字符边界安全）
    var bytes = 0
    var end = 0
    while (end < s.length) {
        val cp = s.codePointAt(end)
        val n = if (cp < 0x80) 1 else if (cp < 0x800) 2 else if (cp < 0x10000) 3 else 4
        if (bytes + n > RENDER_LIMIT) break
        bytes += n
        end += Character.charCount(cp)
    }
    return s.substring(0, end) + RENDER_NOTE
}

private fun saveBody(data: ByteArray, asHex: Boolean, text: String) {
    val dlg = FileDialog(Frame(), "保存 body", FileDialog.SAVE).apply {
        file = if (asHex) "body.txt" else "body.bin"
        isVisible = true
    }
    val dir = dlg.directory ?: return
    val name = dlg.file ?: return
    val f = File(dir, name)
    runCatching {
        if (asHex) f.writeText(text, Charsets.UTF_8) else f.writeBytes(data)
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.sp, color = SubText)
    }
}

@Composable
fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.sp,
        color = Accent,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** 简易分段控件（对齐 mac 版 .pickerStyle(.segmented)） */
@Composable
fun Segmented(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .height(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(ChipBg)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { i, label ->
            val active = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (active) Color.White else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 11.sp,
                    color = if (active) Color(0xFF1F2328) else SubText,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
