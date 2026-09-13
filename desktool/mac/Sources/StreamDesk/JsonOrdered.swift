import Foundation

// MARK: - 保序 JSON 解析 / 渲染
//
// 系统 JSONSerialization 解析出的字典是无序的，会把键顺序打乱；
// 而接口模拟的响应示例（尤其是从 YAPI 拉取的 JSON Schema）键顺序有意义，
// 故这里手写一个「保序」解析器与美化输出器，供「JSON 美化 / 压缩」与 YAPI 示例生成共用。

/// 解析 JSON 文本，保留对象键的原始顺序。非法 JSON 返回 nil。
func parseOrderedJSON(_ text: String) -> JsonNode? {
    var p = OrderedJSONParser(text)
    guard let node = p.parseValue() else { return nil }
    return p.atEnd ? node : nil
}

/// 把 JsonNode 渲染成 JSON 文本。pretty = true 时按 2 空格缩进并保留键顺序。
func renderJSON(_ node: JsonNode, pretty: Bool = true, unit: String = "  ") -> String {
    var out = ""
    out.reserveCapacity(256)
    renderNode(node, depth: 0, pretty: pretty, unit: unit, into: &out)
    return out
}

/// JSON 字符串字面量转义（保留非 ASCII 原字符，不转 \u）
func escapeJSONString(_ s: String) -> String {
    var out = "\""
    out.reserveCapacity(s.count + 2)
    for u in s.unicodeScalars {
        switch u {
        case "\"": out += "\\\""
        case "\\": out += "\\\\"
        case "\n": out += "\\n"
        case "\r": out += "\\r"
        case "\t": out += "\\t"
        case "\u{08}": out += "\\b"
        case "\u{0C}": out += "\\f"
        default:
            if u.value < 0x20 {
                out += String(format: "\\u%04x", u.value)
            } else {
                out.unicodeScalars.append(u)
            }
        }
    }
    return out + "\""
}

private func renderNode(_ node: JsonNode, depth: Int, pretty: Bool, unit: String, into out: inout String) {
    switch node {
    case .object(let pairs):
        if pairs.isEmpty { out += "{}"; return }
        out += "{"
        for (idx, kv) in pairs.enumerated() {
            if pretty { out += "\n" + String(repeating: unit, count: depth + 1) }
            out += escapeJSONString(kv.0)
            out += pretty ? " : " : ":"
            renderNode(kv.1, depth: depth + 1, pretty: pretty, unit: unit, into: &out)
            if idx < pairs.count - 1 { out += "," }
        }
        if pretty { out += "\n" + String(repeating: unit, count: depth) }
        out += "}"

    case .array(let items):
        if items.isEmpty { out += "[]"; return }
        out += "["
        for (idx, item) in items.enumerated() {
            if pretty { out += "\n" + String(repeating: unit, count: depth + 1) }
            renderNode(item, depth: depth + 1, pretty: pretty, unit: unit, into: &out)
            if idx < items.count - 1 { out += "," }
        }
        if pretty { out += "\n" + String(repeating: unit, count: depth) }
        out += "]"

    case .string(let s): out += escapeJSONString(s)
    case .number(let n): out += n
    case .bool(let b): out += b ? "true" : "false"
    case .null: out += "null"
    }
}

// MARK: - 递归下降解析器

private struct OrderedJSONParser {
    private let cs: [Character]
    private var i = 0
    private let maxNodes: Int
    private var nodes = 0

    init(_ s: String, maxNodes: Int = 80_000) {
        cs = Array(s)
        self.maxNodes = maxNodes
    }

    var atEnd: Bool {
        var k = i
        while k < cs.count {
            let c = cs[k]
            if c == " " || c == "\n" || c == "\r" || c == "\t" { k += 1 } else { break }
        }
        return k >= cs.count
    }

    mutating func parseValue() -> JsonNode? {
        skipWs()
        guard i < cs.count else { return nil }
        nodes += 1
        guard nodes <= maxNodes else { return nil }
        switch cs[i] {
        case "{": return parseObject()
        case "[": return parseArray()
        case "\"": return parseString().map { JsonNode.string($0) }
        case "t": return expect("true") ? JsonNode.bool(true) : nil
        case "f": return expect("false") ? JsonNode.bool(false) : nil
        case "n": return expect("null") ? JsonNode.null : nil
        default: return parseNumber()
        }
    }

    private mutating func parseObject() -> JsonNode? {
        i += 1 // 吃掉 '{'
        var pairs: [(String, JsonNode)] = []
        skipWs()
        if i < cs.count, cs[i] == "}" { i += 1; return .object(pairs) }
        while true {
            skipWs()
            guard i < cs.count, cs[i] == "\"", let key = parseString() else { return nil }
            skipWs()
            guard i < cs.count, cs[i] == ":" else { return nil }
            i += 1
            guard let v = parseValue() else { return nil }
            pairs.append((key, v))
            skipWs()
            guard i < cs.count else { return nil }
            if cs[i] == "," { i += 1; continue }
            if cs[i] == "}" { i += 1; return .object(pairs) }
            return nil
        }
    }

