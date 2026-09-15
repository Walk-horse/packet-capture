import SwiftUI
import Combine

// MARK: - 可编辑 JSON 节点

/// 可编辑 JSON 节点（引用类型，便于选中后在底部输入框就地修改并整体序列化回写 body）。
/// 与「只读」JsonNode（JsonTreeView.swift）解耦，避免影响详情 body 的只读树。
///
/// 设计：树本身只读 + 点击选中；编辑统一在底部输入框完成，叶子值「不区分类型」按文本编辑。
final class EditJsonNode: Identifiable, ObservableObject {
    let id = UUID()

    enum Kind: String {
        case object, array, string, number, bool, null
    }

    @Published var kind: Kind
    /// object 子项使用：字段名
    @Published var keyName: String = ""
    /// 是否为 object 的子项（决定底部是否显示「键」输入框）
    @Published var isObjectEntry: Bool = false
    /// object 子节点（每个子项自带 keyName）
    @Published var entries: [EditJsonNode] = []
    /// array 子节点
    @Published var items: [EditJsonNode] = []
    /// 叶子值文本（任意类型统一按文本编辑）
    @Published var rawValue: String = ""

    init(kind: Kind) { self.kind = kind }

    var isContainer: Bool { kind == .object || kind == .array }

    // MARK: 文本编辑绑定（自动推断类型）

    /// 编辑框显示的文本：容器为空；叶子按类型给出可读文本。
    var editText: String {
        switch kind {
        case .object, .array: return ""
        case .string: return rawValue
        case .number: return rawValue
        case .bool: return rawValue
        case .null: return rawValue
        }
    }

    /// 设置编辑文本：写回 rawValue 并按字面量推断类型。
    /// 空文本 → 空字符串；"true"/"false" → 布尔；"null" → 空值；
    /// 数字字面量 → 数字；其余 → 字符串（原样保留，含首尾空格）。
    func setEditText(_ s: String) {
        rawValue = s
        let t = s.trimmingCharacters(in: .whitespaces)
        if t == "true" || t == "false" {
            kind = .bool
        } else if t == "null" {
            kind = .null
        } else if isValidJSONNumber(t) {
            kind = .number
        } else {
            kind = .string
        }
    }

    /// 序列化时该叶子在 JSON 文本里的字面量。
    var literal: String {
        switch kind {
        case .object, .array: return ""
        case .string: return escapeJSONString(rawValue)
        case .number:
            return isValidJSONNumber(rawValue.trimmingCharacters(in: .whitespaces)) ? rawValue : escapeJSONString(rawValue)
        case .bool:
            return (rawValue.trimmingCharacters(in: .whitespaces).lowercased() == "false") ? "false" : "true"
        case .null:
            return "null"
        }
    }
}

extension EditJsonNode {
    /// 由保序 JsonNode（parseOrderedJSON）转换，保留对象键原始顺序。
    static func from(_ node: JsonNode) -> EditJsonNode {
        switch node {
        case .object(let pairs):
            let n = EditJsonNode(kind: .object)
            n.entries = pairs.map { (k, v) -> EditJsonNode in
                let c = EditJsonNode.from(v)
                c.keyName = k
                c.isObjectEntry = true
                return c
            }
            return n
        case .array(let items):
            let n = EditJsonNode(kind: .array)
            n.items = items.map { EditJsonNode.from($0) }
            return n
        case .string(let s):
            let n = EditJsonNode(kind: .string); n.rawValue = s; return n
        case .number(let s):
            let n = EditJsonNode(kind: .number); n.rawValue = s; return n
        case .bool(let b):
            let n = EditJsonNode(kind: .bool); n.rawValue = b ? "true" : "false"; return n
        case .null:
            return EditJsonNode(kind: .null)
        }
    }
}

// MARK: - 序列化（保序）

