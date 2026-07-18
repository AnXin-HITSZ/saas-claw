# PyClaw 前端界面重设计 — 设计文档

日期：2026-07-18
状态：已获用户确认
范围：`pyclaw-web/` 全部 18 个页面 + 全局骨架

## 1. 目标与约束

- **目标**：在功能完全不变的前提下，重新开发前端界面，提升「灵动感」与「高级感」，并补齐缺失的加载状态。
- **约束**：
  - 所有路由、API 调用、权限逻辑、表单字段、业务行为保持现状，仅替换模板结构与样式。
  - 不引入任何 UI 组件库（方案 A：纯手写 CSS 设计系统），不新增重型依赖。
  - 技术栈保持 Vue 3 + Vite + vue-router 4。

## 2. 视觉语言：「深空控制台」（Deep Space Console）

深邃近黑底色 + 琥珀主色与青/紫辅助色构成的极光渐变光晕，配合细网格背景与发光描边，营造 AI 产品的未来感。

### 2.1 色彩系统

- 背景层级（层间用极淡渐变而非纯色区分）：
  - `--bg-abyss: #05070c`（最深层）
  - `--bg-deep: #0a0e16`
  - `--bg-surface: #10151f`
  - `--bg-raised: #161c29`
- 主色：`--accent: #f5a83d`（琥珀，保留品牌连续性）
- 辅助色：`--accent-2: #4dd0e1`（青）、`--accent-3: #8b7cf6`（紫），用于渐变光效、状态区分、图表
- 极光渐变：`--gradient-aurora: linear-gradient(135deg, #f5a83d, #e0637c 45%, #8b7cf6)`，用于品牌标识、关键按钮、高亮描边
- 发光体系：`--glow-accent`、`--glow-cyan` 等分层 box-shadow，用于 hover、聚焦、激活态
- 语义色：success / danger / warning / info 微调至更通透的荧光感

### 2.2 背景质感

- 全局：细网格线（线性渐变网格，透明度约 0.03）+ 顶部极光径向光晕 + 可选噪点纹理（SVG feTurbulence data-URI，极低透明度）
- 侧边栏 / 顶栏：毛玻璃 `backdrop-filter: blur` + 半透明底

### 2.3 字体与排版

- 标题 / 数字展示：`Space Grotesk`（未来感），中文回退系统字体
- 正文：保持 Inter
- 等宽：`JetBrains Mono`，用于代码 / Token / ID
- 标题字距收紧（-0.02em），数字使用 tabular-nums

### 2.4 圆角与阴影

- 圆角：卡片 14px、按钮 10px、标签 999px
- 阴影分层：静态柔和阴影 + hover 时带色发光阴影

## 3. 共享组件（`src/components/ui/`，无依赖手写）

| 组件 | 说明 |
|---|---|
| `AppButton` | primary（极光渐变底 + 发光 hover）/ ghost / danger 三档；内置 `loading` 属性（按钮内 spinner 替换文案、禁用点击） |
| `AppCard` | 发光描边卡片：hover 边框渐变亮起 + 轻微上浮；可选 `glow` 模式 |
| `AppSkeleton` | 骨架屏基元：`text` / `rect` / `circle` 三种形态，微光扫过（shimmer）动画 |
| `AppSpinner` | 渐变圆环 spinner，三档尺寸 |
| `AppEmpty` | 空状态：图标 + 标题 + 描述 + 可选操作按钮 |
| `AppModal` | 统一弹窗：毛玻璃遮罩 + 弹性进入动画 + Esc 关闭 |
| `AppToast` | 全局轻提示（成功/失败/信息），右上角滑入，替代页面内 error-msg 文本 |
| `AppTag` | 状态标签：荧光底色 + 圆点呼吸灯（运行中状态 pulse 动画） |
| `PageHeader` | 统一页头：标题 + 副标题 + 右侧操作区插槽 |

## 4. 加载体系（三层）

