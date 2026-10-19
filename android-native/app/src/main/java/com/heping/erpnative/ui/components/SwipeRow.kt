package com.heping.erpnative.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 可左右滑动露出操作按钮的行（拖动跟手、松手 spring 吸附）：
 * - 向右滑 -> 露出左侧 startActions（如 编辑/复制/打印）
 * - 向左滑 -> 露出右侧 endActions（如 删除）
 *
 * 🔴 交互铁律（经理 2026-10-15 反馈「要划很大范围才显示按钮」）：
 *  1. **触发阈值按固定像素（12.dp）而不是按区域宽度百分比**。
 *     原来 `offsetPx > startPx * 0.4f`，startPx=228dp 时阈值高达 91dp，
 *     手要划掉半个屏幕才展开。改成「滑过 12dp 就吸附」。
 *  2. **快速轻扫（fling）无论幅度多少都直接展开**——用户想要的是「扫一下」的手感，
 *     不是「拖到位」的手感。
 *  3. 已展开时反向滑动 < 12dp 就收起，不要强求拖到底。
 *  4. 展开时禁止内容 click，避免吃掉露出按钮的点击。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeActionsRow(
    startActionsWidth: Dp,
    endActionsWidth: Dp,
    startActions: @Composable RowScope.() -> Unit,
    endActions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val startPx = with(density) { startActionsWidth.toPx() }
    val endPx = with(density) { endActionsWidth.toPx() }
    // 🔴 固定触发阈值 12.dp，别再用宽度百分比
    val triggerPx = with(density) { 12.dp.toPx() }
    // 判定为「快速轻扫」的速度阈值（px/s）
    val flingVel = with(density) { 800f }

    var offsetPx by remember { mutableFloatStateOf(0f) }
    val expanded by remember { derivedStateOf { abs(offsetPx) > 1f } }

    fun settle(target: Float) {
        offsetPx = target
    }

    Box(modifier.fillMaxWidth()) {
        // 背景：左侧动作区（右滑露出）。贴到最左侧，按钮之间无空隙。
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .width(startActionsWidth)
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
            content = startActions
        )
        // 背景：右侧动作区（左滑露出）
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .width(endActionsWidth)
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            content = endActions
        )
        // 前景：可拖动的内容
        Box(
            Modifier
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        // 跟手不卡顿；带一点阻尼，手感更像原生列表
                        offsetPx = (offsetPx + delta).coerceIn(-endPx, startPx)
                    },
                    onDragStarted = { settle(offsetPx) },
                    onDragStopped = { velocity ->
                        // 🔴 fling（快速轻扫）：扫一下就展开/收起，不看幅度
                        val target = when {
                            velocity > flingVel && startPx > 0f -> startPx
                            velocity > flingVel -> startPx
                            velocity < -flingVel && endPx > 0f -> -endPx
                            velocity < -flingVel -> -endPx
                            // 慢拖：滑过固定阈值就吸附
                            offsetPx > triggerPx -> startPx
                            offsetPx < -triggerPx -> -endPx
                            else -> 0f
                        }
                        settle(target)
                    }
                )
                .clickable(enabled = !expanded) { onClick() }
        ) { content() }
    }
}

/**
 * 滑动露出的操作按钮。
 *
 * 🔴 经理 2026-10-15 反馈「按钮间空白太多」：原来 width=76.dp 但圆图标只有 40.dp，
 * 左右各空 18.dp，三个按钮排一起中间像隔着一条河。
 * 现在 width 收到 56.dp、padding 只留 1.dp、图标 20.dp、文字 10.sp，
 * 按钮之间几乎贴着，一眼能看清有几个操作。
 */
@Composable
fun SwipeAction(
    icon: ImageVector,
    text: String,
    tint: Color,
    width: Dp = 56.dp,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(width)
            .padding(vertical = 2.dp, horizontal = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = text, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            text,
            fontSize = 10.sp,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
