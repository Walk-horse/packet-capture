import Foundation
import SwiftUI

// MARK: - YAPI 接口拉取
//
// 用途：接口模拟规则里的「响应示例」可以直接从 YAPI 拉取——
//   输入 接口 ID + Token（可选 UID），面板请求 {服务地址}/api/interface/get?id=xxx，
//   把返回的 res_body（JSON Schema 或 raw 示例）转换成可直接下发的示例响应。
// Token 可持久化保存在本机（UserDefaults），下次打开自动带出。

/// 从 YAPI 拉取并转换好的结果
struct YapiFetched {
    let id: String
    let title: String
    let method: String
    let path: String
    let contentType: String
    /// 已美化的响应示例（JSON 会按 YAPI 定义的键顺序生成）
    let body: String
    let isJson: Bool
    let projectId: String?
    /// 该接口在 YAPI 上的状态：done / undone
    let status: String?

    var summary: String {
        "\(method.isEmpty ? "*" : method)  \(path)"
    }
}

struct YapiError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

// MARK: - 请求实现

enum YapiAPI {

    static let defaultService = "https://stp.haier.net"

    /// 规范化服务地址：补协议头、去掉尾部斜杠与误粘的路径
    static func normalizeService(_ raw: String) -> String {
        var base = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if base.isEmpty { base = defaultService }
        if !base.lowercased().hasPrefix("http://") && !base.lowercased().hasPrefix("https://") {
            base = "https://" + base
        }
        for junk in ["/api/interface/get", "/api/interface/list", "/api/project/get"] {
            if base.lowercased().hasSuffix(junk) {
                base = String(base.dropLast(junk.count))
            }
        }
        while base.hasSuffix("/") { base.removeLast() }
        return base
    }

