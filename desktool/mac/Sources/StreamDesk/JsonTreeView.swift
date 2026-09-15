import SwiftUI

// MARK: - JSON 树

/// JSON 树每层缩进步长（pt）。两套树（只读 / 可编辑）共用，保证双端视觉一致。
let treeIndent: CGFloat = 16

/// 层级缩进 + 淡色引导竖线：每层画一条 1pt 竖线，深层节点的归属一眼可辨（DevTools 风格）。
/// 放在行 `HStack` 内部当占位用——宽度确定，不受 `fixedSize` / 双轴 ScrollView 的宽度提案影响。
struct TreeIndentGuides: View {
    let depth: Int

    var body: some View {
        HStack(spacing: 0) {
            ForEach(0..<max(0, depth), id: \.self) { _ in
                Rectangle()
                    .fill(Color.secondary.opacity(0.16))
                    .frame(width: 1)
                    .frame(width: treeIndent, alignment: .leading)
            }
        }
        .fixedSize()
    }
}

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
        // 字段按字母序（大小写不敏感）排列，便于在大响应里定位字段。
        // 注：JSONSerialization 的字典本就无序，这里显式排序只是把随机序变确定序；
        // 「文本」视图仍按原文（YAPI 定义）顺序展示，两者定位不同。
        // 数组元素顺序不受影响（语义相关，必须保序）。
        let items = dict
            .map { (k, v) -> (String, JsonNode) in (k, buildJsonNode(v, counter: &counter)) }
            .sorted { $0.0.localizedCaseInsensitiveCompare($1.0) == .orderedAscending }
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
private func highlightedText(_ s: String, query: String, color: Color, exactMatch: Bool = false) -> AttributedString {
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
    let options: String.CompareOptions = exactMatch ? [.literal] : [.caseInsensitive, .diacriticInsensitive]
    var searchStart = s.startIndex
    var last = s.startIndex
    var found = false
    while let r = s.range(of: query, options: options, range: searchStart..<s.endIndex) {
        searchStart = r.upperBound
        if exactMatch, !isTreeWordBoundaryMatch(s, r) { continue }
        found = true
        append(String(s[last..<r.lowerBound]), highlighted: false)
        append(String(s[r]), highlighted: true)
        last = r.upperBound
    }
    if !found {
        append(s, highlighted: false)
        return result
    }
    append(String(s[last..<s.endIndex]), highlighted: false)
    return result
}

/// 判断命中区间在精确模式下是否为独立词（前后非字母/数字/_）
private func isTreeWordBoundaryMatch(_ s: String, _ r: Range<String.Index>) -> Bool {
    let wordChars = CharacterSet.alphanumerics.union(.init(charactersIn: "_"))
    if r.lowerBound != s.startIndex {
        let prev = s.index(before: r.lowerBound)
        let prevSet = CharacterSet(charactersIn: String(s[prev]))
        if wordChars.isSuperset(of: prevSet) { return false }
    }
    if r.upperBound != s.endIndex {
        let nextSet = CharacterSet(charactersIn: String(s[r.upperBound]))
        if wordChars.isSuperset(of: nextSet) { return false }
    }
    return true
}

/// JSON 树视图：可折叠，DevTools 配色；query 非空时对命中片段加淡黄色背景。
///
/// 性能：把「当前展开状态」下的递归树**拍平成一维行数组**，用 `LazyVStack` 惰性渲染——
/// 只实例化可见行，万级节点也不卡（`ScrollView+VStack` 旧实现会一次性建全部视图）。
/// 折叠状态用 `collapsed`（path 集合）集中管理，切换折叠即重拍平一次（O(n) 纯数组操作，极快）。
struct JsonTreeView: View {
    let root: JsonNode
    var query: String = ""
    var exactMatch: Bool = false

    @State private var collapsed = Set<String>()

    var body: some View {
        GeometryReader { geo in
            ScrollView([.horizontal, .vertical]) {
                LazyVStack(alignment: .leading, spacing: 1) {
                    ForEach(flatten(root, key: nil, depth: 0, path: "root", query: query, exactMatch: exactMatch), id: \.path) { row in
                        JsonTreeRow(
                            node: row.node, key: row.key, depth: row.depth, id: row.path,
                            query: query, exactMatch: exactMatch,
                            isCollapsed: collapsed.contains(row.path)
                        ) { toggle(row.path) }
                    }
                }
                // 内容窄/矮于视口时靠左上（双轴 ScrollView 默认居中）；超宽可横向滚动。
                // minHeight 撑满视口高度 → 内容垂直「居上」，不再被居中。
                .frame(minWidth: max(0, geo.size.width - 20),
                       minHeight: max(0, geo.size.height - 20),
                       alignment: .topLeading)
                // 上下留白，避免首/末行贴边被裁
                .padding(.horizontal, 10)
                .padding(.vertical, 10)
            }
        }
    }

