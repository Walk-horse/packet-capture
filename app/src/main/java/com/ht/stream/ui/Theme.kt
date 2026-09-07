package com.ht.stream.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** iOS Stream 风格配色 */
object StreamColors {
    val Blue = Color(0xFF4C8BF5)        // 顶栏 / 工具图标
    val ButtonBlue = Color(0xFF4C8BF5)  // 抓包历史按钮
    val Orange = Color(0xFFE8661C)      // 开始抓包按钮
    val Teal = Color(0xFF5AB3A6)        // 设置图标
    val BgGray = Color(0xFFF2F2F7)      // 分组背景
    val RedText = Color(0xFFE0443A)     // 危险操作
    val Divider = Color(0xFFE5E5EA)
    val SubText = Color(0xFF8E8E93)
}

private val LightColors = lightColorScheme(
    primary = StreamColors.Blue,
    secondary = StreamColors.Teal,
    background = StreamColors.BgGray,
    surface = Color.White,
    surfaceVariant = StreamColors.BgGray
)

private val DarkColors = darkColorScheme(
    primary = StreamColors.Blue,
    secondary = StreamColors.Teal
)

@Composable
fun StreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
