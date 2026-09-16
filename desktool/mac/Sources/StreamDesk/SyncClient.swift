import Foundation
import SwiftUI

/// 已连接的 USB 设备
struct UsbDevice: Identifiable {
    let serial: String
    let model: String
    var id: String { serial }
}

/// 手机端同步客户端：定时增量拉取 /api/state，支持自动同步与手动同步
@MainActor
final class SyncClient: ObservableObject {

    enum Status: Equatable {
        case idle
        case syncing
        case ok(Date)
        case failed(String)

        var text: String {
            switch self {
            case .idle: return "未连接"
            case .syncing: return "同步中…"
            case .ok(let d): return "已同步 \(Self.clock.string(from: d))"
            case .failed(let msg): return "失败：\(msg)"
            }
        }

        var color: Color {
            switch self {
            case .idle: return .gray
            case .syncing: return .orange
            case .ok: return .green
            case .failed: return .red
            }
        }

        private static let clock: DateFormatter = {
            let f = DateFormatter()
            f.dateFormat = "HH:mm:ss"
            return f
        }()
    }

    struct Stats {
        var capturing = false
        var upload: Int64 = 0
        var download: Int64 = 0
        var requests = 0
        var passthrough = 0
        var startedAt: Int64 = 0
    }

    @Published var address: String
    @Published private(set) var autoSync: Bool
    @Published private(set) var interval: Double
    @Published private(set) var status: Status = .idle
    @Published private(set) var exchanges: [ExchangeRecord] = []
    @Published private(set) var sessions: [SessionInfo] = []
    @Published private(set) var passthrough: [PassRecord] = []
    @Published private(set) var stats = Stats()
    @Published private(set) var devices: [UsbDevice] = []
    @Published private(set) var selectedSerial: String?
    @Published var needsPick = false
    /// WS 推送是否已连接（实时增量通道；原轮询通道照常保留作为兜底）
    @Published private(set) var wsConnected = false
    /// 一键重联进行中（UI 用于禁用按钮 / 展示进度）
    @Published private(set) var reconnecting = false

    /// 手机端接口模拟状态（随 /api/state 轮询更新）
    @Published private(set) var mockStatus: MockStatus?

    /// 本地最多保留条数（超出丢弃最旧的，与手机端 500 上限解耦）
    private let maxLocal = 2000
    private var ids: Set<String> = []
    private var timer: Timer?
    /// 拉取调度：单 worker + 待办折叠（全量优先），避免请求被丢弃或堆积
    private var workerRunning = false
    private var incrementalRequested = false
    private var fullRequested = false
    /// 自愈限频：上次尝试重建 adb forward 的时间 / 是否正在进行
    private var lastHealAt = Date.distantPast
    private var healInFlight = false

    init() {
        let ud = UserDefaults.standard
        self.address = ud.string(forKey: Key.address) ?? ""
        self.autoSync = ud.object(forKey: Key.autoSync) as? Bool ?? true
        self.interval = ud.object(forKey: Key.interval) as? Double ?? 1.5
    }

    private enum Key {
        static let address = "desktool.address"
        static let autoSync = "desktool.autoSync"
        static let interval = "desktool.interval"
    }

    // MARK: - 配置

    func saveAddress() {
        UserDefaults.standard.set(address, forKey: Key.address)
        connectWs()
    }

    func setAutoSync(_ on: Bool) {
        autoSync = on
        UserDefaults.standard.set(on, forKey: Key.autoSync)
        schedule()
        if on { Task { await pull() } }
        connectWs()
    }

    func setInterval(_ value: Double) {
        interval = value
        UserDefaults.standard.set(value, forKey: Key.interval)
        schedule()
    }

    // MARK: - USB 设备选择

    /// 通过 adb 探测所有已授权的 USB 手机：
    /// - 0 台：报错
    /// - 1 台：自动连接
    /// - 多台：置 needsPick，由界面弹出选择器让用户选
    func detectUsb() async {
        guard let adb = Self.adbPath() else {
            status = .failed("未找到 adb，请安装 Android SDK 平台工具（或把 adb 放入 PATH）")
            return
        }
        do {
            let out = try await Task.detached { try Self.runAdb(["devices", "-l"], adb: adb) }.value
            let list = Self.parseDeviceList(out)
            devices = list
            if list.isEmpty {
                needsPick = false
                status = .failed("未发现已授权的 USB 设备（请确认 USB 调试已开启）")
            } else if list.count == 1 {
                needsPick = false
                await selectDevice(list[0])
            } else {
                needsPick = true
            }
        } catch {
            status = .failed(friendly(error))
        }
    }

