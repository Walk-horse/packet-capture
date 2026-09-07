package com.ht.stream.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ht.stream.data.HostsStore

/** Hosts 设置：本地域名映射，抓包开启时生效 */
@Composable
fun HostsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var domain by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }

    val mappings = remember(version) { HostsStore.list(context) }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加 Hosts 映射") },
            text = {
                Column {
                    OutlinedTextField(
                        value = domain, onValueChange = { domain = it },
                        label = { Text("被映射的域名") },
                        placeholder = { Text("mock.example.com") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = target, onValueChange = { target = it },
                        label = { Text("映射到的 IP 地址或域名") },
                        placeholder = { Text("127.0.0.1") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (domain.isNotBlank() && target.isNotBlank()) {
                        HostsStore.add(context, domain, target)
                        version++
                        domain = ""; target = ""
                        showAdd = false
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("取消") }
            }
        )
    }

    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        StreamTopBar(
            title = "Hosts 设置",
            onBack = onBack,
            actions = {
                TextButton(onClick = { showAdd = true }) {
                    Text("添加", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            GroupNote("以下是本地 HOSTS 映射，只在抓包开启时有效（代理层将命中域名的连接改指向映射目标）")
            if (mappings.isNotEmpty()) {
                Group {
                    mappings.forEachIndexed { i, m ->
                        CellRow(
                            title = m.domain,
                            subtitle = "→ ${m.target}",
                            trailing = {
                                IconButton(onClick = {
                                    HostsStore.remove(context, m.domain); version++
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = StreamColors.RedText)
                                }
                            },
                            showDivider = i < mappings.size - 1
                        )
                    }
                }
            }
            GroupNote("您可以导出 HOSTS 给其他人，避免繁琐配置")
            Group {
                CellRow(
                    "导出/分享 Hosts",
                    titleColor = StreamColors.RedText,
                    onClick = {
                        val text = HostsStore.exportText(context).ifEmpty { "# 暂无映射" }
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                            putExtra(Intent.EXTRA_TITLE, "hosts.txt")
                        }
                        context.startActivity(Intent.createChooser(send, "导出 Hosts"))
                    },
                    showDivider = false
                )
            }
        }
    }
}
