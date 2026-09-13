# desktool/win — Packet Capture 桌面面板（Windows / Kotlin + Compose Desktop）

配套 Android 抓包 App（Packet Capture / `com.ht.stream`）的 **Windows 桌面面板**，
是 `desktool/mac`（macOS / SwiftUI 版）的 1:1 功能移植。手机端开启同步服务后，面板通过局域网
（或 USB + adb 端口转发）实时拉取抓包结果并查看请求详情。

> 同一份代码在 macOS / Linux 上也能直接 `gradlew run` 起来，便于在没有 Windows 机器时验证界面逻辑；
> 发布 Windows 安装包需在 Windows 上执行 `packageMsi` / `packageExe`。

## 一、使用流程

1. **手机端开启同步**
   - 打开 Packet Capture → 设置 →「设置抓包模式」→ 底部「同步数据到电脑」开关
   - 开启后页面显示同步地址，例如 `http://192.168.1.20:17890`（点击可复制）
   - 前提：手机与电脑处于**同一 Wi-Fi**；同步服务随 App 进程存活（抓包期间由前台服务保活）
   - 调试捷径：`adb shell am start -n com.ht.stream.debug/com.ht.stream.MainActivity --ez sync_on true`
     （注意：该 Activity 是 singleTop，**需先 `am force-stop` 再启动**，否则走热启动不会执行 onCreate）

2. **电脑端连接**
   - 工具栏地址框填入手机 IP + 端口，例如 `192.168.1.20:17890`（也可粘完整地址，会自动规范化）
   - 回车即触发一次全量同步
   - 或点地址框左侧的 **USB 图标**：自动通过 adb 探测已授权设备并做端口转发
     （0 台报错 / 1 台自动连接 / 多台弹出选择框；每台设备分配 `17890+序号` 的本地端口，互不冲突）

3. **同步方式**
   - **自动同步**：工具栏「自动」开关，间隔可选 1s / 2s / 5s（默认开启 1.5s）
   - **手动同步**：点 ⟳，只拉取自上次以来的**新增**请求
   - **全量重新同步**：点 ⋯ →「全量重新同步」，清空游标整体重拉（手机端清空历史后用它对齐）
   - 设置（地址 / 开关 / 间隔）保存在本机 `java.util.prefs`，重启后保留

## 二、界面

- **左栏**：
  - 会话列表（全部请求 / 各次抓包会话）
  - **域名分组**：按 host 聚合，显示各域名请求数并降序排列，默认展示前 20 个，点「显示全部 N 个域名」展开；
    点选后中栏只显示该域名的请求
  - 透传连接；底部实时统计（是否抓包中、请求数、上传下载、本地已同步条数）
- **工具栏**：脉冲图标 + 标题 / 状态灯 + USB 探测 + 地址框 + 状态文案 /「自动」开关 · 间隔 · ⟳ 手动同步 · ⋯ 更多
  （图标均有悬停提示）
- **中栏**：请求列表（方法/状态码着色，接口名 + 域名，耗时与时间），顶部搜索 host / path / method / 状态码；
  搜索框下方为**接口类型过滤**（全部 / Fetch·XHR / 文档 / CSS / JS / 图片 / Wasm / 其他，带各类计数，
  口径与手机端一致：响应 Content-Type 优先，缺失按 path 扩展名兜底）
- **右栏**：请求详情
  - 请求行 URL 可选中复制；**「复制 cURL」**生成可直接重放的 curl 命令（方法、完整 URL、全部请求头、
    请求体 `--data-raw`；原始 body 为压缩时写入解压明文并去掉 `Content-Encoding`/`Content-Length`）
  - 状态、耗时、时间、请求/响应大小、远端 IP、UID、收藏 一栏展示；失败时额外显示错误行
  - 四个分页：请求头 / 请求体 / 响应头 / 响应体（默认停在**响应体**）
  - Body 支持 **文本 / HEX** 双视角、JSON 美化（**文本为 JSON 时自动勾选**，手动调整后不再自动覆盖）、
    复制、保存为文件
  - 大 body 优化：base64 解码结果走 LRU 缓存（256 条 / 96MB）；文本/HEX/美化在后台线程解析一次并缓存；
    渲染层用 Swing `JTextArea` 承载（与 mac 版用 `NSTextView` 同理，超过 1MB 截断显示，复制/保存仍为完整内容）
  - 压缩内容（gzip / deflate / br / zstd）由手机端解压后同步，电脑端无需解压库；
    二进制显示格式提示，可切 HEX 看原始字节

## 三、同步协议

与 mac 版完全一致，手机端内嵌 HTTP 服务（`SyncServer`），默认端口 `17890`（被占用时自动向后顺延 10 个）：

```
GET /api/state?after=<lastExchangeId>   # 增量：返回比该 id 更新的请求（倒序）
GET /api/state                          # 全量：after 找不到时返回 full=true 全量
GET /api/ping
```

响应字段：`capturing`、`uploadBytes`、`downloadBytes`、`requestCount`、`passthroughCount`、`full`、
`sessions[]`、`passthrough[]`、`exchanges[]`（含 headers 对、body Base64、手机端已解析文本 `respText`）。

