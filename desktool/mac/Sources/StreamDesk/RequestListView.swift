import SwiftUI

struct RequestListView: View {
    @EnvironmentObject var client: SyncClient
    let panel: Panel
    @Binding var selectedId: String?
    @Binding var search: String
    @State private var typeSel: ReqType = .all

    /// 一次遍历同时得到「搜索结果 / 各类型计数 / 当前类型结果」，避免重复分类
    private var prepared: (searched: [ExchangeRecord], counts: [ReqType: Int], rows: [ExchangeRecord]) {
        let base: [ExchangeRecord]
        switch panel {
        case .all:
            base = client.exchanges
        case .session(let id):
            base = client.exchanges.filter { $0.sessionId == id }
        case .domain(let host):
            base = client.exchanges.filter { $0.host == host }
        case .passthrough:
            base = []
        }
        let q = search.trimmed
        let searched: [ExchangeRecord]
        if q.isEmpty {
            searched = base
        } else {
            searched = base.filter { e in
                e.host.localizedCaseInsensitiveContains(q) ||
                e.path.localizedCaseInsensitiveContains(q) ||
                e.method.localizedCaseInsensitiveContains(q) ||
                String(e.statusCode).contains(q)
            }
        }
        var counts: [ReqType: Int] = [:]
        var rows: [ExchangeRecord] = []
        rows.reserveCapacity(searched.count)
        for e in searched {
            let t = classifyType(e)
            counts[t, default: 0] += 1
            if typeSel == .all || t == typeSel { rows.append(e) }
        }
        return (searched, counts, rows)
    }

    var body: some View {
        let data = prepared
        VStack(spacing: 0) {
            if case .domain(let host) = panel {
                HStack(spacing: 6) {
                    Image(systemName: "globe")
                        .foregroundStyle(.blue)
                        .font(.system(size: 11))
                    Text(host.isEmpty ? "(无域名)" : host)
                        .font(.system(size: 12, weight: .semibold))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer()
                    Text("\(data.searched.count) 个请求")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Color.primary.opacity(0.04))
            }

            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                TextField("搜索 host / path / method / 状态码", text: $search)
                    .textFieldStyle(.roundedBorder)
                if !search.isEmpty {
                    Button("清空") { search = "" }
                }
                Text("\(data.rows.count) 条")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(8)

            TypeFilterRow(counts: data.counts, selected: $typeSel)

            Divider()

            if panel == .passthrough {
                PassListView()
            } else if data.rows.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "tray")
                        .font(.system(size: 28))
                        .foregroundStyle(.secondary)
                    Text(typeSel == .all || data.searched.isEmpty ? "暂无请求" : "当前类型下无请求")
                        .foregroundStyle(.secondary)
                    if client.exchanges.isEmpty {
                        Text("填写手机同步地址后点击「手动同步」")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(data.rows, selection: $selectedId) { e in
                    ExchangeRowView(e: e)
                        .tag(e.id)
                }
                .listStyle(.inset)
            }
        }
        .frame(minWidth: 380)
    }
}

private struct ExchangeRowView: View {
    let e: ExchangeRecord

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text(e.method.isEmpty ? "?" : e.method)
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(.white)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(methodColor(e.method), in: RoundedRectangle(cornerRadius: 4))
                .frame(width: 52)

            VStack(alignment: .leading, spacing: 2) {
                Text(e.displayPath)
                    .font(.system(size: 13))
                    .lineLimit(2)
                Text(e.host)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 6)

            VStack(alignment: .trailing, spacing: 2) {
                statusText
                    .font(.system(size: 11, weight: .semibold))
                Text(fmtDuration(e.durationMs))
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
                Text(fmtTime(e.startDate))
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
            }
            .frame(width: 78, alignment: .trailing)
        }
        .padding(.vertical, 3)
    }

    @ViewBuilder
    private var statusText: some View {
        if e.isFailed {
            Text("失败").foregroundStyle(.red)
        } else if e.statusCode == 0 {
            Text("进行中").foregroundStyle(.secondary)
        } else {
            Text("\(e.statusCode)").foregroundStyle(statusColor(e.statusCode))
        }
    }
}

private struct PassListView: View {
    @EnvironmentObject var client: SyncClient

    var body: some View {
        if client.passthrough.isEmpty {
            VStack(spacing: 8) {
                Image(systemName: "lock.slash")
                    .font(.system(size: 26))
                    .foregroundStyle(.secondary)
                Text("暂无透传连接")
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List(client.passthrough) { p in
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: p.tls ? "lock.slash.fill" : "arrow.left.arrow.right")
                        .foregroundStyle(.orange)
                        .frame(width: 20)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(p.host):\(p.port)")
                            .font(.system(size: 13))
                        Text("原因：\(p.reason)")
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 2) {
                        Text("↑\(fmtSize(Int(p.upBytes))) ↓\(fmtSize(Int(p.downBytes)))")
                            .font(.system(size: 10))
                        Text(fmtDuration(p.durationMs))
                            .font(.system(size: 10))
                            .foregroundStyle(.secondary)
                        Text(fmtTime(p.startDate))
                            .font(.system(size: 10))
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 3)
            }
            .listStyle(.inset)
        }
    }
}

// MARK: - 接口类型过滤

private struct TypeFilterRow: View {
    let counts: [ReqType: Int]
    @Binding var selected: ReqType

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(ReqType.allCases) { t in
                    TypeChip(
                        title: t.rawValue,
                        count: t == .all ? nil : counts[t, default: 0],
                        selected: selected == t
                    ) { selected = t }
                }
            }
            .padding(.horizontal, 8)
        }
        .padding(.vertical, 6)
    }
}

private struct TypeChip: View {
    let title: String
    let count: Int?
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Text(title)
                if let count {
                    Text("\(count)")
                        .foregroundStyle(selected ? Color.white.opacity(0.85) : .secondary)
                }
            }
            .font(.system(size: 11))
            .padding(.horizontal, 9)
            .padding(.vertical, 3)
            .background(
                selected ? Color.accentColor : Color.primary.opacity(0.07),
                in: Capsule()
            )
            .foregroundStyle(selected ? Color.white : Color.primary)
        }
        .buttonStyle(.plain)
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
