import SwiftUI

/// 接口模拟：规则列表（左中栏）。
/// 规则在 Mac 端编辑，点「推送配置到手机」覆盖手机端配置；
/// 手机端还需「开启总开关 + 为对应应用打开开关」才会返回模拟响应。
struct MockListView: View {
    @EnvironmentObject var client: SyncClient
    @EnvironmentObject var store: MockRuleStore
    @Binding var selectedId: String?

    @State private var pushing = false
    @State private var pulling = false
    @State private var toast: String?
    @State private var showPickFromCapture = false
    @State private var showYapi = false

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            if store.rules.isEmpty {
                emptyState
            } else {
                List(selection: $selectedId) {
                    ForEach(store.rules) { rule in
                        MockRuleRow(rule: rule) { on in
                            store.setEnabled(rule.id, on)
                        }
                        .tag(rule.id)
                        .contextMenu {
                            Button("复制一份") { store.duplicate(rule.id) }
                            Divider()
                            Button("删除", role: .destructive) { store.remove(rule.id) }
                        }
                    }
                }
                .listStyle(.inset)
            }
        }
        .frame(minWidth: 380)
        .onAppear { Task { await client.refreshMockStatus() } }
    }

    // MARK: - 顶部

    private var header: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                Text("接口模拟")
                    .font(.system(size: 13, weight: .semibold))
                Text("\(store.rules.count) 条规则")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)

                Spacer()

                Menu {
                    Button("新增空白规则") {
                        let r = store.add()
                        selectedId = r.id
                    }
                    Button("从抓包记录选择…") { showPickFromCapture = true }
                        .disabled(client.exchanges.isEmpty)
                    Divider()
                    Button("从 YAPI 获取…") { showYapi = true }
                } label: {
                    Label("新增", systemImage: "plus")
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
                .help("新增接口模拟规则")

                Button {
                    Task { await push() }
                } label: {
                    if pushing { ProgressView().controlSize(.small) }
                    else { Label("推送配置到手机", systemImage: "arrow.up.to.line") }
                }
                .controlSize(.small)
                .disabled(pushing)
                .help("把全部规则整体覆盖到手机端")

                Button {
                    Task { await pullFromPhone() }
                } label: {
                    if pulling { ProgressView().controlSize(.small) }
                    else { Label("从手机拉取", systemImage: "arrow.down.to.line") }
                }
                .controlSize(.small)
                .disabled(pulling)
                .help("用手机端当前配置覆盖本地规则")
            }

            HStack(spacing: 6) {
                Circle()
                    .fill((client.mockStatus?.enabled ?? false) ? Color.green : Color.secondary)
                    .frame(width: 6, height: 6)
                if let m = client.mockStatus {
                    Text(m.enabled
                         ? "手机端已开启 · \(m.enabledApps.count) 个应用启用模拟 · 手机端 \(m.ruleCount) 条规则"
                         : "手机端接口模拟未开启（在手机「总览 → 工具 → 接口模拟」中打开）")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                } else {
                    Text("未获取到手机端模拟状态（检查同步地址）")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if let toast {
                    Text(toast)
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(Color.primary.opacity(0.04))
        .sheet(isPresented: $showPickFromCapture) {
            MockPickFromCaptureView { e in
                let r = store.add(store.makeRule(from: e))
                selectedId = r.id
                showPickFromCapture = false
            } onCancel: {
                showPickFromCapture = false
            }
        }
        .sheet(isPresented: $showYapi) {
            MockYapiSheet(
                onApply: { f, host in
                    let r = store.add(store.makeRule(from: f, host: host))
                    selectedId = r.id
                    showYapi = false
                    showToast("已从 YAPI #\(f.id) 创建规则：\(f.summary)")
                },
                onCancel: { showYapi = false },
                applyTitle: "创建规则"
            )
            .environmentObject(store)
            .environmentObject(client)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "arrow.triangle.2.circlepath")
                .font(.system(size: 30))
                .foregroundStyle(.secondary)
            Text("暂无模拟接口")
                .foregroundStyle(.secondary)
            Text("点「新增」配置接口；响应示例可从抓包记录填充、\n或按 YAPI 接口 ID 拉取，未配置时手机端按响应类型自动生成。")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            HStack(spacing: 8) {
                Button("从抓包记录新建") { showPickFromCapture = true }
                    .disabled(client.exchanges.isEmpty)
                Button("从 YAPI 获取") { showYapi = true }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - 动作

    private func push() async {
        pushing = true
        defer { pushing = false }
        let msg = await client.pushMock(store.rules)
        showToast(msg)
    }

    private func pullFromPhone() async {
        pulling = true
        defer { pulling = false }
        guard let cfg = await client.fetchMockConfig() else {
            showToast("拉取失败：请检查同步地址与连接")
            return
        }
        if let rules = cfg.rules {
            store.replaceAll(rules)
            showToast("已从手机拉取 \(rules.count) 条规则")
        } else {
            showToast("手机端无规则数据")
        }
    }

    private func showToast(_ msg: String) {
        toast = msg
        Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if toast == msg { toast = nil }
        }
    }
}

// MARK: - 规则行

private struct MockRuleRow: View {
    let rule: MockRule
    let onToggle: (Bool) -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Toggle("", isOn: Binding(get: { rule.enabled }, set: { onToggle($0) }))
                .labelsHidden()
                .toggleStyle(.checkbox)
                .help(rule.enabled ? "已启用（可关闭）" : "已停用（可开启）")

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(rule.method.isEmpty ? "*" : rule.method)
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(methodColor(rule.method), in: RoundedRectangle(cornerRadius: 4))
                    Text("\(rule.host)\(rule.path)")
                        .font(.system(size: 13))
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                Text(subtitle)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
        }
        .padding(.vertical, 3)
        .opacity(rule.enabled ? 1 : 0.5)
    }

    private var subtitle: String {
        var s = "响应 \(rule.statusCode) · \(rule.contentType) · \(rule.sourceText)"
        if rule.delayMs > 0 { s += " · 延迟 \(rule.delayMs)ms" }
        if !rule.headers.isEmpty { s += " · \(rule.headers.count) 个自定义头" }
        return s
    }
}

// MARK: - 从抓包记录挑选

private struct MockPickFromCaptureView: View {
    @EnvironmentObject var client: SyncClient
    let onPick: (ExchangeRecord) -> Void
    let onCancel: () -> Void

    @State private var query = ""

    private var items: [ExchangeRecord] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = client.exchanges.filter { !$0.isPending }
        guard !q.isEmpty else { return Array(base.prefix(200)) }
        return base.filter {
            $0.host.localizedCaseInsensitiveContains(q) || $0.path.localizedCaseInsensitiveContains(q)
        }.prefix(200).map { $0 }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("搜索 host / path", text: $query)
                    .textFieldStyle(.roundedBorder)
                Button("取消") { onCancel() }
            }
            .padding(10)
            Divider()
            List(items) { e in
                Button {
                    onPick(e)
                } label: {
                    HStack(spacing: 8) {
                        Text(e.method.isEmpty ? "?" : e.method)
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 5).padding(.vertical, 1)
                            .background(methodColor(e.method), in: RoundedRectangle(cornerRadius: 4))
                            .frame(width: 50)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(e.displayPath).font(.system(size: 12)).lineLimit(1)
                            Text(e.host).font(.system(size: 10)).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer()
                        Text("\(e.statusCode)").font(.system(size: 11)).foregroundStyle(.secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .frame(width: 520, height: 420)
        }
    }
}
