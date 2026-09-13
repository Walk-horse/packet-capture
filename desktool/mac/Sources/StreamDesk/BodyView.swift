import SwiftUI
import AppKit

/// 后台线程可用的常量（文件作用域，非 actor 隔离）
private enum BodyLimits {
    /// 文本视图渲染上限，超出截断（复制/保存仍为完整内容）
    static let renderLimit = 1024 * 1024
    static let note = "\n\n… 为流畅渲染仅显示前 1 MB（完整内容可用「复制 / 保存」导出）"
}

struct BodyView: View {
    /// 唯一标识：切换请求或 body 变化时重新解析
    let key: String
    let data: Data
    let encoding: String?
    let contentType: String?
    let truncated: Bool

    @State private var mode: Mode = .text
    @State private var prettyOn = false
    @State private var decoded: String?
    @State private var pretty: String?
    @State private var hex: String?
    @State private var jsonRoot: JsonNode?
    @State private var query = ""
    @State private var matchIndex = 0
    @State private var matchCount = 0
    @State private var working = false

    enum Mode: String, CaseIterable, Identifiable {
        case text = "文本"
        case json = "JSON树"
        case hex = "HEX"
        var id: String { rawValue }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Picker("", selection: $mode) {
                    ForEach(Mode.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .frame(width: 200)

                Spacer()

                Button("复制") {
                    copyToPasteboard(mode == .hex ? (hex ?? hexDump(data)) : fullText)
                }
                Button("保存") { save() }
            }
            .font(.system(size: 11))
            .padding(8)

            // 信息（左）+ 搜索框（右）
            HStack(spacing: 8) {
                if mode == .text, looksLikeJSON {
                    Toggle("JSON 美化", isOn: $prettyOn)
                        .toggleStyle(.checkbox)
                }

                Text("大小 \(fmtSize(data.count))")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                if let ct = contentType, !ct.isEmpty {
                    Text(ct).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1)
                }
                if let enc = encoding, !enc.isEmpty {
                    Text("已解压 \(enc.uppercased())")
                        .font(.system(size: 11))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 1)
                        .background(.orange.opacity(0.15), in: Capsule())
                }
                if truncated {
                    Text("同步已截断")
                        .font(.system(size: 11))
                        .foregroundStyle(.orange)
                }
                if working {
                    ProgressView()
                        .controlSize(.small)
                        .scaleEffect(0.7)
                        .frame(width: 14, height: 14)
                }

                Spacer()

