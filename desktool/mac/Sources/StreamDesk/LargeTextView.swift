import SwiftUI
import AppKit

/// 大文本渲染视图：用 NSTextView 承载，避免 SwiftUI Text 在 MB 级文本上的布局卡顿。
/// 关键优化：开启 allowsNonContiguousLayout，仅布局可见区域，MB 级文本切换/滚动不再卡主线程。
/// 附带搜索：query 非空时高亮全部匹配（黄色），并按 matchIndex 滚动到当前匹配。
struct LargeTextView: NSViewRepresentable {
    let text: String
    var fontSize: CGFloat = 12
    var query: String = ""
    var matchIndex: Int = 0
    var matchCount: Binding<Int>

    final class Coordinator: NSObject {
        var prevText = ""
        var prevQuery = ""
        var prevIndex = -1
        var prevCount = -1
        var ranges: [NSRange] = []
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

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
        // 核心优化：非连续布局，避免一次性全量排版 MB 级文本
        tv.layoutManager?.allowsNonContiguousLayout = true
        if let container = tv.textContainer {
            container.containerSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
            container.widthTracksTextView = true
        }
        tv.autoresizingMask = [.width]

        scroll.documentView = tv
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        guard let tv = scroll.documentView as? NSTextView,
              let lm = tv.layoutManager,
              let ts = tv.textStorage else { return }
        let co = context.coordinator

        // 文本变化：整体替换并回到顶部（强制后续重算高亮）
        if tv.string != text {
            ts.replaceCharacters(in: NSRange(location: 0, length: ts.length), with: text)
            tv.scrollToBeginningOfDocument(nil)
            co.prevText = text
            co.prevQuery = ""
            co.prevCount = -1
            co.ranges = []
        }

        // 搜索词变化：重算高亮与匹配区间
        if query != co.prevQuery {
            applyHighlight(tv: tv, lm: lm, ts: ts, co: co)
            co.prevQuery = query
        }

        // 当前匹配序号变化：滚动到对应区间（无需重算）
        if matchIndex != co.prevIndex, !co.ranges.isEmpty {
            let idx = min(max(matchIndex, 0), co.ranges.count - 1)
            tv.scrollRangeToVisible(co.ranges[idx])
            tv.setSelectedRange(co.ranges[idx])
            co.prevIndex = matchIndex
        }
    }

    private func applyHighlight(tv: NSTextView, lm: NSLayoutManager, ts: NSTextStorage, co: Coordinator) {
        let full = NSRange(location: 0, length: ts.length)
        lm.removeTemporaryAttribute(.backgroundColor, forCharacterRange: full)
        guard !query.isEmpty else {
            co.ranges = []
            co.prevCount = 0
            matchCount.wrappedValue = 0
            return
        }
        // 直接在原串上做大小写不敏感搜索：lowercased 后下标会与原串错位（Unicode），导致高亮区间不准
        let s = ts.string
        let q = query
        var ranges: [NSRange] = []
        var searchStart = s.startIndex
        while let r = s.range(of: q, options: [.caseInsensitive, .diacriticInsensitive], range: searchStart..<s.endIndex) {
            ranges.append(NSRange(r, in: s))
            searchStart = r.upperBound
            if ranges.count >= 5000 { break } // 上限保护，避免极端情况卡顿
        }
        // 淡黄色高亮
        let hl = NSColor(srgbRed: 1.0, green: 0.98, blue: 0.65, alpha: 1.0)
        for r in ranges {
            lm.addTemporaryAttribute(.backgroundColor, value: hl, forCharacterRange: r)
        }
        co.ranges = ranges
        let count = ranges.count
        if count != co.prevCount {
            matchCount.wrappedValue = count
            co.prevCount = count
        }
        co.prevIndex = -1 // 触发下次滚动到首个匹配
    }
}
