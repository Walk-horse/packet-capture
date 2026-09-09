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
    /// 手机端已解压/识别的文本（优先使用，Mac 端无需自带解压库）
    let phoneText: String?
    let encoding: String?
    let contentType: String?
    let truncated: Bool

    @State private var mode: Mode = .text
    @State private var prettyOn = false
    /// 是否已完成「JSON 自动美化」判断（用户手动改过之后不再自动覆盖）
    @State private var autoPrettyDone = false
    @State private var decoded: String?
    @State private var pretty: String?
    @State private var hex: String?
    @State private var working = false

    enum Mode: String, CaseIterable, Identifiable {
        case text = "文本"
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
                .frame(width: 130)

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

                Button("复制") {
                    copyToPasteboard(mode == .hex ? (hex ?? hexDump(data)) : fullText)
                }
                Button("保存") { save() }
            }
            .font(.system(size: 11))
            .padding(8)

            Divider()

            content
        }
        // 切换请求 / body 变化：重置缓存并按需解析
        .task(id: key) {
            decoded = nil; pretty = nil; hex = nil
            autoPrettyDone = false
            await prepareText()
            autoEnablePretty()
            if mode == .hex { await prepareHex() }
        }
        // 切换视图：按需解析（已解析则直接复用）
        .task(id: mode) {
            if mode == .hex { await prepareHex() } else { await prepareText(); autoEnablePretty() }
        }
        .task(id: prettyOn) { await preparePretty() }
        .onChange(of: prettyOn) { _ in autoPrettyDone = true }
    }

    // MARK: - 内容

    @ViewBuilder
    private var content: some View {
        if data.isEmpty {
            placeholder("空 body")
        } else if mode == .hex {
            if let hex {
                LargeTextView(text: hex)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        } else if let d = decoded {
            LargeTextView(text: pretty ?? d)
        } else if working {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            placeholder("二进制内容（共 \(fmtSize(data.count))），可切换到 HEX 视图查看")
        }
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

    /// 文本视图内容是 JSON 时自动勾选美化（用户手动调整过则不再自动覆盖）
    private func autoEnablePretty() {
        guard !autoPrettyDone, !prettyOn, mode == .text, looksLikeJSON else { return }
        prettyOn = true
    }

    private var fullText: String {
        if let p = pretty { return p }
        return phoneText ?? (decodeText(data) ?? "")
    }

    private func prepareText() async {
        guard decoded == nil else { return }
        if data.isEmpty { decoded = ""; return }
        if let p = phoneText {
            decoded = Self.clip(p)
            return
        }
        working = true
        defer { working = false }
        let d = data
        decoded = await Task.detached(priority: .userInitiated) { () -> String? in
            guard let s = decodeText(d) else { return nil }
            return Self.clip(s)
        }.value
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
        pretty = await Task.detached(priority: .userInitiated) { prettyJSON(t) }.value
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
