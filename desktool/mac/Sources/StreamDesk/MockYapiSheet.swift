import SwiftUI

/// 接口模拟：「从 YAPI 获取」面板。
/// 输入接口 ID + Token（可选 UID）→ 拉取接口定义 → 自动把 res_body（JSON Schema 或 raw 示例）
/// 转成响应示例，并尽量从抓包记录里匹配出 host，最后创建新规则或写入当前规则。
struct MockYapiSheet: View {
    /// 结果应用回调：(YAPI 结果, host)
    let onApply: (YapiFetched, String) -> Void
    let onCancel: () -> Void
    /// 底部按钮文案（新建 = 创建规则；编辑页 = 应用到当前规则）
    var applyTitle: String = "创建规则"
    /// 编辑页进来时带的当前规则 YAPI ID（用于预填 / 重新拉取）
    var prefillYapiId: String? = nil

    @EnvironmentObject var settings: YapiSettings
    @EnvironmentObject var store: MockRuleStore
    @EnvironmentObject var client: SyncClient

    @State private var service = ""
    @State private var id = ""
    @State private var token = ""
    @State private var uid = ""
    @State private var remember = true
    @State private var showToken = false
    @State private var showAdvanced = false
    @State private var loading = false
    @State private var error: String?
    @State private var fetched: YapiFetched?
    @State private var host = ""
    @State private var hostHinted = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    form
                    if let error { errorBlock(error) }
                    if let fetched { resultBlock(fetched) }
                }
                .padding(14)
            }
            Divider()
            footer
        }
        .frame(width: 580, height: 560)
        .onAppear(perform: loadSettings)
    }

    // MARK: - 头部 / 底部

    private var header: some View {
        HStack(spacing: 8) {
            Image(systemName: "arrow.down.doc")
                .foregroundStyle(.blue)
            VStack(alignment: .leading, spacing: 1) {
                Text("从 YAPI 获取接口")
                    .font(.system(size: 13, weight: .semibold))
                Text("拉取接口的 method / path 与响应定义，自动生成响应示例（JSON 会保持 YAPI 上的字段顺序）")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color.primary.opacity(0.04))
    }

    private var footer: some View {
        HStack(spacing: 8) {
            if settings.hasSavedToken {
                Button("清除已保存的 Token") {
                    settings.forgetToken()
                    token = ""
                    uid = ""
                }
                .controlSize(.small)
                .help("从本机设置中删除已保存的 YAPI Token")
            }
            Spacer()
            Button("取消") { onCancel() }
                .controlSize(.small)
            Button(applyTitle) {
                if let f = fetched { onApply(f, host.trimmingCharacters(in: .whitespaces)) }
            }
            .controlSize(.small)
            .keyboardShortcut(.defaultAction)
            .disabled(fetched == nil)
            .help(fetched == nil ? "先拉取到接口后才能应用" : "用拉取到的接口信息创建/更新规则")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    // MARK: - 表单

    private var form: some View {
        VStack(alignment: .leading, spacing: 10) {
            Grid(alignment: .leadingFirstTextBaseline, horizontalSpacing: 10, verticalSpacing: 8) {
                GridRow {
                    Text("服务地址").gridColumnAlignment(.trailing).foregroundStyle(.secondary)
                    TextField("https://stp.haier.net", text: $service)
                        .textFieldStyle(.roundedBorder)
                }
                GridRow {
                    Text("接口 ID").gridColumnAlignment(.trailing).foregroundStyle(.secondary)
                    HStack(spacing: 8) {
                        TextField("YAPI 接口 ID，如 189868", text: $id)
                            .textFieldStyle(.roundedBorder)
                            .onSubmit { Task { await fetch() } }
                        Text("对应 /api/interface/get?id=")
                            .font(.system(size: 10))
                            .foregroundStyle(.secondary)
                    }
                }
                GridRow {
                    Text("Token").gridColumnAlignment(.trailing).foregroundStyle(.secondary)
                    HStack(spacing: 6) {
                        Group {
                            if showToken {
                                TextField("_yapi_token 值，或整段 Cookie", text: $token)
                            } else {
                                SecureField("_yapi_token 值，或整段 Cookie", text: $token)
                            }
                        }
                        .textFieldStyle(.roundedBorder)
                        .onSubmit { Task { await fetch() } }

                        Button {
                            showToken.toggle()
                        } label: {
                            Image(systemName: showToken ? "eye.slash" : "eye")
                        }
                        .controlSize(.small)
                        .help(showToken ? "隐藏 Token" : "显示 Token")
                    }
                }
                GridRow {
                    Text("").gridColumnAlignment(.trailing)
                    HStack(spacing: 12) {
                        Toggle("记住 Token（保存在本机）", isOn: $remember)
                            .toggleStyle(.checkbox)
                            .font(.system(size: 11))
                        Button(showAdvanced ? "收起高级" : "高级（UID）") { showAdvanced.toggle() }
                            .buttonStyle(.link)
                            .font(.system(size: 11))
                    }
                }
                if showAdvanced {
                    GridRow {
                        Text("UID").gridColumnAlignment(.trailing).foregroundStyle(.secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            TextField("_yapi_uid，如 4472", text: $uid)
                                .textFieldStyle(.roundedBorder)
                            Text("只填 Token 时需要 UID；若 Token 栏直接粘贴整段 Cookie 则无需填写。")
                                .font(.system(size: 10))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                GridRow {
                    Text("Host").gridColumnAlignment(.trailing).foregroundStyle(.secondary)
                    VStack(alignment: .leading, spacing: 2) {
                        TextField("api.example.com（YAPI 只提供 path，可留空稍后补）", text: $host)
                            .textFieldStyle(.roundedBorder)
                        Text(hostHinted
                             ? "已按同名 path 的抓包记录自动填入，可修改。"
                             : "YAPI 不返回域名；若本地已有该 path 的抓包记录会自动填入，否则请手工填写或稍后在规则里补。")
                            .font(.system(size: 10))
                            .foregroundStyle(hostHinted ? Color.green : .secondary)
                    }
                }
            }

            HStack(spacing: 10) {
                Button {
                    Task { await fetch() }
                } label: {
                    if loading {
                        HStack(spacing: 6) {
                            ProgressView().controlSize(.small)
                            Text("拉取中…")
                        }
                    } else {
                        Label("拉取接口", systemImage: "arrow.clockwise")
                    }
                }
                .controlSize(.small)
                .disabled(loading)

                if !settings.lastId.isEmpty, settings.lastId != id {
                    Button("上次：\(settings.lastId)") { id = settings.lastId }
                        .controlSize(.small)
                        .help("填入上次拉取过的接口 ID")
                }
                Spacer()
            }

            Text("Token 获取：浏览器登录 YAPI → F12 → Network 里任一 /api/ 请求 → Request Headers 的 Cookie 整段复制（形如 _yapi_token=xxx; _yapi_uid=4472），直接粘贴到上面的 Token 栏即可，最省事且不易出错；也可只填 _yapi_token（需是完整的 JWT，别被截断）并在「高级」里补 UID。")
                .font(.system(size: 10))
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - 错误 / 结果

    private func errorBlock(_ msg: String) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
                .font(.system(size: 11))
            Text(msg)
                .font(.system(size: 11))
                .foregroundStyle(.primary)
                .textSelection(.enabled)
            Spacer()
        }
        .padding(8)
        .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 6))
    }

    private func resultBlock(_ f: YapiFetched) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "checkmark.seal.fill").foregroundStyle(.green).font(.system(size: 11))
                Text(f.title.isEmpty ? "（无标题）" : f.title)
                    .font(.system(size: 12, weight: .semibold))
                    .lineLimit(1)
                Spacer()
            }
            HStack(spacing: 6) {
                Text(f.method)
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 5).padding(.vertical, 1)
                    .background(methodColor(f.method), in: RoundedRectangle(cornerRadius: 4))
                Text(f.path)
                    .font(.system(size: 11, design: .monospaced))
                    .textSelection(.enabled)
                    .lineLimit(1)
                Spacer()
            }
            HStack(spacing: 10) {
                label("YAPI #\(f.id)")
                label(f.contentType)
                label(f.isJson ? "JSON 示例" : "文本示例")
                label("示例 \(fmtSize(f.body.utf8.count))")
                if let p = f.projectId { label("项目 \(p)") }
                if let s = f.status { label(s) }
            }
            Text("响应示例预览")
                .font(.system(size: 10))
                .foregroundStyle(.secondary)
            ScrollView {
                Text(f.body.isEmpty ? "（YAPI 未配置响应示例，规则将留空并由手机端自动生成）" : String(f.body.prefix(6000)))
                    .font(.system(size: 10.5, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(6)
            }
            .frame(height: 150)
            .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 6))
        }
        .padding(10)
        .background(Color.green.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
    }

    private func label(_ s: String) -> some View {
        Text(s)
            .font(.system(size: 10))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 5).padding(.vertical, 1)
            .background(Color.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 4))
    }

    // MARK: - 动作

    private func loadSettings() {
        service = settings.service
        token = settings.token
        uid = settings.uid
        remember = settings.remember
        id = settings.lastId
        // 编辑已有 YAPI 规则时，预填它自己的接口 ID（便于「重新拉取」）
        if let prefill = prefillYapiId, !prefill.isEmpty { id = prefill }
    }

    private func fetch() async {
        loading = true
        error = nil
        fetched = nil
        hostHinted = false
        settings.service = service
        settings.token = token
        settings.uid = uid
        settings.remember = remember
        settings.lastId = id.trimmingCharacters(in: .whitespacesAndNewlines)
        settings.persist()

        do {
            let f = try await YapiAPI.fetchInterface(service: service, id: id, token: token, uid: uid)
            fetched = f
            if host.trimmingCharacters(in: .whitespaces).isEmpty,
               let hint = store.hostHint(forPath: f.path, in: client.exchanges) {
                host = hint
                hostHinted = true
            }
        } catch {
            self.error = (error as? YapiError)?.message ?? error.localizedDescription
        }
        loading = false
    }
}
