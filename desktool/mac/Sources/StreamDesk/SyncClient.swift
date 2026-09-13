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
        var base = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !base.isEmpty else { return nil }
        if !base.hasPrefix("http://") && !base.hasPrefix("https://") {
            base = "http://" + base
        }
        base = base.replacingOccurrences(of: "/api/state", with: "")
        while base.hasSuffix("/") { base.removeLast() }

        var fullPath = base + "/api/state"
        // full=1 时手机端返回全部请求（不管是否已同步）；否则只返回尚未同步的部分
        if full { fullPath += "?full=1" }
        return URL(string: fullPath)
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
