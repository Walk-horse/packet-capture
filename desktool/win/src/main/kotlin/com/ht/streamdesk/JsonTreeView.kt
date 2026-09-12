package com.ht.streamdesk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// 语法着色（DevTools 风格）
private val JsonKeyColor = Color(0xFF9C27B0)
private val JsonStringColor = Color(0xFF2E7D32)
private val JsonNumberColor = Color(0xFF1565C0)
private val JsonBoolColor = Color(0xFFAD1457)
private val JsonNullColor = Color(0xFF757575)
private val JsonPunctColor = Color(0xFF616161)

private val jsonParser = Json { isLenient = true; ignoreUnknownKeys = true }

/**
 * 可折叠 / 展开的 JSON 树视图。
 *
 * - 容器（对象/数组）行可点击折叠，折叠时显示 `… N 项` 摘要；
 * - 默认折叠 depth≥2 的容器，避免大 JSON 一次性铺开卡顿；
 * - 键/字符串/数字/布尔/null 各自着色，标点灰色，等宽字体；
 * - key 顺序保留 JSON 原文顺序（kotlinx JsonObject 保序），与「文本」视图对齐；
 * - query 非空时全量树展示、命中片段加淡黄色背景（对齐 mac 版，不做节点过滤）。
 */
@Composable
fun JsonTreeView(json: String, query: String = "") {
    val root = remember(json) { runCatching { jsonParser.parseToJsonElement(json.trim()) }.getOrNull() }
    if (root == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("JSON 解析失败，可切换到「文本」视图查看原始内容", fontSize = 12.sp, color = Color(0xFFE53935))
        }
        return
    }
    val collapsed = remember(json) { mutableStateOf(defaultCollapsed(root)) }
    val rows = remember(json, collapsed.value, query) { buildRows(root, collapsed.value, query) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
        items(rows, key = { it.path }) { row ->
            JsonTreeRow(row, collapsed.value) { path ->
                val set = collapsed.value.toMutableSet()
                if (set.contains(path)) set.remove(path) else set.add(path)
                collapsed.value = set
            }
        }
    }
}

private data class TreeRow(
    val path: String,
    val depth: Int,
    val line: AnnotatedString,
    val isContainer: Boolean,
)

/**
 * 按 query 命中片段拆分着色：命中片段加淡黄色背景，其余保持 color。
 * 直接在原串上做大小写不敏感搜索，索引与原串天然对齐（避开 lowercased 下标错位坑）。
 */
private fun hl(s: String, color: Color, query: String): AnnotatedString = buildAnnotatedString {
    if (query.isEmpty()) {
        pushStyle(SpanStyle(color = color)); append(s); pop()
        return@buildAnnotatedString
    }
    var start = 0
    while (true) {
        val idx = s.indexOf(query, start, ignoreCase = true)
        if (idx < 0) break
        if (idx > start) {
            pushStyle(SpanStyle(color = color)); append(s.substring(start, idx)); pop()
        }
        pushStyle(SpanStyle(color = color, background = MatchHighlight))
        append(s.substring(idx, idx + query.length))
        pop()
        start = idx + query.length
    }
    if (start < s.length) {
        pushStyle(SpanStyle(color = color)); append(s.substring(start)); pop()
    }
}

private fun braceOf(v: JsonElement) = if (v is JsonObject) "{" else "["
private fun closingBrace(b: String) = if (b == "{") "}" else "]"

private fun defaultCollapsed(node: JsonElement): Set<String> {
    val acc = LinkedHashSet<String>()
    collect(node, "", 0, acc)
    return acc
}

private fun collect(node: JsonElement, path: String, depth: Int, acc: LinkedHashSet<String>) {
    when (node) {
        is JsonObject -> {
            if (depth >= 2) acc.add(path)
            node.entries.forEach { (k, v) -> collect(v, "$path/k:$k", depth + 1, acc) }
        }
        is JsonArray -> {
            if (depth >= 2) acc.add(path)
            node.forEachIndexed { i, v -> collect(v, "$path/i:$i", depth + 1, acc) }
        }
        else -> Unit
    }
}

