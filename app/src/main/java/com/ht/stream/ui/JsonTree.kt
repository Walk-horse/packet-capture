package com.ht.stream.ui

import android.util.JsonReader
import android.util.JsonToken
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.StringReader

/**
 * JSON 树形查看：按节点展开 / 折叠。
 * 用流式 JsonReader 构建轻量节点树，超大 JSON 直接放弃（回退文本视角）。
 */

/** JSON 树节点 */
sealed interface JsonNode {
    data class Obj(val entries: List<Pair<String, JsonNode>>) : JsonNode
    data class Arr(val items: List<JsonNode>) : JsonNode
    data class Prim(val text: String, val kind: PrimKind) : JsonNode

    enum class PrimKind { STR, NUM, BOOL, NULL }
}

sealed interface JsonTreeResult {
    data class Ok(val root: JsonNode) : JsonTreeResult
    data object NotJson : JsonTreeResult
    data object TooLarge : JsonTreeResult
}

/** 超过该字符数的 body 不做树形解析（避免内存与排版开销） */
private const val MAX_JSON_TREE_TEXT = 400_000

/** 节点总数上限，超出即视为过大 */
private const val MAX_JSON_NODES = 20_000

/** 单次铺开的行数上限（防止一次组合上万个 Text） */
private const val MAX_JSON_ROWS = 1500

private const val MAX_JSON_DEPTH = 128

/** 值显示长度上限（仅影响展示，复制仍为全文） */
private const val MAX_PRIM_DISPLAY = 400

/** 解析文本为 JSON 树；非 JSON 或过大时返回对应状态 */
fun parseJsonTree(text: String?): JsonTreeResult {
    if (text.isNullOrBlank()) return JsonTreeResult.NotJson
    val t = text.trim()
    if (t.isEmpty() || (t[0] != '{' && t[0] != '[')) return JsonTreeResult.NotJson
    if (t.length > MAX_JSON_TREE_TEXT) return JsonTreeResult.TooLarge
    val count = intArrayOf(0)
    return try {
        val root = JsonReader(StringReader(t)).apply { isLenient = true }.use { r ->
            readJsonNode(r, count, 0)
        } ?: return JsonTreeResult.NotJson
        JsonTreeResult.Ok(root)
    } catch (_: TooLargeJson) {
        JsonTreeResult.TooLarge
    } catch (_: Throwable) {
        JsonTreeResult.NotJson
    }
}

private class TooLargeJson : RuntimeException()

private fun readJsonNode(r: JsonReader, count: IntArray, depth: Int): JsonNode? {
    if (depth > MAX_JSON_DEPTH) throw TooLargeJson()
    count[0]++
    if (count[0] > MAX_JSON_NODES) throw TooLargeJson()
    return when (r.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            r.beginObject()
            val list = ArrayList<Pair<String, JsonNode>>()
            while (r.hasNext()) {
                val name = r.nextName()
                list.add(name to (readJsonNode(r, count, depth + 1) ?: JsonNode.Prim("null", JsonNode.PrimKind.NULL)))
            }
            r.endObject()
            JsonNode.Obj(list)
        }
        JsonToken.BEGIN_ARRAY -> {
            r.beginArray()
            val list = ArrayList<JsonNode>()
            while (r.hasNext()) {
                list.add(readJsonNode(r, count, depth + 1) ?: JsonNode.Prim("null", JsonNode.PrimKind.NULL))
            }
            r.endArray()
            JsonNode.Arr(list)
        }
        JsonToken.STRING -> JsonNode.Prim(r.nextString(), JsonNode.PrimKind.STR)
        JsonToken.NUMBER -> JsonNode.Prim(r.nextString(), JsonNode.PrimKind.NUM)
        JsonToken.BOOLEAN -> JsonNode.Prim(if (r.nextBoolean()) "true" else "false", JsonNode.PrimKind.BOOL)
        JsonToken.NULL -> { r.nextNull(); JsonNode.Prim("null", JsonNode.PrimKind.NULL) }
        else -> { r.skipValue(); JsonNode.Prim("null", JsonNode.PrimKind.NULL) }
    }
}

// ------- 展开折叠状态 -------

private enum class JKind { PRIM, OBJ, ARR, CLOSE }