- 增量依赖「列表头部追加」语义：服务端找不到 `after` 则回退全量，客户端整体替换
- body 单条同步上限 512KB（超出标记 `truncated`），文本同步上限 256K 字符
- 面板本地最多保留 2000 条

> 解析时开启了 `ignoreUnknownKeys`：手机端新增字段（例如 `startedAt`）不会导致面板报错。

## 四、构建与运行

前置：**JDK 17+**（推荐 JDK 21），`JAVA_HOME` 指向它。

```bash
# 开发运行（Windows / macOS / Linux 通用）
./gradlew run            # Windows: gradlew.bat run
# 或
./run.sh                 # Windows: run.bat

# 单元测试（格式化 / 类型分类 / 协议解析 / 增量合并）
./gradlew test

# 打包 Windows 安装包（必须在 Windows 上执行）
./gradlew packageMsi     # 输出 build/compose/binaries/main/msi/*.msi
./gradlew packageExe     # 输出 build/compose/binaries/main/exe/*.exe
# 或直接
./pack_win.bat
```

产物目录：`build/compose/binaries/main/{msi,exe}`；绿色免安装版可用 `./gradlew createDistributable`
（`build/compose/binaries/main/app/PacketCaptureDesk/`）。

## 五、目录结构

```
desktool/win/
├─ build.gradle.kts              Kotlin 2.1.20 + Compose 1.8.2 + kotlinx-serialization
├─ settings.gradle.kts           仓库配置（Maven Central / Google / JetBrains Compose dev）
├─ gradle.properties
├─ run.sh / run.bat              开发运行
├─ pack_win.bat                  打包 msi
├─ src/main/kotlin/com/ht/streamdesk/
│  ├─ Main.kt                    应用入口 + 窗口 + 调色板
│  ├─ SyncClient.kt              轮询 / 增量合并 / 自动同步 / adb USB 探测
│  ├─ Models.kt                  协议模型（@Serializable）+ body LRU 缓存
│  ├─ ContentView.kt             三栏布局 + 工具栏 + 多设备选择框
│  ├─ SidebarView.kt             会话列表 / 域名分组 / 统计条
│  ├─ RequestListView.kt         请求列表 / 类型过滤 / 透传列表
│  ├─ DetailView.kt              请求详情（头部信息 + 四页签 + 复制 cURL）
│  ├─ BodyView.kt                body 文本 / HEX / JSON 美化 / 复制 / 保存
│  ├─ LargeTextView.kt           Swing JTextArea 承载大文本
│  ├─ DesktopIcons.kt            自绘矢量图标集
│  ├─ Formatting.kt              大小、耗时、颜色、hex dump、解码、cURL
│  └─ ResourceType.kt            接口类型分类
└─ src/test/
   ├─ kotlin/com/ht/streamdesk/ProtocolTest.kt   单测（含增量合并语义）
   └─ resources/state-sample.json                真机抓取的真实同步样本
```

## 六、与 mac 版的差异（有意为之）

1. **图标**：mac 用 SF Symbols，本版内置 `DesktopIcons.kt` 手绘 24×24 矢量图标。
   原因：`org.jetbrains.compose.material:material-icons-core` 只发到 1.7.3，与本工程 Compose 1.8.2 不匹配；
   而 androidx 的 `material-icons-core-desktop` 会把 `androidx.compose.ui` 拖进来，与 `org.jetbrains.compose.ui` 重复类。
2. **`decodeText` 更严格**：mac 版兜底会退到 Latin-1，而 Latin-1 对任意字节都成功 → 二进制 body 会被当成
   乱码文本（cURL 里也会生成乱码 `--data-raw`）。本版改为「UTF-16 BOM → 严格 UTF-8 → 可打印占比 ≥90%」，
   不可读即返回 `null`，交给 HEX 视图，并在 cURL 里提示改用 `--data-binary @文件`。
3. **大文本渲染**：mac 用 `NSTextView`，本版用 Swing `JTextArea`（同为视口渲染 + 可选中复制）。
4. **设置存储**：mac 用 `UserDefaults`，本版用 `java.util.prefs`（Windows 落在注册表，macOS 落在 plist）。

## 七、已知坑 / 故障排查

- **设置存储位置（macOS）**：JBR 的 `java.util.prefs` 落在
  `~/Library/Preferences/com.ht.streamdesk.plist`（按包名反向域名，**不是** `com.apple.java.util.prefs.plist`）。
  想恢复出厂状态：`defaults delete com.ht.streamdesk`（走系统 API，直接改文件可能被缓存覆盖）。
- **macOS 上 `gradlew run` 报 skiko `Operation not permitted`**：`~/.skiko` 运行时解压被系统拦截。
  本仓库把 skiko dylib 预解压到 `.skiko-libs/`，`build.gradle.kts` 检测到该目录会自动加
  `-Dskiko.library.path` 跳过解压（Windows 不受影响，目录不存在即不生效）。
- **Wi-Fi 直连回退只信 `wlan0`**：抓包 VPN 开启时手机默认路由指向 TUN（`ip route get` 会拿到
  `10.0.0.2` 甚至蜂窝 IP），因此绝不用 route 表兜底；adb forward 失败会自动重试一次。
- **诊断日志**：关键链路（启动 / USB 探测 / forward / 全量与失败）落盘 `/tmp/streamdesk-debug.log`，
  增量 tick 不记录以免刷屏。
