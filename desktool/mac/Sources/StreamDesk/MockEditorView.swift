import SwiftUI

/// 接口模拟：单条规则配置页（右栏）。
/// 编辑即写回本地存储；改完点列表上的「推送配置到手机」下发。
struct MockEditorView: View {
    let ruleId: String?

    @EnvironmentObject var store: MockRuleStore
    @EnvironmentObject var client: SyncClient

    @State private var draft: MockRule?
    @State private var pushMsg: String?
    @State private var pushing = false
    @State private var showYapi = false
    /// 勾选后：示例里的 JSON 自动按缩进美化（不改变字段顺序）
    @AppStorage("mock.autoPrettyJson") private var autoPrettyJson = false
    @FocusState private var bodyFocused: Bool

    var body: some View {
        Group {
            if draft != nil, store.rule(ruleId) != nil {
                editor
            } else {
                VStack(spacing: 8) {
                    Image(systemName: "square.and.pencil")
                        .font(.system(size: 30))
                        .foregroundStyle(.secondary)
                    Text("选择左侧的一条接口规则进行配置")
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: ruleId) {
            draft = store.rule(ruleId)
            if autoPrettyJson { _ = prettyBody(quiet: true) }
        }
        .onChange(of: autoPrettyJson) { on in
            if on { _ = prettyBody(quiet: true) }
        }
        .onChange(of: bodyFocused) { focused in
            // 编辑结束后（失焦）再美化，避免打字过程中光标跳动
            if !focused, autoPrettyJson { _ = prettyBody(quiet: true) }
        }
    }

    // MARK: - 表单

    /// 面板本身不滚动：表单项固定，只有「响应示例」编辑区吸收剩余高度并内部滚动。
    private var editor: some View {
        VStack(alignment: .leading, spacing: 14) {
            headerBlock
            matchBlock
            responseBlock
            footerBlock
        }
        .padding(16)
        .frame(minWidth: 420, maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .sheet(isPresented: $showYapi) {
            MockYapiSheet(
                onApply: { f, host in
                    let id = ruleId ?? draft?.id ?? ""
                    store.apply(f, to: id, fillHost: host)
                    draft = store.rule(id)
                    showYapi = false
                    if autoPrettyJson { _ = prettyBody(quiet: true) }
                    pushMsg = "已从 YAPI 拉取：\(f.summary)"
                },
                onCancel: { showYapi = false },
                applyTitle: "应用到当前规则",
                prefillYapiId: draft?.yapiId
            )
            .environmentObject(store)
            .environmentObject(client)
        }
    }

    private var headerBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                TextField("规则备注（可选）", text: bind(\.name))
                    .textFieldStyle(.roundedBorder)
                Toggle("启用", isOn: bind(\.enabled))
                    .toggleStyle(.switch)
                    .controlSize(.small)
            }
            Text("匹配 method + host + path 的请求，由手机端直接返回下面的响应，不再访问真实服务器。")
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
        }
    }

