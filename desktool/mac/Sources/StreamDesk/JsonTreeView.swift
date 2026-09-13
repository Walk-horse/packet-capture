import SwiftUI

// MARK: - JSON 树

/// JSON 节点（不可变，用于递归渲染）
indirect enum JsonNode {
    case object([(String, JsonNode)])
    case array([JsonNode])
    case string(String)
    case number(String)
    case bool(Bool)
    case null

    var isContainer: Bool {
        switch self {
        case .object, .array: return true
        default: return false
        }
    }
}

/// 把 JSONSerialization 解析出的 Any 转为 JsonNode（带节点上限，避免超大 body 卡顿）
/// 保留原始 key 顺序，与「文本」视图对齐。
func buildJsonNode(_ value: Any, counter: inout Int) -> JsonNode {
    counter += 1
    if counter > 30000 {
        return .string("(节点过多，已截断，请切换到「文本」视图)")
    }
    if let dict = value as? [String: Any] {
        // 不排序：保持 JSON 原文顺序，避免与文本视图对不上
        let items = dict.map { (k, v) -> (String, JsonNode) in (k, buildJsonNode(v, counter: &counter)) }
        return .object(items)
    } else if let arr = value as? [Any] {
        return .array(arr.map { buildJsonNode($0, counter: &counter) })
    } else if let n = value as? NSNumber {
        if CFGetTypeID(n) == CFBooleanGetTypeID() {
            return .bool(n.boolValue)
        }
        return .number(n.stringValue)
    } else if let s = value as? String {
        return .string(s)
    } else if value is NSNull {
        return .null
    } else {
        return .null
    }
}

/// 把字符串按 query 命中片段拆成 AttributedString，命中片段加淡黄色背景，整体保持 color 配色。
/// （SwiftUI Text 不支持拼接带 background 的 Text 段，故用 AttributedString）
private func highlightedText(_ s: String, query: String, color: Color) -> AttributedString {
    var result = AttributedString()
    func append(_ str: String, highlighted: Bool) {
        guard !str.isEmpty else { return }
        var a = AttributedString(str)
        a.foregroundColor = color
        if highlighted { a.backgroundColor = Color.yellow.opacity(0.45) }
        result.append(a)
    }
    guard !query.isEmpty else {
        append(s, highlighted: false)
        return result
    }
    var searchStart = s.startIndex
    var last = s.startIndex
    var found = false
    while let r = s.range(of: query, options: [.caseInsensitive, .diacriticInsensitive], range: searchStart..<s.endIndex) {
        found = true
        append(String(s[last..<r.lowerBound]), highlighted: false)
        append(String(s[r]), highlighted: true)
        last = r.upperBound
        searchStart = r.upperBound
    }
    if !found {
        append(s, highlighted: false)
        return result
    }
    append(String(s[last..<s.endIndex]), highlighted: false)
    return result
}

/// JSON 树视图：可折叠，DevTools 配色；query 非空时对命中片段加淡黄色背景。
struct JsonTreeView: View {
    let root: JsonNode
    var query: String = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 1) {
                JsonNodeRow(node: root, key: nil, depth: 0, query: query)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
        }
    }
}

private struct JsonNodeRow: View {
    let node: JsonNode
    let key: String?
    let depth: Int
    let query: String

    @State private var expanded = true

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            HStack(alignment: .top, spacing: 4) {
                Text(triangle)
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
                    .frame(width: 12, alignment: .leading)
                    .contentShape(Rectangle())
                    .onTapGesture { if node.isContainer { expanded.toggle() } }

                keyLabel

                if node.isContainer {
                    summaryLabel
                } else {
                    valueView
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { if node.isContainer { expanded.toggle() } }

            if node.isContainer && expanded {
                containerChildren
            }
        }
        .padding(.leading, depth == 0 ? 0 : 14)
    }

    private var triangle: String {
        node.isContainer ? (expanded ? "▾" : "▸") : ""
    }

    @ViewBuilder
    private var keyLabel: some View {
        if let k = key {
            Text(highlightedText("\"\(k)\":", query: query, color: .secondary))
                .font(.system(size: 12, design: .monospaced))
        }
    }

    @ViewBuilder
    private var summaryLabel: some View {
        switch node {
        case .object(let items):
            Text("{ \(items.count) }").font(.system(size: 11)).foregroundStyle(.secondary)
        case .array(let items):
            Text("[ \(items.count) ]").font(.system(size: 11)).foregroundStyle(.secondary)
        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private var valueView: some View {
        switch node {
        case .string(let s):
            Text(highlightedText("\"\(s)\"", query: query, color: .red))
                .font(.system(size: 12, design: .monospaced))
        case .number(let n):
            Text(highlightedText(n, query: query, color: .blue))
                .font(.system(size: 12, design: .monospaced))
        case .bool(let b):
            Text(highlightedText(b ? "true" : "false", query: query, color: .purple))
                .font(.system(size: 12, design: .monospaced))
        case .null:
            Text(highlightedText("null", query: query, color: .secondary))
                .font(.system(size: 12, design: .monospaced))
                .italic()
        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private var containerChildren: some View {
        switch node {
        case .object(let items):
            ForEach(items, id: \.0) { k, child in
                JsonNodeRow(node: child, key: k, depth: depth + 1, query: query)
            }
        case .array(let items):
            ForEach(Array(items.enumerated()), id: \.offset) { i, child in
                JsonNodeRow(node: child, key: "\(i)", depth: depth + 1, query: query)
            }
        default:
            EmptyView()
        }
    }
}
