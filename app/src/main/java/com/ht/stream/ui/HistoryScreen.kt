package com.ht.stream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ht.stream.data.RequestStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 抓包历史：SESSION 列表 + 清空 */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenCurrent: () -> Unit
) {
    val sessions by RequestStore.sessions.collectAsState()
    RequestStore.tick.collectAsState()
    var showClearConfirm by remember { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空抓包历史") },
            text = { Text("清空全部记录，无法撤销") },
            confirmButton = {
                TextButton(onClick = {
                    RequestStore.clearHistory()
                    showClearConfirm = false
                }) { Text("清空", color = StreamColors.RedText) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }

    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        StreamTopBar(title = "抓包历史", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("抓包 SESSION 历史记录")
            Group {
                if (sessions.isEmpty()) {
                    CellRow("暂无记录", showDivider = false)
                }
                sessions.forEachIndexed { i, s ->
                    val running = s.endTime == 0L
                    CellRow(
                        title = timeFmt.format(Date(s.startTime)),
                        subtitle = if (running) "进行中" else "${s.durationSec} 秒",
                        showChevron = true,
                        onClick = { onOpenSession(s.id) },
                        showDivider = i < sessions.size - 1
                    )
                }
            }
            SectionHeader("清空全部记录，无法撤销")
            Group {
                CellRow(
                    "清空抓包历史",
                    titleColor = StreamColors.RedText,
                    onClick = { showClearConfirm = true },
                    showDivider = false
                )
            }
        }
    }
}
