import Foundation

// MARK: - 资源类型（与手机端 ReqType 一致）

enum ReqType: String, CaseIterable, Identifiable {
    case all = "全部"
    case xhr = "Fetch/XHR"
    case doc = "文档"
    case css = "CSS"
    case js = "JS"
    case img = "图片"
    case wasm = "Wasm"
    case other = "其他"

    var id: String { rawValue }
}

/// 按 Content-Type（响应优先）归类，缺失/不明确时按 path 扩展名兜底。
/// 抓包层无法感知页面发起类型（fetch/xhr/document），JSON/XML/表单等 API 响应归为 Fetch/XHR。
func classifyType(_ e: ExchangeRecord) -> ReqType {
    let raw = (e.respType?.isEmpty == false) ? (e.respType ?? "") : (e.reqType ?? "")
    let ct = raw
        .lowercased()
        .components(separatedBy: ";")[0]
        .trimmingCharacters(in: .whitespaces)
    let base = e.path.components(separatedBy: "?")[0].lowercased()
    func hasExt(_ list: [String]) -> Bool { list.contains { base.hasSuffix($0) } }

    if ct.hasPrefix("image/") { return .img }
    if ct.hasPrefix("application/wasm") { return .wasm }
    if ct == "text/css" { return .css }
    if ct.contains("javascript") || ct.contains("ecmascript") { return .js }
    if ct.contains("html") || ct == "application/xhtml+xml" { return .doc }
    if ct.contains("json") || ct.contains("xml")
        || ct.contains("x-www-form-urlencoded") || ct.contains("multipart/form-data") { return .xhr }

    if hasExt([".css"]) { return .css }
    if hasExt([".js", ".mjs", ".cjs"]) { return .js }
    if hasExt([".html", ".htm"]) { return .doc }
    if hasExt([".wasm"]) { return .wasm }
    if hasExt([".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".bmp", ".avif", ".heic"]) { return .img }
    return .other
}
