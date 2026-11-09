package com.heping.erpnative.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 设计 token（v6.0 重构）
 *
 * 🔴 目的：把散落在 292 个按钮、10 种圆角、6 处硬编码颜色里的"随手值"收进这里。
 *    以后调全局风格只改这个文件，不用逐页翻。
 *
 * 🔴 硬指标（门店场景实测约束，不是审美偏好）：
 *    · 主操作高 48dp —— 低于 44dp 在走动/单手/戴手套时点不准
 *    · 按钮间距 ≥16dp —— 否则容易误触，删除类按钮尤甚
 *    · 文字与底色对比度 ≥4.5:1
 */
object ErpSpacing {
    /** 4dp 栅格：极小间隙（图标与文字之间） */
    val xxs = 4.dp
    /** 8dp：控件内部 */
    val xs = 8.dp
    /** 12dp：卡片内边距 */
    val sm = 12.dp
    /** 16dp：卡片外边距 / 按钮最小间距 */
    val md = 16.dp
    /** 24dp：区块之间 */
    val lg = 24.dp
    /** 32dp：页面级留白 */
    val xl = 32.dp
}

/**
 * 圆角只有 4 档 —— 原来全项目散着 10 种（4/6/7/8/10/12/14/16/20/28），
 * 同一个列表里相邻两行圆角都不一样，看着"毛"。
 */
object ErpRadius {
    /** 8dp：标签、小徽章、输入框内的小块 */
    val sm = 8.dp
    /** 12dp：按钮、输入框 */
    val md = 12.dp
    /** 16dp：卡片、弹窗 */
    val lg = 16.dp
    /** 24dp：底部抽屉、大面板 */
    val xl = 24.dp
}

/**
 * 尺寸硬指标。改这里要同步改按钮组件，否则会出现"字大框小"。
 */
object ErpSize {
    /** 主操作高度（Material 推荐 40，但门店场景用 48 更稳） */
    val buttonPrimary = 48.dp
    /** 次操作高度 */
    val buttonSecondary = 44.dp
    /** 三级文字按钮高度（视觉小，但热区要撑够） */
    val buttonTertiary = 40.dp
    /** 图标按钮热区下限 */
    val iconTouchTarget = 44.dp
    /** 输入框高度 */
    val field = 48.dp
    /** 列表行最小高度（保证可点） */
    val listRow = 56.dp
}

/**
 * 动效时长（Material 3 官方规范）
 *   · 进慢出快：进入 400ms / 退出 200ms
 *   · 大面积慢、小面积快
 *   · 单个动画不超过 1000ms
 */
object ErpMotion {
    val press = 100          // 按钮按下（颜色加深）
    val smallState = 150     // 小状态变化
    val collapse = 220       // 折叠区展开（沿用上一版手感的节奏）
    val number = 250         // 金额/数量变化
    val tabSwitch = 300      // 底部 Tab 切换
    val dialog = 300         // 弹窗、底部抽屉
    val enterScreen = 400    // 进入新页面
    val exitScreen = 200     // 返回上一页
}