    private mutating func parseArray() -> JsonNode? {
        i += 1 // 吃掉 '['
        var items: [JsonNode] = []
        skipWs()
        if i < cs.count, cs[i] == "]" { i += 1; return .array(items) }
        while true {
            guard let v = parseValue() else { return nil }
            items.append(v)
            skipWs()
            guard i < cs.count else { return nil }
            if cs[i] == "," { i += 1; continue }
            if cs[i] == "]" { i += 1; return .array(items) }
            return nil
        }
    }

    /// 进入时 cs[i] == '"'，成功时已吃掉收尾引号
    private mutating func parseString() -> String? {
        i += 1
        var out = ""
        while i < cs.count {
            let c = cs[i]
            if c == "\"" { i += 1; return out }
            if c == "\\" {
                i += 1
                guard i < cs.count else { return nil }
                let e = cs[i]
                i += 1
                switch e {
                case "\"": out.append("\"")
                case "\\": out.append("\\")
                case "/": out.append("/")
                case "b": out.append("\u{08}")
                case "f": out.append("\u{0C}")
                case "n": out.append("\n")
                case "r": out.append("\r")
                case "t": out.append("\t")
                case "u":
                    guard let s = readUnicodeEscape() else { return nil }
                    out += s
                default: return nil
                }
                continue
            }
            out.append(c)
            i += 1
        }
        return nil
    }

    private mutating func readUnicodeEscape() -> String? {
        guard let hi = readHex4() else { return nil }
        // 代理对：高位 D800-DBFF 后应紧跟 \uDC00-\uDFFF
        if hi >= 0xD800 && hi <= 0xDBFF {
            guard i + 1 < cs.count, cs[i] == "\\", cs[i + 1] == "u" else { return nil }
            i += 2
            guard let lo = readHex4(), lo >= 0xDC00, lo <= 0xDFFF else { return nil }
            let cp = 0x10000 + ((hi - 0xD800) << 10) + (lo - 0xDC00)
            guard let scalar = UnicodeScalar(cp) else { return nil }
            return String(scalar)
        }
        guard let scalar = UnicodeScalar(hi) else { return nil }
        return String(scalar)
    }

    private mutating func readHex4() -> UInt32? {
        guard i + 4 <= cs.count else { return nil }
        var v: UInt32 = 0
        for _ in 0..<4 {
            guard let d = cs[i].hexDigitValue else { return nil }
            v = (v << 4) | UInt32(d)
            i += 1
        }
        return v
    }

    private mutating func parseNumber() -> JsonNode? {
        let start = i
        while i < cs.count, "0123456789+-.eE".contains(cs[i]) { i += 1 }
        guard i > start else { return nil }
        let s = String(cs[start..<i])
        guard Double(s) != nil else { return nil }
        return .number(s)
    }

    private mutating func skipWs() {
        while i < cs.count {
            let c = cs[i]
            if c == " " || c == "\n" || c == "\r" || c == "\t" { i += 1 } else { break }
        }
    }

    private mutating func expect(_ literal: String) -> Bool {
        let l = Array(literal)
        guard i + l.count <= cs.count else { return false }
        for (k, ch) in l.enumerated() where cs[i + k] != ch { return false }
        i += l.count
        return true
    }
}

// MARK: - JsonNode 取值辅助

extension JsonNode {
    /// 取对象的某个子节点（保序查找）
    func child(_ key: String) -> JsonNode? {
        if case .object(let pairs) = self {
            return pairs.first { $0.0 == key }?.1
        }
        return nil
    }

    /// 取子节点的字符串值（数字也转成字符串，便于读 id）
    func str(_ key: String) -> String? {
        switch child(key) {
        case .string(let s): return s
        case .number(let n): return n
        default: return nil
        }
    }

    func int(_ key: String) -> Int? {
        if case .number(let n) = child(key) { return Int(n) ?? Double(n).map { Int($0) } }
        return nil
    }

    func bool(_ key: String) -> Bool? {
        if case .bool(let b) = child(key) { return b }
        return nil
    }

    var stringValue: String? {
        if case .string(let s) = self { return s }
        if case .number(let n) = self { return n }
        return nil
    }

    var isObject: Bool {
        if case .object = self { return true }
        return false
    }

    var objectPairs: [(String, JsonNode)]? {
        if case .object(let p) = self { return p }
        return nil
    }

    /// 对象是否为空（用于「示例未配置」判定）
    var isEmptyContainer: Bool {
        switch self {
        case .object(let p): return p.isEmpty
        case .array(let a): return a.isEmpty
        case .string(let s): return s.isEmpty
        default: return false
        }
    }
}
