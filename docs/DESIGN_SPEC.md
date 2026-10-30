# RoundTrace 设计规范（DESIGN_SPEC）

> 版本 v1.0 · 2026-10-30
> 权威来源：PRD §5（UI/UX 规范）+ TDD §7（Compose 实现规范）
> 本文件是 UI 实现的唯一设计契约。实现与本文件冲突时，以本文件 + PRD 为准。

## 0. 设计取向判定（记录一次决策）

用 `ui-ux-pro-max` 对本产品做过一次设计系统检索，引擎给出的方向是「3D & Hyperrealism + 深色绒绿#15803D + 金#D97706 + WebGL 效果」。

**未采纳该配色与风格**，理由：
1. PRD §5.1 明确规定浅色默认、`#F7F8FA` 背景、`#3956D8` 主操作，并要求「禁止中世纪重装饰、过量插画、复杂过场动画」。
2. 引擎命中的是「gaming showcase / 沉浸式体验」品类，与本产品「现场单手快速录入的实用工具」定位不符。深色高饱和背景 + 3D 效果会拉低录入时的信息可读性，也违背 PRD 的性能与克制要求。

**采纳的是它的 UX 硬性规则**（与 PRD 一致，作为验收项）：
- Android 触控目标 ≥ 48dp（不是 iOS 的 44pt）
- 正文对比度 ≥ 4.5:1
- 状态不得仅用颜色表达，必须叠加图标或文字
- 无 emoji 充当图标，统一用矢量图标

结论：配色与风格走 PRD，交互与可访问性走 PRD + 上述规则，两者不冲突。

---

## 1. 色彩 Token

> ⚠️ **本节色值已被 v2「硅粉」方案取代**（2026-10-30）。权威值见 `docs/PALETTE_SILICON_PINK.md`，
> 交接件 `docs/handoff/Color.silicon-pink.kt` 可直接覆盖 `Color.kt`。
> 保留本节是为了说明**语义位**（哪些槽位存在、各自职责），色值以 PALETTE 文档为准。

### 1.1 Light（默认）

| 语义 | Hex | 用途 |
|---|---|---|
| background | `#F7F8FA` | 页面底色 |
| surface | `#FFFFFF` | 卡片、输入区 |
| surfaceVariant | `#EEF1F6` | 次级容器、未选中座位底 |
| onBackground / onSurface | `#171B23` | 主文字 |
| onSurfaceVariant | `#667085` | 次要文字、说明 |
| outline | `#D6DBE5` | 描边、分隔线 |
| primary | `#3956D8` | 主操作、选中态、进行中 |
| onPrimary | `#FFFFFF` | 主按钮文字 |
| primaryContainer | `#E4E9FF` | 选中座位底、主色浅底 |
| onPrimaryContainer | `#1D2C7A` | 选中座位文字 |
| success | `#168A61` | 任务成功 |
| successContainer | `#E2F4EC` | 成功浅底 |
| error | `#D64C4C` | 任务失败、删除 |
| errorContainer | `#FBE7E7` | 失败浅底 |
| accent | `#C6903D` | 特殊身份点缀（梅林/派西维尔）、警示性中性 |
| accentContainer | `#FAF0DE` | 身份标签浅底 |
| unknown | `#8A94A6` | 未知/未记录 |
| unknownContainer | `#EDF0F4` | 未知浅底 |

### 1.2 Dark

| 语义 | Hex |
|---|---|
| background | `#101319` |
| surface | `#171B23` |
| surfaceVariant | `#20252F` |
| onBackground / onSurface | `#F2F4F8` |
| onSurfaceVariant | `#98A2B3` |
| outline | `#2E3542` |
| primary | `#8EA2FF` |
| onPrimary | `#10194A` |
| primaryContainer | `#2A3878` |
| onPrimaryContainer | `#DCE3FF` |
| success | `#3DBE8B` |
| successContainer | `#123B2C` |
| error | `#F0736F` |
| errorContainer | `#45201F` |
| accent | `#DCAF62` |
| accentContainer | `#3D2E17` |
| unknown | `#7C8798` |
| unknownContainer | `#232935` |

> 深色不是生硬反色：primary/success/error 在深色下提亮以保证 4.5:1；surface 层次用亮度递增而非纯黑。

### 1.3 语义状态四态（全局统一）
| 状态 | 颜色 | 图标 | 文字 |
|---|---|---|---|
| 成功 / 已完成 | success | `CheckCircle` | 「成功」 |
| 失败 | error | `Cancel` | 「失败」 |
| 未知 / 未记录 | unknown | `HelpOutline` | 「未知」 |
| 待处理 / 进行中 | primary | `RadioButtonChecked` | 「待处理」 |

**铁律**：任何任务结果、投票结果、任务牌结果，都必须「颜色 + 图标 + 文字」三重表达，禁止只靠绿/红。

---

## 2. 字体与排版

系统字体（`FontFamily.Default`），不引入自定义字体文件。

