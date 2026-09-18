package com.ht.stream.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ht.stream.capture.CaptureVpnService
import com.ht.stream.data.CaptureMode
import com.ht.stream.proxy.LocalProxyServer

/** HTTPS 抓包：安装 CA + 导出 + HTTPS 代理开关 + 清除 MITM 缓存 */
@Composable
fun HttpsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var httpsProxy by remember { mutableStateOf(CaptureMode.httpsProxyOn(context)) }

    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        StreamTopBar(title = "HTTPS 抓包", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            GroupNote(
                "如果您要抓取 HTTPS 的请求，需要先启动抓包，然后安装 CA 证书。\n\n" +
                    "注意：Android 7+ 应用默认不信任用户证书，仅「信任用户 CA」的 App 可解密；" +
                    "其余 App 会在 TLS 握手失败时记录原因并断开连接。"
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

            SectionHeader("HTTPS 代理（系统代理）")
            Group {
                CellRow(
                    title = "启用 HTTPS 代理",
                    subtitle = "开启后可抓取 WebView / H5 等走系统代理的流量；需 Android 10+，开启抓包时生效。目标 App 不信任本 CA 时会记录 TLS 握手失败。",
                    showDivider = false,
                    trailing = {
                        Switch(
                            checked = httpsProxy,
                            onCheckedChange = {
                                CaptureMode.setHttpsProxyOn(context, it)
                                httpsProxy = it
                                Toast.makeText(
                                    context,
                                    if (CaptureVpnService.running.value) "已保存，重启抓包后生效"
                                    else "已保存，下次开启抓包生效",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = StreamColors.Blue)
                        )
                    }
                )
            }
            GroupNote(
                "该开关对应 ProxyPin 的「HTTPS 代理」：开启后会在 VPN 接口上把整机 HTTP/HTTPS 系统代理" +
                    "指向本机代理端口，从而接管 WebView 等原本直连 + 抢 QUIC 的流量。\n\n" +
                    "默认开启，与 ProxyPin 一致；关闭后仅保留 VPN 透明接管路径。"
            )

            SectionHeader("清除 MITM 缓存")
            Group {
                CellRow(
                    "清除 MITM 缓存",
                    titleColor = StreamColors.RedText,
                    onClick = {
                        LocalProxyServer.clearMitmCache()
                        Toast.makeText(context, "已清除站点证书缓存", Toast.LENGTH_SHORT).show()
                    },
                    showDivider = false
                )
            }
        }
    }
}