private class JRow(
    val id: String,
    val depth: Int,
    val kind: JKind,
    val label: String?,
    val isIndex: Boolean,
    val prim: JsonNode.Prim?,
    val size: Int,
    val collapsed: Boolean,
    val bracket: Char
)

/** 所有容器节点路径（用于「折叠全部」） */
private fun collectContainers(node: JsonNode): List<String> {
    val out = ArrayList<String>()
    fun walk(n: JsonNode, path: String) {
        when (n) {
            is JsonNode.Obj -> {
                out.add(path)
                n.entries.forEachIndexed { i, (_, v) -> walk(v, "$path/$i") }
            }
            is JsonNode.Arr -> {
                out.add(path)
                n.items.forEachIndexed { i, v -> walk(v, "$path/$i") }
            }
            is JsonNode.Prim -> Unit
        }
    }
    walk(node, "r")
    return out
}

/** 默认折叠深度 >= [fromDepth] 的容器（根与其直接子级展开，方便一眼看结构） */
private fun collectCollapsedFrom(node: JsonNode, fromDepth: Int): Set<String> {
    val out = HashSet<String>()
    fun walk(n: JsonNode, path: String, depth: Int) {
        when (n) {
            is JsonNode.Obj -> {
                if (depth >= fromDepth) out.add(path)
                n.entries.forEachIndexed { i, (_, v) -> walk(v, "$path/$i", depth + 1) }
            }
            is JsonNode.Arr -> {
                if (depth >= fromDepth) out.add(path)
                n.items.forEachIndexed { i, v -> walk(v, "$path/$i", depth + 1) }
            }
            is JsonNode.Prim -> Unit
        }
    }
    walk(node, "r", 0)
    return out
}

/** 按折叠状态铺平为可见行 */
private fun flattenJson(root: JsonNode, collapsed: Set<String>): Pair<List<JRow>, Boolean> {
    val out = ArrayList<JRow>(256)
    var truncated = false

    fun walk(node: JsonNode, label: String?, isIndex: Boolean, depth: Int, path: String) {
        if (out.size >= MAX_JSON_ROWS) { truncated = true; return }
        when (node) {
            is JsonNode.Prim ->
                out.add(JRow(path, depth, JKind.PRIM, label, isIndex, node, 0, false, ' '))
            is JsonNode.Obj -> {
                val col = path in collapsed
                out.add(JRow(path, depth, JKind.OBJ, label, isIndex, null, node.entries.size, col, '{'))
                if (!col) {
                    for (i in node.entries.indices) {
                        if (out.size >= MAX_JSON_ROWS) { truncated = true; break }
                        val (k, v) = node.entries[i]
                        walk(v, k, false, depth + 1, "$path/$i")
                    }
                    if (!truncated) out.add(JRow("$path#c", depth, JKind.CLOSE, null, false, null, 0, false, '}'))
                }
            }
            is JsonNode.Arr -> {
                val col = path in collapsed
                out.add(JRow(path, depth, JKind.ARR, label, isIndex, null, node.items.size, col, '['))
                if (!col) {
                    for (i in node.items.indices) {
                        if (out.size >= MAX_JSON_ROWS) { truncated = true; break }
                        walk(node.items[i], i.toString(), true, depth + 1, "$path/$i")
                    }
                    if (!truncated) out.add(JRow("$path#c", depth, JKind.CLOSE, null, false, null, 0, false, ']'))
                }
            }
        }
    }

    walk(root, null, false, 0, "r")
    return out to truncated
}

// ------- 搜索（过滤视图） -------

private fun selfMatches(node: JsonNode, label: String?, q: String): Boolean {
    if (label != null && label.contains(q, true)) return true
    return node is JsonNode.Prim && node.text.contains(q, true)
}

private fun subTreeMatches(node: JsonNode, label: String?, q: String): Boolean {
    if (selfMatches(node, label, q)) return true
    return when (node) {
        is JsonNode.Prim -> false
        is JsonNode.Obj -> node.entries.any { (k, v) -> subTreeMatches(v, k, q) }
        is JsonNode.Arr -> node.items.anyIndexed { i, v -> subTreeMatches(v, i.toString(), q) }
    }
}

private fun <T> List<T>.anyIndexed(pred: (Int, T) -> Boolean): Boolean {
    for (i in indices) if (pred(i, this[i])) return true
    return false
}

