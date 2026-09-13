import AppKit
import SwiftUI

// 离屏渲染 MockEditorView 并导出 PNG，用于在无法截屏（无屏幕录制权限）的环境下校验布局。
// 用法：./run.sh [宽] [高] [输出路径]
// 要点：
//   1) 用 @main + -parse-as-library，避免 main.swift 的顶层非隔离上下文访问 @MainActor 的 store/client；
//   2) SwiftUI 视图放进 NSWindow 后跑几圈 RunLoop 让布局/文本控件就绪；
//   3) 用 bitmapImageRepForCachingDisplay + cacheDisplay 自绘取图，不需要系统截屏权限。

@main
struct SnapshotRunner {
    @MainActor
    static func main() {
        let args = CommandLine.arguments
        let width = args.count > 1 ? (Double(args[1]) ?? 760) : 760
        let height = args.count > 2 ? (Double(args[2]) ?? 720) : 720
        let outPath = args.count > 3 ? args[3] : "/tmp/mock_editor.png"

        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)

        // 造一条带 JSON 示例的规则，最大化覆盖：状态码/延迟/Content-Type/自定义头/响应示例
        let store = MockRuleStore()
        store.replaceAll([])
        var seed = MockRule()
        seed.name = "用户详情（快照用例）"
        seed.method = "POST"
        seed.host = "api.example.com"
        seed.path = "/api/user/detail"
        seed.statusCode = 200
        seed.contentType = "application/json"
        seed.headers = [["X-Mock", "1"], ["X-Trace", "abc123"]]
        seed.body = #"{"code":0,"msg":"ok","data":{"id":1001,"name":"张三","vip":true,"tags":["vip","new"],"profile":{"city":"青岛","score":9.5},"items":[{"sku":"A1","qty":2}]}}"#
        seed.yapiId = "189868"
        let rule = store.add(seed)

        let client = SyncClient()
        let root = MockEditorView(ruleId: rule.id)
            .environmentObject(store)
            .environmentObject(client)

        let hosting = NSHostingView(rootView: root)
        hosting.frame = NSRect(x: 0, y: 0, width: width, height: height)

        let window = NSWindow(
            contentRect: hosting.frame,
            styleMask: [.titled, .closable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.contentView = hosting
        window.makeKeyAndOrderFront(nil)

        // 让 SwiftUI 完成布局 + NSTextView 内容渲染
        let deadline = Date().addingTimeInterval(1.6)
        while Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.02))
        }

        hosting.layoutSubtreeIfNeeded()
        guard let rep = hosting.bitmapImageRepForCachingDisplay(in: hosting.bounds) else {
            FileHandle.standardError.write("无法创建位图\n".data(using: .utf8)!)
            exit(1)
        }
        hosting.cacheDisplay(in: hosting.bounds, to: rep)
        guard let png = rep.representation(using: .png, properties: [:]) else {
            FileHandle.standardError.write("PNG 编码失败\n".data(using: .utf8)!)
            exit(1)
        }
        try! png.write(to: URL(fileURLWithPath: outPath))
        print("已写出快照：\(outPath)  (\(Int(width))x\(Int(height)))")
        print("规则数量：\(store.rules.count)，示例字节：\(rule.body.utf8.count)")
    }
}
