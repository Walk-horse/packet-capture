import SwiftUI

struct SidebarView: View {
    @EnvironmentObject var client: SyncClient
    @Binding var panel: Panel
    @State private var showAllDomains = false

    /// 域名 → 请求数，按请求数降序
    private var domains: [(String, Int)] {
        var map: [String: Int] = [:]
        for e in client.exchanges { map[e.host, default: 0] += 1 }
        return map.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
    }

    private let domainLimit = 20

    var body: some View {
        let all = domains
        let visible = showAllDomains ? all : Array(all.prefix(domainLimit))
        List(selection: $panel) {
            Section("会话") {
                HStack {
                    Label("全部请求", systemImage: "tray.full")
                    Spacer()
                    countBadge(client.exchanges.count)
                }
                .tag(Panel.all)

                ForEach(client.sessions) { s in
                    HStack(spacing: 6) {
                        Image(systemName: s.endTime == 0 ? "record.circle" : "checkmark.circle")
                            .foregroundStyle(s.endTime == 0 ? .red : .secondary)
                            .font(.system(size: 11))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(fmtFullTime(s.startDate))
                                .font(.system(size: 12))
                            Text("\(fmtDuration(s.durationSec * 1000)) · \(s.requestCount) 请求")
                                .font(.system(size: 10))
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        countBadge(s.requestCount)
                    }
                    .tag(Panel.session(s.id))
                }
            }

            Section("域名") {
                if visible.isEmpty {
                    Text("暂无域名")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(visible, id: \.0) { host, count in
                        HStack(spacing: 6) {
                            Image(systemName: "globe")
                                .foregroundStyle(.blue)
                                .font(.system(size: 11))
                            Text(host.isEmpty ? "(无域名)" : host)
                                .font(.system(size: 12))
                                .lineLimit(1)
                                .truncationMode(.middle)
                            Spacer()
                            countBadge(count)
                        }
                        .tag(Panel.domain(host))
                    }
                    if all.count > domainLimit && !showAllDomains {
                        Button("显示全部 \(all.count) 个域名") { showAllDomains = true }
                            .font(.system(size: 11))
                    }
                }
            }

            Section("其他") {
                HStack {
                    Label("透传连接", systemImage: "lock.slash")
                    Spacer()
                    countBadge(client.passthrough.count)
                }
                .tag(Panel.passthrough)
            }
        }
        .listStyle(.sidebar)
        .frame(minWidth: 220)
        .safeAreaInset(edge: .bottom) { StatsBar() }
    }

    private func countBadge(_ n: Int) -> some View {
        Text("\(n)")
            .font(.system(size: 11))
            .foregroundStyle(.secondary)
            .monospacedDigit()
    }
}

private struct StatsBar: View {
    @EnvironmentObject var client: SyncClient

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Divider()
            HStack(spacing: 6) {
                Circle()
                    .fill(client.stats.capturing ? .green : .gray)
                    .frame(width: 7, height: 7)
                Text(client.stats.capturing ? "手机正在抓包" : "未在抓包")
                    .font(.system(size: 11, weight: .medium))
            }
            statRow("请求 / 透传", "\(client.stats.requests) / \(client.stats.passthrough)")
            statRow("上传 / 下载", "\(fmtSize(Int(client.stats.upload))) / \(fmtSize(Int(client.stats.download)))")
            statRow("本地已同步", "\(client.exchanges.count) 条")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
    }

    private func statRow(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title).font(.system(size: 10)).foregroundStyle(.secondary)
            Spacer()
            Text(value).font(.system(size: 10)).monospacedDigit()
        }
    }
}