/**
 * 搜索态铺平：只保留「自身命中」或「子树内命中」的节点。
 * 命中容器一律展开（忽略手动折叠状态）；自身命中但子树无命中的容器显示折叠摘要，避免出现空壳。
 */
private fun flattenJsonFiltered(root: JsonNode, query: String): Pair<List<JRow>, Boolean> {
    val q = query.trim()
    val out = ArrayList<JRow>(128)
    var truncated = false

    fun walk(node: JsonNode, label: String?, isIndex: Boolean, depth: Int, path: String) {
        if (out.size >= MAX_JSON_ROWS) { truncated = true; return }
        val self = selfMatches(node, label, q)
        when (node) {
            is JsonNode.Prim -> if (self) out.add(JRow(path, depth, JKind.PRIM, label, isIndex, node, 0, false, ' '))
            is JsonNode.Obj, is JsonNode.Arr -> {
                val obj = node as? JsonNode.Obj
                val kids: List<Pair<String, JsonNode>> = if (obj != null) {
                    obj.entries
                } else {
                    @Suppress("UNCHECKED_CAST")
                    (node as JsonNode.Arr).items.mapIndexed { i, v -> i.toString() to v }
                }
                val size = kids.size
                val childHit = kids.any { (k, v) -> subTreeMatches(v, k, q) }
                if (!self && !childHit) return
                val isArr = obj == null
                val kind = if (isArr) JKind.ARR else JKind.OBJ
                val open = if (isArr) '[' else '{'
                val close = if (isArr) ']' else '}'
                // 自身命中但子树无命中：折叠展示为 "{ … N 项 }"
                val col = self && !childHit
                out.add(JRow(path, depth, kind, label, isIndex, null, size, col, open))
                if (!col) {
                    for (i in kids.indices) {
                        if (out.size >= MAX_JSON_ROWS) { truncated = true; break }
                        val (k, v) = kids[i]
                        walk(v, k, isArr, depth + 1, "$path/$i")
                    }
                    if (!truncated) out.add(JRow("$path#c", depth, JKind.CLOSE, null, false, null, 0, false, close))
                }
            }
        }
    }

    walk(root, null, false, 0, "r")
    return out to truncated
}

/** 统计树中命中查询的节点数（键名或原始值包含 query，忽略大小写） */
fun countJsonMatches(root: JsonNode, query: String): Int {
    val q = query.trim()
    if (q.isEmpty()) return 0
    var n = 0
    fun walk(node: JsonNode, label: String?) {
        if (selfMatches(node, label, q)) n++
        when (node) {
            is JsonNode.Prim -> Unit
            is JsonNode.Obj -> node.entries.forEach { (k, v) -> walk(v, k) }
            is JsonNode.Arr -> node.items.forEachIndexed { i, v -> walk(v, i.toString()) }
        }
    }
    walk(root, null)
    return n
}

// ------- 配色（浅色底，参考 DevTools） -------

private val JsonKeyColor = Color(0xFF881391)
private val JsonStrColor = Color(0xFFC41A16)
private val JsonNumColor = Color(0xFF1C00CF)
private val JsonBoolColor = Color(0xFF0D22AA)
private val JsonNullColor = Color(0xFF8A8A8E)
private val JsonPunctColor = Color(0xFF55575C)
private val JsonIdxColor = Color(0xFF9AA0A8)
private val JsonArrowColor = Color(0xFF1976D2)
private val JsonHitBg = Color(0xFFFFE082)

private fun primDisplay(p: JsonNode.Prim): Pair<String, Color> = when (p.kind) {
    JsonNode.PrimKind.STR -> {
        val q = quoteJson(p.text)
        val s = if (q.length > MAX_PRIM_DISPLAY) q.substring(0, MAX_PRIM_DISPLAY) + "…\"" else q
        s to JsonStrColor
    }
    JsonNode.PrimKind.NUM -> p.text to JsonNumColor
    JsonNode.PrimKind.BOOL -> p.text to JsonBoolColor
    JsonNode.PrimKind.NULL -> "null" to JsonNullColor
}