1. **顶部进度条**：路由切换时顶栏下方显示 2px 极光渐变进度条（`RouteProgress.vue`，挂在 App.vue，监听 router 钩子）
2. **骨架屏**：所有列表 / 详情 / 表格页首屏加载用骨架屏，形状匹配真实内容布局
3. **按钮内 spinner**：所有提交类操作（创建、保存、删除确认、发送消息）点击后按钮进入 loading 态，防止重复提交

## 5. 动效体系

- 页面转场：保留并优化现有 fade + slide，时长 200ms
- 列表入场：卡片 stagger 依次上浮（每卡片延迟 40ms）
- 微交互：按钮 hover 上浮 1px + 发光；输入框 focus 光晕；导航项激活时左侧指示条滑入
- 状态呼吸：运行中的 Pod / Claw 状态点 pulse 动画
- 全局遵守 `prefers-reduced-motion`

## 6. 各页面重设计要点

### 门户组
- **WelcomePage**：全屏极光渐变背景 + 网格，品牌大字标题带渐变文字效果，特性卡片玻璃质感 + hover 发光，CTA 按钮极光渐变
- **LoginPage / RegisterPage**：居中玻璃拟态卡片悬浮于极光背景，输入框 focus 光晕，提交按钮 loading 态，错误提示改 Toast

### 工作台骨架
- **WorkspaceLayout**：侧边栏毛玻璃化，导航激活态 = 左侧渐变指示条 + 发光底，品牌 mark 极光渐变；底部用户卡片重排；移动端顶栏毛玻璃

### 核心业务组
- **ClawListPage**：统计卡片加渐变图标与数字动画；Claw 卡片重设计（状态呼吸灯、hover 边框流光、stagger 入场）；首屏骨架 = 卡片网格骨架；创建/删除操作按钮 loading
- **ClawDetailPage**：信息分区卡片化，状态时间线可视化，操作按钮分组到页头右侧
- **ClawChatPage**：对话气泡重设计（用户右 / AI 左，AI 气泡渐变描边），输入框悬浮发光底栏，AI 回复中「打字中」三点动画，工具调用 / 审批消息做成嵌入式卡片
- **WorkspaceFilesPage**：文件列表表格化 + 类型图标，加载骨架

### 配置组
- **AgentConfigPage / ProviderPage / SecretPage / TokenPage**：统一卡片式布局，表单分组卡片化，敏感值等宽字体 + 一键复制按钮，保存按钮 loading
- **ToolCatalogPage**：工具卡片网格，风险等级荧光标签区分
- **PodStatusPage**：状态总览统计行 + Pod 卡片，运行状态呼吸灯

### 管理后台组
- **UserManagePage / ChannelPage / AuditLogPage / UsagePage**：表格升级（表头吸顶、行 hover 发光、斑马纹优化），用量页统计卡片 + 纯 CSS 简单柱状图，全部表格加载骨架

## 7. 错误处理

- 操作失败统一走 `AppToast`（错误文案沿用现有 catch 逻辑中的信息）
- 页面级加载失败保留错误态展示，样式升级为带重试按钮的空状态卡片
- 表单校验行为不变，仅样式升级（focus 光晕、错误边框荧光红）

## 8. 测试与验证

- 前端无既有单测体系，本次以人工验证为主：
  - `npm run build` 构建通过
  - 逐页面走查：功能行为与重设计前一致（路由、表单提交、权限跳转）
  - 加载态走查：每个异步操作有对应 loading 表现
  - `prefers-reduced-motion` 下动效降级
- 不改动 `src/api/client.js`、`src/composables/useAuth.js`、`src/router/index.js` 的逻辑（路由钩子仅追加进度条监听，不改守卫行为）

## 9. 实施顺序（概要）

1. 设计 tokens + 全局样式重写（App.vue / main.js 字体引入）
2. 共享组件库（第 3 节全部组件）
3. 加载体系接入（RouteProgress + api client loading 钩子）
4. 工作台骨架（WorkspaceLayout）
5. 门户组 → 核心业务组 → 配置组 → 管理后台组逐页重设计
6. 全量走查与构建验证
