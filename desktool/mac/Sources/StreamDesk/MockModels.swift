import Foundation

// MARK: - 接口模拟（与手机端 MockStore.MockRule 字段一一对应）

/// 一条接口模拟规则：命中 method + host + path 的请求，由手机端直接返回配置的响应。
/// body 为空表示「未配置示例」，手机端会按接口响应数据类型自动生成示例。
struct MockRule: Codable, Identifiable, Hashable {
    var id: String
    var enabled: Bool
    var name: String
    var method: String
    var host: String
    var path: String
    var statusCode: Int
    var contentType: String
    var body: String
    var delayMs: Int
    var headers: [[String]]
    /// 响应示例来源：YAPI 接口 ID（手工填写/抓包生成的规则为 nil）
    var yapiId: String?

    init(
        id: String = UUID().uuidString,
        enabled: Bool = true,
        name: String = "",
        method: String = "GET",
        host: String = "",
        path: String = "",
        statusCode: Int = 200,
        contentType: String = "application/json",
        body: String = "",
        delayMs: Int = 0,
        headers: [[String]] = [],
        yapiId: String? = nil
    ) {
        self.id = id
        self.enabled = enabled
        self.name = name
        self.method = method
        self.host = host
        self.path = path
        self.statusCode = statusCode
        self.contentType = contentType
        self.body = body
        self.delayMs = delayMs
        self.headers = headers
        self.yapiId = yapiId
    }

    /// 是否配置了自定义响应示例（否则手机端按响应类型自动生成）
    var usesCustomBody: Bool { !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// 响应示例的来源描述
    var sourceText: String {
        if let yapiId, !yapiId.isEmpty { return "YAPI #\(yapiId)" }
        return usesCustomBody ? "自定义示例" : "自动生成示例"
    }

    /// 列表展示用：method + host + path
    var summary: String {
        let m = method.isEmpty ? "*" : method
        return "\(m)  \(host)\(path)"
    }
}

/// 手机端上报的接口模拟状态（GET /api/mock 或 /api/state 的 mock 字段）
struct MockStatus: Decodable {
    let enabled: Bool
    let ruleCount: Int
    let enabledApps: [String]
    let updatedAt: Int64?
}

/// POST /api/mock 的返回（增量合并统计）
struct MockPushResult: Decodable {
    let ok: Bool
    let count: Int?
    let added: Int?
    let updated: Int?
    let error: String?
}

/// GET /api/mock 的返回（含规则明细，可用于「从手机拉取配置」）
struct MockConfigResponse: Decodable {
    let ok: Bool?
    let enabled: Bool?
    let rules: [MockRule]?
    let enabledApps: [String]?
    let updatedAt: Int64?
    let error: String?
}

// MARK: - 示例生成（与手机端 MockEngine 同口径）

/// 按接口响应数据类型生成示例：JSON → 保留键结构、值置类型占位；XML/HTML → 标签骨架；文本 → 截断原文。
enum MockSample {