private fun rowAnnotated(r: JRow): AnnotatedString = buildAnnotatedString {
    // 缩进 + 箭头占位（箭头 1 字符 + 1 空格，保证与无箭头行对齐）
    if (r.depth > 0) append("  ".repeat(r.depth))
    when (r.kind) {
        JKind.OBJ, JKind.ARR -> withStyle(SpanStyle(color = JsonArrowColor)) {
            append(if (r.collapsed) "▸ " else "▾ ")
        }
        else -> append("  ")
    }
    // 键 / 数组下标
    if (r.label != null) {
        if (r.isIndex) withStyle(SpanStyle(color = JsonIdxColor)) { append(r.label) }
        else withStyle(SpanStyle(color = JsonKeyColor)) { append(quoteJson(r.label)) }
        withStyle(SpanStyle(color = JsonPunctColor)) { append(": ") }
    }
    when (r.kind) {
        JKind.PRIM -> r.prim?.let { p ->
            val (text, color) = primDisplay(p)
            withStyle(SpanStyle(color = color)) { append(text) }
        }
        JKind.OBJ, JKind.ARR -> {
            val open = r.bracket
            val close = if (r.bracket == '{') '}' else ']'
            withStyle(SpanStyle(color = JsonPunctColor)) { append(open.toString()) }
            if (r.collapsed) {
                withStyle(SpanStyle(color = JsonPunctColor)) { append(" … ") }
                withStyle(SpanStyle(color = JsonIdxColor)) { append("${r.size} 项") }
                withStyle(SpanStyle(color = JsonPunctColor)) { append(" $close") }
            }
        }
        JKind.CLOSE -> withStyle(SpanStyle(color = JsonPunctColor)) { append(r.bracket.toString()) }
    }
}

/**
 * JSON 树视图：点击容器行展开/折叠；顶部提供「展开全部 / 折叠全部」。
 * [query] 非空时进入搜索态：只列出命中节点及其祖先链，并高亮命中片段。
 * 注意：外层调用方已提供纵向滚动，这里只做内容排列（不用 LazyColumn，避免嵌套滚动冲突）。
 */
@Composable
fun JsonTreeView(root: JsonNode, modifier: Modifier = Modifier, query: String = "") {
    val containers = remember(root) { collectContainers(root) }
    val initial = remember(root) { collectCollapsedFrom(root, fromDepth = 2) }
    var collapsed by remember(root) { mutableStateOf(initial) }

    val q = query.trim()
    // 两个 remember 都无条件调用，保证插槽顺序稳定
    val normal = remember(root, collapsed, q) { if (q.isEmpty()) flattenJson(root, collapsed) else null }
    val searched = remember(root, q) { if (q.isEmpty()) null else flattenJsonFiltered(root, q) }
    val flat = searched ?: normal!!
    val rows = flat.first
    val truncated = flat.second

    Column(modifier.fillMaxWidth()) {
        if (q.isEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TreeAction("展开全部") { collapsed = emptySet() }
                Spacer(Modifier.width(12.dp))
                TreeAction("折叠全部") { collapsed = containers.toSet() }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            if (rows.isEmpty() && q.isNotEmpty()) {
                Text(
                    "未找到匹配「$q」的节点",
                    fontSize = 11.sp, color = JsonNullColor,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            rows.forEach { r ->
                key(r.id) {
                    JsonTreeRow(r, q) {
                        collapsed = if (r.id in collapsed) collapsed - r.id else collapsed + r.id
                    }
                }
            }
            if (truncated) {
                Text(
                    "…[内容过大，仅铺开前 $MAX_JSON_ROWS 行；可切到「文本」查看全部]",
                    fontSize = 11.sp, color = JsonNullColor,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun JsonTreeRow(row: JRow, query: String = "", onToggle: () -> Unit) {
    val line = remember(row, query) { highlightRow(rowAnnotated(row), query) }
    val expandable = row.kind == JKind.OBJ || row.kind == JKind.ARR
    Text(
        line,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = 17.sp,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (expandable && query.isEmpty()) Modifier.clickable { onToggle() } else Modifier)
            .padding(vertical = 1.dp)
    )
}

/** 给整行文本中命中 [query] 的片段加底色（保留原有语法配色） */
private fun highlightRow(src: AnnotatedString, query: String): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return src
    return buildAnnotatedString {
        append(src)
        val t = src.text
        var i = t.indexOf(q, 0, ignoreCase = true)
        while (i >= 0) {
            addStyle(SpanStyle(background = JsonHitBg), i, i + q.length)
            i = t.indexOf(q, i + q.length, ignoreCase = true)
        }
    }
}

@Composable
private fun TreeAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        color = StreamColors.Blue,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}