    /// 组装 Cookie 头。
    /// - 直接粘整段 Cookie（含 `_yapi_token=`）时原样使用；
    /// - 只填 token 时拼 `_yapi_token=<token>`，若填了 uid 再拼 `_yapi_uid=<uid>`。
    static func cookieHeader(token: String, uid: String?) -> String? {
        let t = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return nil }
        if t.contains("_yapi_token") || t.contains(";") {
            return t
        }
        var c = "_yapi_token=\(t)"
        if let uid, !uid.trimmingCharacters(in: .whitespaces).isEmpty {
            c += "; _yapi_uid=\(uid.trimmingCharacters(in: .whitespaces))"
        }
        return c
    }

    /// 拉取一条接口定义
    static func fetchInterface(service: String,
                               id: String,
                               token: String,
                               uid: String?) async throws -> YapiFetched {
        let sid = id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !sid.isEmpty else { throw YapiError(message: "请先填写 YAPI 接口 ID") }
        guard let cookie = cookieHeader(token: token, uid: uid) else {
            throw YapiError(message: "请填写 YAPI Token（或直接粘贴整段 Cookie）")
        }
        let base = normalizeService(service)
        guard var comps = URLComponents(string: base + "/api/interface/get") else {
            throw YapiError(message: "服务地址不合法：\(base)")
        }
        comps.queryItems = [URLQueryItem(name: "id", value: sid)]
        guard let url = comps.url else { throw YapiError(message: "服务地址不合法：\(base)") }

        var req = URLRequest(url: url, timeoutInterval: 20)
        req.httpMethod = "GET"
        req.setValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
        req.setValue(cookie, forHTTPHeaderField: "Cookie")
        req.setValue("\(base)/", forHTTPHeaderField: "Referer")
        req.setValue("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) StreamDesk/1.0",
                     forHTTPHeaderField: "User-Agent")
        req.setValue("no-cache", forHTTPHeaderField: "Cache-Control")

        let result: (Data, URLResponse)
        do {
            result = try await URLSession.shared.data(for: req)
        } catch {
            throw YapiError(message: friendlyNetworkError(error, base: base))
        }
        let data = result.0
        let resp = result.1
        if let http = resp as? HTTPURLResponse, http.statusCode != 200 {
            throw YapiError(message: "YAPI 返回 HTTP \(http.statusCode)，请检查服务地址是否需要内网/VPN")
        }
        guard let text = String(data: data, encoding: .utf8), !text.isEmpty else {
            throw YapiError(message: "YAPI 返回内容为空或不是 UTF-8 文本")
        }
        return try parseResponse(text, fallbackId: sid)
    }

    /// 解析 /api/interface/get 的返回（拆出来便于离线校验与复用）
    static func parseResponse(_ text: String, fallbackId: String) throws -> YapiFetched {
        guard let root = parseOrderedJSON(text) else {
            let head = text.prefix(120).replacingOccurrences(of: "\n", with: " ")
            throw YapiError(message: "YAPI 返回的不是 JSON（可能被登录页拦截）：\(head)")
        }
        // 非 JSON 顶层的兜底（例如直接给了接口对象）
        let code = root.int("errcode")
        if let code, code != 0 {
            let msg = root.str("errmsg") ?? "未知错误"
            if code == 40011 || msg.contains("登录") {
                throw YapiError(message: "YAPI 登录态无效（errcode \(code)：\(msg)）。请在浏览器打开 YAPI → F12 → Network → 复制接口请求里的完整 Cookie，整段粘贴到「Token」栏（只填 _yapi_token 时需要它未被截断，且要配 _yapi_uid）。")
            }
            throw YapiError(message: "YAPI 返回错误 \(code)：\(msg)")
        }
        guard let data0 = root.child("data"), data0.isObject else {
            throw YapiError(message: "YAPI 返回中没有接口数据，请检查接口 ID 是否正确")
        }

        let title = data0.str("title") ?? ""
        let method = (data0.str("method") ?? "GET").uppercased()
        var path = data0.str("path") ?? ""
        if path.isEmpty { path = "/" }
        let resType = (data0.str("res_body_type") ?? "json").lowercased()
        let resBody = data0.str("res_body") ?? ""
        let schemaFlag = data0.bool("res_body_is_json_schema") ?? true
        let iid = data0.str("_id") ?? fallbackId

        let sample = buildSample(resType: resType, resBody: resBody, isSchema: schemaFlag)
        let ct = inferContentType(resType: resType, body: sample.body)

        return YapiFetched(
            id: iid,
            title: title,
            method: method.isEmpty ? "GET" : method,
            path: path,
            contentType: ct,
            body: sample.body,
            isJson: sample.isJson,
            projectId: data0.str("project_id"),
            status: data0.str("status")
        )
    }

    /// 把 res_body 转换成示例文本
    static func buildSample(resType: String, resBody: String, isSchema: Bool) -> (body: String, isJson: Bool) {
        let trimmed = resBody.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return ("", false) }

        if resType == "json" {
            guard let node = parseOrderedJSON(trimmed) else { return (trimmed, false) }
            if isSchema && looksLikeSchema(node) {
                return (renderJSON(YapiSchema.sample(node)), true)
            }
            // res_body 本身就是字面示例：按定义原样美化，保留键顺序
            return (renderJSON(node), true)
        }
        // raw 类型：原文直接使用；若是 JSON 也顺手美化
        if let node = parseOrderedJSON(trimmed), node.isObject || isArrayNode(node) {
            return (renderJSON(node), true)
        }
        return (trimmed, false)
    }

    private static func isArrayNode(_ n: JsonNode) -> Bool {
        if case .array = n { return true }
        return false
    }

    /// 判断 res_body 是 JSON Schema 还是字面示例
    static func looksLikeSchema(_ n: JsonNode) -> Bool {
        guard let pairs = n.objectPairs else { return false }
        let keys = Set(pairs.map { $0.0 })
        if keys.contains("properties") || keys.contains("items") || keys.contains("enum") { return true }
        let schemaTypes: Set<String> = ["object", "array", "string", "integer", "number", "boolean", "null"]
        if let t = n.str("type"), schemaTypes.contains(t) {
            let metaOnly: Set<String> = ["type", "title", "description", "$schema", "required",
                                         "definitions", "$ref", "mock", "default", "example", "format"]
            if keys.isSubset(of: metaOnly) { return true }
        }
        return false
    }

    /// 按 res_body_type / 示例内容推断 Content-Type
    static func inferContentType(resType: String, body: String) -> String {
        if resType == "json" { return "application/json" }
        let t = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.hasPrefix("{") || t.hasPrefix("[") { return "application/json" }
        if t.hasPrefix("<?xml") { return "application/xml" }
        if t.hasPrefix("<") { return "text/html" }
        return "text/plain"
    }

    private static func friendlyNetworkError(_ error: Error, base: String) -> String {
        guard let e = error as? URLError else { return "请求失败：\(error.localizedDescription)" }
        switch e.code {
        case .cannotFindHost, .dnsLookupFailed:
            return "域名解析失败（\(base)），可能需要接入公司内网/VPN"
        case .cannotConnectToHost:
            return "连接不上 \(base)，请确认是否需要内网/VPN 或代理"
        case .timedOut:
            return "请求 \(base) 超时"
        case .notConnectedToInternet:
            return "本机网络不可用"
        case .appTransportSecurityRequiresSecureConnection:
            return "该地址需要 HTTPS"
        default:
            return "请求失败：\(e.localizedDescription)"
        }
    }
}

