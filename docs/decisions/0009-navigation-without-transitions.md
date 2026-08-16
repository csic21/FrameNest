# 决策：应用导航不使用页面转场

- 状态：accepted
- 日期：2026-08-16
- 任务：FN-44

## 问题

Navigation Compose 会为未指定转场的页面应用默认淡入淡出。FrameNest 的顶层 Tab 也通过
同一个 NavHost 导航，因此页面和 Tab 切换都会出现渐隐，且播放器返回路径需要额外维护
局部例外。产品要求所有页面直接切换，不使用任何导航转场。

## 证据

- `FrameNestApp.kt` 的顶层 Tab 通过 `navController.navigate` 切换 NavHost 目标。
- 原 NavHost 未指定全局转场，Browse 和 Recent 只在从播放器返回时覆盖默认淡入，Player
  只覆盖返回退出；其他路径仍继承 Navigation Compose 默认动画。
- 用户于 2026-08-16 确认不需要任何页面或 Tab 转场。

## 决定

在应用唯一的 NavHost 上将 `enterTransition`、`exitTransition`、`popEnterTransition` 和
`popExitTransition` 全部设为 `None`，并移除各 destination 的局部转场覆盖。该规则适用于
顶层 Tab、普通页面前进和系统返回。

## 未选择的方案

- 只关闭顶层 Tab 动画：普通页面仍会渐隐，不符合所有页面无转场的要求。
- 保留播放器专用例外：全局规则已经覆盖，继续保留会形成重复配置。
- 将时长缩短：仍然存在转场，不符合直接切换的产品选择。

## 后果

- 新增 destination 默认不得引入页面转场；若未来恢复动画，需要显式更新本决策。
- 页面切换后旧 destination 不应因转场继续参与组合，播放器退出也不再依赖局部导航例外。
- 控件自身的状态动画不受影响，本决策只约束 NavHost 页面切换。
