import Foundation
import SwiftUI

// MARK: - 格式化

func fmtSize(_ n: Int) -> String {
    if n < 1024 { return "\(n) B" }
    if n < 1024 * 1024 { return String(format: "%.1f KB", Double(n) / 1024) }
    return String(format: "%.2f MB", Double(n) / 1024 / 1024)
}

func fmtDuration(_ ms: Int64) -> String {
    if ms < 0 { return "进行中" }
    if ms < 1000 { return "\(ms) ms" }
    return String(format: "%.2f s", Double(ms) / 1000)
}

private let timeFmt: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss"
    return f
}()

private let fullFmt: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "yyyy-MM-dd HH:mm:ss"
    return f
}()

func fmtTime(_ d: Date) -> String { timeFmt.string(from: d) }
func fmtFullTime(_ d: Date) -> String { fullFmt.string(from: d) }

func methodColor(_ m: String) -> Color {
    switch m.uppercased() {
    case "GET": return .blue
    case "POST": return .orange
    case "PUT": return .purple
    case "DELETE": return .red
    case "PATCH": return .pink
    case "HEAD": return .teal
    default: return .secondary
    }
}

func statusColor(_ code: Int) -> Color {
    switch code {
    case 200..<300: return .green
    case 300..<400: return .blue
    case 400..<500: return .orange
    case 500..<600: return .red
    default: return .secondary
    }
}

// MARK: - body 解析

/// 十六进制转储（默认只转前 64KB，避免大 body 卡顿）
func hexDump(_ data: Data, limit: Int = 64 * 1024) -> String {
    let bytes = [UInt8](data.prefix(limit))
    var out = ""
    out.reserveCapacity(bytes.count * 4)
    var offset = 0
    while offset < bytes.count {
        let end = min(offset + 16, bytes.count)
        let line = bytes[offset..<end]
        let hex = line.map { String(format: "%02x", $0) }.joined(separator: " ")
        let ascii = line.map { b -> String in
            if b >= 0x20 && b < 0x7f { return String(UnicodeScalar(b)) }
            return "."
        }.joined()
        out += String(format: "%08x  %-47s  |%@|\n", offset, hex, ascii)
        offset = end
    }
    if data.count > limit {
        out += "\n… 仅显示前 \(fmtSize(limit))（共 \(fmtSize(data.count))）"
    }
    return out
}

/// 本地兜底解码：UTF-16(BOM) → UTF-8 → 拉丁1；全二进制返回 nil
func decodeText(_ data: Data) -> String? {
    guard !data.isEmpty else { return "" }
    if data.count >= 2 {
        let b0 = data[data.startIndex]
        let b1 = data[data.startIndex + 1]
        if (b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF) {
            let body = data.dropFirst(2)
            if b0 == 0xFE && b1 == 0xFF {
                return String(bytes: body, encoding: .utf16BigEndian)
            }
            return String(bytes: body, encoding: .utf16LittleEndian)
        }
    }
    if let s = String(data: data, encoding: .utf8) { return s }
    if let s = String(data: data, encoding: .isoLatin1) { return s }
    return nil
}

/// JSON 美化（用于详情 body 展示开关）
func prettyJSON(_ text: String) -> String? {
    guard let data = text.data(using: .utf8) else { return nil }
    do {
        let obj = try JSONSerialization.jsonObject(with: data, options: [])
        let pretty = try JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted, .withoutEscapingSlashes])
        return String(data: pretty, encoding: .utf8)
    } catch {
        return nil
    }
}

// MARK: - cURL

/// 单引号包裹并转义内部单引号，保证可安全粘贴到 shell
func shellQuote(_ s: String) -> String {
    "'" + s.replacingOccurrences(of: "'", with: "'\\''") + "'"
}

/// 由抓包记录还原 cURL 命令（headers 来自请求行，body 用手机端已解压文本）
func curlCommand(for e: ExchangeRecord) -> String {
    let method = e.method.isEmpty ? "GET" : e.method
    var cmd = "curl"
    if method.uppercased() != "GET" { cmd += " -X \(method)" }
    cmd += " \(shellQuote(e.url))"

    let decompressed = (e.reqEncoding ?? "").isEmpty == false
    let skip: Set<String> = decompressed
        ? ["content-length", "content-encoding"]
        : ["content-length"]

    for (name, value) in e.requestHeaderPairs {
        if skip.contains(name.lowercased()) { continue }
        cmd += " \\\n  -H \(shellQuote("\(name): \(value)"))"
    }

    let data = e.requestData
    if !data.isEmpty {
        if let text = decodeText(data) {
            cmd += " \\\n  --data-raw \(shellQuote(text))"
        } else {
            cmd += " \\\n  --data-binary @<文件路径>（二进制 body，请用「保存」导出后替换）"
        }
    }
    if decompressed {
        cmd += " \\\n  # 原始 body 为 \(e.reqEncoding?.uppercased() ?? "") 压缩，此处已写入解压后的明文"
    }
    return cmd
}

func copyToPasteboard(_ text: String) {
    NSPasteboard.general.clearContents()
    NSPasteboard.general.setString(text, forType: .string)
}
