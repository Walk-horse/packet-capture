package com.ht.stream.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class TutorialSection(val title: String, val lines: List<String>)

private val tutorialSections = listOf(
    TutorialSection(
        "快速开始",
        listOf(
            "1. 总览页点「开始抓包」，授予 VPN 权限",
            "2. 按钮变绿并开始计时后，打开任意 App 上网",
            "3. 回到本 App → 抓包历史 → 查看全部请求"
        )
    ),
    TutorialSection(
        "HTTPS 抓包（解密）",
        listOf(
            "1. 设置 → HTTPS 抓包 → 安装 CA 证书（系统安装或导出到下载目录手动安装）",
            "2. Android 7+ 第三方 App 默认不信任用户证书，此类 App 会自动加密透传，不影响上网",
            "3. 要解密任意 App：需 root 后将 CA 移入系统证书目录，或目标 App 放行用户证书",
            "4. 换设备/重装 App 后需重新安装 CA；「清除 MITM 缓存」可重置证书缓存"
        )
    ),
    TutorialSection(
        "抓包模式（黑/白名单）",
        listOf(
            "黑名单：列表中的域名不解密、不记录，直接透传",
            "白名单：仅列表中的域名被解密记录，其余全部透传（白名单优先于黑名单）",
            "支持通配符：*.example.com 匹配所有子域名"
        )
    ),
    TutorialSection(
        "Hosts 设置",
        listOf(
            "将域名映射到指定 IP，命中后代理改连映射目标（SNI/Host 头保持原域名）",
            "常用于把接口指向测试环境，同样支持 *. 通配符"
        )
    ),
    TutorialSection(
        "构建请求 / 重放",
        listOf(
            "工具 → 构建请求：手动组装方法/链接/请求头/请求体，▶ 执行",
            "支持粘贴 curl 命令自动解析填充",
            "请求详情 → 分享 → 编辑重放请求，可一键预填后修改重发"
        )
    ),
    TutorialSection(
        "常见问题",
        listOf(
            "Q：抓不到任何请求？A：确认 VPN 已开启（状态栏有 VPN 图标），且没有其他 VPN 占用",
            "Q：其他 App 提示网络错误？A：升级至最新版本后依然存在时，把对应域名加入黑名单",
            "Q：HTTPS 看不到内容？A：该 App 不信任用户 CA，属 Android 系统限制，非 root 无解",
            "Q：日志在哪里？A：设置 → 日志，每次抓包生成一个文件，可查看/分享/清空"
        )
    )
)

/** 教程页 */
@Composable
fun TutorialScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        StreamTopBar(title = "教程", onBack = onBack, backLabel = "总览")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            tutorialSections.forEach { section ->
                SectionHeader(section.title)
                Group {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        section.lines.forEachIndexed { i, line ->
                            Text(line, fontSize = 13.sp, lineHeight = 20.sp)
                            if (i < section.lines.lastIndex) Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** 关于页 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) { "-" }

    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        StreamTopBar(title = "关于", onBack = onBack, backLabel = "总览")
        Column(
            Modifier.fillMaxWidth().padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(72.dp).background(Color.White, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.ht.stream.R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("Packet capture", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("版本 $version", fontSize = 13.sp, color = StreamColors.SubText)
        }

        SectionHeader("技术说明")
        Group {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "本机 VpnService 接管流量 → 用户态 TCP 中继 → 本地代理（127.0.0.1:8888）→ " +
                        "HTTP 解析 / TLS MITM 解密（BouncyCastle 动态签发站点证书）",
                    fontSize = 13.sp, lineHeight = 20.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Kotlin · Jetpack Compose · minSdk 24",
                    fontSize = 12.sp, color = StreamColors.SubText, fontFamily = FontFamily.Monospace
                )
            }
        }

        SectionHeader("免责声明")
        Group {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "本工具仅供开发调试与学习使用。请确保只抓取自己设备或已获授权的流量，" +
                        "使用者需自行承担因不当使用产生的法律责任。",
                    fontSize = 13.sp, lineHeight = 20.sp
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