    /// 选择某台 USB 设备作为同步源：
    /// 为该设备分配独立本地端口（17890 + 序号）做 adb forward，避免多机抢占同一端口
    func selectDevice(_ dev: UsbDevice) async {
        guard let adb = Self.adbPath() else {
            status = .failed("未找到 adb，请安装 Android SDK 平台工具")
            return
        }
        guard let idx = devices.firstIndex(where: { $0.serial == dev.serial }) else { return }
        let localPort = 17890 + idx
        status = .syncing
        do {
            _ = try? Self.runAdb(["forward", "--remove", "tcp:\(localPort)"], adb: adb)
            if (try? Self.runAdb(["-s", dev.serial, "forward", "tcp:\(localPort)", "tcp:17890"], adb: adb)) != nil {
                address = "127.0.0.1:\(localPort)"
            } else {
                let ip = try await Task.detached { try Self.getWifiIp(serial: dev.serial, adb: adb) }.value
                address = "\(ip):17890"
            }
            selectedSerial = dev.serial
            saveAddress()
            await resetAndPull()
        } catch {
            status = .failed(friendly(error))
        }
    }

    private nonisolated static func adbPath() -> String? {
        let candidates = [
            (NSHomeDirectory() as NSString).appendingPathComponent("Library/Android/sdk/platform-tools/adb"),
            "/usr/local/bin/adb",
            "/opt/homebrew/bin/adb"
        ]
        for c in candidates where FileManager.default.isExecutableFile(atPath: c) { return c }
        if let pathEnv = ProcessInfo.processInfo.environment["PATH"] {
            for dir in pathEnv.split(separator: ":") {
                let p = (dir as NSString).appendingPathComponent("adb")
                if FileManager.default.isExecutableFile(atPath: p) { return p }
            }
        }
        return nil
    }

