package com.ht.stream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.stream.data.CaptureMode

/** 设置抓包模式：黑名单 / 白名单 */
@Composable
fun ModeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<String?>(null) } // "black" / "white"
    var editText by remember { mutableStateOf("") }

    val blackOn = remember(version) { CaptureMode.blacklistOn(context) }
    val whiteOn = remember(version) { CaptureMode.whitelistOn(context) }
    val quicOn = remember(version) { CaptureMode.quicBlockOn(context) }

    editing?.let { which ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (which == "black") "黑名单设置" else "白名单设置") },
            text = {
                Column {
                    Text("每行一个域名，支持 *.example.com 通配子域", fontSize = 12.sp, color = StreamColors.SubText)
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth().height(160.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val list = editText.split("\n", ",", ";").map { it.trim() }.filter { it.isNotEmpty() }
                    if (which == "black") CaptureMode.setBlacklist(context, list)
                    else CaptureMode.setWhitelist(context, list)
                    version++
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            }
        )
    }

    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        StreamTopBar(title = "设置抓包模式", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ModeSection(
                title = "黑名单模式",
                desc = "默认嗅探除了您设置的黑名单以外的域名的请求，适用于全局抓包以及处理 SSL Pinning 的场景。",
                switchLabel = "开启黑名单模式",
                checked = blackOn,
                onChecked = { CaptureMode.setBlacklistOn(context, it); version++ },
                listLabel = "黑名单设置",
                onEditList = {
                    editText = CaptureMode.blacklist(context).joinToString("\n")
                    editing = "black"
                }
            )
            ModeSection(
                title = "白名单模式",
                desc = "默认只嗅探您设置的白名单里的域名请求，适用于只调试固定某几个域名的请求的场景。（白名单优先于黑名单）",
                switchLabel = "开启白名单模式",
                checked = whiteOn,
                onChecked = { CaptureMode.setWhitelistOn(context, it); version++ },
                listLabel = "白名单设置",
                onEditList = {
                    editText = CaptureMode.whitelist(context).joinToString("\n")
                    editing = "white"
                }
            )
            ModeSection(
                title = "HTTP/3 (QUIC) 回退",
                desc = "启用后拦截 UDP 443 流量，迫使使用 HTTP/3 的 App 回退到 HTTPS（HTTP/2），从而可被解密记录。\n\n注意：少数仅支持 HTTP/3 的服务可能因此无法连接，遇到时请关闭此项。",
                switchLabel = "启用 QUIC 回退",
                checked = quicOn,
                onChecked = { CaptureMode.setQuicBlockOn(context, it); version++ },
                listLabel = null
            )
        }
    }
}

@Composable
private fun ModeSection(
    title: String,
    desc: String,
    switchLabel: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    listLabel: String?,
    onEditList: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth().background(Color.White)) {
        Text(
            title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp)
        )
        Text(
            desc,
            fontSize = 13.sp,
            color = StreamColors.SubText,
            lineHeight = 19.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)
        )
        CellRow(switchLabel, trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChecked,
                colors = SwitchDefaults.colors(checkedTrackColor = StreamColors.Blue)
            )
        })
        if (listLabel != null) {
            CellRow(listLabel, showChevron = true, onClick = onEditList ?: {}, showDivider = false)
        }
    }
}