/// 把可编辑节点序列化回 JSON 文本，pretty = true 时按 2 空格缩进并保留键顺序。
func serializeJson(_ node: EditJsonNode, pretty: Bool = true) -> String {
    var out = ""
    out.reserveCapacity(256)
    writeNode(node, depth: 0, pretty: pretty, unit: "  ", into: &out)
    return out
}

private func writeNode(_ node: EditJsonNode, depth: Int, pretty: Bool, unit: String, into out: inout String) {
    switch node.kind {
    case .object:
        if node.entries.isEmpty { out += "{}"; return }
        out += "{"
        for (idx, child) in node.entries.enumerated() {
            if pretty { out += "\n" + String(repeating: unit, count: depth + 1) }
            out += escapeJSONString(child.keyName)
            out += pretty ? " : " : ":"
            writeNode(child, depth: depth + 1, pretty: pretty, unit: unit, into: &out)
            if idx < node.entries.count - 1 { out += "," }
        }
        if pretty { out += "\n" + String(repeating: unit, count: depth) }
        out += "}"
    case .array:
        if node.items.isEmpty { out += "[]"; return }
        out += "["
        for (idx, child) in node.items.enumerated() {
            if pretty { out += "\n" + String(repeating: unit, count: depth + 1) }
            writeNode(child, depth: depth + 1, pretty: pretty, unit: unit, into: &out)
            if idx < node.items.count - 1 { out += "," }
        }
        if pretty { out += "\n" + String(repeating: unit, count: depth) }
        out += "]"
    case .string, .number, .bool, .null:
        out += node.literal
    }
}

/// 宽松校验：允许整数 / 小数 / 科学计数法，拒绝空与纯空白、前导零等非法形式。
func isValidJSONNumber(_ s: String) -> Bool {
    guard !s.isEmpty else { return false }
    let pattern = "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$"
    return s.range(of: pattern, options: .regularExpression) != nil
}

// MARK: - 命中片段高亮（与只读树一致）

private func highlightedText(_ s: String, query: String, color: Color) -> AttributedString {
    var result = AttributedString()
    func append(_ str: String, highlighted: Bool) {
        guard !str.isEmpty else { return }
        var a = AttributedString(str)
        a.foregroundColor = color
        if highlighted { a.backgroundColor = Color.yellow.opacity(0.45) }
        result.append(a)
    }
    guard !query.isEmpty else { append(s, highlighted: false); return result }
    var searchStart = s.startIndex
    var last = s.startIndex
    var found = false
    let options: String.CompareOptions = [.caseInsensitive, .diacriticInsensitive]
    while let r = s.range(of: query, options: options, range: searchStart..<s.endIndex) {
        found = true
        append(String(s[last..<r.lowerBound]), highlighted: false)
        append(String(s[r]), highlighted: true)
        last = r.upperBound
        searchStart = r.upperBound
    }
    if !found { append(s, highlighted: false); return result }
    append(String(s[last..<s.endIndex]), highlighted: false)
    return result
}

// MARK: - 只读 JSON 树视图（点击选中节点）

/// 模拟响应示例的「JSON 树」：只读展示，点击任一节点即选中（不就地编辑、不增删）。
/// 选中后由父视图在底部输入框完成编辑，任意改动通过 onChange 序列化回写 body。
/// query 非空时对命中片段高亮（只读）。
///
/// 性能：把「当前展开状态」下的递归树**拍平成一维行数组**，用 `LazyVStack` 惰性渲染——
/// 只实例化可见行，万级节点也不卡（旧 `ScrollView+VStack` 递归会一次性建全部视图）。
/// 折叠状态用 `collapsed`（node.id 集合）集中管理。
/// 关键收益：每个 `EditNodeRow` 只 `@ObservedObject` 自己的节点，编辑某个叶子只重渲那一行，
/// 不再触发整棵树 `body` 重算（父视图 `@ObservedObject root` 不递归订阅子节点）。
struct EditableJsonTreeView: View {
    @ObservedObject var root: EditJsonNode
    var query: String = ""
    /// 当前选中节点 id（高亮用）
    var selectedId: EditJsonNode.ID? = nil
    /// 点击节点回调（选中后在底部编辑）
    var onSelect: (EditJsonNode) -> Void = { _ in }

