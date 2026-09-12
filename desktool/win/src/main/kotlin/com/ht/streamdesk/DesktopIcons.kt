package com.ht.streamdesk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 自绘矢量图标集（对齐 mac 版 SF Symbols 观感）。
 *
 * 为什么不用 Material Icons：`org.jetbrains.compose.material:material-icons-core` 只发到 1.7.3，
 * 与本工程的 Compose 1.8.2 不匹配；而 androidx 的 `material-icons-core-desktop` 会把
 * androidx.compose.ui 拖进来，与本工程 org.jetbrains.compose.ui 重复类。
 * 这里以 24×24 网格手绘需要的图标，零额外依赖，颜色由调用方给。
 */
enum class StreamIconKind {
    Pulse, Phone, Cable, Search, Refresh, More, Back, Close,
    Globe, Lock, LockSlash, Tray, Doc, Check, ChevronDown, ChevronUp, Swap,
}

@Composable
fun StreamIcon(
    kind: StreamIconKind,
    size: Dp = 16.dp,
    tint: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        drawIcon(kind, tint, u, ::p)
    }
}

private fun DrawScope.drawIcon(
    kind: StreamIconKind,
    tint: Color,
    u: Float,
    p: (Float, Float) -> Offset,
) {
    val w = 2f * u
    val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (kind) {
        // SF Symbols: waveform.path.ecg —— 心电波形（一条直线夹一段心跳尖峰）
        StreamIconKind.Pulse -> {
            val path = Path().apply {
                moveTo(p(2f, 12f).x, p(2f, 12f).y)
                lineTo(p(8f, 12f).x, p(8f, 12f).y)
                lineTo(p(9.5f, 9f).x, p(9.5f, 9f).y)
                lineTo(p(10.5f, 12f).x, p(10.5f, 12f).y)
                lineTo(p(12.5f, 3f).x, p(12.5f, 3f).y)
                lineTo(p(14f, 18.5f).x, p(14f, 18.5f).y)
                lineTo(p(15.5f, 12f).x, p(15.5f, 12f).y)
                lineTo(p(22f, 12f).x, p(22f, 12f).y)
            }
            drawPath(path, tint, style = stroke)
        }

        // SF Symbols: iphone —— 竖屏手机
        StreamIconKind.Phone -> {
            drawRoundRect(
                color = tint,
                topLeft = p(8f, 2.5f),
                size = Size(8f * u, 19f * u),
                cornerRadius = CornerRadius(2f * u),
                style = stroke,
            )
            drawLine(tint, p(10.5f, 19f), p(13.5f, 19f), strokeWidth = w, cap = StrokeCap.Round)
        }

        // SF Symbols: cable.connector —— 线缆 + 插头
        StreamIconKind.Cable -> {
            drawLine(tint, p(2f, 12f), p(13f, 12f), strokeWidth = w, cap = StrokeCap.Round)
            drawRoundRect(
                color = tint,
                topLeft = p(13f, 8f),
                size = Size(8f * u, 8f * u),
                cornerRadius = CornerRadius(2f * u),
                style = stroke,
            )
            drawLine(tint, p(16.5f, 9.5f), p(16.5f, 14.5f), strokeWidth = 1.6f * u, cap = StrokeCap.Round)
        }

        // SF Symbols: magnifyingglass
        StreamIconKind.Search -> {
            drawCircle(tint, radius = 6.5f * u, center = p(10.5f, 10.5f), style = stroke)
            drawLine(tint, p(15.2f, 15.2f), p(21f, 21f), strokeWidth = w, cap = StrokeCap.Round)
        }

        // SF Symbols: arrow.clockwise —— 顺时针环形箭头
        StreamIconKind.Refresh -> {
            drawArc(
                color = tint,
                startAngle = 40f,
                sweepAngle = 285f,
                useCenter = false,
                topLeft = p(4f, 4f),
                size = Size(16f * u, 16f * u),
                style = stroke,
            )
            val head = Path().apply {
                moveTo(p(19.5f, 4.5f).x, p(19.5f, 4.5f).y)
                lineTo(p(19.5f, 9.5f).x, p(19.5f, 9.5f).y)
                lineTo(p(14.5f, 9.5f).x, p(14.5f, 9.5f).y)
                close()
            }
            drawPath(head, tint)
        }

        // SF Symbols: ellipsis.circle —— 圆内三点
        StreamIconKind.More -> {
            drawCircle(tint, radius = 9.5f * u, center = p(12f, 12f), style = stroke)
            for (x in listOf(7.5f, 12f, 16.5f)) {
                drawCircle(tint, radius = 1.7f * u, center = p(x, 12f))
            }
        }

        StreamIconKind.Back -> {
            drawLine(tint, p(15f, 5f), p(8f, 12f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(tint, p(8f, 12f), p(15f, 19f), strokeWidth = w, cap = StrokeCap.Round)
        }

        StreamIconKind.Close -> {
            drawLine(tint, p(6f, 6f), p(18f, 18f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(tint, p(18f, 6f), p(6f, 18f), strokeWidth = w, cap = StrokeCap.Round)
        }

        // SF Symbols: globe
        StreamIconKind.Globe -> {
            drawCircle(tint, radius = 9f * u, center = p(12f, 12f), style = stroke)
            drawLine(tint, p(3f, 12f), p(21f, 12f), strokeWidth = 1.6f * u)
            drawOval(
                color = tint,
                topLeft = p(7.5f, 3f),
                size = Size(9f * u, 18f * u),
                style = Stroke(width = 1.6f * u),
            )
        }

        StreamIconKind.Lock, StreamIconKind.LockSlash -> {
            drawRoundRect(
                color = tint,
                topLeft = p(4.5f, 10.5f),
                size = Size(15f * u, 10.5f * u),
                cornerRadius = CornerRadius(1.5f * u),
                style = stroke,
            )
            drawArc(
                color = tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = p(7.5f, 4f),
                size = Size(9f * u, 11f * u),
                style = stroke,
            )
            if (kind == StreamIconKind.LockSlash) {
                drawLine(tint, p(3f, 21f), p(21f, 3f), strokeWidth = w, cap = StrokeCap.Round)
            }
        }

        // SF Symbols: tray.full —— 收件盘（托盘 + 文件）
        StreamIconKind.Tray -> {
            val tray = Path().apply {
                moveTo(p(3f, 9f).x, p(3f, 9f).y)
                lineTo(p(7.5f, 14f).x, p(7.5f, 14f).y)
                lineTo(p(16.5f, 14f).x, p(16.5f, 14f).y)
                lineTo(p(21f, 9f).x, p(21f, 9f).y)
                lineTo(p(21f, 18f).x, p(21f, 18f).y)
                lineTo(p(3f, 18f).x, p(3f, 18f).y)
                close()
            }
            drawPath(tray, tint, style = stroke)
            drawRoundRect(
                color = tint,
                topLeft = p(8.5f, 4f),
                size = Size(3.5f * u, 7f * u),
                cornerRadius = CornerRadius(0.8f * u),
                style = Stroke(width = 1.4f * u, cap = StrokeCap.Round),
            )
            drawRoundRect(
                color = tint,
                topLeft = p(13.5f, 5f),
                size = Size(3.5f * u, 6f * u),
                cornerRadius = CornerRadius(0.8f * u),
                style = Stroke(width = 1.4f * u, cap = StrokeCap.Round),
            )
        }

        // SF Symbols: doc / doc.text.magnifyingglass 的简版 —— 文档
        StreamIconKind.Doc -> {
            val path = Path().apply {
                moveTo(p(5f, 3f).x, p(5f, 3f).y)
                lineTo(p(14f, 3f).x, p(14f, 3f).y)
                lineTo(p(19f, 8f).x, p(19f, 8f).y)
                lineTo(p(19f, 21f).x, p(19f, 21f).y)
                lineTo(p(5f, 21f).x, p(5f, 21f).y)
                close()
            }
            drawPath(path, tint, style = stroke)
            val fold = Path().apply {
                moveTo(p(14f, 3f).x, p(14f, 3f).y)
                lineTo(p(14f, 8f).x, p(14f, 8f).y)
                lineTo(p(19f, 8f).x, p(19f, 8f).y)
            }
            drawPath(fold, tint, style = Stroke(width = 1.4f * u, cap = StrokeCap.Round))
        }

        // SF Symbols: checkmark —— 选中态
        StreamIconKind.Check -> {
            val path = Path().apply {
                moveTo(p(5f, 12.5f).x, p(5f, 12.5f).y)
                lineTo(p(10f, 17.5f).x, p(10f, 17.5f).y)
                lineTo(p(19f, 6.5f).x, p(19f, 6.5f).y)
            }
            drawPath(path, tint, style = Stroke(width = 2.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        StreamIconKind.ChevronDown -> {
            drawLine(tint, p(7f, 10f), p(12f, 15f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(tint, p(12f, 15f), p(17f, 10f), strokeWidth = w, cap = StrokeCap.Round)
        }

        StreamIconKind.ChevronUp -> {
            drawLine(tint, p(7f, 15f), p(12f, 10f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(tint, p(12f, 10f), p(17f, 15f), strokeWidth = w, cap = StrokeCap.Round)
        }

        // SF Symbols: arrow.left.arrow.right —— 左右双向箭头
        StreamIconKind.Swap -> {
            drawLine(tint, p(4f, 9f), p(20f, 9f), strokeWidth = w, cap = StrokeCap.Round)
            val l = Path().apply {
                moveTo(p(8f, 5.5f).x, p(8f, 5.5f).y)
                lineTo(p(4f, 9f).x, p(4f, 9f).y)
                lineTo(p(8f, 12.5f).x, p(8f, 12.5f).y)
                close()
            }
            drawPath(l, tint)
            drawLine(tint, p(4f, 15f), p(20f, 15f), strokeWidth = w, cap = StrokeCap.Round)
            val r = Path().apply {
                moveTo(p(16f, 11.5f).x, p(16f, 11.5f).y)
                lineTo(p(20f, 15f).x, p(20f, 15f).y)
                lineTo(p(16f, 18.5f).x, p(16f, 18.5f).y)
                close()
            }
            drawPath(r, tint)
        }
    }
}
