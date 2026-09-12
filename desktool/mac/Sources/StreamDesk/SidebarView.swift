import SwiftUI
import AppKit

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
            // 当前抓包信息
            Section("当前抓包") {
                CurrentCaptureCard()
                HStack {
                    Label("全部请求", systemImage: "tray.full")
                    Spacer()
                    countBadge(client.exchanges.count)
                }
                .tag(Panel.all)
            }

            // 历史抓包记录
            Section("历史记录") {
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
                if client.sessions.isEmpty {
                    Text("暂无历史会话")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
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

/// 当前抓包状态卡：抓包中/未开始、实时计时、上行/下行、请求/透传。
private struct CurrentCaptureCard: View {
    @EnvironmentObject var client: SyncClient
    @State private var nowTick = Date()

    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        let elapsed: Int64? = (client.stats.capturing && client.stats.startedAt > 0)
            ? max(0, Int64(nowTick.timeIntervalSince1970 * 1000) - client.stats.startedAt)
            : nil
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Circle()
                    .fill(client.stats.capturing ? .green : .gray)
                    .frame(width: 8, height: 8)
                Text(client.stats.capturing ? "手机正在抓包" : "未在抓包")
                    .font(.system(size: 12, weight: .medium))
                Spacer()
                if let e = elapsed {
                    Text(fmtDuration(Int64(e)))
                        .font(.system(size: 12, weight: .semibold, design: .monospaced))
                        .foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 4) {
                statCell("上行", fmtSize(Int(client.stats.upload)))
                statCell("下行", fmtSize(Int(client.stats.download)))
            }
            HStack(spacing: 4) {
                statCell("请求", "\(client.stats.requests)")
                statCell("透传", "\(client.stats.passthrough)")
            }
        }
        .padding(10)
        .background(Color(nsColor: .controlBackgroundColor))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .onReceive(timer) { t in nowTick = t }
    }

    private func statCell(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.system(size: 10)).foregroundStyle(.secondary)
            Text(value).font(.system(size: 12, weight: .medium)).monospacedDigit()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct StatsBar: View {
    @EnvironmentObject var client: SyncClient

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Divider()
            HStack(spacing: 6) {
                Image(systemName: client.wsConnected ? "bolt.fill" : "bolt")
                    .foregroundStyle(client.wsConnected ? .green : .secondary)
                    .font(.system(size: 11))
                Text(client.wsConnected ? "推送已连接" : "推送未连接")
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
            }
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
