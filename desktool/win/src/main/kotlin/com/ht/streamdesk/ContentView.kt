package com.ht.streamdesk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

@Composable
fun ContentView(client: SyncClient) {
    var panel by remember { mutableStateOf<Panel>(Panel.All) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        logd("startup: address='${client.address.value}' autoSync=${client.autoSync.value} interval=${client.interval.value}")
        // 首次启动（无保存地址）自动做一次 USB 探测，插上手机打开即用
        if (client.address.value.isBlank()) client.detectUsb()
        client.startIfNeeded()
        // 本地无数据（首次启动/刚清空）时自动全量拉一次：手机端 synced 是全局标志，
        // 若另一台面板先跑过，增量会一直是空；空面板直接全量兜底
        if (client.exchanges.value.isEmpty()) client.resetAndPull()
        logd("startup done: autoSync=${client.autoSync.value}")
    }

    Column(Modifier.fillMaxSize()) {
        Toolbar(client)
        HorizontalDivider(color = HairLine)
        Row(Modifier.fillMaxSize()) {
            SidebarView(client, panel) { panel = it }
            VDivider()
            RequestListView(
                client = client,
                panel = panel,
                selectedId = selectedId,
                search = search,
                onSearchChange = { search = it },
                onSelect = { selectedId = it },
            )
            VDivider()
            DetailView(client, selectedId)
        }
    }

    val needsPick by client.needsPick.collectAsState()
    if (needsPick) DevicePickerDialog(client)
}

@Composable
fun VDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(HairLine))
}

// MARK: - 工具栏

@Composable
private fun Toolbar(client: SyncClient) {
    val scope = rememberCoroutineScope()
    val status by client.status.collectAsState()
    val autoSync by client.autoSync.collectAsState()
    val interval by client.interval.collectAsState()
    val address by client.address.collectAsState()
    var addrText by remember(address) { mutableStateOf(address) }
    var moreOpen by remember { mutableStateOf(false) }
    var intervalOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().height(52.dp).background(Color.White).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 品牌
        StreamIcon(StreamIconKind.Pulse, size = 20.dp, tint = Accent)
        Spacer(Modifier.width(8.dp))
        Text("Packet Capture", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.width(16.dp))

        // 连接区：可压缩，窄窗口时地址框自动收窄，避免与其它控件重叠
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolIconButton(StreamIconKind.Cable, "通过 USB 探测手机地址") {
                scope.launch { client.detectUsb() }
            }
            Spacer(Modifier.width(6.dp))
            StreamIcon(StreamIconKind.Phone, size = 13.dp, tint = SubText)
            Spacer(Modifier.width(6.dp))

            // 地址输入（占满剩余空间，最小宽度兜底）
            Box(
                Modifier
                    .weight(1f, fill = true)
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF2F3F5))
                    .border(1.dp, HairLine, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (addrText.isEmpty()) {
                    Text("手机地址，如 192.168.1.20:17890", fontSize = 11.sp, color = SubText)
                }
                BasicTextField(
                    value = addrText,
                    onValueChange = { addrText = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 11.sp, color = Color(0xFF1F2328)),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier.fillMaxWidth().onEnterKey {
                        client.setAddress(addrText)
                        scope.launch { client.resetAndPull() }
                    },
                )
            }

            Spacer(Modifier.width(8.dp))
            Text(
                status.text,
                fontSize = 11.sp,
                color = SubText,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 150.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        // 自动同步（自绘迷你开关：M3 Switch 有 48dp 最小触摸目标，强设小尺寸会溢出压到相邻控件）
        Text("自动", fontSize = 11.sp, color = SubText)
        Spacer(Modifier.width(4.dp))
        MiniSwitch(
            checked = autoSync,
            onCheckedChange = { client.setAutoSync(it); if (it) scope.launch { client.pull() } },
        )
        Spacer(Modifier.width(8.dp))

        // 间隔
        Box {
            SmallButton(
                text = when (interval.toInt()) {
                    1 -> "1s"; 2 -> "2s"; 5 -> "5s"; else -> "${interval}s"
                },
                enabled = autoSync,
                onClick = { intervalOpen = true },
            )
            DropdownMenu(expanded = intervalOpen, onDismissRequest = { intervalOpen = false }) {
                listOf(1.0, 2.0, 5.0).forEach { v ->
                    DropdownMenuItem(
                        text = { Text("${v.toInt()}s", fontSize = 12.sp) },
                        onClick = { client.setInterval(v); intervalOpen = false },
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))

        ToolIconButton(StreamIconKind.Refresh, "手动同步（仅拉取新增请求）") {
            scope.launch { client.pull() }
        }
        Spacer(Modifier.width(4.dp))

        Box {
            ToolIconButton(StreamIconKind.More, "更多操作") { moreOpen = true }
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                DropdownMenuItem(
                    text = { Text("全量重新同步", fontSize = 12.sp) },
                    onClick = { moreOpen = false; scope.launch { client.resetAndPull() } },
                )
                DropdownMenuItem(
                    text = { Text("清空本地数据", fontSize = 12.sp) },
                    onClick = { moreOpen = false; client.clearLocal() },
                )
            }
        }
    }
}

