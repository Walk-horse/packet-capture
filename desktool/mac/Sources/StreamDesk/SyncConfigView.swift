import SwiftUI

/// 同步配置面板：地址填写、USB 设备选择（多台时选取）、自动/间隔策略。
/// 设备「选择方式」收敛到此处，不再散落在工具栏。
struct SyncConfigView: View {
    @EnvironmentObject var client: SyncClient
    @Environment(\.dismiss) private var dismiss
    @State private var detecting = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top) {
                Text("同步设置")
                    .font(.headline)
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .symbolRenderingMode(.hierarchical)
                        .font(.title3)
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .help("关闭")
                .keyboardShortcut(.escape, modifiers: [])
            }

            // 手机地址
            VStack(alignment: .leading, spacing: 6) {
                Text("手机同步地址")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                HStack {
                    TextField("如 192.168.1.20:17890 或 127.0.0.1:17890", text: $client.address)
                        .textFieldStyle(.roundedBorder)
                    Button("保存") {
                        client.saveAddress()
                        Task { await client.resetAndPull() }
                    }
                }
                HStack(spacing: 10) {
                    Image(systemName: client.wsConnected ? "bolt.fill" : "bolt")
                        .foregroundStyle(client.wsConnected ? .green : .secondary)
                    Text(client.wsConnected ? "推送通道已连接" : "推送通道未连接")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(client.status.text)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            Divider()

            // USB 设备选择
            VStack(alignment: .leading, spacing: 8) {
                Text("USB 设备")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Button {
                    detecting = true
                    Task {
                        await client.detectUsb()
                        detecting = false
                    }
                } label: {
                    Label(detecting ? "探测中…" : "通过 USB 探测并选择手机", systemImage: "cable.connector")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .disabled(detecting)

                if client.needsPick {
                    Text("检测到多台设备，请选择要抓取的手机：")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
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
                                Spacer()
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                } else if !(client.selectedSerial ?? "").isEmpty {
                    Text("当前：已选择 \(client.selectedSerial ?? "")")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                } else if client.devices.isEmpty {
                    Text("尚未探测到设备。请连接 USB 并开启调试授权，点上方按钮探测。")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            Divider()

            // 自动同步策略
            VStack(alignment: .leading, spacing: 8) {
                Text("同步策略")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Toggle("自动同步", isOn: Binding(
                    get: { client.autoSync },
                    set: { client.setAutoSync($0) }
                ))
                .toggleStyle(.switch)
                .controlSize(.small)
                Picker("轮询间隔", selection: Binding(
                    get: { client.interval },
                    set: { client.setInterval($0) }
                )) {
                    Text("1s").tag(1.0)
                    Text("2s").tag(2.0)
                    Text("5s").tag(5.0)
                }
                .pickerStyle(.segmented)
                .controlSize(.small)
                .disabled(!client.autoSync)
                Text("说明：保留 HTTP 轮询兜底，同时启用手机端 WebSocket 实时增量推送。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            Spacer()
        }
        .padding(20)
        .frame(width: 360, height: 420)
    }
}