    @State private var collapsed = Set<EditJsonNode.ID>()

    var body: some View {
        GeometryReader { geo in
            ScrollView([.horizontal, .vertical]) {
                LazyVStack(alignment: .leading, spacing: 2) {
                    ForEach(flatten(root, key: nil, depth: 0), id: \.node.id) { data in
                        EditNodeRow(
                            node: data.node, key: data.key, depth: data.depth,
                            query: query,
                            selected: selectedId == data.node.id,
                            isCollapsed: collapsed.contains(data.node.id)
                        ) {
                            toggle(data.node.id)
                        } onSelect: {
                            onSelect(data.node)
                        }
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

    private func toggle(_ id: EditJsonNode.ID) {
        if collapsed.contains(id) { collapsed.remove(id) }
        else { collapsed.insert(id) }
    }

    /// 递归拍平为可见行（折叠节点不展开其子行）
    private func flatten(_ node: EditJsonNode, key: String?, depth: Int) -> [EditRow] {
        var rows: [EditRow] = []
        flatten(node, key: key, depth: depth, into: &rows)
        return rows
    }

    private func flatten(_ node: EditJsonNode, key: String?, depth: Int, into rows: inout [EditRow]) {
        rows.append(EditRow(node: node, key: key, depth: depth))
        if node.isContainer, !collapsed.contains(node.id) {
            // 对象字段按字母序（大小写不敏感）**仅排序显示**；序列化仍按 entries 原顺序，
            // 不改变 body 的字段顺序（保序，避免与文本视图/YAPI 定义顺序打架）。
            // 数组元素保持原顺序（顺序有语义）。
            for child in node.entries.sorted(by: {
                $0.keyName.localizedCaseInsensitiveCompare($1.keyName) == .orderedAscending
            }) {
                flatten(child, key: child.keyName, depth: depth + 1, into: &rows)
            }
            for child in node.items {
                flatten(child, key: nil, depth: depth + 1, into: &rows)
            }
        }
    }
}

private struct EditRow {
    let node: EditJsonNode
    let key: String?
    let depth: Int
}

private struct EditNodeRow: View {
    @ObservedObject var node: EditJsonNode
    let key: String?
    let depth: Int
    let query: String
    let selected: Bool
    let isCollapsed: Bool
    let onToggle: () -> Void
    let onSelect: () -> Void

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

            if key != nil {
                Text(highlightedText("\"\(key!)\":", query: query, color: .secondary))
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundColor(.secondary)
            }

            if node.isContainer {
                Text(node.kind == .object ? "{ \(node.entries.count) }" : "[ \(node.items.count) ]")
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
            } else {
                Text(highlightedText(node.literal, query: query, color: leafColor))
                    .font(.system(size: 12, design: .monospaced))
            }

            Spacer().frame(maxWidth: 8)
        }
        .contentShape(Rectangle())
        .onTapGesture { onSelect() }
        // LazyVStack 在双轴 ScrollView 中宽度提案为 0，行会被压扁导致文本逐字换行成一列；
        // fixedSize(horizontal) 让行按自身内容宽度布局，横向滚动照常。
        .fixedSize(horizontal: true, vertical: false)
        // 选中底色放在 fixedSize 之后：从缩进区起铺到内容末尾，深层节点也能一眼看出选中
        .background(selected ? Color.accentColor.opacity(0.15) : Color.clear)
    }

    private var leafColor: Color {
        switch node.kind {
        case .string: return .red
        case .number: return .blue
        case .bool: return .purple
        case .null: return .secondary
        default: return .primary
        }
    }
}
