import SwiftUI
import AppKit

/// 可编辑大文本视图：用 NSTextView 承载，支持就地编辑，并复用「只读」LargeTextView 的搜索高亮 / 跳转能力。
/// - text：与外部状态双向绑定（body 文本）
/// - query / matchIndex / matchCount：搜索词、当前匹配序号、命中总数
struct EditableTextView: NSViewRepresentable {
    @Binding var text: String
    var fontSize: CGFloat = 12
    var query: String = ""
    var exactMatch: Bool = false
    var matchIndex: Binding<Int> = .constant(0)
    var matchCount: Binding<Int> = .constant(0)
    /// 编辑结束（失焦）回调，用于「JSON 美化」自动美化等
    var onEditingEnded: () -> Void = {}

    final class Coordinator: NSObject, NSTextViewDelegate {
        var parent: EditableTextView
        var prevText = ""
        var prevQuery = ""
        var prevIndex = -1
        var prevCount = -1
        var ranges: [NSRange] = []
        var onEditingEnded: () -> Void = {}

        init(_ parent: EditableTextView) { self.parent = parent }

        func textDidChange(_ notification: Notification) {
            guard let tv = notification.object as? NSTextView else { return }
            let s = tv.string
            if s != parent.text { parent.text = s }
        }

        func textDidEndEditing(_ notification: Notification) {
            onEditingEnded()
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeNSView(context: Context) -> NSScrollView {
        let scroll = NSScrollView()
        scroll.hasVerticalScroller = true
        scroll.hasHorizontalScroller = true
        scroll.autohidesScrollers = true
        scroll.drawsBackground = false
        scroll.borderType = .noBorder

        let tv = NSTextView()
        tv.isEditable = true
        tv.isSelectable = true
        tv.isRichText = false
        tv.allowsUndo = true
        tv.usesFindBar = false
        tv.isAutomaticSpellingCorrectionEnabled = false
        tv.isGrammarCheckingEnabled = false
        tv.backgroundColor = .clear
        tv.textColor = .labelColor
        tv.font = NSFont.monospacedSystemFont(ofSize: fontSize, weight: .regular)
        tv.isVerticallyResizable = true
        tv.isHorizontallyResizable = false
        tv.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
        tv.autoresizingMask = [.width, .height]
        tv.layoutManager?.allowsNonContiguousLayout = true
        if let container = tv.textContainer {
            container.containerSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
            container.widthTracksTextView = true
        }
        tv.delegate = context.coordinator
        tv.textStorage?.replaceCharacters(in: NSRange(location: 0, length: 0), with: text)
        tv.scrollToBeginningOfDocument(nil)

        scroll.documentView = tv
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        let co = context.coordinator
        co.parent = self   // 保持 binding 最新（struct 每帧重建，coordinator 持久）
        co.onEditingEnded = onEditingEnded   // 每帧刷新回调，确保闭包捕获最新状态
        guard let tv = scroll.documentView as? NSTextView,
              let lm = tv.layoutManager,
              let ts = tv.textStorage else { return }

        // 外部改动（YAPI 填充 / 生成 / 树编辑回写）：整体替换并回到顶部
        if tv.string != text {
            ts.replaceCharacters(in: NSRange(location: 0, length: ts.length), with: text)
            tv.scrollToBeginningOfDocument(nil)
            tv.setSelectedRange(NSRange(location: 0, length: 0))
            co.prevText = text
            co.prevQuery = ""
            co.prevCount = -1
            co.ranges = []
        }

        // 搜索词变化：重算高亮与命中区间
        if query != co.prevQuery {
            applyHighlight(tv: tv, lm: lm, ts: ts, co: co)
            co.prevQuery = query
        }

        // 当前匹配序号变化：滚动并选中对应区间
        if matchIndex.wrappedValue != co.prevIndex, !co.ranges.isEmpty {
            let idx = min(max(matchIndex.wrappedValue, 0), co.ranges.count - 1)
            tv.scrollRangeToVisible(co.ranges[idx])
            tv.setSelectedRange(co.ranges[idx])
            co.prevIndex = matchIndex.wrappedValue
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
        let s = ts.string
        let q = query
        let options: String.CompareOptions = exactMatch ? [.literal] : [.caseInsensitive, .diacriticInsensitive]
        var ranges: [NSRange] = []
        var searchStart = s.startIndex
        while let r = s.range(of: q, options: options, range: searchStart..<s.endIndex) {
            searchStart = r.upperBound
            if exactMatch, !isEditableWordBoundaryMatch(s, r) { continue }
            ranges.append(NSRange(r, in: s))
            if ranges.count >= 5000 { break }
        }
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
        co.prevIndex = -1
    }
}

private func isEditableWordBoundaryMatch(_ s: String, _ r: Range<String.Index>) -> Bool {
    let wordChars = CharacterSet.alphanumerics.union(.init(charactersIn: "_"))
    if r.lowerBound != s.startIndex {
        let prev = s.index(before: r.lowerBound)
        let prevSet = CharacterSet(charactersIn: String(s[prev]))
        if wordChars.isSuperset(of: prevSet) { return false }
    }
    if r.upperBound != s.endIndex {
        let nextSet = CharacterSet(charactersIn: String(s[r.upperBound]))
        if wordChars.isSuperset(of: nextSet) { return false }
    }
    return true
}
