package com.ht.stream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * 接口模拟设置页。
 *
 * - 顶部：总开关 + 桌面端下发的规则概览
 * - 中部：已配置接口列表（来自 Mac），点击可预览实际会返回的响应示例
 * - 下部：手机应用列表，每项右侧「开启 / 关闭」——只有开启的应用才会被模拟
 */
@Composable
fun MockScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var master by remember { mutableStateOf(MockStore.isEnabled(ctx)) }
    var enabledApps by remember { mutableStateOf(MockStore.enabledApps(ctx)) }
    var rules by remember { mutableStateOf(MockStore.rules(ctx)) }
    var updatedAt by remember { mutableStateOf(MockStore.rulesUpdatedAt(ctx)) }
    var apps by remember { mutableStateOf<List<LaunchApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Pair<MockRule, String>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
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
                                val resp = MockEngine.respond(ctx, rule, rule.method, rule.host, rule.path)
                                preview = rule to String(resp.body, Charsets.UTF_8)
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

    preview?.let { (rule, text) ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("响应示例", fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        "${rule.method.ifBlank { "*" }} ${rule.host}${rule.path}",
                        fontSize = 12.sp, color = StreamColors.SubText
                    )
                    Text(
                        "${rule.statusCode} · ${rule.contentType} · ${rule.sourceText}",
                        fontSize = 12.sp, color = StreamColors.SubText
                    )
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text.ifEmpty { "（空响应体）" },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF1A1A1A),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } }
        )
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
}

private fun fmtTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
