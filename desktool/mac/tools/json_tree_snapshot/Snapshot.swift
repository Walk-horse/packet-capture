import AppKit
import SwiftUI

// 离屏渲染两棵 JSON 树（只读 JsonTreeView / 可编辑 EditableJsonTreeView）并导出 PNG，
// 用于在无截屏权限的环境下校验「层级缩进」等纯视觉问题。
// 用法：./run.sh [宽] [高] [输出前缀]   默认 620x760 → /tmp/json_tree_readonly.png / _editable.png

@main
struct TreeSnapshotRunner {
    @MainActor
    static func main() {
        let args = CommandLine.arguments
        let width = args.count > 1 ? (Double(args[1]) ?? 620) : 620
        let height = args.count > 2 ? (Double(args[2]) ?? 760) : 760
        let prefix = args.count > 3 ? args[3] : "/tmp/json_tree"

        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)

        // 4 层嵌套 + 数组 + 数组内对象，覆盖缩进校验所需的全部层级形态
        let json = #"""
        {"data":[{"id":1,"name":"a"},{"id":2}],"func":{"buttonSort":["canBuyAgain","canDelete"],"inner":{"deep":{"leaf":"x","n":1},"arr":["p","q"]}},"q0":{"canBalancePage":false,"canBuyAgain":true,"z":null}}
        """#

        let obj = try! JSONSerialization.jsonObject(with: json.data(using: .utf8)!)
        var counter = 0
        let root = buildJsonNode(obj, counter: &counter)

        // 1) 只读树（详情 body 用的那棵）
        render(
            AnyView(JsonTreeView(root: root).frame(width: width, height: height)),
            size: NSSize(width: width, height: height),
            out: "\(prefix)_readonly.png"
        )

        // 2) 可编辑树（模拟响应示例用的那棵）
        let editRoot = EditJsonNode.from(parseOrderedJSON(json)!)
        render(
            AnyView(EditableJsonTreeView(root: editRoot).frame(width: width, height: height)),
            size: NSSize(width: width, height: height),
            out: "\(prefix)_editable.png"
        )
    }

    @MainActor
    private static func render(_ view: AnyView, size: NSSize, out: String) {
        let hosting = NSHostingView(rootView: view)
        hosting.frame = NSRect(origin: .zero, size: size)

        let window = NSWindow(
            contentRect: hosting.frame,
            styleMask: [.titled, .resizable],
            backing: .buffered,
            defer: false
        )
        window.contentView = hosting
        window.makeKeyAndOrderFront(nil)

        let deadline = Date().addingTimeInterval(1.2)
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
        try! png.write(to: URL(fileURLWithPath: out))
        print("已写出快照：\(out)  (\(Int(size.width))x\(Int(size.height)))")
    }
}
