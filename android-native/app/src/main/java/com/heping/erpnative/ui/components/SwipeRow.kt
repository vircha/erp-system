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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 可左右滑动露出操作按钮的行（拖动跟手、松手 spring 吸附）：
 * - 向右滑 -> 露出左侧 startActions（如 编辑/复制/打印）
 * - 向左滑 -> 露出右侧 endActions（如 删除）
 *
 * 🔴🔴 交互铁律（经理 2026-10-15 反馈「要划很大范围才显示按钮」→ 2026-10-28 反馈
 *   「滑动完后滑回正常显示很难滑，要非常准确的才能滑回去」）：
 *
 *  1. **触发阈值按固定像素（12.dp）而不是按区域宽度百分比**。
 *     原来 `offsetPx > startPx * 0.4f`，startPx=170dp 时阈值高达 68dp，
 *     手要划掉大半个屏幕才展开。改成「滑过 12dp 就吸附」。
 *  2. **快速轻扫（fling）无论幅度多少都直接展开**——用户想要的是「扫一下」的手感，
 *     不是「拖到位」的手感。
 *  3. 🔴🔴 **判定必须用「相对位移」（当前 offset − 拖动起始 offset），不能用绝对 offset**。
 *     ❌ 旧代码 `offsetPx > triggerPx`：已展开时 offsetPx=170dp，
 *        用户向左滑回去，offsetPx 要从 170 一路降到 12 以下才判收起 →
 *        **等于要拖 158dp 且松手时机卡在边界上，经理反馈「非常准确才能滑回去」**。
 *     ✅ 正确：记录 `dragStart`，用 `rel = offset − dragStart` 判定，
 *        收起只需再滑 12dp 即可。
 *  4. 🔴🔴 **fling 方向要按「当前是否已展开」判定**：
 *     ❌ 旧代码 `velocity < -flingVel -> -endPx`：已展开时向左快扫会**跳到另一侧**
 *        露出「删除」按钮，而不是收回原位。
 *     ✅ 正确：已展开时，反向 fling = 收起；同向 fling = 保持展开。
 *  5. **展开状态下点击内容区 = 收起**（旧版把 clickable 直接 disabled，
 *     展开后除了继续滑没有任何便捷的收起途径）。
 *  6. **吸附用 spring 动画**，不是瞬间跳变。
 *  7. `forceCollapsed` / `onExpandedChange`：供列表页做「同时只展开一行」互斥。
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
    forceCollapsed: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val startPx = with(density) { startActionsWidth.toPx() }
    val endPx = with(density) { endActionsWidth.toPx() }
    // 🔴 固定触发阈值 12.dp，别再用宽度百分比
    val triggerPx = with(density) { 12.dp.toPx() }
    // 判定为「快速轻扫」的速度阈值（px/s）
    val flingVel = with(density) { 1000f }

    val scope = rememberCoroutineScope()
    // 用 Animatable：snapTo 会自动打断进行中的吸附动画，拖动不会有「抖一下」
    val offsetX = remember { Animatable(0f) }
    // 拖动起始位置 —— 收起判定的关键
    var dragStart by remember { mutableFloatStateOf(0f) }
    val expanded by remember { derivedStateOf { abs(offsetX.value) > 1f } }

    // 吸附动画（spring）
    fun animateTo(target: Float) {
        scope.launch {
            offsetX.animateTo(
                target,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    // 外部要求收起（例如列表页只允许展开一行）
    LaunchedEffect(forceCollapsed) {
        if (forceCollapsed && abs(offsetX.value) > 1f) {
            offsetX.animateTo(
                0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
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
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        // 跟手不卡顿；snapTo 会取消进行中的吸附动画
                        scope.launch(Dispatchers.Main.immediate) {
                            offsetX.snapTo((offsetX.value + delta).coerceIn(-endPx, startPx))
                        }
                    },
                    onDragStarted = {
                        // 🔴 记住起点，收起判定用相对位移
                        dragStart = offsetX.value
                    },
                    onDragStopped = { velocity ->
                        val start = dragStart
                        val rel = offsetX.value - start
                        val target: Float = when {
                            // ① 已经展开：只有「朝收起方向」才会收回
                            abs(start) > 1f -> {
                                val closingByDrag = (start > 0f && rel < 0f) || (start < 0f && rel > 0f)
                                val closingByFling = (start > 0f && velocity < 0f) || (start < 0f && velocity > 0f)
                                when {
                                    // 反向快扫 → 立刻收起（原来会跳到另一侧露出「删除」）
                                    closingByFling -> 0f
                                    // 反向拖过 12dp → 收起（原来要拖 158dp）
                                    closingByDrag && abs(rel) > triggerPx -> 0f
                                    // 幅度不够 → 弹回原位
                                    else -> start
                                }
                            }
                            // ② 未展开：扫一下就展开，拖过 12dp 也展开
                            else -> when {
                                velocity > flingVel -> startPx
                                velocity < -flingVel -> -endPx
                                rel > triggerPx -> startPx
                                rel < -triggerPx -> -endPx
                                else -> 0f
                            }
                        }
                        onExpandedChange(target != 0f)
                        animateTo(target)
                    }
                )
                .clickable {
                    // 🔴 展开状态下点击内容 = 收起（比继续滑容易得多）
                    if (expanded) {
                        onExpandedChange(false)
                        animateTo(0f)
                    } else {
                        onClick()
                    }
                }
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