| 角色 | 字号 | 行高 | 字重 |
|---|---|---|---|
| 页面标题 | 24sp | 32sp | Medium |
| 区块标题 | 18sp | 26sp | Medium |
| 卡片主文字 / 正文重点 | 16sp | 24sp | Medium |
| 正文 | 15sp | 22sp | Normal |
| 辅助说明 | 13sp | 18sp | Normal |
| 座位数字 | 20sp | 24sp | Medium |
| 标签 / 徽标 | 12sp | 16sp | Medium |

- 必须支持系统字体放大至 1.3x 不裁切、不溢出（Compose 用 `sp`，禁止 `dp` 写字号）。
- 数字统一用等宽感对齐（座位号、票数、失败牌数）。

## 3. 间距 / 圆角 / 层级

- 间距梯度：4 / 8 / 12 / 16 / 24 / 32 dp，页面水平内边距 16dp，卡片内边距 16dp。
- 圆角：卡片 16dp、按钮 12dp、座位格 12dp、标签 8dp、胶囊 999dp。
- 阴影：仅卡片一档（`elevation 1dp`），不堆叠多层阴影。
- 分隔线：1dp `outline`，优先用留白代替分割线。

## 4. 组件规格

### 4.1 RoundTraceScaffold
- `TopAppBar`（Small）+ 底部 3 Tab：首页 / 记录 / 复盘。
- TopBar 右侧放「保存状态」与会话级操作（撤销）。
- 底部 Tab 高度 ≥ 64dp，图标 24dp + 12sp 文字。

### 4.2 SeatPicker（核心组件）
- 网格：5–10 个座位，一行 5 列（10 人时两行 5 列），间距 8dp。
- 单格最小 56×56dp（大于 48dp 硬下限），数字居中 20sp。
- 状态：未选中（`surfaceVariant` 底 + `onSurface` 字）/ 选中（`primaryContainer` 底 + 2dp `primary` 描边 + `onPrimaryContainer` 字）/ 禁用（40% 透明）。
- 右上角可挂小徽标（身份👑、队长、观察者），徽标 ≤ 14dp 且用图标不用文字。
- `contentDescription` 格式：「4 号座位，当前未选中」/「4 号座位，已选中，队长」。
- 单选模式（选队长、选自己座位）与多选模式（选上车队伍）共用同一组件，靠参数切换。

### 4.3 QuestStepper
- 5 个圆点或短条，当前任务放大并标文字「第 3 任务」。
- 已完成任务按成功/失败着色 + 图标；未开始用 `unknown`。

### 4.4 ProposalCard
- 一行摘要：`第 2 任务 · 第 1 次提案 · 队长 3 号`。
- 队伍座位用迷你编号胶囊横向排列（28dp 高）。
- 投票结果区：`赞成 7 / 反对 1` + 结论徽标（通过=primary，否决=error）。
- 明确分两栏展示：**组队投票** 与 **任务牌结果**，中间有 `HorizontalDivider`。这两块视觉上必须不同，防止用户混淆。

### 4.5 VoteRecorder（两种模式）
- 模式 A 逐人：座位网格切换 同意/反对/未知 三态（点击循环）。
- 模式 B 汇总：两个数字步进器「赞成 N」「反对 M」，剩余自动算「未知 K」。
- 顶部实时显示「赞成 7 > 半数 4 → 通过」，实时校验 `赞成+反对 ≤ 人数`。
- 平票时提示「平票视为否决」。

### 4.6 MissionOutcomeSelector
- 独立卡片，标题明确写「任务牌（不是投票）」。
- 失败牌数：0 / 1 / 2 / 3 / 未知 的分段选择器（`SegmentedButton`）。
- 下方实时显示规则判定：「第 4 任务需 ≥2 张失败牌 → 本任务成功（含 1 张失败牌）」。
- 失败牌数未知时结果区显示「结果未知」，不得自动判定。

### 4.7 ClaimQuickAdd
- 预置标签横向 Chip：怀疑 / 保护 / 跳身份 / 对跳 / 推荐组队 / 改站边 / 关键言论 / 其他。
- 三段式：选标签（1 次触控）→ 选发言者（1 次）→ 可选目标（1 次），文字备注折叠在「补充说明」里。
- 达成 PRD 要求「常见事件 ≤ 3 次触控」。
- 「跳身份」必须渲染为 `Claim` 样式（虚线框 + 「声称」前缀），与「真实身份」在视觉上永久区分。

### 4.8 TimelineEventCard
- 左侧 2dp 竖线 + 节点圆点，按 `occurredOrder` 排序。
- 卡片两行：首行事件摘要，次行「第 N 任务 · 第 M 次提案」。
- 点击展开证据与原始 payload。
- 未记录字段显示 `—— 未记录`（`unknown` 色），不显示空白。

