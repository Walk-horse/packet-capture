# Packet capture

Android 端抓包工具，界面与功能参考 iOS「Stream」实现。本机 VPN 接管流量 → 本地代理解析 → HTTP/HTTPS 请求可视化。

## 技术栈

Kotlin + Jetpack Compose + Material3 · minSdk 24 · targetSdk 35 · BouncyCastle（证书签发）

## 架构

```
App 流量
   │
   ▼
VpnService (TUN 10.0.0.2)              capture/CaptureVpnService.kt
   │  用户态 TCP 协议栈（简化版）          capture/TcpSession.kt
   │  UDP 中继（DNS）                    capture/UdpSession.kt
   ▼
LocalProxyServer (127.0.0.1:8888)      proxy/LocalProxyServer.kt
   ├─ 明文 HTTP → 解析记录 → 转发
   ├─ TLS ClientHello → SNI → 动态签站点证书 → MITM → 解析记录
   ├─ MITM 失败的 host → 自动进黑名单 → 加密透传（不干扰上网）
   ├─ 抓包模式命中的 host → 直接透传（黑白名单）
   ├─ Hosts 命中的域名 → 改连映射 IP（SNI/Host 头保持原域名）
   └─ 其他协议 → 盲转发
   ▼
RequestStore (StateFlow) → Compose UI
```

## 功能

### 总览

- 一键开始/停止抓包（VpnService 授权），抓包中按钮变绿并实时计时
- 上传/下载流量、请求数实时统计

### 抓包历史 / 全部请求

- 按抓包批次（SESSION）分组，支持清空历史
- 全部请求 / 按域名 两种视图，按 host·path·method 搜索
- 列表显示 method 彩标、host、path、状态码、大小、耗时

### 抓包详情

- 总览（域名/流量/地址/状态/标识符）、请求、响应、时间 四个 Tab
- 请求/响应 headers + body 预览，gzip/deflate 自动解码，JSON 自动美化
- 响应 body 搜索（高亮 + 匹配计数）、body 一键复制
- 收藏（⭐）与更多操作：分享请求和响应 / 获取 cURL 命令 / 编辑重放请求 / 导出 HAR

### 工具

- **构建请求**：方法/链接/请求头/请求体组装执行，支持粘贴 curl 自动解析
- **Hosts 设置**：域名 → IP 映射（支持 `*.` 通配），代理层生效，可导出分享
- **收藏请求**：收藏列表直达详情
- **常用工具**：URL 编解码 / Base64 / MD5 / 时间戳转化 / RSA-2048 加解密

### 设置

- **HTTPS 抓包**：安装 CA 证书（系统安装 / 导出到下载目录）、清除 MITM 缓存
- **设置抓包模式**：黑/白名单（白名单优先），命中的域名直接透传
- **日志**：每次抓包生成一个日志文件，可查看/分享/清空
- **教程**：内置使用教程与 FAQ
- **关于**：版本信息与免责声明

## 已知限制

| 限制                | 说明                                                                                                                                   |
| ------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| Android 7+ 信任问题 | 第三方 App 默认不信任用户 CA，此类 App 的 HTTPS 无法解密（首次连接失败后自动透传，不影响上网）。要解密需 root 后将 CA 装入系统证书目录 |
| 单请求单连接        | 对 App 回 `Connection: close`，不支持 keep-alive 复用与 HTTP/2（ALPN 强制 http/1.1）                                                   |
| 简化 TCP 栈         | 不重传、不处理乱序（本机 VPN 环境够用）                                                                                                |
| 无 App 归属识别     | VpnService 拿不到包级 UID 映射，「按进程」分组未实现                                                                                   |

## 构建

```bash
export JAVA_HOME=<JDK 21 路径>
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 安装启动，点「开始抓包」，授予 VPN 权限
2. 打开任意 App 上网，回到本 App → 抓包历史 查看请求
3. HTTPS 解密：设置 → HTTPS 抓包 → 安装「Packet capture CA」证书
4. 详情页右上角可收藏、复制 curl、重放、导出 HAR

## 免责声明
