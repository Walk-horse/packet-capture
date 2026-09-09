import Foundation
import SwiftUI

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
