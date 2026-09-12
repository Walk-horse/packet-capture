import Foundation

// MARK: - 同步协议模型（与手机端 SyncJson 字段一一对应）

struct SyncState: Decodable {
    let capturing: Bool
    let uploadBytes: Int64
    let downloadBytes: Int64
    let requestCount: Int
    let passthroughCount: Int
    let startedAt: Int64
    let full: Bool
    let sessions: [SessionInfo]
    let passthrough: [PassRecord]
    let exchanges: [ExchangeRecord]
}

/// 手机端 WS 推送消息（快照 / 增量），与 SyncJson.wsSnapshot / wsDelta 字段对应
struct WsMessage: Decodable {
    let type: String            // "snapshot" | "delta"
    let capturing: Bool
    let uploadBytes: Int64
    let downloadBytes: Int64
    let requestCount: Int
    let passthroughCount: Int
    let startedAt: Int64
    let exchanges: [ExchangeRecord]
    let passthrough: [PassRecord]
}

struct SessionInfo: Decodable, Identifiable, Hashable {
    let id: String
    let startTime: Int64
    let endTime: Int64
    let durationSec: Int64
    let requestCount: Int
    let passthroughCount: Int

    var startDate: Date { Date(timeIntervalSince1970: Double(startTime) / 1000) }
}

struct PassRecord: Decodable, Identifiable, Hashable {
    let id: String
    let sessionId: String
    let host: String
    let port: Int
    let tls: Bool
    let reason: String
    let startTime: Int64
    let endTime: Int64
    let durationMs: Int64
    let upBytes: Int64
    let downBytes: Int64

    var startDate: Date { Date(timeIntervalSince1970: Double(startTime) / 1000) }
}

struct ExchangeRecord: Decodable, Identifiable {
    let id: String
    let sessionId: String
    let scheme: String
    let host: String
    let port: Int
    let isTls: Bool
    let method: String
    let path: String
    let url: String
    let statusCode: Int
    let statusText: String
    let state: String
    let error: String?
    let favorite: Bool
    let remoteIp: String?
    let uid: Int
    let startTime: Int64
    let endTime: Int64
    let durationMs: Int64
    let requestHeaders: [[String]]
    let responseHeaders: [[String]]
    let respType: String?
    let reqType: String?
    let requestBodyB64: String
    let requestBodyTruncated: Bool
    let responseBodyB64: String
    let responseBodyTruncated: Bool
    let reqEncoding: String?
    let respEncoding: String?
    /// 手机端已按 Content-Encoding 解压并识别编码后的可读文本（二进制为格式提示）
    let reqText: String?
    let respText: String?

    var startDate: Date { Date(timeIntervalSince1970: Double(startTime) / 1000) }

    var requestHeaderPairs: [(String, String)] { Self.pairs(requestHeaders) }
    var responseHeaderPairs: [(String, String)] { Self.pairs(responseHeaders) }

    func header(_ pairs: [(String, String)], _ name: String) -> String? {
        pairs.first { $0.0.caseInsensitiveCompare(name) == .orderedSame }?.1
    }

    private static func pairs(_ raw: [[String]]) -> [(String, String)] {
        raw.compactMap { row in
            guard row.count >= 2 else { return nil }
            return (row[0], row[1])
        }
    }

    var isFailed: Bool { state == "FAILED" }
    var isPending: Bool { state == "PENDING" }
    var displayPath: String { path.isEmpty ? "/" : path }
}

/// body 解码缓存：避免列表/详情反复对同一条 body 做 base64 解码
private final class BodyCache {
    static let shared = BodyCache()
    private let cache = NSCache<NSString, NSData>()
    private init() {
        cache.countLimit = 256
        cache.totalCostLimit = 96 * 1024 * 1024
    }
    func data(for key: String, b64: String) -> Data {
        let k = key as NSString
        if let hit = cache.object(forKey: k) { return hit as Data }
        let d = Data(base64Encoded: b64) ?? Data()
        cache.setObject(d as NSData, forKey: k, cost: d.count)
        return d
    }
}

extension ExchangeRecord {
    var requestData: Data { BodyCache.shared.data(for: id + "#req", b64: requestBodyB64) }
    var responseData: Data { BodyCache.shared.data(for: id + "#resp", b64: responseBodyB64) }

    /// 不解码即可估算的大小（仅用于展示，避免为显示字节数解码整个 body）
    var requestSize: Int { (requestBodyB64.count * 3) / 4 }
    var responseSize: Int { (responseBodyB64.count * 3) / 4 }
}

/// 侧栏可选项
enum Panel: Hashable {
    case all
    case session(String)
    case domain(String)
    case passthrough
}
