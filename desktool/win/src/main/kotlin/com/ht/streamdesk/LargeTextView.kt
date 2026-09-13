package com.ht.streamdesk

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.text.DefaultHighlighter

/**
 * 大文本渲染视图：用 Swing `JTextArea` 承载。
 *
 * 与 mac 版用 `NSTextView` 同理 —— Compose 的 `Text` 在 MB 级文本上排版会明显卡顿，
 * 而 JTextArea 只做视口渲染，且天然支持选择/复制、不换行横向滚动。
 *
 * 搜索：query 非空时用 Highlighter 高亮全部匹配（淡黄色），并按 matchIndex 滚动到当前匹配。
 * 搜索直接在原串上做大小写不敏感匹配（与 mac 版口径一致），计数由 BodyView 统一计算。
 */
@Composable
fun LargeTextView(
    text: String,
    modifier: Modifier = Modifier,
    query: String = "",
    matchIndex: Int = 0,
    fontSize: Int = 12,
) {
    SwingPanel(
        background = Color.White,
        modifier = modifier.fillMaxSize(),
        factory = {
            val area = JTextArea().apply {
                isEditable = false
                isFocusable = true
                lineWrap = false
                wrapStyleWord = false
                font = Font(Font.MONOSPACED, Font.PLAIN, fontSize)
                border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
                background = java.awt.Color.WHITE
                foreground = java.awt.Color(0x1F, 0x23, 0x28)
                caretPosition = 0
            }
            JScrollPane(area).apply {
                border = null
                verticalScrollBar.unitIncrement = 16
                horizontalScrollBar.unitIncrement = 16
            }
        },
        update = { pane ->
            val area = pane.viewport.view as JTextArea
            val textChanged = area.text != text
            if (textChanged) {
                area.text = text
                area.caretPosition = 0
            }
            val prevQuery = area.getClientProperty("q") as? String
            val ranges: List<IntRange>
            if (textChanged || prevQuery != query) {
                area.highlighter.removeAllHighlights()
                ranges = findMatchRanges(text, query)
                if (ranges.isNotEmpty()) {
                    val painter = DefaultHighlighter.DefaultHighlightPainter(java.awt.Color(255, 250, 166))
                    for (r in ranges) {
                        runCatching { area.highlighter.addHighlight(r.first, r.last + 1, painter) }
                    }
                }
                area.putClientProperty("q", query)
                area.putClientProperty("ranges", ranges)
                area.putClientProperty("idx", -1) // 触发首次滚动到首个匹配
            } else {
                @Suppress("UNCHECKED_CAST")
                ranges = area.getClientProperty("ranges") as? List<IntRange> ?: emptyList()
            }
            // 当前匹配序号变化：选中并滚动到对应区间
            val prevIdx = area.getClientProperty("idx") as? Int ?: -1
            if (ranges.isNotEmpty() && matchIndex != prevIdx) {
                val r = ranges[matchIndex.coerceIn(0, ranges.size - 1)]
                area.select(r.first, r.last + 1)
                runCatching {
                    area.modelToView2D(r.first)?.let { area.scrollRectToVisible(it.bounds) }
                }
                area.putClientProperty("idx", matchIndex)
            }
        },
    )
}

/** 大小写不敏感、不重叠的匹配区间（上限 5000，避免极端情况卡顿） */
private fun findMatchRanges(s: String, q: String): List<IntRange> {
    if (s.isEmpty() || q.isEmpty()) return emptyList()
    val out = mutableListOf<IntRange>()
    var start = 0
    while (out.size < 5000) {
        val idx = s.indexOf(q, start, ignoreCase = true)
        if (idx < 0) break
        out.add(idx until (idx + q.length))
        start = idx + q.length
    }
    return out
}