                HStack(spacing: 4) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField("搜索", text: $query)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 200)
                if !query.isEmpty {
                    let count = searchMatchCount
                        Text(count == 0 ? "0/0" : "\(min(matchIndex + 1, count))/\(count)")
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                        if mode != .json {
                            Button { stepMatch(-1) } label: { Image(systemName: "chevron.up").resizable().frame(width: 8, height: 8) }
                                .frame(width: 18, height: 18)
                                .help("上一个匹配")
                            Button { stepMatch(1) } label: { Image(systemName: "chevron.down").resizable().frame(width: 8, height: 8) }
                                .frame(width: 18, height: 18)
                                .help("下一个匹配")
                        }
                    }
                }
            }
            .font(.system(size: 11))
            .padding(.horizontal, 8)
            .padding(.bottom, 8)

            Divider()

            content
        }
        // 切换请求 / body 变化：重置缓存并按需解析
        .task(id: key) {
            decoded = nil; pretty = nil; hex = nil; jsonRoot = nil
            query = ""; matchIndex = 0; matchCount = 0
            await prepareText()
            if mode == .hex { await prepareHex() }
            if mode == .json { await prepareJson() }
        }
        // 切换视图：按需解析（已解析则直接复用）。默认文本只做轻量解码；
        // JSON 树 / 美化仅在用户点选对应模式时才构建，不提前解析。
        .task(id: mode) {
            if mode == .hex { await prepareHex() }
            else if mode == .json { await prepareJson() }
            else { await prepareText() }
        }
        .task(id: prettyOn) { await preparePretty() }
    }

    // MARK: - 内容

    @ViewBuilder
    private var content: some View {
        if data.isEmpty {
            placeholder("空 body")
        } else if mode == .hex {
            if let hex {
                LargeTextView(text: hex, query: query, matchIndex: matchIndex, matchCount: $matchCount)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        } else if mode == .json {
            jsonContent
        } else if let d = decoded {
            LargeTextView(text: pretty ?? d, query: query, matchIndex: matchIndex, matchCount: $matchCount)
        } else if working {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            placeholder("二进制内容（共 \(fmtSize(data.count))），可切换到 HEX 视图查看")
        }
    }

    @ViewBuilder
    private var jsonContent: some View {
        if let root = jsonRoot {
            // 全量树 + 命中片段淡黄高亮，与「文本」视图内容一致
            JsonTreeView(root: root, query: query)
        } else if working {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if !looksLikeJSON {
            placeholder("非 JSON 内容（共 \(fmtSize(data.count))），可切换到「文本」或「HEX」视图查看")
        } else {
            placeholder("JSON 解析失败，可切换到「文本」视图查看原始内容")
        }
    }

    /// 搜索计数基准串：与当前视图展示内容一致
    private var searchBaseText: String {
        if mode == .hex { return hex ?? hexDump(data) }
        return pretty ?? decoded ?? (decodeText(data) ?? "")
    }

    /// 统一搜索命中数（大小写不敏感、不重叠计数），文本 / JSON树 两种模式共用同一基准 → 结果一致
    private var searchMatchCount: Int {
        guard !query.isEmpty else { return 0 }
        var count = 0
        var start = searchBaseText.startIndex
        let s = searchBaseText
        while let r = s.range(of: query, options: [.caseInsensitive, .diacriticInsensitive], range: start..<s.endIndex) {
            count += 1
            start = r.upperBound
            if count >= 5000 { break }
        }
        return count
    }

    private func stepMatch(_ dir: Int) {
        let count = searchMatchCount
        guard count > 0 else { return }
        matchIndex = (matchIndex + dir + count) % count
    }

    private func placeholder(_ text: String) -> some View {
        VStack { Text(text).foregroundStyle(.secondary) }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - 解析（后台线程，结果缓存，避免每次重绘都重算）

    private var looksLikeJSON: Bool {
        guard let d = decoded else { return false }
        let t = d.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.hasPrefix("{") || t.hasPrefix("[")
    }

    /// 文本视图内容是 JSON 时，顶部出现「JSON 美化」开关；是否美化由用户点击决定（默认纯文本）

    private var fullText: String {
        if let p = pretty { return p }
        return decoded ?? (decodeText(data) ?? "")
    }

    private func prepareText() async {
        guard decoded == nil else { return }
        if data.isEmpty { decoded = ""; return }
        working = true
        defer { working = false }
        let d = data
        decoded = await Task.detached(priority: .userInitiated) { decodeText(d) }.value
    }

    private func prepareHex() async {
        guard hex == nil else { return }
        working = true
        defer { working = false }
        let d = data
        hex = await Task.detached(priority: .userInitiated) { hexDump(d) }.value
    }

    private func preparePretty() async {
        guard prettyOn else { pretty = nil; return }
        guard let t = decoded, pretty == nil else { return }
        working = true
        defer { working = false }
        let result: String? = await Task.detached(priority: .userInitiated) { prettyJSON(t) }.value
        pretty = result.map(Self.clip)
    }

    /// 解析 JSON 为可折叠树（依赖 decoded 文本；解析失败保留 nil 走占位提示）
    private func prepareJson() async {
        guard mode == .json else { jsonRoot = nil; return }
        await prepareText() // 确保 decoded 就绪（幂等）
        guard let t = decoded else { jsonRoot = nil; return }
        working = true
        defer { working = false }
        jsonRoot = await Task.detached(priority: .userInitiated) { () -> JsonNode? in
            guard let data = t.data(using: .utf8) else { return nil }
            do {
                let obj = try JSONSerialization.jsonObject(with: data, options: [])
                var counter = 0
                return buildJsonNode(obj, counter: &counter)
            } catch {
                return nil
            }
        }.value
    }

    private static let renderLimit = 1024 * 1024
    private static let clipNote = "\n\n… 为流畅渲染仅显示前 1 MB（完整内容可用「复制 / 保存」导出）"

    /// 限制渲染文本长度（后台线程调用，需 nonisolated）
    nonisolated private static func clip(_ s: String) -> String {
        guard s.utf8.count > BodyLimits.renderLimit else { return s }
        let idx = s.index(s.startIndex, offsetBy: BodyLimits.renderLimit, limitedBy: s.endIndex) ?? s.endIndex
        return String(s[..<idx]) + BodyLimits.note
    }

    private func save() {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = mode == .hex ? "body.txt" : "body.bin"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        if mode == .hex {
            try? (hex ?? hexDump(data)).data(using: .utf8)?.write(to: url)
        } else {
            try? data.write(to: url)
        }
    }
}