private fun buildRows(root: JsonElement, collapsed: Set<String>, query: String): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()
    emit(root, null, collapsed, query, "", 0, rows)
    return rows
}

/** 发出一个值：容器→带开关的行 + 子节点 + 收尾；叶子→一行 */
private fun emit(
    v: JsonElement,
    key: String?,
    collapsed: Set<String>,
    query: String,
    path: String,
    depth: Int,
    rows: MutableList<TreeRow>,
) {
    when (v) {
        is JsonObject, is JsonArray -> emitContainer(v, key, collapsed, query, path, depth, rows)
        else -> rows.add(TreeRow(path, depth, leafLine(key, v, query), isContainer = false))
    }
}

private fun emitContainer(
    v: JsonElement,
    key: String?,
    collapsed: Set<String>,
    query: String,
    path: String,
    depth: Int,
    rows: MutableList<TreeRow>,
) {
    val brace = braceOf(v)
    val n = if (v is JsonObject) v.size else (v as JsonArray).size
    val isCollapsed = path.isNotEmpty() && collapsed.contains(path)
    rows.add(
        TreeRow(
            path = path,
            depth = depth,
            line = containerHead(key, brace, n, isCollapsed, query),
            isContainer = path.isNotEmpty(),
        )
    )
    if (!isCollapsed && n > 0) {
        val childDepth = depth + 1
        if (v is JsonObject) {
            v.entries.forEach { (k, child) -> emit(child, k, collapsed, query, "$path/k:$k", childDepth, rows) }
        } else {
            (v as JsonArray).forEachIndexed { i, child -> emit(child, null, collapsed, query, "$path/i:$i", childDepth, rows) }
        }
        rows.add(TreeRow(path + "/close", depth, closeLine(brace), isContainer = false))
    }
}

private fun containerHead(key: String?, brace: String, n: Int, collapsed: Boolean, query: String): AnnotatedString =
    buildAnnotatedString {
        if (key != null) {
            append(hl("\"$key\"", JsonKeyColor, query))
            pushStyle(SpanStyle(color = JsonPunctColor)); append(": "); pop()
        }
        pushStyle(SpanStyle(color = JsonPunctColor))
        if (collapsed) {
            append(brace)
            append("…")
            if (n > 0) append(" $n 项 ")
            append(closingBrace(brace))
        } else {
            append(if (n == 0) brace + closingBrace(brace) else brace)
        }
        pop()
    }

private fun closeLine(brace: String): AnnotatedString = buildAnnotatedString {
    pushStyle(SpanStyle(color = JsonPunctColor)); append(closingBrace(brace)); pop()
}

private fun leafLine(key: String?, v: JsonElement, query: String): AnnotatedString = buildAnnotatedString {
    if (key != null) {
        append(hl("\"$key\"", JsonKeyColor, query))
        pushStyle(SpanStyle(color = JsonPunctColor)); append(": "); pop()
    }
    val p = v as JsonPrimitive
    val c = p.content
    when {
        p is JsonNull -> append(hl("null", JsonNullColor, query))
        p.isString -> append(hl("\"" + c + "\"", JsonStringColor, query))
        c == "true" || c == "false" -> append(hl(c, JsonBoolColor, query))
        else -> append(hl(c, JsonNumberColor, query))
    }
}

@Composable
private fun JsonTreeRow(row: TreeRow, collapsed: Set<String>, onToggle: (String) -> Unit) {
    val isCollapsed = collapsed.contains(row.path)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = row.isContainer) { if (row.isContainer) onToggle(row.path) }
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.isContainer) {
            StreamIcon(
                if (isCollapsed) StreamIconKind.ChevronDown else StreamIconKind.ChevronUp,
                size = 14.dp,
                tint = SubText,
                modifier = Modifier.size(14.dp),
            )
        } else {
            Spacer(Modifier.width(14.dp))
        }
        Spacer(Modifier.width((row.depth * 12).dp))
        Text(row.line, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}