    private nonisolated static func runAdb(_ args: [String], adb: String) throws -> String {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: adb)
        p.arguments = args
        let out = Pipe()
        let err = Pipe()
        p.standardOutput = out
        p.standardError = err
        try p.run()
        p.waitUntilExit()
        let o = String(data: out.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        if p.terminationStatus != 0 {
            let e = String(data: err.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
            throw NSError(domain: "adb", code: Int(p.terminationStatus),
                          userInfo: [NSLocalizedDescriptionKey: (e.isEmpty ? o : e).trimmingCharacters(in: .whitespacesAndNewlines)])
        }
        return o
    }

    private nonisolated static func parseDeviceList(_ output: String) -> [UsbDevice] {
        var res: [UsbDevice] = []
        for line in output.split(separator: "\n") {
            let parts = line.split(separator: "\t").map { $0.trimmingCharacters(in: .whitespaces) }
            guard parts.count >= 2, parts[1] == "device" else { continue }
            let serial = parts[0]
            guard !serial.hasPrefix("emulator") else { continue }
            var model = ""
            for kv in parts.dropFirst(2) where kv.hasPrefix("model:") {
                model = String(kv.dropFirst(6))
            }
            res.append(UsbDevice(serial: serial, model: model))
        }
        return res
    }

    private nonisolated static func getWifiIp(serial: String, adb: String) throws -> String {
        let out = try runAdb(["-s", serial, "shell", "ip", "addr", "show", "wlan0"], adb: adb)
        if let m = out.firstMatch(of: #/inet\s+(\d{1,3}(?:\.\d{1,3}){3})/\d+/#) {
            return String(m.1)
        }
        let route = try runAdb(["-s", serial, "shell", "ip", "route", "get", "1.1.1.1"], adb: adb)
        if let m = route.firstMatch(of: #/src\s+(\d{1,3}(?:\.\d{1,3}){3})/#) {
            return String(m.1)
        }
        throw NSError(domain: "adb", code: 3,
                      userInfo: [NSLocalizedDescriptionKey: "未获取到手机 Wi-Fi IP（手机可能未连接 Wi-Fi）"])
    }

    /// 启动自动同步（首次进入或地址变更后调用）
    func startIfNeeded() {
        // 启动必拉一次：本地为空时必须走全量。
        // 增量请求在手机端「synced 标志位」协议下多半回空（历史记录早已被消费过），
        // 否则会出现「已连接、状态正常，但列表 0 条」。
        Task { await pullSmart() }
        schedule()
        connectWs()
    }

    /// 智能选择拉取方式：本地为空 → 全量（否则列表会一直是空的）；有数据 → 增量
    func pullSmart() async {
        await pull(full: exchanges.isEmpty)
    }

    // MARK: - 同步

    /// 增量同步一次；full = true 时请求全量（手机端回传全部并置已同步）
    ///
    /// 并发调用采用「合并待办 + 单 worker」：同一时刻只有一个请求在飞，期间到来的请求
    /// 折叠成待办（全量优先），worker 跑完继续消费。
    /// 早期用 `if inFlight { return }` 会**静默丢弃**请求——启动时的全量兜底与定时器
    /// 触发的增量很容易撞上，全量被吞掉就表现为偶发空列表；直接排队又会在网络卡顿时堆积请求。
    func pull(full: Bool = false) async {
        if full { fullRequested = true } else { incrementalRequested = true }
        guard !workerRunning else { return }
        workerRunning = true
        defer { workerRunning = false }
        while fullRequested || incrementalRequested {
            let doFull = fullRequested
            fullRequested = false
            incrementalRequested = false
            await performPull(full: doFull)
        }
    }

    private func performPull(full: Bool) async {
        guard let url = makeURL(full: full) else {
            status = .failed("请先在工具栏填写手机同步地址")
            return
        }
        status = .syncing
        do {
            var request = URLRequest(
                url: url,
                cachePolicy: .reloadIgnoringLocalAndRemoteCacheData,
                timeoutInterval: 12
            )
            request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
            let (data, _) = try await URLSession.shared.data(for: request)
            // 后台解码：数百条记录 × 大 body 的 payload 可能数 MB，主线程解码会冻结 UI、
            // 让「同步中」长时间不结束，甚至触发 12s 超时误报失败
            let state = try await Task.detached(priority: .userInitiated) {
                try JSONDecoder().decode(SyncState.self, from: data)
            }.value
            apply(state)
            status = .ok(Date())
            // HTTP 通道通了说明链路正常，清掉 WS 失败计数（避免误触发自愈）
            wsFailStreak = 0
            Self.logLine("pull \(full ? "全量" : "增量") 成功：本次 \(state.exchanges.count) 条，累计 \(exchanges.count) 条")
        } catch {
            status = .failed(friendly(error))
            Self.logLine("pull \(full ? "全量" : "增量") 失败：\(friendly(error))")
            selfHealForwardIfNeeded()
        }
    }

    /// 全量重新同步：清空本地缓存后整体拉取（手机端返回全部请求并置已同步）
    func resetAndPull() async {
        ids.removeAll()
        await pull(full: true)
        connectWs()
    }

    // MARK: - 移动端主动推送（WebSocket）

    private var wsTask: URLSessionWebSocketTask?
    private var wsRetry: Task<Void, Never>?
    /// WS 连接代次：用于忽略「主动重连」产生的旧连接回调（否则形成重连风暴）
    private var wsGeneration = 0
    /// WS 连续失败次数（成功即清零）：连续失败才触发 adb forward 自愈
    private var wsFailStreak = 0

    /// 连接手机端 WS 推送通道（ws://<addr>/api/ws）。
    /// 原 HTTP 轮询通道保持不变，WS 仅作为实时增量补充；断线自动重连。
    ///
    /// 用 `wsGeneration` 标记连接代次：主动重连时会 cancel 旧 task，旧 task 的 receive 回调
    /// 会以 failure 返回——若不区分，就会「自己掐断自己 → 判定失败 → 再重连」形成重连风暴
    /// （进一步还会误触发 adb forward 自愈，把正常链路反复重建）。
    func connectWs() {
        wsGeneration += 1
        let gen = wsGeneration
        wsTask?.cancel(with: .goingAway, reason: nil)
        wsTask = nil
        guard let url = wsURL() else { return }
        let task = URLSession.shared.webSocketTask(with: url)
        wsTask = task
        task.resume()
        receiveWs(task, gen: gen)
    }

    private func wsURL() -> URL? {
        var base = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !base.isEmpty else { return nil }
        if !base.hasPrefix("http://"), !base.hasPrefix("https://") { base = "http://" + base }
        base = base.replacingOccurrences(of: "/api/state", with: "")
        while base.hasSuffix("/") { base.removeLast() }
        guard let u = URL(string: base) else { return nil }
        // http(s) -> ws(s)
        let scheme = u.scheme == "https" ? "wss" : "ws"
        var comps = URLComponents(url: u, resolvingAgainstBaseURL: false)
        comps?.scheme = scheme
        return comps?.url?.appendingPathComponent("api/ws")
    }

    private func receiveWs(_ task: URLSessionWebSocketTask, gen: Int) {
        task.receive { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                // 旧代次连接的回调（多半是主动重连时被 cancel 的），直接忽略
                guard gen == self.wsGeneration else { return }
                switch result {
                case .failure:
                    self.wsConnected = false
                    self.wsFailStreak += 1
                    self.scheduleWsRetry()
                    // 连续失败 → 做一次 HTTP 探活：
                    //   探活成功 → 链路其实正常（WS 自身抖动），不折腾；
                    //   探活失败 → 由 performPull 的错误分支统一触发 adb forward 自愈。
                    // 这样「是否重建转发」只有一个判定入口，WS 抖动不会误触发重建
                    // （重建会掐断正常连接，反而把链路搞坏）。
                    if self.wsFailStreak >= 3 {
                        self.wsFailStreak = 0
                        Task { await self.pullSmart() }
                    }
                case .success(let msg):
                    self.wsFailStreak = 0
                    if case .string(let text) = msg {
                        self.handleWsText(text)
                    }
                    self.receiveWs(task, gen: gen)
                }
            }
        }
    }