    private var matchBlock: some View {
        GroupBox(label: Label("匹配条件", systemImage: "line.3.horizontal.decrease.circle")) {
            Grid(alignment: .leadingFirstTextBaseline, horizontalSpacing: 10, verticalSpacing: 8) {
                GridRow {
                    Text("方法").gridColumnAlignment(.trailing).foregroundStyle(.secondary)
                    Picker("", selection: bind(\.method)) {
                        Text("任意 *").tag("*")
                        Text("GET").tag("GET")
                        Text("POST").tag("POST")
                        Text("PUT").tag("PUT")
                        Text("PATCH").tag("PATCH")
                        Text("DELETE").tag("DELETE")
                        Text("HEAD").tag("HEAD")
                        Text("OPTIONS").tag("OPTIONS")
                    }
                    .labelsHidden()
                    .frame(width: 130)
                }
                GridRow {
                    Text("Host").gridColumnAlignment(.trailing).foregroundStyle(.secondary)
                    TextField("api.example.com（支持 *.example.com）", text: bind(\.host))
                        .textFieldStyle(.roundedBorder)
                }
                GridRow {
                    Text("Path").gridColumnAlignment(.trailing).foregroundStyle(.secondary)
                    TextField("/api/user（支持 /api/* 通配；留空 = 不限 path；勿带 query）", text: bind(\.path))
                        .textFieldStyle(.roundedBorder)
                }
                GridRow {
                    Text("").gridColumnAlignment(.trailing)
                    Text("匹配优先级：精确 path > 通配 > 未配置，避免宽规则吞掉明细接口。")
                        .font(.system(size: 10))
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.top, 4)
        }
    }

    private var responseBlock: some View {
        GroupBox(label: Label("模拟响应", systemImage: "arrow.turn.down.right")) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    Text("状态码").foregroundStyle(.secondary)
                    TextField("200", value: bind(\.statusCode), format: .number)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 70)
                    Menu("常用") {
                        ForEach([200, 201, 202, 204, 301, 302, 400, 401, 403, 404, 500, 502, 503, 504], id: \.self) { c in
                            Button("\(c)") { set(\.statusCode, c) }
                        }
                    }
                    .fixedSize()

                    Text("延迟").foregroundStyle(.secondary)
                    TextField("0", value: bind(\.delayMs), format: .number)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 70)
                    Text("ms").foregroundStyle(.secondary)
                    Spacer()
                }

                HStack(spacing: 10) {
                    Text("Content-Type").foregroundStyle(.secondary)
                    TextField("application/json", text: bind(\.contentType))
                        .textFieldStyle(.roundedBorder)
                    Menu("常用") {
                        ForEach(["application/json", "text/plain", "text/html", "application/xml",
                                 "application/javascript", "application/x-www-form-urlencoded",
                                 "application/octet-stream"], id: \.self) { ct in
                            Button(ct) { set(\.contentType, ct) }
                        }
                    }
                    .fixedSize()
                }

                HStack(spacing: 10) {
                    Text("自定义头").foregroundStyle(.secondary)
                    Text("每行一个，形如 X-Mock: 1")
                        .font(.system(size: 10))
                        .foregroundStyle(.secondary)
                    Spacer()
                }
                TextEditor(text: headersText)
                    .font(.system(size: 11, design: .monospaced))
                    .frame(height: 54)
                    .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color.primary.opacity(0.15)))

                Divider()

                HStack(spacing: 8) {
                    Text("响应示例").foregroundStyle(.secondary)
                    if let yid = draft?.yapiId, !yid.isEmpty {
                        Text("来源 YAPI #\(yid)")
                            .font(.system(size: 10))
                            .foregroundStyle(.blue)
                            .padding(.horizontal, 5).padding(.vertical, 1)
                            .background(Color.blue.opacity(0.12), in: RoundedRectangle(cornerRadius: 4))
                            .help("该规则的响应示例来自 YAPI 接口 #\(yid)")
                    }
                    Spacer()
                    Button {
                        showYapi = true
                    } label: {
                        Label(draft?.yapiId?.isEmpty == false ? "重新从 YAPI 获取" : "从 YAPI 获取",
                              systemImage: "arrow.down.doc")
                    }
                    .controlSize(.small)
                    .help("输入 YAPI 接口 ID 与 Token，拉取接口的 method/path 与响应示例")
                    Button("按响应类型生成") { generateFromType() }
                        .controlSize(.small)
                        .help("用抓到的同接口响应作为结构样本，按类型生成占位示例")
                    Button("从抓包填充") { fillFromCapture(useOriginal: true) }
                        .controlSize(.small)
                        .help("直接用抓包到的响应原文作为示例")
                    Button("清空") { set(\.body, ""); set(\.yapiId, nil); pushMsg = "已清空：将由手机端自动生成示例" }
                        .controlSize(.small)
                }

                TextEditor(text: bind(\.body))
                    .font(.system(size: 11.5, design: .monospaced))
                    .focused($bodyFocused)
                    .frame(minHeight: 120)
                    .frame(maxHeight: .infinity)   // 吸收剩余高度，滚动条落在这里
                    .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color.primary.opacity(0.15)))

                HStack(spacing: 8) {
                    Text(bodySizeText)
                        .font(.system(size: 10))
                        .foregroundStyle(draft?.usesCustomBody == true ? Color.secondary : Color.orange)
                        .help(draft?.usesCustomBody == true
                              ? "命中后手机端返回上面的示例"
                              : "示例为空：手机端会按接口响应数据类型自动生成示例")
                    jsonBadge
                    Spacer()
                    Toggle("JSON 美化", isOn: $autoPrettyJson)
                        .toggleStyle(.checkbox)
                        .controlSize(.small)
                        .help("勾选后自动美化示例里的 JSON：从 YAPI 拉取、填充抓包或编辑结束时生效（保留字段原有顺序）")
                    if !autoPrettyJson {
                        Button("压缩") { minifyBody() }
                            .controlSize(.small)
                            .help("去掉缩进与换行，压成一行 JSON")
                        Button("JSON 美化") { _ = prettyBody() }
                            .controlSize(.small)
                            .help("按 JSON 缩进展开，保留字段原有顺序")
                    }
                }

                Divider()

                HStack(spacing: 8) {
                    if let pushMsg {
                        Text(pushMsg)
                            .font(.system(size: 10))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer()
                    Button {
                        Task {
                            pushing = true
                            let msg = await client.pushMock(store.rules)
                            pushMsg = msg
                            pushing = false
                        }
                    } label: {
                        if pushing { ProgressView().controlSize(.small) }
                        else { Text("推送全部规则到手机") }
                    }
                    .controlSize(.small)
                    .disabled(pushing)
                }
            }
            .padding(.top, 4)
            .frame(maxHeight: .infinity, alignment: .top)
        }
        .frame(maxHeight: .infinity)
    }

    private var footerBlock: some View {
        Text("提示：手机端需开启「接口模拟」总开关，并在应用列表里为该应用打开开关；HTTPS 接口需先在手机上安装并信任抓包证书。")
            .font(.system(size: 11))
            .foregroundStyle(.secondary)
    }

    private var bodySizeText: String {
        let n = draft?.body.utf8.count ?? 0
        if n == 0 { return "示例为空（自动生成）" }
        return "示例 \(fmtSize(n))"
    }

    /// JSON 合法性徽标（非 JSON 内容不显示）
    @ViewBuilder
    private var jsonBadge: some View {
        switch jsonState {
        case .none:
            EmptyView()
        case .valid:
            Text("JSON 合法")
                .font(.system(size: 10))
                .foregroundStyle(.green)
        case .invalid:
            Text("JSON 有误")
                .font(.system(size: 10))
                .foregroundStyle(.orange)
        }
    }

    private enum JsonState { case none, valid, invalid }

    private var jsonState: JsonState {
        let text = (draft?.body ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return .none }
        let looksJson = text.hasPrefix("{") || text.hasPrefix("[")
            || (draft?.contentType.lowercased().contains("json") ?? false)
        guard looksJson else { return .none }
        return parseOrderedJSON(text) != nil ? .valid : .invalid
    }

    /// 美化示例里的 JSON。quiet = true 时用于「自动美化」，失败/无变化不刷提示。
    @discardableResult
    private func prettyBody(quiet: Bool = false) -> Bool {
        guard let d = draft else { return false }
        let text = d.body.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty {
            if !quiet { pushMsg = "示例为空，无需美化" }
            return false
        }
        guard let out = YapiSchema.pretty(text) else {
            if !quiet {
                pushMsg = "不是合法 JSON，无法美化（XML / HTML / 纯文本示例保持原样）"
            }
            return false
        }
        guard out != d.body else { return true }   // 已经美化过，不写回（避免失焦时反复触发存储写入）
        set(\.body, out)
        if !quiet { pushMsg = "已按 JSON 美化（保留字段原有顺序）" }
        return true
    }

    /// 自动美化开关打开时，对即将写入的文本先做一次美化（非 JSON 原样返回）
    private func autoPrettied(_ text: String) -> String {
        guard autoPrettyJson else { return text }
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, let out = YapiSchema.pretty(t) else { return text }
        return out
    }

    private func minifyBody() {
        guard let d = draft else { return }
        let text = d.body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { pushMsg = "示例为空，无需压缩"; return }
        guard let out = YapiSchema.minify(text) else {
            pushMsg = "不是合法 JSON，无法压缩"
            return
        }
        set(\.body, out)
        pushMsg = "已压缩为单行 JSON"
    }

    // MARK: - 绑定与动作

    private func bind<T>(_ kp: WritableKeyPath<MockRule, T>) -> Binding<T> {
        Binding(
            get: { (draft ?? MockRule())[keyPath: kp] },
            set: { v in
                guard var d = draft else { return }
                d[keyPath: kp] = v
                draft = d
                store.update(d)
            }
        )
    }

    private func set<T>(_ kp: WritableKeyPath<MockRule, T>, _ value: T) {
        guard var d = draft else { return }
        d[keyPath: kp] = value
        draft = d
        store.update(d)
    }

    private var headersText: Binding<String> {
        Binding(
            get: {
                (draft?.headers ?? [])
                    .map { $0.count >= 2 ? "\($0[0]): \($0[1])" : ($0.first ?? "") }
                    .joined(separator: "\n")
            },
            set: { v in
                guard var d = draft else { return }
                d.headers = v.split(separator: "\n", omittingEmptySubsequences: true).compactMap { line in
                    guard let i = line.firstIndex(of: ":") else { return nil }
                    let k = String(line[..<i]).trimmingCharacters(in: .whitespaces)
                    let val = String(line[line.index(after: i)...]).trimmingCharacters(in: .whitespaces)
                    return k.isEmpty ? nil : [k, val]
                }
                draft = d
                store.update(d)
            }
        )
    }

    /// 找到与当前规则匹配的最近一条抓包记录（作为示例来源）
    private func matchedCapture() -> ExchangeRecord? {
        guard let d = draft else { return nil }
        let target = d.path.split(separator: "?").first.map(String.init) ?? d.path
        return client.exchanges.first { e in
            !e.responseData.isEmpty &&
            (d.method == "*" || e.method.caseInsensitiveCompare(d.method) == .orderedSame) &&
            (d.host.isEmpty || e.host.caseInsensitiveCompare(d.host) == .orderedSame) &&
            (target.isEmpty || (e.path.split(separator: "?").first.map(String.init) ?? e.path) == target)
        }
    }

    private func generateFromType() {
        guard let d = draft else { return }
        let template = matchedCapture()?.responseData
        let sample = MockSample.generate(contentType: d.contentType, template: template)
        set(\.body, autoPrettied(sample))
        pushMsg = template == nil ? "已按 Content-Type 生成示例" : "已按抓到的响应结构生成示例"
    }

    private func fillFromCapture(useOriginal: Bool) {
        guard draft != nil else { return }
        guard let e = matchedCapture() else {
            pushMsg = "没有找到匹配的抓包响应，可先用「按响应类型生成」"
            return
        }
        let text = String(data: e.responseData, encoding: .utf8) ?? ""
        guard !text.isEmpty else {
            pushMsg = "该响应不是可读文本，已改为按类型生成"
            generateFromType()
            return
        }
        set(\.body, autoPrettied(text))
        pushMsg = useOriginal ? "已用抓包响应原文填充" : "已填充"
    }
}
