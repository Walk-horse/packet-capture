import SwiftUI

enum DetailTab: String, CaseIterable, Identifiable {
    case requestHeaders = "请求头"
    case requestBody = "请求体"
    case responseHeaders = "响应头"
    case responseBody = "响应体"

    var id: String { rawValue }
}

struct DetailView: View {
    @EnvironmentObject var client: SyncClient
    let id: String?
    @State private var tab: DetailTab = .responseBody
    /// 选中记录快照：仅在选中项或列表条数变化时查找，避免每次重绘全表扫描
    @State private var record: ExchangeRecord?

    private func load() {
        guard let id else { record = nil; return }
        record = client.exchanges.first { $0.id == id }
    }

    var body: some View {
        Group {
            if let e = record {
                VStack(spacing: 0) {
                    headerBlock(e)
                    Divider()
                    Picker("", selection: $tab) {
                        ForEach(DetailTab.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .padding(8)
                    content(e)
                }
            } else {
                VStack(spacing: 10) {
                    Image(systemName: "doc.text.magnifyingglass")
                        .font(.system(size: 30))
                        .foregroundStyle(.secondary)
                    Text("在中间列表选择一个请求查看详情")
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(minWidth: 420)
        .task(id: id) { load() }
        .onChange(of: client.exchanges.count) { _ in load() }
    }

    // MARK: - 头部

    private func headerBlock(_ e: ExchangeRecord) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 8) {
                Text(e.method)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(methodColor(e.method), in: RoundedRectangle(cornerRadius: 4))
                Text(e.url)
                    .font(.system(size: 13, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button("复制 URL") { copyToPasteboard(e.url) }
                    .help("复制完整请求 URL")
                CopyCurlButton(exchange: e)
            }

            HStack(spacing: 14) {
                meta("状态", statusSummary(e), statusColor(e.statusCode))
                meta("耗时", fmtDuration(e.durationMs), .primary)
                meta("时间", fmtFullTime(e.startDate), .primary)
                meta("请求体", fmtSize(e.requestSize), .primary)
                meta("响应体", fmtSize(e.responseSize), .primary)
                if let ip = e.remoteIp { meta("远端 IP", ip, .primary) }
                if e.uid >= 0 { meta("UID", "\(e.uid)", .primary) }
                if e.favorite { meta("收藏", "★", .yellow) }
            }
            .font(.system(size: 11))

            if let err = e.error {
                Text("错误：\(err)")
                    .font(.system(size: 11))
                    .foregroundStyle(.red)
            }
        }
        .padding(12)
    }

    private func statusSummary(_ e: ExchangeRecord) -> String {
        if e.isFailed { return "失败" }
        if e.statusCode == 0 { return "未完成" }
        return "\(e.statusCode) \(e.statusText)"
    }

    private func meta(_ title: String, _ value: String, _ color: Color) -> some View {
        HStack(spacing: 3) {
            Text(title).foregroundStyle(.secondary)
            Text(value).foregroundStyle(color).monospacedDigit()
        }
    }

    // MARK: - 内容

    @ViewBuilder
    private func content(_ e: ExchangeRecord) -> some View {
        switch tab {
        case .requestHeaders:
            HeaderTable(pairs: e.requestHeaderPairs)
        case .responseHeaders:
            HeaderTable(pairs: e.responseHeaderPairs)
        case .requestBody:
            BodyView(
                key: "\(e.id)-req-\(e.requestBodyB64.count)",
                data: e.requestData,
                phoneText: e.reqText,
                encoding: e.reqEncoding,
                contentType: e.reqType,
                truncated: e.requestBodyTruncated
            )
        case .responseBody:
            BodyView(
                key: "\(e.id)-resp-\(e.responseBodyB64.count)",
                data: e.responseData,
                phoneText: e.respText,
                encoding: e.respEncoding,
                contentType: e.respType,
                truncated: e.responseBodyTruncated
            )
        }
    }
}

// MARK: - 复制 cURL

private struct CopyCurlButton: View {
    let exchange: ExchangeRecord
    @State private var copied = false

    var body: some View {
        Button(copied ? "已复制 ✓" : "复制 cURL") {
            copyToPasteboard(curlCommand(for: exchange))
            copied = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copied = false }
        }
        .help("复制可直接重放的 cURL 命令（含请求头与请求体）")
    }
}

private struct HeaderTable: View {
    let pairs: [(String, String)]

    var body: some View {
        if pairs.isEmpty {
            VStack {
                Text("无 Header").foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(pairs.enumerated()), id: \.offset) { _, pair in
                        HStack(alignment: .top, spacing: 8) {
                            Text(pair.0)
                                .font(.system(size: 12, weight: .semibold))
                                .frame(width: 180, alignment: .trailing)
                            Text(pair.1)
                                .font(.system(size: 12, design: .monospaced))
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        Divider()
                    }
                }
                .padding(12)
            }
        }
    }
}