    private func handleWsText(_ text: String) {
        // 后台解码后回主线程应用，避免大 payload 在 WS 回调里阻塞主线程（同 pull 的卡顿根因）
        Task.detached(priority: .userInitiated) { [weak self] in
            guard let data = text.data(using: .utf8),
                  let msg = try? JSONDecoder().decode(WsMessage.self, from: data) else { return }
            await MainActor.run { [weak self] in
                guard let self else { return }
                self.applyWs(msg)
                self.wsConnected = true
                self.status = .ok(Date())
            }
        }
    }

    private func scheduleWsRetry() {
        wsRetry?.cancel()
        wsRetry = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run { self.connectWs() }
        }
    }

    /// 应用 WS 消息：snapshot 与 delta 统一按增量并入（按 id 去重）。
    /// 手机端 snapshot 现在只发「尚未同步」的子集，不再整体替换，避免清屏/重连后误删本地已展示记录。
    private func applyWs(_ msg: WsMessage) {
        // 状态变更推送（type:"status"）：抓包开始/停止时手机端主动下发，仅刷新统计卡
        //（capturing / startedAt / 流量计数），不动请求列表。即便 autoSync 关闭也能即时更新。
        if msg.type == "status" {
            stats = Stats(
                capturing: msg.capturing,
                upload: msg.uploadBytes,
                download: msg.downloadBytes,
                requests: msg.requestCount,
                passthrough: msg.passthroughCount,
                startedAt: msg.startedAt
            )
            return
        }
        stats = Stats(
            capturing: msg.capturing,
            upload: msg.uploadBytes,
            download: msg.downloadBytes,
            requests: msg.requestCount,
            passthrough: msg.passthroughCount,
            startedAt: msg.startedAt
        )
        let fresh = msg.exchanges.filter { !ids.contains($0.id) }
        if !fresh.isEmpty {
            exchanges.insert(contentsOf: fresh, at: 0)
            fresh.forEach { ids.insert($0.id) }
        }
        let passIds = Set(passthrough.map(\.id))
        let freshPass = msg.passthrough.filter { !passIds.contains($0.id) }
        if !freshPass.isEmpty {
            passthrough.insert(contentsOf: freshPass, at: 0)
        }
        if exchanges.count > maxLocal {
            let dropped = exchanges[maxLocal...]
            dropped.forEach { ids.remove($0.id) }
            exchanges = Array(exchanges.prefix(maxLocal))
        }
    }

    /// 仅清空本地显示，不改动手机端记录的 synced 标志位。
    /// 因此清屏后手机端不会回退成全量：已同步的记录仍 synced=true，增量同步只下发此后新请求；
    /// 若想重新看已同步的历史，点「全量重新同步」（发 ?full=1 让手机端回传全部）。
    func clearLocal() {
        exchanges = []
        sessions = []
        passthrough = []
        stats = Stats()
        status = .idle
    }

    // MARK: - adb forward 自愈

    /// 连接失败时自动重建 adb forward。
    ///
    /// 背景：`adb forward` 规则不跨 USB 断连持久化——手机插拔/锁屏重连后规则就丢了，
    /// 表现为「面板连不上手机」。当同步地址是本机回环（127.0.0.1/localhost）时，
    /// 连不上几乎必然意味着转发规则丢失，这里限频重建，下一轮轮询即自动恢复。
    private func selfHealForwardIfNeeded() {
        guard Self.isLoopbackAddress(address) else { return }
        guard !healInFlight else { return }
        guard Date().timeIntervalSince(lastHealAt) >= 8 else { return }
        guard let adb = Self.adbPath(), let port = Self.port(from: address) else { return }

        lastHealAt = Date()
        healInFlight = true
        let serial = selectedSerial
        Task.detached(priority: .utility) { [weak self] in
            _ = try? Self.runAdb(["forward", "--remove", "tcp:\(port)"], adb: adb)
            var args: [String] = []
            if let serial { args += ["-s", serial] }
            args += ["forward", "tcp:\(port)", "tcp:17890"]
            let ok = (try? Self.runAdb(args, adb: adb)) != nil
            await MainActor.run { [weak self] in
                guard let self else { return }
                self.healInFlight = false
                Self.logLine(ok
                    ? "[ok] 连接失败，已重建 adb forward tcp:\(port) -> tcp:17890\(serial.map { " (serial \($0))" } ?? "")"
                    : "[x] 重建 adb forward tcp:\(port) 失败（手机是否已插好/授权？）")
                guard ok else { return }
                // 通道恢复后立刻补一次：自动同步关闭时（autoSync=0）没有定时器，
                // 不补拉的话面板会一直停在「失败」状态不恢复。
                self.connectWs()
                Task { await self.pullSmart() }
            }
        }
    }

    // MARK: - 一键重联移动端

    /// 主动诊断 + 修复整条链路，区别于被动限频自愈 `selfHealForwardIfNeeded`。
    ///
    /// 旧轮询自愈只在「HTTP 拉取失败且地址为回环」时限频 8s 重建一次 `adb forward`：
    /// - 不重启 adb server —— USB 授权丢失 / 守护进程僵死时 forward 建了也无效；
    /// - 被动触发 —— autoSync=0 时定时器不跑，永远走不到自愈；
    /// - 不重连 WS、不主动补拉 —— 链路恢复不即时。
    ///
    /// 本方法把恢复链跑一遍并逐条写诊断日志（`/tmp/streamdesk-panel.log`）：
    /// 1. 重启 adb server（修复 USB 授权 / 守护进程异常，这是旧自愈漏掉的关键一步）
    /// 2. 探测已授权 USB 设备（USB 物理层 / 授权问题在此暴露）
    /// 3. 按设备重建 adb forward（移除旧规则 + 重新建立）
    /// 4. 重连 WS 推送通道并全量补拉数据
    func reconnect() async {
        guard !reconnecting else { return }
        reconnecting = true
        status = .syncing
        Self.logLine("=== 一键重联移动端 ===")
        defer {
            reconnecting = false
            Self.logLine("=== 重联结束 ===")
        }

        // 非回环（Wi-Fi / 直连 IP）：adb 修不了网络层，只重连通道 + 补拉
        guard Self.isLoopbackAddress(address) else {
            Self.logLine("[i] 地址非回环（Wi-Fi/直连），跳过 adb 修复，仅重连通道并补拉")
            connectWs()
            await resetAndPull()
            return
        }

        guard let adb = Self.adbPath() else {
            status = .failed("未找到 adb，无法执行重联（请安装 Android SDK 平台工具并加入 PATH）")
            Self.logLine("[x] 未找到 adb，重联终止")
            return
        }

        // 1) 探测设备。注意：adb server 活着就**不要** kill-server——重启会掐掉正常链路，
        //    且重启后设备重新枚举需要数秒，立刻探测会误判「无设备」直接终止（已踩过）。
        Self.logLine("[1/5] 探测 USB 设备…")
        var serial = await Self.waitForDevice(adb: adb, seconds: 3)
        if serial == nil {
            // adb server 可能僵死：这时才重启，并**轮询等待**设备重新枚举（MIUI 重新授权也可能耗时）
            Self.logLine("    未见设备 → 重启 adb server 后继续等待…")
            _ = try? Self.runAdb(["kill-server"], adb: adb)
            _ = try? Self.runAdb(["start-server"], adb: adb)
            Self.logLine("    adb server 已重启，等待设备重新枚举…")
            serial = await Self.waitForDevice(adb: adb, seconds: 10)
        }
        guard let dev = serial else {
            status = .failed("未发现已授权的 USB 设备（请检查 USB 连接与调试授权）")
            Self.logLine("[x] 等待后仍无可用 USB 设备，重联终止")
            return
        }
        if dev != selectedSerial { selectedSerial = dev }
        Self.logLine("    使用设备 \(dev)")

        // 2) 重建 adb forward：移除旧规则（USB 断连后规则往往已失效/残留）后按设备重建
        Self.logLine("[2/5] 重建 adb forward…")
        let port = Self.port(from: address) ?? 17890
        _ = try? Self.runAdb(["forward", "--remove", "tcp:\(port)"], adb: adb)
        let ok = (try? Self.runAdb(["-s", dev, "forward", "tcp:\(port)", "tcp:17890"], adb: adb)) != nil
        if ok {
            Self.logLine("    forward tcp:\(port) → \(dev):tcp:17890 已建立")
        } else {
            Self.logLine("    带 -s 建立失败，尝试不带 -s 兜底")
            let ok2 = (try? Self.runAdb(["forward", "tcp:\(port)", "tcp:17890"], adb: adb)) != nil
            if !ok2 {
                status = .failed("adb forward 建立失败（手机端 App 是否已启动抓包？）")
                Self.logLine("    forward 仍失败，重联终止")
                return
            }
        }

        // 3) 手机端服务探活：GET / 自检接口（**不消费 synced 标志位**）。
        //    adb 链路通 ≠ 手机端服务在跑——App 活着但 17890 未监听时（同步开关被关/服务线程死了），
        //    forward 转发会被手机端拒绝，客户端表现为「空回复 / 连接丢失」。
        Self.logLine("[3/5] 手机端服务探活…")
        if await Self.probeService(address: address, tries: 2) {
            Self.logLine("    手机端服务正常")
        } else {
            // 4) 手机端服务没起 → adb 冷启动 App（force-stop + autostart + sync_on）再等恢复
            Self.logLine("    手机端服务无响应 → 尝试冷启动抓包 App…")
            let revived = await Self.revivePhoneService(adb: adb, serial: dev, address: address)
            if !revived {
                status = .failed("adb 链路已通，但手机端同步服务无法恢复（请在 App 内检查同步开关）")
                Self.logLine("[x] 冷启动后手机端服务仍无响应，重联终止")
                return
            }
            Self.logLine("    手机端服务已恢复")
        }

        // 5) 重连 WS + 全量补拉：本地空则全量已在 resetAndPull 内处理，这里统一全量拉一次以恢复列表
        Self.logLine("[5/5] 重连推送通道并全量补拉…")
        connectWs()
        await resetAndPull()
    }

    /// 轮询等待已授权设备出现（每 0.5s 探一次）。adb server 重启后设备重新枚举需要时间，
    /// 不能只探一次就判死。返回第一个可用设备 serial。
    private nonisolated static func waitForDevice(adb: String, seconds: Double) async -> String? {
        let deadline = Date().addingTimeInterval(seconds)
        while true {
            if let out = try? runAdb(["devices", "-l"], adb: adb),
               let first = parseDeviceList(out).first {
                return first.serial
            }
            if Date() >= deadline { return nil }
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
    }

    /// 手机端服务探活：请求自检接口 `GET /`（不消费 synced），2~3s 超时。
    private nonisolated static func probeService(address: String, tries: Int) async -> Bool {
        var base = address.trimmingCharacters(in: .whitespacesAndNewlines)
        if !base.hasPrefix("http://") && !base.hasPrefix("https://") { base = "http://" + base }
        base = base.replacingOccurrences(of: "/api/state", with: "")
        base = base.replacingOccurrences(of: "/api/mock", with: "")
        while base.hasSuffix("/") { base.removeLast() }
        guard let url = URL(string: base + "/") else { return false }
        for _ in 0..<max(1, tries) {
            var req = URLRequest(url: url, timeoutInterval: 3)
            req.cachePolicy = .reloadIgnoringLocalCacheData
            if let (_, resp) = try? await URLSession.shared.data(for: req),
               let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) {
                return true
            }
            if tries > 1 { try? await Task.sleep(nanoseconds: 400_000_000) }
        }
        return false
    }

    /// 冷启动手机端抓包 App：同步服务没在跑（App 活着但 17890 未监听）时的最后一招。
    /// debug 包优先；force-stop 后 `am start --ez autostart/sync_on true`（仅 onCreate 读 extras，冷启动场景有效）。
    private nonisolated static func revivePhoneService(adb: String, serial: String, address: String) async -> Bool {
        let packages = ["com.ht.stream.debug", "com.ht.stream"]
        var target: String?
        for pkg in packages { // 优先挑活着的进程
            let pids = ((try? runAdb(["-s", serial, "shell", "pidof", pkg], adb: adb)) ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !pids.isEmpty { target = pkg; break }
        }
        if target == nil {
            for pkg in packages { // 都没进程则挑已安装的
                if let out = try? runAdb(["-s", serial, "shell", "pm", "list", "packages", pkg], adb: adb),
                   out.contains(pkg) { target = pkg; break }
            }
        }
        guard let pkg = target else {
            logLine("    未在手机上找到抓包 App（com.ht.stream.debug / com.ht.stream）")
            return false
        }
        logLine("    冷启动 \(pkg)（force-stop + autostart + sync_on）…")
        _ = try? runAdb(["-s", serial, "shell", "am", "force-stop", pkg], adb: adb)
        _ = try? runAdb(["-s", serial, "shell", "am", "start",
                         "-n", "\(pkg)/com.ht.stream.MainActivity",
                         "--ez", "autostart", "true", "--ez", "sync_on", "true"], adb: adb)
        // 等服务起来（App 冷启动 + SyncServer 监听需要几秒）
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline {
            if await probeService(address: address, tries: 1) { return true }
            try? await Task.sleep(nanoseconds: 600_000_000)
        }
        return false
    }

    /// 地址是否指向本机回环（只有这种地址才靠 adb forward，才值得自愈）
    private nonisolated static func isLoopbackAddress(_ addr: String) -> Bool {
        var s = addr.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        s = s.replacingOccurrences(of: "http://", with: "")
        s = s.replacingOccurrences(of: "https://", with: "")
        s = s.replacingOccurrences(of: "/api/state", with: "")
        let host = s.split(separator: ":").first.map(String.init) ?? s
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    /// 从地址里取本地端口（默认 17890）
    private nonisolated static func port(from addr: String) -> Int? {
        var s = addr.trimmingCharacters(in: .whitespacesAndNewlines)
        s = s.replacingOccurrences(of: "http://", with: "")
        s = s.replacingOccurrences(of: "https://", with: "")
        s = s.replacingOccurrences(of: "/api/state", with: "")
        let parts = s.split(separator: ":")
        guard parts.count >= 2, let p = Int(parts[1].prefix(while: { $0.isNumber })) else { return 17890 }
        return p
    }

    /// 诊断日志：/tmp/streamdesk-panel.log（排查「连不上 / 列表为空」时先看这里）
    private nonisolated static func logLine(_ msg: String) {
        let line = "[\(ISO8601DateFormatter().string(from: Date()))] \(msg)\n"
        let path = "/tmp/streamdesk-panel.log"
        if let h = FileHandle(forWritingAtPath: path) {
            h.seekToEndOfFile()
            h.write(Data(line.utf8))
            try? h.close()
        } else {
            try? line.write(toFile: path, atomically: true, encoding: .utf8)
        }
    }

    // MARK: - 内部

    private func apply(_ s: SyncState) {
        stats = Stats(
            capturing: s.capturing,
            upload: s.uploadBytes,
            download: s.downloadBytes,
            requests: s.requestCount,
            passthrough: s.passthroughCount,
            startedAt: s.startedAt
        )
        sessions = s.sessions
        passthrough = s.passthrough
        if let m = s.mock { mockStatus = m }

        if s.full {
            exchanges = s.exchanges
            ids = Set(s.exchanges.map(\.id))
        } else {
            let fresh = s.exchanges.filter { !ids.contains($0.id) }
            if !fresh.isEmpty {
                exchanges.insert(contentsOf: fresh, at: 0)
                fresh.forEach { ids.insert($0.id) }
            }
        }
        if exchanges.count > maxLocal {
            let dropped = exchanges[maxLocal...]
            dropped.forEach { ids.remove($0.id) }
            exchanges = Array(exchanges.prefix(maxLocal))
        }
    }

    private func makeURL(full: Bool) -> URL? {
        guard var url = endpoint("/api/state") else { return nil }
        if full {
            url = URL(string: url.absoluteString + "?full=1") ?? url
        }
        return url
    }

    /// 按手机地址拼出某个 API 的完整 URL（自动补 http://、去掉多余的 /api/xxx）
    private func endpoint(_ path: String) -> URL? {
        var base = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !base.isEmpty else { return nil }
        if !base.hasPrefix("http://") && !base.hasPrefix("https://") {
            base = "http://" + base
        }
        base = base.replacingOccurrences(of: "/api/state", with: "")
        base = base.replacingOccurrences(of: "/api/mock", with: "")
        while base.hasSuffix("/") { base.removeLast() }
        return URL(string: base + path)
    }

    // MARK: - 接口模拟

    /// 把本地规则推送到手机（整体覆盖）。
    /// 手机端只有「开启总开关 + 对应应用开关」的应用才会走模拟响应。
    func pushMock(_ rules: [MockRule]) async -> String {
        guard let url = endpoint("/api/mock") else { return "请先填写手机同步地址" }
        var req = URLRequest(url: url, timeoutInterval: 12)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        do {
            req.httpBody = try JSONEncoder().encode(["rules": rules])
        } catch {
            return "规则序列化失败：\(error.localizedDescription)"
        }
        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard let http = resp as? HTTPURLResponse else { return "推送失败：无响应" }
            let result = try? JSONDecoder().decode(MockPushResult.self, from: data)
            if http.statusCode == 200, result?.ok == true {
                Self.logLine("mock 推送成功：\(result?.count ?? rules.count) 条规则")
                await refreshMockStatus()
                return "已推送 \(result?.count ?? rules.count) 条规则到手机"
            }
            let msg = result?.error ?? "HTTP \(http.statusCode)"
            Self.logLine("mock 推送失败：\(msg)")
            return "推送失败：\(msg)"
        } catch {
            Self.logLine("mock 推送失败：\(friendly(error))")
            return "推送失败：\(friendly(error))"
        }
    }

    /// 拉取手机端当前的接口模拟配置（含规则明细，可用于反向同步）
    func fetchMockConfig() async -> MockConfigResponse? {
        guard let url = endpoint("/api/mock") else { return nil }
        var req = URLRequest(url: url, timeoutInterval: 12)
        req.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        do {
            let (data, _) = try await URLSession.shared.data(for: req)
            let cfg = try JSONDecoder().decode(MockConfigResponse.self, from: data)
            mockStatus = MockStatus(
                enabled: cfg.enabled ?? false,
                ruleCount: cfg.rules?.count ?? 0,
                enabledApps: cfg.enabledApps ?? [],
                updatedAt: cfg.updatedAt
            )
            return cfg
        } catch {
            Self.logLine("mock 拉取配置失败：\(friendly(error))")
            return nil
        }
    }

    /// 只刷新手机端接口模拟状态（不改规则）
    func refreshMockStatus() async {
        _ = await fetchMockConfig()
    }

    private func schedule() {
        timer?.invalidate()
        timer = nil
        guard autoSync, interval > 0 else { return }
        let t = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
            Task { @MainActor in await self?.pull() }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    private func friendly(_ error: Error) -> String {
        if let e = error as? URLError {
            switch e.code {
            case .cannotConnectToHost, .cannotFindHost:
                return "无法连接手机（检查 Wi-Fi 是否同网、同步开关是否打开）"
            case .timedOut:
                return "连接超时"
            case .notConnectedToInternet:
                return "本机网络不可用"
            default:
                return e.localizedDescription
            }
        }
        return error.localizedDescription
    }
}
