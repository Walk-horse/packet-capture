import SwiftUI

struct ContentView: View {
    @EnvironmentObject var client: SyncClient
    @State private var panel: Panel = .all
    @State private var selectedId: String?
    @State private var search = ""

    var body: some View {
        NavigationSplitView {
            SidebarView(panel: $panel)
        } content: {
            RequestListView(panel: panel, selectedId: $selectedId, search: $search)
        } detail: {
            DetailView(id: selectedId)
        }
        .toolbar { toolbarContent }
        .onAppear { client.startIfNeeded() }
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
                    Task { await client.detectUsb() }
                } label: {
                    Image(systemName: "cable.connector")
                }
                .controlSize(.small)
                .help("通过 USB 探测手机地址（连接多台时可选择）")
                .popover(isPresented: $client.needsPick, arrowEdge: .bottom) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("选择要抓取的手机")
                            .font(.headline)
                            .padding(.bottom, 2)
                        ForEach(client.devices) { dev in
                            Button {
                                Task { await client.selectDevice(dev) }
                                client.needsPick = false
                            } label: {
                                HStack(spacing: 6) {
                                    if client.selectedSerial == dev.serial {
                                        Image(systemName: "checkmark").foregroundStyle(.blue)
                                    }
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(dev.model.isEmpty ? dev.serial : dev.model)
                                            .font(.system(size: 12))
                                        if !dev.model.isEmpty {
                                            Text(dev.serial)
                                                .font(.caption2)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(12)
                    .frame(minWidth: 220)
                }
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

                Menu {
                    Button("全量重新同步") { Task { await client.resetAndPull() } }
                    Button("清空本地数据") { client.clearLocal() }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .controlSize(.small)
                .help("更多操作")
            }
        }
    }
}