// MARK: - JSON Schema → 示例

/// YAPI 的 res_body 是 JSON Schema，这里按 schema 生成「全字段占位」的示例对象，
/// 键顺序与 YAPI 上的定义保持一致。
enum YapiSchema {

    static func sample(_ schema: JsonNode, depth: Int = 0) -> JsonNode {
        guard depth < 24 else { return .null }
        guard schema.isObject else { return schema } // 已是字面量
        let type = schema.str("type") ?? ""

        if let items = schema.child("items") {
            return .array([sample(items, depth: depth + 1)])
        }
        if type == "array" { return .array([]) }
        if let props = schema.child("properties"), let list = props.objectPairs {
            return .object(list.map { ($0.0, sample($0.1, depth: depth + 1)) })
        }
        if type == "object" { return .object([]) }

        // 标量：优先用 YAPI 上填的 example / default / enum
        if let ex = schema.child("example") { return ex }
        if let dv = schema.child("default") { return dv }
        if let en = schema.child("enum"), case .array(let arr) = en, let first = arr.first { return first }

        switch type {
        case "integer", "number": return .number("0")
        case "boolean": return .bool(false)
        case "null": return .null
        case "string", "":
            switch schema.str("format") ?? "" {
            case "date-time": return .string("2020-01-01 00:00:00")
            case "date": return .string("2020-01-01")
            case "time": return .string("00:00:00")
            case "email": return .string("user@example.com")
            case "uri", "url": return .string("https://example.com")
            default: return .string("")
            }
        default:
            return .string("")
        }
    }

    /// 提供给「JSON 美化」按钮的兜底：把任意 JSON 文本美化（保序）
    static func pretty(_ text: String) -> String? {
        guard let node = parseOrderedJSON(text) else { return nil }
        return renderJSON(node)
    }

    static func minify(_ text: String) -> String? {
        guard let node = parseOrderedJSON(text) else { return nil }
        return renderJSON(node, pretty: false)
    }
}

// MARK: - YAPI 设置（Token / 服务地址持久化）

/// YAPI 连接设置。服务地址与上次用的接口 ID 始终记住；
/// Token / UID 由「记住 Token」开关控制是否写入本机（明文存 UserDefaults）。
@MainActor
final class YapiSettings: ObservableObject {
    private enum Key {
        static let service = "yapi.service"
        static let token = "yapi.token"
        static let uid = "yapi.uid"
        static let lastId = "yapi.lastId"
        static let remember = "yapi.remember"
    }

    @Published var service: String
    @Published var token: String
    @Published var uid: String
    @Published var lastId: String
    @Published var remember: Bool

    private let d = UserDefaults.standard

    init() {
        service = d.string(forKey: Key.service) ?? YapiAPI.defaultService
        token = d.string(forKey: Key.token) ?? ""
        uid = d.string(forKey: Key.uid) ?? ""
        lastId = d.string(forKey: Key.lastId) ?? ""
        remember = d.object(forKey: Key.remember) as? Bool ?? true
        if service.isEmpty { service = YapiAPI.defaultService }
    }

    /// 把当前设置写回本机
    func persist() {
        d.set(service, forKey: Key.service)
        d.set(lastId, forKey: Key.lastId)
        d.set(remember, forKey: Key.remember)
        if remember {
            d.set(token, forKey: Key.token)
            d.set(uid, forKey: Key.uid)
        } else {
            d.removeObject(forKey: Key.token)
            d.removeObject(forKey: Key.uid)
        }
    }

    /// 是否已经存过 Token（用于界面提示）
    var hasSavedToken: Bool {
        remember && !(d.string(forKey: Key.token) ?? "").isEmpty
    }

    func forgetToken() {
        d.removeObject(forKey: Key.token)
        d.removeObject(forKey: Key.uid)
        token = ""
        uid = ""
    }
}