    private func toggle(_ path: String) {
        if collapsed.contains(path) { collapsed.remove(path) }
        else { collapsed.insert(path) }
    }

    /// 递归拍平为可见行（折叠节点不展开其子行）
    private func flatten(_ node: JsonNode, key: String?, depth: Int, path: String, query: String, exactMatch: Bool) -> [JsonRow] {
        var rows: [JsonRow] = []
        flatten(node, key: key, depth: depth, path: path, query: query, exactMatch: exactMatch, into: &rows)
        return rows
    }

    private func flatten(_ node: JsonNode, key: String?, depth: Int, path: String, query: String, exactMatch: Bool, into rows: inout [JsonRow]) {
        rows.append(JsonRow(node: node, key: key, depth: depth, path: path))
        if node.isContainer, !collapsed.contains(path) {
            switch node {
            case .object(let items):
                for (k, child) in items {
                    flatten(child, key: k, depth: depth + 1, path: path + "/o:" + k, query: query, exactMatch: exactMatch, into: &rows)
                }
            case .array(let items):
                for (i, child) in items.enumerated() {
                    flatten(child, key: "\(i)", depth: depth + 1, path: path + "/a:\(i)", query: query, exactMatch: exactMatch, into: &rows)
                }
            default: break
            }
        }
    }
}

private struct JsonRow {
    let node: JsonNode
    let key: String?
    let depth: Int
    let path: String
}

private struct JsonTreeRow: View {
    let node: JsonNode
    let key: String?
    let depth: Int
    let id: String
    let query: String
    let exactMatch: Bool
    let isCollapsed: Bool
    let onToggle: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 4) {
            // 层级缩进 + 引导线（在行内占位，宽度确定）
            if depth > 0 { TreeIndentGuides(depth: depth) }

            if node.isContainer {
                Text(isCollapsed ? "▸" : "▾")
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
                    .frame(width: 12, alignment: .leading)
                    .contentShape(Rectangle())
                    .onTapGesture { onToggle() }
            } else {
                Text("").frame(width: 12)
            }

            if let k = key {
                Text(highlightedText("\"\(k)\":", query: query, color: .secondary, exactMatch: exactMatch))
                    .font(.system(size: 12, design: .monospaced))
            }

            if node.isContainer {
                summaryLabel
            } else {
                valueView
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { if node.isContainer { onToggle() } }
        // LazyVStack 在双轴 ScrollView 中宽度提案为 0，行会被压扁导致文本逐字换行成一列；
        // fixedSize(horizontal) 让行按自身内容宽度布局，横向滚动照常。
        .fixedSize(horizontal: true, vertical: false)
        // 层级缩进已由行内 TreeIndentGuides 承担（不再用 padding，避免与 fixedSize 的先后语义纠缠）
    }

    @ViewBuilder
    private var summaryLabel: some View {
        switch node {
        case .object(let items):
            Text("{ \(items.count) }").font(.system(size: 11)).foregroundColor(.secondary)
        case .array(let items):
            Text("[ \(items.count) ]").font(.system(size: 11)).foregroundColor(.secondary)
        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private var valueView: some View {
        switch node {
        case .string(let s):
            Text(highlightedText("\"\(s)\"", query: query, color: .red, exactMatch: exactMatch))
                .font(.system(size: 12, design: .monospaced))
        case .number(let n):
            Text(highlightedText(n, query: query, color: .blue, exactMatch: exactMatch))
                .font(.system(size: 12, design: .monospaced))
        case .bool(let b):
            Text(highlightedText(b ? "true" : "false", query: query, color: .purple, exactMatch: exactMatch))
                .font(.system(size: 12, design: .monospaced))
        case .null:
            Text(highlightedText("null", query: query, color: .secondary, exactMatch: exactMatch))
                .font(.system(size: 12, design: .monospaced))
                .italic()
        default:
            EmptyView()
        }
    }
}
