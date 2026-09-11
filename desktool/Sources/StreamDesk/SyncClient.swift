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

    /// 本地最多保留条数（超出丢弃最旧的，与手机端 500 上限解耦）
    private let maxLocal = 2000
    private var lastId: String?
    private var ids: Set<String> = []
    private var timer: Timer?
    private var inFlight = false

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
    }

    func setAutoSync(_ on: Bool) {
        autoSync = on
        UserDefaults.standard.set(on, forKey: Key.autoSync)
        schedule()
        if on { Task { await pull() } }
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
        if exchanges.isEmpty { Task { await pull() } }
        schedule()
    }

    // MARK: - 同步

    /// 增量同步一次；full = true 时忽略游标，整体替换本地数据
    func pull(full: Bool = false) async {
        guard !inFlight else { return }
        guard let url = makeURL(full: full) else {
            status = .failed("请先在工具栏填写手机同步地址")
            return
        }
        inFlight = true
        status = .syncing
        defer { inFlight = false }
        do {
            var request = URLRequest(
                url: url,
                cachePolicy: .reloadIgnoringLocalAndRemoteCacheData,
                timeoutInterval: 12
            )
            request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
            let (data, _) = try await URLSession.shared.data(for: request)
            let state = try JSONDecoder().decode(SyncState.self, from: data)
            apply(state)
            status = .ok(Date())
        } catch {
            status = .failed(friendly(error))
        }
    }

    /// 全量重新同步：清空游标与本地缓存后整体拉取（手机端清空历史后用它对齐）
    func resetAndPull() async {
        lastId = nil
        ids.removeAll()
        await pull(full: true)
    }

    func clearLocal() {
        exchanges = []
        sessions = []
        passthrough = []
        ids.removeAll()
        lastId = nil
        stats = Stats()
        status = .idle
    }

    // MARK: - 内部

    private func apply(_ s: SyncState) {
        stats = Stats(
            capturing: s.capturing,
            upload: s.uploadBytes,
            download: s.downloadBytes,
            requests: s.requestCount,
            passthrough: s.passthroughCount
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
        lastId = exchanges.first?.id
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
        if !full, let id = lastId,
           let encoded = id.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) {
            fullPath += "?after=" + encoded
        }
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
