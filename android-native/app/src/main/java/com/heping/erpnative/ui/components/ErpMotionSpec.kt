package com.heping.erpnative.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.heping.erpnative.ui.theme.ErpMotion

/**
 * 动效规范（v6.0 重构）
 *
 * 🔴 改造前：全项目 `AnimatedContent` **0 处**、`animateColorAsState` **0 处** ——
 *    切页是硬切、按钮按下没有颜色反馈，用户点完不知道有没有点上。
 *
 * 🔴 取值来自 Material 3 官方 Easing & Duration：
 *    · 进慢出快：进入 400ms / 退出 200ms（退出不需要那么多注意力）
 *    · 大面积慢、小面积快
 *    · 缓动必须成对使用：进入用 Decelerate（先快后慢），退出用 Accelerate（先慢后快）
 *
 * 🔴 禁止：无限循环的呼吸/脉冲装饰、每个元素都飞入、单动画超 1000ms、
 *    列表滚动加视差。门店场景是「快速开单」，动画只能帮助理解，不能让人等。
 */

/** Emphasized Decelerate —— 元素进入屏幕（先快后慢，缓缓落位） */
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

/** Emphasized Accelerate —— 元素离开屏幕（先慢后快，走得干脆） */
val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

/** Standard —— 屏幕上原地移动（布局变化、状态切换） */
val EasingStandard = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

/** 进入：下滑 + 淡入（二级页从右滑入） */
fun enterFromRight(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(ErpMotion.enterScreen, easing = EmphasizedDecelerate),
        initialOffsetX = { it / 6 }
    ) + fadeIn(tween(ErpMotion.enterScreen / 2, easing = EmphasizedDecelerate))

/** 退出：向左滑出 + 淡出 */
fun exitToLeft(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(ErpMotion.exitScreen, easing = EmphasizedAccelerate),
        targetOffsetX = { -it / 6 }
    ) + fadeOut(tween(ErpMotion.exitScreen / 2, easing = EmphasizedAccelerate))

/** 返回时反向：从左侧滑入 */
fun enterFromLeft(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(ErpMotion.enterScreen, easing = EmphasizedDecelerate),
        initialOffsetX = { -it / 6 }
    ) + fadeIn(tween(ErpMotion.enterScreen / 2, easing = EmphasizedDecelerate))

/** 返回时反向：向右滑出 */
fun exitToRight(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(ErpMotion.exitScreen, easing = EmphasizedAccelerate),
        targetOffsetX = { it / 6 }
    ) + fadeOut(tween(ErpMotion.exitScreen / 2, easing = EmphasizedAccelerate))

/** Tab 之间：只淡入淡出（同级页面，不该有方向感） */
fun fadeThrough(): ContentTransform =
    fadeIn(tween(ErpMotion.tabSwitch, easing = EasingStandard))
        .togetherWith(fadeOut(tween(ErpMotion.tabSwitch / 2, easing = EasingStandard)))

/**
 * 底部 Tab 切换容器：淡入淡出。
 * 🔴 Tab 是同级关系，用左右滑会有「前进/后退」的错误暗示。
 */
@Composable
fun TabSwitchContent(
    target: Any,
    modifier: Modifier = Modifier,
    content: @Composable (Any) -> Unit
) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = { fadeThrough() },
        label = "tabSwitch"
    ) { t -> content(t) }
}
