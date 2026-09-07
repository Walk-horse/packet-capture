package com.ht.stream.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ht.stream.proxy.LocalProxyServer

/** HTTPS 抓包：安装 CA + 导出 + 清除 MITM 缓存 */
@Composable
fun HttpsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        StreamTopBar(title = "HTTPS 抓包", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            GroupNote(
                "如果您要抓取 HTTPS 的请求，需要先启动抓包，然后安装 CA 证书。\n\n" +
                    "注意：Android 7+ 应用默认不信任用户证书，仅「信任用户 CA」的 App 可解密；" +
                    "其余 App 的 HTTPS 连接会自动透传，不受影响。"
            )
            Group {
                CellRow(
                    "步骤一：安装 CA 证书",
                    onClick = { CertInstall.installViaSystem(context as Activity) },
                    showDivider = false
                )
            }
            GroupNote("系统安装失败时，可导出 .crt 到下载目录，再到 设置→安全→加密与凭据→安装证书 手动选择。")
            Group {
                CellRow(
                    "导出 CA 证书到下载目录",
                    onClick = {
                        val path = CertInstall.exportToDownloads(context)
                        Toast.makeText(
                            context,
                            if (path != null) "已导出：$path" else "导出失败",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    showDivider = false
                )
            }
            SectionHeader("清除 MITM 缓存")
            Group {
                CellRow(
                    "清除 MITM 缓存",
                    titleColor = StreamColors.RedText,
                    onClick = {
                        LocalProxyServer.clearMitmCache()
                        Toast.makeText(context, "已清除站点证书缓存与失败黑名单", Toast.LENGTH_SHORT).show()
                    },
                    showDivider = false
                )
            }
        }
    }
}
