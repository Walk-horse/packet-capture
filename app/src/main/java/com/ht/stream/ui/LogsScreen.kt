package com.ht.stream.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ht.stream.data.FileLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 抓包日志列表 */
@Composable
fun LogsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val files = remember(version) { FileLogger.listFiles(context) }
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        StreamTopBar(
            title = "抓包日志",
            onBack = onBack,
            actions = {
                IconButton(onClick = {
                    files.firstOrNull()?.let { shareLog(context, it) }
                }) {
                    Icon(Icons.Default.Share, contentDescription = "分享最新日志", tint = Color.White)
                }
            }
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("每次抓包生成一个日志文件")
            Group {
                if (files.isEmpty()) {
                    CellRow("暂无日志", showDivider = false)
                }
                files.forEachIndexed { i, f ->
                    CellRow(
                        title = f.name,
                        subtitle = "${timeFmt.format(Date(f.lastModified()))} · ${formatByteSize(f.length())}",
                        showChevron = true,
                        onClick = { onOpen(f.name) },
                        showDivider = i < files.size - 1
                    )
                }
            }
            if (files.isNotEmpty()) {
                SectionHeader("清空全部日志，无法撤销")
                Group {
                    CellRow(
                        "清空日志",
                        titleColor = StreamColors.RedText,
                        onClick = { FileLogger.deleteAll(context); version++ },
                        showDivider = false
                    )
                }
            }
        }
    }
}

/** 日志内容查看页 */
@Composable
fun LogViewScreen(fileName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val file = remember(fileName) { File(File(context.filesDir, "logs"), fileName) }
    val content = remember(fileName) {
        runCatching {
            val bytes = file.readBytes()
            String(bytes, 0, minOf(bytes.size, 512 * 1024), Charsets.UTF_8)
        }.getOrElse { "读取失败：${it.message}" }
    }

    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        StreamTopBar(
            title = fileName,
            onBack = onBack,
            backLabel = "日志",
            actions = {
                IconButton(onClick = { shareLog(context, file) }) {
                    Icon(Icons.Default.Share, contentDescription = "分享", tint = Color.White)
                }
            }
        )
        SelectionContainer {
            Text(
                content,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }
    }
}

private fun shareLog(context: android.content.Context, file: File) {
    val text = runCatching { file.readText() }.getOrElse { return }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_TITLE, file.name)
    }
    context.startActivity(Intent.createChooser(send, "分享日志"))
}
