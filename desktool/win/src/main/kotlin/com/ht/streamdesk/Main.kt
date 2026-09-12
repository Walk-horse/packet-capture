package com.ht.streamdesk

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension

fun main() = application {
    val state = rememberWindowState(size = DpSize(1320.dp, 860.dp))
    Window(
        onCloseRequest = ::exitApplication,
        state = state,
        title = "Packet Capture 桌面面板",
    ) {
        window.minimumSize = Dimension(1080, 680)
        App()
    }
}

@Composable
fun App() {
    val client = remember { SyncClient() }
    DisposableEffect(Unit) { onDispose { client.close() } }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            background = Color(0xFFF6F7F9),
            surface = Color.White,
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ContentView(client)
        }
    }
}

// MARK: - 调色板（对齐 mac 版观感：浅色、蓝色强调）

val Accent = Color(0xFF1A73E8)
val SubText = Color(0xFF6B7280)
val HairLine = Color(0xFFE3E6EA)
val ChipBg = Color(0xFFEEEFF2)
val SelectedBg = Color(0xFFE7F0FE)
val StatsBg = Color(0xFFF0F1F4)