### 4.9 PerspectiveSwitcher
- 三段 `SegmentedButton`：公开 / 当时 / 上帝。
- 「上帝」在对局未揭晓时为禁用态，点击提示「对局结束后可查看」。
- 当前视角必须在页面上常驻一行说明文字，例如「当前视角：1 号梅林，截至第 3 任务」。
- 上帝视角整体加顶部横幅色（`accentContainer`）以区分。

### 4.10 EvidenceCard
- 四段：结论 → 确定性徽标 → 证据事件编号 → 展开解释。
- 确定性徽标四档：`规则确定`（success）/`本人可知`（primary）/`推测`（accent）/`信息不足`（unknown）。
- **推测必须显式带「推测」字样**，不得与「规则确定」同色同形。

### 4.11 SaveStatusBar
- 三态：已保存（`unknown` 文字 + 小勾）/ 保存中（转圈）/ 保存失败（error + 「重试」按钮）。

### 4.12 MatchCard
- 标题、人数、当前任务进度（QuestStepper 迷你版）、状态徽标、更新时间。
- 整卡可点，右侧 overflow 菜单（重命名/删除/导出）。

## 5. 页面级布局

- 统一 `Scaffold`，内容区 `LazyColumn`，底部主操作固定（`bottomBar` 或 `Box` 内右下），保证单手可达。
- 列表项之间 12dp，卡片之间 12dp。
- 所有页面必须有：加载态（骨架或转圈）、空态（图标 + 一句说明 + 主按钮）、错误态（原因 + 重试）。

### 5.1 结局揭晓页（2026-10-30 补写 · 按 `feature/reveal/RevealScreen.kt` 反写并修正）

顺序（自上而下，间距 12dp）：

1. **刺杀提示条**（仅 `AWAITING_ASSASSINATION` 时）— `PanelShape` + `accentContainer` 底，
   文案 `reveal_assassination_hint`：「三次任务成功只是进入刺杀阶段，**不能直接认定好人获胜**」。
   这条是防误判的关键，不允许省略。
2. **角色不完整提示**（`rolesComplete == false`）— `bodySmall` + `accent`，
   文案 `reveal_insufficient`，说明此时高级结论区会显示「信息不足」。
3. **逐座位角色行**（每行：座位号 + 角色选择），座位按 1..N 升序，不跳号。
4. **主按钮**：未揭晓 = `reveal_action`（揭晓全部身份）；已揭晓 = `reveal_done`（禁用态）。
5. **刺杀录入区**（仅 `AWAITING_ASSASSINATION` 时）：刺客座位 → 刺杀目标 → `reveal_save_assassination`。

**座位选择的两条硬要求**（当前实现不满足，见 `DESIGN_SYNC` R8 / R9）：

- **必须显示全部座位**（5–10 人全给），不得只渲染前 5 个；座位数取 `playerCount`，不是常量；
- **必须有选中态**：选中座位用 `primaryContainer` 底 + 2dp `primary` 描边 + `onPrimaryContainer` 文字
  （复用 `SeatPicker` 的选中视觉），并让两组选择器各自带标题，避免「点了不知道选中谁」。

**禁止**：把「三次任务成功」直接判成好人胜；揭晓前不得在任何位置泄漏真实身份（含徽标颜色暗示）。

### 空态文案规范
| 页面 | 空态标题 | 副文案 | 按钮 |
|---|---|---|---|
| 首页 | 还没有对局记录 | 开始记录你的第一局阿瓦隆 | 开始一局 |
| 时间线 | 暂无事件 | 记录提案、投票或发言后会显示在这里 | 去记录 |
| 复盘 | 暂无可用分析 | 需要至少一条已确认的任务记录 | 返回记录 |

## 6. 交互与动效

- 页面切换：默认 `NavHost` 过渡，不自定义复杂动画。
- 选中反馈：座位点击 100–150ms 缩放 0.96 + 颜色变化。
- 禁止：视差、3D、粒子、无限循环动画。
- 危险操作（删除对局、撤销已确认事件）：`AlertDialog` 二次确认；撤销提供 Snackbar 反悔窗口。

## 7. 隐私与遮挡

- 秘密身份、夜间信息默认遮挡（显示 `•••` + 「点击查看」），点击后 5 秒自动回遮，或切后台立即回遮。
- 上帝视角入口在对局 `status != FINISHED/AWAITING_ASSASSINATION` 时禁用。
- 导出默认脱敏，弹窗展示「将包含：公开事件 / 不含：真实身份、私人笔记」。

## 8. 可访问性验收清单

- [ ] 所有可点击元素 ≥ 48dp
- [ ] 正文对比度 ≥ 4.5:1（浅色与深色各测一次）
- [ ] 状态不依赖颜色单通道
- [ ] 座位、按钮、Tab 均有 `contentDescription`
- [ ] 字体放大 1.3x 无裁切
- [ ] 无 emoji 充当图标（统一 Material Icons）
- [ ] 深浅色切换无硬编码颜色残留（禁止在 Composable 里写 `Color(0xFF...)`，一律走 `MaterialTheme.colorScheme`）