/**
 * 带悬浮提示的图标按钮。
 *
 * 用 Material3 官方 `TooltipBox` 渲染提示 —— 提示是独立非交互浮层、由框架统一调度显示/隐藏，
 * 不再自绘 Popup，从根本上消除「鼠标移到提示上→失去按钮 hover→提示消失→又 hover 按钮」的闪烁。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolIconButton(kind: StreamIconKind, hint: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(0xE6202124),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Text(hint, fontSize = 10.sp, color = Color.White, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
            }
        },
        state = rememberTooltipState(),
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (hovered) ChipBg else Color.Transparent)
                .hoverable(interaction)
                .clickable { onClick() }
                .padding(5.dp),
            contentAlignment = Alignment.Center,
        ) {
            StreamIcon(kind, size = 16.dp, tint = if (hovered) Accent else Color(0xFF4B5563))
        }
    }
}

/** 自绘迷你开关（36x20）：避开 M3 Switch 的最小触摸目标溢出问题 */
@Composable
private fun MiniSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .width(36.dp)
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) Accent else Color(0xFFD6D9DE))
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(start = if (checked) 18.dp else 2.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun SmallButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {    Box(
        Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) ChipBg else Color(0xFFF5F6F7))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 11.sp,
            color = if (enabled) Color(0xFF1F2328) else Color(0xFFB0B6BE),
        )
    }
}

/** 回车触发（BasicTextField 没有 onSubmit，这里用 KeyEvent 拦截） */
private fun Modifier.onEnterKey(onEnter: () -> Unit): Modifier =
    this.onPreviewKeyEvent { e ->
        if (e.key == Key.Enter && e.type == KeyEventType.KeyDown) {
            onEnter(); true
        } else false
    }

// MARK: - 多设备选择

@Composable
private fun DevicePickerDialog(client: SyncClient) {
    val scope = rememberCoroutineScope()
    val devices by client.devices.collectAsState()
    val selected by client.selectedSerial.collectAsState()

    Dialog(onDismissRequest = { client.needsPick.value = false }) {
        Column(
            Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("选择要抓取的手机", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            devices.forEach { dev ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            client.needsPick.value = false
                            scope.launch { client.selectDevice(dev) }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected == dev.serial) {
                        StreamIcon(StreamIconKind.Check, size = 12.dp, tint = Accent)
                        Spacer(Modifier.width(6.dp))
                    }
                    Column {
                        Text(
                            dev.model.ifEmpty { dev.serial },
                            fontSize = 12.sp,
                        )
                        if (dev.model.isNotEmpty()) {
                            Text(dev.serial, fontSize = 10.sp, color = SubText)
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SmallButton("取消") { client.needsPick.value = false }
            }
        }
    }
}
