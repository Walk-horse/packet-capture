# desktool — Packet Capture 桌面面板（macOS / SwiftUI）

配套 Android 抓包 App（Packet Capture / `com.ht.stream`）的 macOS 面板。手机端开启同步服务后，面板可通过局域网实时拉取抓包结果并查看请求详情。

## 一、使用流程

1. **手机端开启同步**
   - 打开 Packet Capture → 设置 →「设置抓包模式」→ 底部「同步数据到电脑」开关
   - 开启后页面显示同步地址，例如 `http://192.168.1.20:17890/api/state`（点击可复制）
   - 前提：手机与 Mac 处于**同一 Wi-Fi**；同步服务随 App 进程存活（抓包期间由前台服务保活）

2. **Mac 端连接**
   - 构建并打开面板（见下）
   - 工具栏左侧地址框填入手机 IP + 端口，例如 `192.168.1.20:17890`（也可粘完整地址，会自动规范化）
   - 回车即触发一次全量同步

3. **同步方式**
   - **自动同步**：工具栏「自动同步」开关，间隔可选 1s / 2s / 5s（默认开启，1.5s 起）
   - **手动同步**：点「手动同步」按钮，只拉取自上次以来的**新增**请求
   - **全量重新同步**：点 `⋯` →「全量重新同步」，清空游标整体重拉（手机端清空历史后用它对齐）

## 二、界面

- **左栏**：
  - 会话列表（全部请求 / 各次抓包会话）
  - **域名分组**：按 host 聚合，显示各域名请求数并降序排列，默认展示前 20 个，点「显示全部 N 个域名」展开；点选后中栏只显示该域名的请求
  - 透传连接；底部实时统计（是否抓包中、请求数、上传下载、本地已同步条数）
- **工具栏**：左侧标题，中间同步地址 + 连接状态灯，右侧自动同步开关（紧凑尺寸）/ 间隔 / 手动同步 / 更多
- **中栏**：请求列表（方法/状态码着色，接口名 + 域名，耗时与时间），顶部支持搜索 host / path / method / 状态码；搜索框下方为**接口类型过滤**（全部 / Fetch·XHR / 文档 / CSS / JS / 图片 / Wasm / 其他，带各类计数，口径与手机端一致：响应 Content-Type 优先，缺失按 path 扩展名兜底）
- **右栏**：请求详情
  - 请求行 URL 可复制；**「复制 cURL」** 生成可直接重放的 curl 命令（方法、完整 URL、全部请求头、请求体 `--data-raw`；原始 body 为压缩时会写入解压明文并去掉 `Content-Encoding`/`Content-Length`）；状态、耗时、时间、请求/响应大小、远端 IP、UID 一栏展示
  - 四个分页：请求头 / 请求体 / 响应头 / 响应体
  - Body 支持 **文本 / HEX** 双视角、JSON 美化（**文本为 JSON 时自动勾选**，手动调整后不再自动覆盖）、复制、保存为文件
  - 大 body 优化：base64 解码结果走缓存；文本/HEX/美化在后台线程解析一次并缓存；渲染层用 `NSTextView` 承载（超过 1MB 截断显示，复制/保存仍为完整内容）
  - 压缩内容（gzip / deflate / br / zstd）由手机端解压后同步，Mac 端无需解压库；二进制显示格式提示，可切 HEX 看原始字节

## 三、同步协议

手机端内嵌 HTTP 服务（`SyncServer`），默认端口 `17890`（被占用时自动向后顺延 10 个）：

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

## 四、构建与运行

```bash
cd desktool
./build_app.sh                    # swift build -c release + 组装 .app
open "dist/Packet Capture Panel.app"
```

- 需要 Xcode Command Line Tools（Swift 5.9+ / macOS 13+）
- 产物：`dist/Packet Capture Panel.app`，也可 `swift run StreamDesk` 直接跑
- 若构建报 SwiftPM 沙箱错误（`~/.swiftpm/security`），脚本已带 `--disable-sandbox`

> 说明：`.app` 内 Info.plist 开了 `NSAllowsArbitraryLoads`，因为同步地址是局域网明文 HTTP。

## 五、目录结构

```
desktool/
├─ Package.swift                    SwiftPM（executable target: StreamDesk）
├─ build_app.sh                     构建 + 组装 .app
├─ README.md
└─ Sources/StreamDesk/
   ├─ App.swift                     @main 入口
   ├─ SyncClient.swift              轮询 / 增量合并 / 自动+手动同步
   ├─ Models.swift                  协议模型（Codable）
   ├─ ContentView.swift             三栏布局 + 工具栏
   ├─ SidebarView.swift             会话列表 + 统计条
   ├─ RequestListView.swift         请求列表 / 透传列表
   ├─ DetailView.swift              请求详情（headers / body 分页）
   ├─ BodyView.swift                body 文本 / HEX / 保存
   └─ Formatting.swift              大小、耗时、颜色、hex dump、解码
```
