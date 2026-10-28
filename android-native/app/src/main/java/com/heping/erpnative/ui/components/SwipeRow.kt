package com.heping.erpnative.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 可左右滑动露出操作按钮的行（拖动跟手、松手 spring 吸附）：
 * - 向右滑 -> 露出左侧 startActions（如 编辑/复制/打印）
 * - 向左滑 -> 露出右侧 endActions（如 删除）
 *
 * 🔴🔴 交互铁律（经理 2026-10-15「要划很大范围才显示按钮」→ 2026-10-28
 *   「滑回很难，要非常准确才能滑回去」→ 2026-10-28「订单编辑按钮不见了」）：
 *
 *  1. **触发阈值按固定像素（12.dp）而不是按区域宽度百分比**。
 *     原来 `offsetPx > startPx * 0.4f`，startPx=170dp 时阈值高达 68dp，
 *     手要划掉大半个屏幕才展开。改成「滑过 12dp 就吸附」。
 *  2. **快速轻扫（fling）无论幅度多少都直接展开**——用户想要的是「扫一下」的手感。
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
 *
 * 🔴🔴🔴 2026-10-28 回归修复（经理：「订单编辑按钮不见了」）：
 *   D1 曾把位移改成 `Animatable` + `scope.launch { offsetX.snapTo(...) }` —— **这是错的**。
 *   `Animatable.snapTo` 内部走 `mutatorMutex`，**同优先级的 mutation 会互相取消**：
 *   连续拖动时每个 delta 起一个协程 → 后发的 snapTo 取消先发的 → **累计位移被丢弃**；
 *   松手时 `animateTo(target)` 又会被残留的 snapTo 打断 → **卡片停在半路，
 *   按钮区露不出来 → 经理看到「编辑按钮不见了」**。
 *   ✅ 现在：**拖动位移一律用同步的 `mutableFloatStateOf`**（一次 delta 都不会丢），
 *      吸附动画用独立的 `animate()` 协程，动画值写回同一个 offset（只有一个数据源）。
 */
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

    // 🔴🔴 单一数据源：卡片位移。**必须同步**，绝不能走 suspend。
    val offsetPx = remember { mutableFloatStateOf(0f) }
    // 拖动起始位置 —— 收起判定的关键
    var dragStart by remember { mutableFloatStateOf(0f) }

    // 吸附动画的目标与序号：每次请求吸附就自增序号，用来重启动画协程
    var settleSeq by remember { mutableIntStateOf(0) }
    var settleTarget by remember { mutableFloatStateOf(0f) }

    /** 请求把卡片滑到 target（松手吸附 / 点击收起 / 被别的行挤开时都用它） */
    fun settleTo(target: Float) {
        settleTarget = target
        settleSeq++
    }

    /** 用户重新按住卡片 → 停掉正在播放的吸附动画，停在当前位置 */
    fun cancelSettle() {
        settleTarget = offsetPx.floatValue
        settleSeq++
    }

    // 播放吸附动画（松手后滑到目标位置）。动画值直接写回 offsetPx —— 只有一个数据源。
    // 🔴 顶层 animate(Float, Float, ...) 的 block 是 **(value: Float, velocity: Float) -> Unit**，
    //    不是 receiver 形式（receiver 形式只有内部的 AnimationState.animate 扩展才是），
    //    所以这里必须写两个参数：第一个是当前值，第二个是速度（用不到）。
    LaunchedEffect(settleSeq, settleTarget) {
        val from = offsetPx.floatValue
        val to = settleTarget
        if (abs(from - to) < 0.5f) { offsetPx.floatValue = to; return@LaunchedEffect }
        animate(
            initialValue = from,
            targetValue = to,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        ) { value, _ -> offsetPx.floatValue = value }
    }

    // 外部要求收起（例如列表页只允许展开一行）
    LaunchedEffect(forceCollapsed) {
        if (forceCollapsed && abs(offsetPx.floatValue) > 1f) settleTo(0f)
    }

    val expanded by remember { derivedStateOf { abs(offsetPx.floatValue) > 1f } }

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
                .offset { IntOffset(offsetPx.floatValue.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        // 🔴🔴 同步累加：一次 delta 都不会丢
                        //    （❌ Animatable.snapTo 会因 mutatorMutex 互相取消而丢位移）
                        offsetPx.floatValue =
                            (offsetPx.floatValue + delta).coerceIn(-endPx, startPx)
                    },
                    onDragStarted = {
                        // 用户重新按住 → 停掉正在播放的吸附动画
                        cancelSettle()
                        // 🔴 记住起点，收起判定用相对位移
                        dragStart = offsetPx.floatValue
                    },
                    onDragStopped = { velocity ->
                        val start = dragStart
                        val rel = offsetPx.floatValue - start
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
                        settleTo(target)
                    }
                )
                .clickable {
                    // 🔴 展开状态下点击内容 = 收起（比继续滑容易得多）
                    if (expanded) {
                        onExpandedChange(false)
                        settleTo(0f)
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
