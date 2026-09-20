package com.ht.stream.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.stream.data.MockRule
import com.ht.stream.data.MockStore
import com.ht.stream.proxy.MockEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 接口模拟设置页。
 *
 * - 顶部：总开关 + 桌面端下发的规则概览
 * - 中部：已配置接口列表（来自 Mac），点击进入接口详情编辑响应示例
 * - 下部：手机应用列表，每项右侧「开启 / 关闭」——只有开启的应用才会被模拟
 */
@Composable
fun MockScreen(onBack: () -> Unit, onOpenRule: (String) -> Unit) {
    val ctx = LocalContext.current
    var master by remember { mutableStateOf(MockStore.isEnabled(ctx)) }
    var enabledApps by remember { mutableStateOf(MockStore.enabledApps(ctx)) }
    var rules by remember { mutableStateOf(MockStore.rules(ctx)) }
    var updatedAt by remember { mutableStateOf(MockStore.rulesUpdatedAt(ctx)) }
    var apps by remember { mutableStateOf<List<LaunchApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<MockRule?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 轻提示 2 秒后自动消失（清除这类破坏性操作的反馈）
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2000)
            toast = null
        }
    }

    // 进入页面刷新一次：Mac 可能刚下发过规则
    LaunchedEffect(Unit) {
        rules = MockStore.rules(ctx)
        updatedAt = MockStore.rulesUpdatedAt(ctx)
        enabledApps = MockStore.enabledApps(ctx)
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(ctx.applicationContext) }
    }

    val allApps = apps
    val filtered = remember(allApps, query) {
        val src = allApps.orEmpty()
        val q = query.trim()
        if (q.isEmpty()) src
        else src.filter { it.label.contains(q, true) || it.pkg.contains(q, true) }
    }

    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        StreamTopBar(title = "接口模拟", onBack = onBack, backLabel = "总览")

        toast?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF43A047))
                    .padding(horizontal = 16.dp, vertical = 7.dp)
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            // ---------- 概览 ----------
            item { SectionHeader("状态") }
            item {
                Group {
                    CellRow(
                        title = "接口模拟",
                        subtitle = if (master) "已开启：命中规则的应用将收到模拟响应" else "已关闭：所有请求正常发往真实服务器",
                        showDivider = true,
                        trailing = {
                            Switch(
                                checked = master,
                                onCheckedChange = { on ->
                                    master = on
                                    MockStore.setEnabled(ctx, on)
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = StreamColors.Blue)
                            )
                        }
                    )
                    CellRow(
                        title = "已配置接口",
                        subtitle = if (rules.isEmpty()) "暂无规则，请在 Mac 端「接口模拟」中配置" else "${rules.size} 条规则 · 来自 Mac 端配置",
                        showDivider = true
                    )
                    CellRow(
                        title = "配置同步时间",
                        subtitle = if (updatedAt > 0) fmtTime(updatedAt) else "尚未接收到 Mac 端配置",
                        showDivider = true
                    )
                    CellRow(
                        title = "已开启应用",
                        subtitle = "${enabledApps.size} 个应用将在抓包时使用模拟响应",
                        showDivider = false
                    )
                }
            }
            item {
                GroupNote(
                    "使用步骤：\n" +
                        "1. 在 Mac 面板「接口模拟」里配置接口规则（示例可从抓包记录填充、按响应类型生成，或从 YAPI 接口 ID 拉取；不配置则按响应数据类型自动生成）；\n" +
                        "2. 点「推送配置到手机」下发规则；\n" +
                        "3. 本页开启总开关，并为需要模拟的应用打开右侧开关；\n" +
                        "4. 开启抓包（HTTPS 需先安装并信任证书）后，命中接口即返回模拟响应；\n" +
                        "5. 想重新来过时，用下方「清除配置数据」一键清空手机端配置。"
                )
            }

            // ---------- 维护 ----------
            item { SectionHeader("维护") }
            item {
                Group {
                    CellRow(
                        title = "清除配置数据",
                        subtitle = "删除已配置接口、已开启应用开关，并关闭总开关",
                        titleColor = Color(0xFFD32F2F),
                        showChevron = true,
                        showDivider = false,
                        onClick = { confirmClear = true }
                    )
                }
            }
            item {
                GroupNote(
                    "清除只影响手机端这份配置：抓包记录、已安装的抓包证书都不受影响；" +
                        "Mac 端本地规则也还在，重新点「推送配置到手机」即可恢复。"
                )
            }

            // ---------- 规则列表 ----------
            item { SectionHeader("已配置接口（${rules.size}）") }
            if (rules.isEmpty()) {
                item {
                    Group {
                        CellRow(title = "暂无规则", subtitle = "在 Mac 端添加接口后推送过来", showDivider = false)
                    }
                }
            } else {
                items(rules, key = { it.id }) { rule ->
                    val example = rule.body.ifBlank { "（按响应类型自动生成）" }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.White)
                            .clickable {
                                onOpenRule(rule.id)
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${rule.method.ifBlank { "*" }}  ${rule.host}${rule.path}",
                                fontSize = 14.sp,
                                color = if (rule.enabled) Color(0xFF1A1A1A) else StreamColors.SubText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "响应 ${rule.statusCode} · ${rule.contentType} · ${rule.sourceText}" +
                                    (if (rule.delayMs > 0) " · 延迟 ${rule.delayMs}ms" else ""),
                                fontSize = 12.sp,
                                color = StreamColors.SubText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (rule.enabled) "已启用" else "已停用",
                            fontSize = 12.sp,
                            color = if (rule.enabled) StreamColors.Blue else StreamColors.SubText
                        )
                        IconButton(
                            onClick = { pendingDelete = rule },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除接口",
                                tint = Color(0xFFD32F2F),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = StreamColors.Divider)
                }
            }

            // ---------- 应用列表 ----------
            item { SectionHeader("应用列表（${allApps?.size ?: 0}）") }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(StreamColors.BgGray)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search, contentDescription = null,
                            tint = StreamColors.SubText, modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f).padding(vertical = 7.dp),
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF1A1A1A)),
                            cursorBrush = SolidColor(StreamColors.Blue),
                            decorationBox = { inner ->
                                if (query.isEmpty()) {
                                    Text("搜索应用名 / 包名", fontSize = 14.sp, color = StreamColors.SubText)
                                }
                                inner()
                            }
                        )
                        if (query.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Close, contentDescription = "清空",
                                tint = StreamColors.SubText,
                                modifier = Modifier.size(16.dp).clickable { query = "" }
                            )
                        }
                    }
                }
                HorizontalDivider(color = StreamColors.Divider)
            }

            when {
                allApps == null -> item {
                    Box(Modifier.fillMaxWidth().background(Color.White).padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("加载应用列表…", color = StreamColors.SubText, fontSize = 13.sp)
                    }
                }
                allApps.isEmpty() -> item {
                    Box(Modifier.fillMaxWidth().background(Color.White).padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("未找到可启动的应用", color = StreamColors.SubText, fontSize = 13.sp)
                    }
                }
                filtered.isEmpty() -> item {
                    Box(Modifier.fillMaxWidth().background(Color.White).padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("未找到匹配「${query.trim()}」的应用", color = StreamColors.SubText, fontSize = 13.sp)
                    }
                }
                else -> items(filtered, key = { it.pkg }) { app ->
                    val on = app.pkg in enabledApps
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.White)
                            .clickable {
                                MockStore.setAppEnabled(ctx, app.pkg, !on)
                                enabledApps = MockStore.enabledApps(ctx)
                                // 打开任一应用即视为要使用接口模拟，自动带上总开关
                                if (!on && !master) { master = true; MockStore.setEnabled(ctx, true) }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                app.label, fontSize = 15.sp, color = Color(0xFF1A1A1A),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                app.pkg, fontSize = 12.sp, color = StreamColors.SubText,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = on,
                            onCheckedChange = { v ->
                                MockStore.setAppEnabled(ctx, app.pkg, v)
                                enabledApps = MockStore.enabledApps(ctx)
                                if (v && !master) { master = true; MockStore.setEnabled(ctx, true) }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = StreamColors.Blue)
                        )
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = StreamColors.Divider)
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除配置数据", fontSize = 16.sp) },
            text = {
                Text(
                    buildString {
                        append("将删除 ")
                        append("${rules.size} 条已配置接口")
                        if (enabledApps.isNotEmpty()) append("、${enabledApps.size} 个已开启应用开关")
                        append("，并关闭接口模拟总开关。\n\n")
                        append("本机抓包记录不受影响；Mac 端本地规则仍在，重新推送即可恢复。")
                    },
                    fontSize = 13.sp, color = StreamColors.SubText, lineHeight = 19.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val r = MockStore.clearAll(ctx)
                    master = false
                    rules = emptyList()
                    enabledApps = emptySet()
                    updatedAt = 0L
                    confirmClear = false
                    toast = "已清除配置数据（规则 ${r.optInt("clearedRules")} 条 · 应用 ${r.optInt("clearedApps")} 个）"
                }) { Text("清除", color = Color(0xFFD32F2F)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除接口", fontSize = 16.sp) },
            text = {
                Text(
                    "确定删除「${target.method.ifBlank { "*" }} ${target.host}${target.path}」？\n\n删除后不会再返回该接口的模拟响应。",
                    fontSize = 13.sp,
                    color = StreamColors.SubText,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val removed = MockStore.removeRule(ctx, target.id)
                    rules = MockStore.rules(ctx)
                    updatedAt = MockStore.rulesUpdatedAt(ctx)
                    pendingDelete = null
                    toast = if (removed) "已删除接口模拟规则" else "规则已不存在"
                }) { Text("删除", color = Color(0xFFD32F2F)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/** 已配置接口详情：接口信息与响应结果分 Tab 展示。 */
@Composable
fun MockRuleDetailScreen(
    rule: MockRule,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val generatedBody = remember(rule.id) {
        rule.body.ifBlank {
            val generated = MockEngine.respond(context, rule, rule.method, rule.host, rule.path).body
            String(generated, Charsets.UTF_8)
        }
    }
    var body by remember(rule.id) { mutableStateOf(prettyJson(generatedBody) ?: generatedBody) }
    var enabled by remember(rule.id) { mutableStateOf(rule.enabled) }
    var statusCode by remember(rule.id) { mutableStateOf(rule.statusCode.toString()) }
    var contentType by remember(rule.id) { mutableStateOf(rule.contentType) }
    var delayMs by remember(rule.id) { mutableStateOf(rule.delayMs.toString()) }
    var mainTab by remember(rule.id) { mutableStateOf(0) }
    var responseTab by remember(rule.id) { mutableStateOf(0) }
    var jsonFilter by remember(rule.id) { mutableStateOf("") }
    var jsonExpandedPaths by remember(rule.id) { mutableStateOf<Set<String>?>(null) }
    var selectedPath by remember(rule.id) { mutableStateOf<List<Int>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    val jsonResult = remember(body) { parseJsonTree(body) }
    val jsonRoot = (jsonResult as? JsonTreeResult.Ok)?.root
    LaunchedEffect(body) {
        if (jsonRoot == null) {
            jsonExpandedPaths = null
        } else {
            val valid = jsonEditorContainerPaths(jsonRoot)
            jsonExpandedPaths = jsonExpandedPaths?.intersect(valid)
                ?: initialExpandedJsonPaths(jsonRoot)
        }
    }
    val selectedNode = remember(jsonRoot, selectedPath) {
        if (jsonRoot == null || selectedPath == null) null else jsonNodeAt(jsonRoot, selectedPath!!)
    }
    val selectedField = remember(jsonRoot, selectedPath) {
        if (jsonRoot == null || selectedPath == null) "根节点" else jsonFieldPath(jsonRoot, selectedPath!!)
    }
    var selectedValue by remember(selectedNode, selectedPath) {
        mutableStateOf(selectedNode?.let { jsonNodeEditorValue(it) }.orEmpty())
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(1800)
            toast = null
        }
    }

    fun save() {
        val code = statusCode.toIntOrNull()?.coerceIn(100, 599) ?: 200
        val type = contentType.trim().ifEmpty { "application/json" }
        val delay = delayMs.toLongOrNull()?.coerceIn(0, 30_000L) ?: 0L
        MockStore.upsertRule(
            context,
            rule.copy(
                enabled = enabled,
                statusCode = code,
                contentType = type,
                body = body,
                delayMs = delay
            )
        )
        toast = "已保存接口模拟配置"
    }

    fun applySelectedValue() {
        val root = jsonRoot ?: return
        val path = selectedPath ?: return
        val current = jsonNodeAt(root, path) ?: return
        val replacement = parseEditedJsonNode(current, selectedValue) ?: run {
            toast = "请输入合法的 ${jsonNodeTypeName(current)} 值"
            return
        }
        body = jsonNodeToPrettyText(replaceJsonNode(root, path, replacement))
        selectedPath = null
        toast = "已修改响应结果"
    }

    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        StreamTopBar(
            title = "接口模拟详情",
            onBack = onBack,
            backLabel = "接口模拟",
            actions = {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除接口",
                        tint = Color.White
                    )
                }
                Text(
                    "保存",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { save() }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        )

        CompactTabs(
            items = listOf("接口信息", "响应结果"),
            selected = mainTab,
            onSelect = { mainTab = it },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )

        toast?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF43A047))
                    .padding(horizontal = 16.dp, vertical = 7.dp)
            )
        }

        if (mainTab == 0) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp)
            ) {
                SectionHeader("接口信息")
                Group {
                    MockInfoRow("请求方法", rule.method.ifBlank { "*" })
                    MockInfoRow("Host", rule.host)
                    MockInfoRow("Path", rule.path.ifBlank { "/" })
                }
                SectionHeader("规则设置")
                Group {
                    CellRow(
                        title = "规则状态",
                        subtitle = if (enabled) "已启用：匹配请求时返回模拟响应" else "已停用：匹配请求时继续访问真实服务",
                        showDivider = true,
                        trailing = {
                            Switch(
                                checked = enabled,
                                onCheckedChange = { enabled = it },
                                colors = SwitchDefaults.colors(checkedTrackColor = StreamColors.Blue)
                            )
                        }
                    )
                }

                SectionHeader("响应配置")
                Group {
                    MockEditRow("状态码", statusCode, { statusCode = it })
                    MockEditRow("Content-Type", contentType, { contentType = it })
                    MockEditRow("延迟（毫秒）", delayMs, { delayMs = it })
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                CompactTabs(
                    items = listOf("Text", "JSON"),
                    selected = responseTab,
                    onSelect = {
                        responseTab = it
                        selectedPath = null
                        if (it == 0) jsonFilter = ""
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )

                Box(Modifier.fillMaxSize()) {
                    if (responseTab == 0) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 84.dp)
                        ) {
                            Text(
                                "响应结果",
                                fontSize = 12.sp,
                                color = StreamColors.SubText,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                            BasicTextField(
                                value = body,
                                onValueChange = { body = it; selectedPath = null },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 360.dp)
                                    .background(Color.White)
                                    .padding(16.dp),
                                textStyle = TextStyle(
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = Color(0xFF1A1A1A),
                                    fontFamily = FontFamily.Monospace
                                ),
                                cursorBrush = SolidColor(StreamColors.Blue),
                                decorationBox = { inner ->
                                    if (body.isEmpty()) {
                                        Text("输入响应示例；留空时按 Content-Type 自动生成", fontSize = 13.sp, color = StreamColors.SubText)
                                    }
                                    inner()
                                }
                            )
                            Text(
                                "合法 JSON 已自动格式化；保存后命中该接口时返回此结果",
                                fontSize = 11.sp,
                                color = StreamColors.SubText,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    } else {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .padding(bottom = 156.dp)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFF2F2F7), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = StreamColors.SubText,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                BasicTextField(
                                    value = jsonFilter,
                                    onValueChange = { jsonFilter = it },
                                    singleLine = true,
                                    textStyle = TextStyle(fontSize = 13.sp, color = Color(0xFF1A1A1A)),
                                    modifier = Modifier.weight(1f),
                                    decorationBox = { inner ->
                                        if (jsonFilter.isEmpty()) {
                                            Text("过滤字段名 / 值", fontSize = 13.sp, color = StreamColors.SubText)
                                        }
                                        inner()
                                    }
                                )
                                if (jsonFilter.isNotEmpty()) {
                                    Text(
                                        "${jsonRoot?.let { countJsonMatches(it, jsonFilter) } ?: 0} 处",
                                        fontSize = 11.sp,
                                        color = StreamColors.SubText
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "清除过滤",
                                        tint = StreamColors.SubText,
                                        modifier = Modifier.size(15.dp).clickable { jsonFilter = "" }
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            when (jsonResult) {
                                is JsonTreeResult.Ok -> JsonTreeEditor(
                                    root = jsonResult.root,
                                    query = jsonFilter,
                                    expanded = jsonExpandedPaths ?: initialExpandedJsonPaths(jsonResult.root),
                                    selectedPath = selectedPath,
                                    onExpandedChange = { jsonExpandedPaths = it },
                                    onSelect = { path, node ->
                                        selectedPath = path
                                        selectedValue = jsonNodeEditorValue(node)
                                    }
                                )
                                JsonTreeResult.NotJson -> Text(
                                    "当前响应不是合法 JSON，请切换到 Text 编辑后再返回 JSON。",
                                    fontSize = 13.sp,
                                    color = StreamColors.SubText,
                                    modifier = Modifier.padding(8.dp)
                                )
                                JsonTreeResult.TooLarge -> Text(
                                    "JSON 内容过大，请切换到 Text 编辑。",
                                    fontSize = 13.sp,
                                    color = StreamColors.SubText,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }

                    selectedNode?.let { node ->
                        SurfaceJsonEditor(
                            node = node,
                            field = selectedField,
                            value = selectedValue,
                            onValueChange = { selectedValue = it },
                            onApply = ::applySelectedValue,
                            onDismiss = { selectedPath = null },
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除接口", fontSize = 16.sp) },
            text = {
                Text(
                    "确定删除「${rule.method.ifBlank { "*" }} ${rule.host}${rule.path}」？\n\n删除后不会再返回该接口的模拟响应。",
                    fontSize = 13.sp,
                    color = StreamColors.SubText,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    MockStore.removeRule(context, rule.id)
                    confirmDelete = false
                    onBack()
                }) { Text("删除", color = Color(0xFFD32F2F)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

/** 合法 JSON 在 Text 页的默认显示格式。非 JSON 响应保持原文。 */
private fun prettyJson(text: String): String? {
    val source = text.trim()
    if (source.isEmpty()) return null
    return runCatching {
        when (val value = JSONTokener(source).nextValue()) {
            is JSONObject -> value.toString(2)
            is JSONArray -> value.toString(2)
            else -> null
        }
    }.getOrNull()
}

private fun jsonPathKey(path: List<Int>): String = path.joinToString("/")

private fun initialExpandedJsonPaths(root: JsonNode): Set<String> {
    val out = HashSet<String>()
    fun walk(node: JsonNode, path: List<Int>, depth: Int) {
        if (node is JsonNode.Obj || node is JsonNode.Arr) {
            if (depth < 2) out += jsonPathKey(path)
            val children = when (node) {
                is JsonNode.Obj -> node.entries.map { it.second }
                is JsonNode.Arr -> node.items
                is JsonNode.Prim -> emptyList()
            }
            children.forEachIndexed { index, child -> walk(child, path + index, depth + 1) }
        }
    }
    walk(root, emptyList(), 0)
    return out
}

private fun jsonEditorContainerPaths(root: JsonNode): Set<String> {
    val out = HashSet<String>()
    fun walk(node: JsonNode, path: List<Int>) {
        if (node is JsonNode.Obj || node is JsonNode.Arr) {
            out += jsonPathKey(path)
            when (node) {
                is JsonNode.Obj -> node.entries.forEachIndexed { index, (_, child) ->
                    walk(child, path + index)
                }
                is JsonNode.Arr -> node.items.forEachIndexed { index, child ->
                    walk(child, path + index)
                }
                is JsonNode.Prim -> Unit
            }
        }
    }
    walk(root, emptyList())
    return out
}

private fun jsonNodeAt(root: JsonNode, path: List<Int>): JsonNode? {
    var current: JsonNode = root
    for (index in path) {
        current = when (current) {
            is JsonNode.Obj -> current.entries.getOrNull(index)?.second ?: return null
            is JsonNode.Arr -> current.items.getOrNull(index) ?: return null
            is JsonNode.Prim -> return null
        }
    }
    return current
}

private fun jsonFieldPath(root: JsonNode, path: List<Int>): String {
    if (path.isEmpty()) return "$"
    var current: JsonNode = root
    val out = StringBuilder("$")
    for (index in path) {
        when (current) {
            is JsonNode.Obj -> {
                val key = current.entries.getOrNull(index)?.first ?: return out.toString()
                if (key.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) {
                    out.append('.').append(key)
                } else {
                    out.append('[').append(JSONObject.quote(key)).append(']')
                }
                current = current.entries.getOrNull(index)?.second ?: return out.toString()
            }
            is JsonNode.Arr -> {
                out.append('[').append(index).append(']')
                current = current.items.getOrNull(index) ?: return out.toString()
            }
            is JsonNode.Prim -> return out.toString()
        }
    }
    return out.toString()
}

private fun replaceJsonNode(root: JsonNode, path: List<Int>, replacement: JsonNode): JsonNode {
    if (path.isEmpty()) return replacement
    val index = path.first()
    val tail = path.drop(1)
    return when (root) {
        is JsonNode.Obj -> JsonNode.Obj(root.entries.mapIndexed { i, (key, value) ->
            key to if (i == index) replaceJsonNode(value, tail, replacement) else value
        })
        is JsonNode.Arr -> JsonNode.Arr(root.items.mapIndexed { i, value ->
            if (i == index) replaceJsonNode(value, tail, replacement) else value
        })
        is JsonNode.Prim -> root
    }
}

private fun jsonNodeEditorValue(node: JsonNode): String = when (node) {
    is JsonNode.Prim -> node.text
    else -> jsonNodeToPrettyText(node)
}

private fun jsonNodeTypeName(node: JsonNode): String = when (node) {
    is JsonNode.Prim -> when (node.kind) {
        JsonNode.PrimKind.STR -> "文本"
        JsonNode.PrimKind.NUM -> "数字"
        JsonNode.PrimKind.BOOL -> "布尔值"
        JsonNode.PrimKind.NULL -> "null"
    }
    is JsonNode.Obj -> "对象 JSON"
    is JsonNode.Arr -> "数组 JSON"
}

private fun parseEditedJsonNode(original: JsonNode, value: String): JsonNode? {
    if (original is JsonNode.Prim) {
        return when (original.kind) {
            JsonNode.PrimKind.STR -> JsonNode.Prim(value, JsonNode.PrimKind.STR)
            JsonNode.PrimKind.NUM -> value.trim().takeIf {
                it.matches(Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?"))
            }?.let { JsonNode.Prim(it, JsonNode.PrimKind.NUM) }
            JsonNode.PrimKind.BOOL -> value.trim().lowercase().takeIf {
                it == "true" || it == "false"
            }?.let { JsonNode.Prim(it, JsonNode.PrimKind.BOOL) }
            JsonNode.PrimKind.NULL -> if (value.trim() == "null") {
                JsonNode.Prim("null", JsonNode.PrimKind.NULL)
            } else null
        }
    }
    return when (val parsed = parseJsonTree(value)) {
        is JsonTreeResult.Ok -> parsed.root.takeIf { it is JsonNode.Obj || it is JsonNode.Arr }
        else -> null
    }
}

private fun jsonNodeToPrettyText(node: JsonNode): String {
    fun write(value: JsonNode, depth: Int): String {
        val indent = "  ".repeat(depth)
        val childIndent = "  ".repeat(depth + 1)
        return when (value) {
            is JsonNode.Prim -> when (value.kind) {
                JsonNode.PrimKind.STR -> JSONObject.quote(value.text)
                JsonNode.PrimKind.NUM, JsonNode.PrimKind.BOOL, JsonNode.PrimKind.NULL -> value.text
            }
            is JsonNode.Obj -> if (value.entries.isEmpty()) "{}" else buildString {
                append("{\n")
                value.entries.forEachIndexed { index, (key, child) ->
                    append(childIndent)
                    append(JSONObject.quote(key))
                    append(": ")
                    append(write(child, depth + 1))
                    if (index < value.entries.lastIndex) append(',')
                    append('\n')
                }
                append(indent).append('}')
            }
            is JsonNode.Arr -> if (value.items.isEmpty()) "[]" else buildString {
                append("[\n")
                value.items.forEachIndexed { index, child ->
                    append(childIndent).append(write(child, depth + 1))
                    if (index < value.items.lastIndex) append(',')
                    append('\n')
                }
                append(indent).append(']')
            }
        }
    }
    return write(node, 0)
}

@Composable
private fun JsonTreeEditor(
    root: JsonNode,
    query: String,
    expanded: Set<String>,
    selectedPath: List<Int>?,
    onExpandedChange: (Set<String>) -> Unit,
    onSelect: (List<Int>, JsonNode) -> Unit
) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        JsonEditorNode(
            node = root,
            label = null,
            path = emptyList(),
            depth = 0,
            query = query,
            expanded = expanded,
            selectedPath = selectedPath,
            onToggle = { path ->
                val key = jsonPathKey(path)
                onExpandedChange(if (key in expanded) expanded - key else expanded + key)
            },
            onSelect = onSelect
        )
    }
}

@Composable
private fun JsonEditorNode(
    node: JsonNode,
    label: String?,
    path: List<Int>,
    depth: Int,
    query: String,
    expanded: Set<String>,
    selectedPath: List<Int>?,
    onToggle: (List<Int>) -> Unit,
    onSelect: (List<Int>, JsonNode) -> Unit
) {
    val q = query.trim()
    val selfMatch = q.isEmpty() || jsonEditorSelfMatches(node, label, q)
    val childMatch = q.isNotEmpty() && jsonEditorChildMatches(node, q)
    if (q.isNotEmpty() && !selfMatch && !childMatch) return

    val key = jsonPathKey(path)
    val isContainer = node is JsonNode.Obj || node is JsonNode.Arr
    val isExpanded = key in expanded || (q.isNotEmpty() && childMatch)
    val selected = selectedPath == path && node is JsonNode.Prim
    val line = jsonEditorLine(node, label, isExpanded, q)
    Text(
        line,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFFDDEBFF) else Color.Transparent)
            .clickable {
                if (isContainer) onToggle(path) else onSelect(path, node)
            }
            .padding(start = (depth * 18).dp, top = 2.dp, bottom = 2.dp, end = 8.dp)
    )

    if (isContainer && isExpanded) {
        when (node) {
            is JsonNode.Obj -> node.entries.forEachIndexed { index, (childLabel, child) ->
                if (q.isEmpty() || jsonEditorSubtreeMatches(child, childLabel, q)) {
                    JsonEditorNode(child, childLabel, path + index, depth + 1, q, expanded, selectedPath, onToggle, onSelect)
                }
            }
            is JsonNode.Arr -> node.items.forEachIndexed { index, child ->
                val childLabel = index.toString()
                if (q.isEmpty() || jsonEditorSubtreeMatches(child, childLabel, q)) {
                    JsonEditorNode(child, childLabel, path + index, depth + 1, q, expanded, selectedPath, onToggle, onSelect)
                }
            }
            is JsonNode.Prim -> Unit
        }
        Text(
            if (node is JsonNode.Obj) "}" else "]",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF55575C),
            modifier = Modifier.padding(start = (depth * 18).dp + 18.dp, top = 2.dp, bottom = 2.dp)
        )
    }
}

private fun jsonEditorSelfMatches(node: JsonNode, label: String?, query: String): Boolean =
    (label != null && label.contains(query, true)) ||
        (node is JsonNode.Prim && node.text.contains(query, true))

private fun jsonEditorSubtreeMatches(node: JsonNode, label: String?, query: String): Boolean {
    if (jsonEditorSelfMatches(node, label, query)) return true
    return when (node) {
        is JsonNode.Obj -> node.entries.any { (key, child) -> jsonEditorSubtreeMatches(child, key, query) }
        is JsonNode.Arr -> node.items.withIndex().any { (index, child) -> jsonEditorSubtreeMatches(child, index.toString(), query) }
        is JsonNode.Prim -> false
    }
}

private fun jsonEditorChildMatches(node: JsonNode, query: String): Boolean = when (node) {
    is JsonNode.Obj -> node.entries.any { (key, child) -> jsonEditorSubtreeMatches(child, key, query) }
    is JsonNode.Arr -> node.items.withIndex().any { (index, child) -> jsonEditorSubtreeMatches(child, index.toString(), query) }
    is JsonNode.Prim -> false
}

private val MockJsonKeyColor = Color(0xFF881391)
private val MockJsonStringColor = Color(0xFFC41A16)
private val MockJsonNumberColor = Color(0xFF1C00CF)
private val MockJsonBooleanColor = Color(0xFF0D22AA)
private val MockJsonNullColor = Color(0xFF8A8A8E)
private val MockJsonPunctuationColor = Color(0xFF55575C)
private val MockJsonIndexColor = Color(0xFF9AA0A8)
private val MockJsonArrowColor = Color(0xFF1976D2)
private val MockJsonHitColor = Color(0xFFFFE082)

private fun jsonEditorLine(
    node: JsonNode,
    label: String?,
    expanded: Boolean,
    query: String
): AnnotatedString {
    val line = buildAnnotatedString {
        if (node is JsonNode.Obj || node is JsonNode.Arr) {
            withStyle(SpanStyle(color = MockJsonArrowColor)) { append(if (expanded) "▾ " else "▸ ") }
        } else {
            withStyle(SpanStyle(color = MockJsonPunctuationColor)) { append("  ") }
        }
        if (label != null) {
            if (node is JsonNode.Arr) {
                withStyle(SpanStyle(color = MockJsonIndexColor)) { append(label) }
            } else {
                withStyle(SpanStyle(color = MockJsonKeyColor)) { append(JSONObject.quote(label)) }
            }
            withStyle(SpanStyle(color = MockJsonPunctuationColor)) { append(": ") }
        }
        when (node) {
            is JsonNode.Obj -> {
                withStyle(SpanStyle(color = MockJsonPunctuationColor)) {
                    append('{')
                    if (!expanded) append(" … ${node.entries.size} 项 }")
                }
            }
            is JsonNode.Arr -> {
                withStyle(SpanStyle(color = MockJsonPunctuationColor)) {
                    append('[')
                    if (!expanded) append(" … ${node.items.size} 项 ]")
                }
            }
            is JsonNode.Prim -> {
                val (text, color) = when (node.kind) {
                    JsonNode.PrimKind.STR -> JSONObject.quote(node.text) to MockJsonStringColor
                    JsonNode.PrimKind.NUM -> node.text to MockJsonNumberColor
                    JsonNode.PrimKind.BOOL -> node.text to MockJsonBooleanColor
                    JsonNode.PrimKind.NULL -> "null" to MockJsonNullColor
                }
                withStyle(SpanStyle(color = color)) { append(text) }
            }
        }
    }
    if (query.isBlank()) return line
    return buildAnnotatedString {
        append(line)
        var index = line.text.indexOf(query, ignoreCase = true)
        while (index >= 0) {
            addStyle(SpanStyle(background = MockJsonHitColor), index, index + query.length)
            index = line.text.indexOf(query, index + query.length, ignoreCase = true)
        }
    }
}

@Composable
private fun SurfaceJsonEditor(
    node: JsonNode,
    field: String,
    value: String,
    onValueChange: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().navigationBarsPadding(),
        color = Color.White,
        shadowElevation = 10.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        field,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF333333),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${jsonNodeTypeName(node)} · 修改字段值",
                        fontSize = 10.sp,
                        color = StreamColors.SubText
                    )
                }
                Text(
                    "取消",
                    fontSize = 12.sp,
                    color = StreamColors.SubText,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp)
                )
                Text(
                    "应用",
                    fontSize = 12.sp,
                    color = StreamColors.Blue,
                    modifier = Modifier.clickable(onClick = onApply).padding(6.dp)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp, max = 96.dp)
                    .background(StreamColors.BgGray, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                textStyle = TextStyle(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Color(0xFF1A1A1A),
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = SolidColor(StreamColors.Blue)
            )
        }
    }
}

@Composable
private fun MockInfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = StreamColors.SubText, modifier = Modifier.width(100.dp))
        SelectionContainer {
            Text(value, fontSize = 13.sp, color = Color(0xFF1A1A1A), fontFamily = FontFamily.Monospace)
        }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = StreamColors.Divider)
}

@Composable
private fun MockEditRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = StreamColors.SubText, modifier = Modifier.width(100.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .background(StreamColors.BgGray, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = Color(0xFF1A1A1A))
        )
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = StreamColors.Divider)
}

private fun fmtTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
