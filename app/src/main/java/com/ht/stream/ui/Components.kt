package com.ht.stream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 蓝色顶栏（iOS Stream 风格）：居中标题 + 可选返回 + 可选右侧动作 */
@Composable
fun StreamTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    backLabel: String = "总览",
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(StreamColors.Blue)
            .statusBarsPadding()
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterStart) {
            if (onBack != null) {
                Row(
                    Modifier.clickable(onClick = onBack).padding(start = 4.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(backLabel, color = Color.White, fontSize = 15.sp)
                }
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            Modifier.width(96.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions?.invoke(this)
        }
    }
}

/** 分组小标题（工具 / 设置 / 请求行 …） */
@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = StreamColors.SubText,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp)
    )
}

/** 白色分组容器 */
@Composable
fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White)) {
        content()
    }
}

/** iOS 风格 cell：标题 + 可选副标题 + 可选右侧内容/箭头 */
@Composable
fun CellRow(
    title: String,
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    showDivider: Boolean = true
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, fontSize = 12.sp, color = StreamColors.SubText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            if (showChevron) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = StreamColors.SubText,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (showDivider) HorizontalDivider(Modifier.padding(start = 16.dp), color = StreamColors.Divider)
    }
}

/** iOS 风格紧凑分段控件：灰底圆角容器 + 白色选中块，高度 30dp */
@Composable
fun CompactTabs(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(StreamColors.Divider, RoundedCornerShape(9.dp))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { i, label ->
            val active = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .then(if (active) Modifier.background(Color.White, RoundedCornerShape(7.dp)) else Modifier)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) Color(0xFF1A1A1A) else StreamColors.SubText,
                    maxLines = 1
                )
            }
        }
    }
}

/** 分组内说明文字（灰色小字段落） */
@Composable
fun GroupNote(text: String) {
    Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(text, fontSize = 13.sp, color = StreamColors.SubText, lineHeight = 19.sp)
        HorizontalDivider(Modifier.padding(top = 12.dp), color = StreamColors.Divider)
    }
}
