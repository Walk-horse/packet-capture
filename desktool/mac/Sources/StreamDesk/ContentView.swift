import SwiftUI

struct ContentView: View {
    @EnvironmentObject var client: SyncClient
    @StateObject private var mockStore = MockRuleStore()
    @StateObject private var yapiSettings = YapiSettings()
    @State private var panel: Panel = .all
    @State private var selectedId: String?
    @State private var selectedRuleId: String?
    @State private var search = ""
    @State private var showConfig = false
    /// 默认三栏全部展开（左侧导航不折叠）
    @State private var columnVisibility: NavigationSplitViewVisibility = .all

    var body: some View {
        NavigationSplitView(columnVisibility: $columnVisibility) {
            SidebarView(panel: $panel)
        } content: {
            if panel == .mock {
                MockListView(selectedId: $selectedRuleId)
            } else {
                RequestListView(panel: panel, selectedId: $selectedId, search: $search)
            }
        } detail: {
            if panel == .mock {
                MockEditorView(ruleId: selectedRuleId)
            } else {
                DetailView(id: selectedId)
            }
        }
        .environmentObject(mockStore)
        .environmentObject(yapiSettings)
        .toolbar { toolbarContent }
        .onAppear { client.startIfNeeded() }
        .sheet(isPresented: $showConfig) {
            SyncConfigView().environmentObject(client)
        }
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        // 左：图标（窗口标题栏已显示「Packet Capture」，此处只保留视觉识别图）
        ToolbarItem(placement: .navigation) {
            Image(systemName: "waveform.path.ecg")
                .foregroundStyle(.blue)
                .help("Packet Capture 桌面面板")
        }

        // 中：同步地址与连接状态
        ToolbarItem(placement: .principal) {
            HStack(spacing: 6) {
                Circle()
                    .fill(client.status.color)
                    .frame(width: 7, height: 7)
                    .help(client.status.text)
                Image(systemName: "iphone")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                Button {
                    showConfig = true
                } label: {
                    Image(systemName: "cable.connector")
                }
                .controlSize(.small)
                .help("同步设置（USB 探测 / 设备选择 / 地址 / 策略）")
                TextField("手机地址，如 192.168.1.20:17890", text: $client.address)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(size: 11))
                    .frame(width: 210)
                    .onSubmit {
                        client.saveAddress()
                        Task { await client.resetAndPull() }
                    }
                Text(client.status.text)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .frame(minWidth: 96, alignment: .leading)
            }
        }

        // 右：同步控制（紧凑尺寸）
        ToolbarItem(placement: .primaryAction) {
            HStack(spacing: 6) {
                Toggle("自动", isOn: Binding(
                    get: { client.autoSync },
                    set: { client.setAutoSync($0) }
                ))
                .toggleStyle(.switch)
                .controlSize(.small)
                .font(.system(size: 11))
                .help("自动同步开关")

                Picker("", selection: Binding(
                    get: { client.interval },
                    set: { client.setInterval($0) }
                )) {
                    Text("1s").tag(1.0)
                    Text("2s").tag(2.0)
                    Text("5s").tag(5.0)
                }
                .controlSize(.small)
                .frame(width: 58)
                .disabled(!client.autoSync)
                .help("自动同步间隔")

                Button {
                    Task { await client.pull() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .controlSize(.small)
                .help("手动同步（仅拉取新增请求）")

                Button {
                    Task { await client.reconnect() }
                } label: {
                    if client.reconnecting {
                        ProgressView()
                            .controlSize(.small)
                    } else {
                        Image(systemName: "arrow.triangle.2.circlepath")
                    }
                }
                .controlSize(.small)
                .help("一键重联移动端：重启 adb → 探测设备 → 重建转发 → 重连推送并补拉")
                .disabled(client.reconnecting)

                Button {
                    client.clearLocal()
                } label: {
                    Image(systemName: "trash")
                }
                .controlSize(.small)
                .help("清空本地数据")

                Menu {
                    Button("全量重新同步") { Task { await client.resetAndPull() } }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .controlSize(.small)
                .help("更多操作")
            }
        }
    }
}