    static func generate(contentType: String, template: Data?) -> String {
        let ct = contentType.lowercased()
        if let template, !template.isEmpty {
            if let text = String(data: template, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty {
                if let json = jsonSample(text) { return json }
                if text.hasPrefix("<") { return markupSample(text, ct) }
                if !ct.contains("octet-stream"), isMostlyText(text) {
                    return String(text.prefix(2048))
                }
            }
        }
        return defaultSample(ct)
    }

    private static func jsonSample(_ text: String) -> String? {
        guard let data = text.data(using: .utf8),
              let v = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        else { return nil }
        guard JSONSerialization.isValidJSONObject(placeholder(v)) else { return nil }
        guard let out = try? JSONSerialization.data(withJSONObject: placeholder(v), options: [.prettyPrinted, .sortedKeys]),
              let s = String(data: out, encoding: .utf8) else { return nil }
        return s
    }

    private static func placeholder(_ v: Any) -> Any {
        if let dict = v as? [String: Any] {
            var out: [String: Any] = [:]
            dict.forEach { out[$0.key] = placeholder($0.value) }
            return out
        }
        if let arr = v as? [Any] {
            return arr.prefix(3).map { placeholder($0) }
        }
        if v is String { return "" }
        if v is NSNumber {
            // JSON 布尔在 JSONSerialization 里是 NSNumber，用 CFGetTypeID 区分
            return CFGetTypeID(v as CFTypeRef) == CFBooleanGetTypeID() ? false : 0
        }
        if v is NSNull { return NSNull() }
        return ""
    }

    private static func markupSample(_ text: String, _ contentType: String) -> String {
        let pattern = try? NSRegularExpression(pattern: "<([A-Za-z_][\\w:.-]*)")
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        var tags: [String] = []
        pattern?.enumerateMatches(in: text, range: range) { m, _, _ in
            guard let m, let r = Range(m.range(at: 1), in: text) else { return }
            let tag = String(text[r])
            if !tag.hasPrefix("?") && !tags.contains(tag) { tags.append(tag) }
        }
        let picked = Array(tags.prefix(8))
        guard let root = picked.first else { return defaultSample(contentType) }
        let children = picked.dropFirst()
        let body = children.map { "<\($0)></\($0)>" }.joined()
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><\(root)>\(body)</\(root)>"
    }

    private static func defaultSample(_ ct: String) -> String {
        if ct.contains("json") { return "{\n  \"code\" : 0,\n  \"message\" : \"mock\",\n  \"data\" : {\n\n  }\n}" }
        if ct.contains("xml") { return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><response><code>0</code><message>mock</message></response>" }
        if ct.contains("html") { return "<!DOCTYPE html><html><head><title>mock</title></head><body>mock</body></html>" }
        if ct.hasPrefix("text/") { return "mock" }
        return ""
    }

    private static func isMostlyText(_ s: String) -> Bool {
        let sample = s.prefix(4096)
        if sample.isEmpty { return false }
        let bad = sample.filter { $0 == "\u{FFFD}" || ($0.asciiValue.map { $0 < 0x20 && $0 != 0x0A && $0 != 0x0D && $0 != 0x09 } ?? false) }
        return bad.count * 50 <= sample.count
    }
}


// MARK: - 本地规则存储

/// 接口模拟规则的本地存储（~/Library/Application Support/StreamDesk/mock-rules.json）。
/// 规则在 Mac 端编辑，点「推送配置到手机」后按 host + path 增量合并到手机端。
@MainActor
final class MockRuleStore: ObservableObject {
    @Published var rules: [MockRule] = []

    private let fileURL: URL

    init() {
        let dir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("StreamDesk", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fileURL = dir.appendingPathComponent("mock-rules.json")
        load()
    }

    var filePath: String { fileURL.path }

    func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let list = try? JSONDecoder().decode([MockRule].self, from: data) else { return }
        rules = list
    }

    func save() {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? enc.encode(rules) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }

    @discardableResult
    func add(_ rule: MockRule = MockRule()) -> MockRule {
        rules.append(rule)
        save()
        return rule
    }

    func remove(_ id: String) {
        rules.removeAll { $0.id == id }
        save()
    }

    func duplicate(_ id: String) {
        guard var r = rule(id) else { return }
        r.id = UUID().uuidString
        r.name = r.name.isEmpty ? "副本" : r.name + " 副本"
        rules.append(r)
        save()
    }

    func update(_ rule: MockRule) {
        guard let i = rules.firstIndex(where: { $0.id == rule.id }) else { return }
        rules[i] = rule
        save()
    }

    func rule(_ id: String?) -> MockRule? {
        guard let id else { return nil }
        return rules.first { $0.id == id }
    }

    func setEnabled(_ id: String, _ on: Bool) {
        guard let i = rules.firstIndex(where: { $0.id == id }) else { return }
        rules[i].enabled = on
        save()
    }

    /// 用一条抓包记录生成规则：host/path/method/状态码/Content-Type 直接带出，
    /// 响应体若为可读文本则作为示例填入（留空则手机端按响应类型自动生成示例）。
    func makeRule(from e: ExchangeRecord) -> MockRule {
        var r = MockRule()
        r.method = e.method.isEmpty ? "*" : e.method
        r.host = e.host
        r.path = e.path
        r.statusCode = e.statusCode > 0 ? e.statusCode : 200
        r.contentType = e.respType?.trimmingCharacters(in: .whitespaces) ?? "application/json"
        if r.contentType.isEmpty { r.contentType = "application/json" }
        let text = String(data: e.responseData, encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if isTextual(r.contentType), !text.isEmpty, text.utf8.count <= 256 * 1024 {
            r.body = text
        }
        r.name = "\(e.method) \(e.displayPath)"
        return r
    }

    /// 从手机拉取规则并按 host + path 增量合并：相同目标更新，不同目标追加。
    /// 更新时保留本地 id，避免当前选中的规则因同步改变身份而跳转。
    @discardableResult
    func mergeFromPhone(_ list: [MockRule]) -> (added: Int, updated: Int, total: Int) {
        var indices: [String: Int] = [:]
        for (index, rule) in rules.enumerated() {
            indices[endpointKey(rule)] = index
        }

        var added = 0
        var updated = 0
        for incoming in list {
            let key = endpointKey(incoming)
            if let index = indices[key] {
                var replacement = incoming
                replacement.id = rules[index].id
                rules[index] = replacement
                updated += 1
            } else {
                rules.append(incoming)
                indices[key] = rules.count - 1
                added += 1
            }
        }
        save()
        return (added, updated, rules.count)
    }

    private func endpointKey(_ rule: MockRule) -> String {
        let host = rule.host.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return "\(host)\u{001F}\(rule.path)"
    }

    // MARK: - YAPI

    /// 用 YAPI 拉取结果生成一条新规则（host 由调用方按抓包记录或手工补）
    func makeRule(from y: YapiFetched, host: String = "") -> MockRule {
        var r = MockRule()
        r.method = y.method
        r.host = host
        r.path = y.path
        r.contentType = y.contentType
        r.body = y.body
        r.yapiId = y.id
        r.name = y.title.isEmpty ? y.summary : y.title
        return r
    }

    /// 把 YAPI 结果合并进已有规则：更新 method/path/示例，保留开关、延迟、自定义头等本地设置。
    /// - Parameter fillHost: host 为空时用于补齐的 host（通常取自同名 path 的抓包记录）
    func apply(_ y: YapiFetched, to ruleId: String, fillHost: String? = nil) {
        guard let i = rules.firstIndex(where: { $0.id == ruleId }) else { return }
        var r = rules[i]
        r.method = y.method
        r.path = y.path
        r.contentType = y.contentType
        r.body = y.body
        r.yapiId = y.id
        if r.name.trimmingCharacters(in: .whitespaces).isEmpty { r.name = y.title }
        if let fillHost, !fillHost.isEmpty, r.host.isEmpty { r.host = fillHost }
        rules[i] = r
        save()
    }

    /// 在抓包记录里按 path 找一个 host（YAPI 只给 path，host 通常来自实际抓包）
    func hostHint(forPath path: String, in exchanges: [ExchangeRecord]) -> String? {
        let target = path.split(separator: "?").first.map(String.init) ?? path
        guard !target.isEmpty else { return nil }
        return exchanges.first { e in
            !e.isPending && (e.path.split(separator: "?").first.map(String.init) ?? e.path) == target
        }?.host
    }

    private func isTextual(_ contentType: String) -> Bool {
        let ct = contentType.lowercased()
        return ct.contains("json") || ct.contains("xml") || ct.hasPrefix("text/")
            || ct.contains("javascript") || ct.contains("x-www-form-urlencoded")
    }
}
