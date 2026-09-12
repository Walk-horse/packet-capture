import SwiftUI
import AppKit

/// 大文本渲染视图：用 NSTextView 承载，避免 SwiftUI Text 在 MB 级文本上的布局卡顿
struct LargeTextView: NSViewRepresentable {
    let text: String
    var fontSize: CGFloat = 12

    func makeNSView(context: Context) -> NSScrollView {
        let scroll = NSScrollView()
        scroll.hasVerticalScroller = true
        scroll.hasHorizontalScroller = true
        scroll.autohidesScrollers = true
        scroll.drawsBackground = false
        scroll.borderType = .noBorder

        let tv = NSTextView()
        tv.isEditable = false
        tv.isSelectable = true
        tv.isRichText = false
        tv.allowsUndo = false
        tv.usesFindBar = true
        tv.isAutomaticSpellingCorrectionEnabled = false
        tv.backgroundColor = .clear
        tv.textColor = .labelColor
        tv.font = NSFont.monospacedSystemFont(ofSize: fontSize, weight: .regular)
        tv.isVerticallyResizable = true
        tv.isHorizontallyResizable = false
        tv.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
        if let container = tv.textContainer {
            container.containerSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
            container.widthTracksTextView = true
        }
        tv.autoresizingMask = [.width]

        scroll.documentView = tv
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        guard let tv = scroll.documentView as? NSTextView else { return }
        if tv.string != text {
            tv.string = text
            tv.scrollToBeginningOfDocument(nil)
        }
        let font = NSFont.monospacedSystemFont(ofSize: fontSize, weight: .regular)
        if tv.font != font { tv.font = font }
    }
}
