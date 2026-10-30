# RoundTrace 设计 — 代码同步契约（DESIGN_SYNC）

> 版本 v1.0 · 2026-10-30
> 设计稿：https://ardot.tencent.com/file/741982307190774 （`局迹 RoundTrace · 设计同步 v1.0`）
> 上游契约：`docs/DESIGN_SPEC.md`（Token/组件规格）+ `01_产品需求文档_PRD.md`
> **本文件是设计侧与编码侧的唯一同步入口。** 设计与实现的任何分歧，先写进这里再动手。

---

## 0. 一句话规则

设计侧只做「像素、文案、组件规格」；编码侧只做「Kotlin、状态、数据」。
**谁都不重复对方的验证工作**——不重复跑构建、不重复跑测试、不重复出 APK、不重复截图验机。

---

## 1. 分工与不重复校验

| 事项 | 归属 | 证据形式 |
|---|---|---|
| 编译 / 单测 / lint / APK | 编码侧 | `docs/TEST_REPORT.md` 一次性记录 |
| 真机或模拟器截图（浅色 + 深色 + 1.3x 字体） | 编码侧（有设备时） | 截图贴进 TEST_REPORT |
| 设计稿与代码的逐屏对照、尺寸与文案核对 | 设计侧 | 本文件 §2/§3 |
| 规则正确性（票数、失败牌、视角边界） | 编码侧 | 单测 R01–R14 / K01–K10 |
| 视觉层级、间距、状态三重表达（色+图标+文字） | 设计侧 | 本文件 §3 |

**设计侧本轮不做的事**（避免重复劳动与冲突）：
1. 不修改 `app/src/**` 任何 Kotlin 文件；
2. 不执行 `./gradlew`（构建与测试由编码侧单一出口负责）；
3. 不新增 DESIGN_SPEC 之外的组件或颜色，避免编码侧返工重写设计系统。

---

## 2. 稿件 ↔ 代码对照表

**主线进度基线**（取自 `docs/TEST_REPORT.md` 2026-10-30 15:00 版）：M0–M3 已完成，117 个单测全绿，
`assembleDebug` 产出 18.27 MB APK；M4（T50–T58）/ M5（T59–T65）未开始。设计稿的 6 张画板中，
前 5 张对应已实现代码，第 6 张（复盘）正好是 M4 的施工图。

| 设计稿画板 | 代码位置 | 状态 |
|---|---|---|
| 01 首页 · 列表 | `feature/home/HomeScreen.kt`、`core/ui/component/MatchCard.kt`、`QuestStepper(compact=true)` | ✅ 已实现，稿件逐节点对齐 |
| 02 创建对局 · 角色构成 | `feature/create/CreateMatchScreen.kt`（`StepIndicator` / `RolesStep` / `RoleRow` / `BottomBar`） | ✅ 已实现 |
| 03 现场记录 | `feature/record/RecordScreen.kt` + `ProposalCard` + `QuestStepper` + `SaveStatusBar` + `RecordTimelineSection` | ✅ 已实现（**操作区有改动，见 R1**） |
| 04 组队投票（弹层） | `feature/record/VoteSheet.kt` + `core/ui/component/VoteRecorder.kt` | ✅ 已实现（**网格列宽见 R2**） |
| 05 任务牌（弹层） | `feature/record/MissionSheet.kt` + `MissionOutcomeSelector.kt` | ✅ 已实现 |
| 06 复盘 · 信息视角 | `feature/replay/ReplayScreen.kt` + `PerspectiveSwitcher` + `EvidenceCard` | ✅ 15:23 已实现（**两处偏差见 R6 / R7**；画板缺「时间位置选择」控件，以代码为准） |
| —— 结局揭晓（画板没有） | `feature/reveal/RevealScreen.kt` | ⚪ 代码先行，设计侧未出稿；结构符合 DESIGN_SPEC 的「先揭晓、再复盘」顺序 |
| —— 关键发言（画板没有） | `feature/claim/ClaimScreen.kt` + `ClaimQuickAdd`（DESIGN_SPEC §4.7） | ⚪ 代码先行；三段式（标签→发言者→目标）与规范一致，`claim_is_not_fact` 已有防误读提示 |

设计稿中的 `组件 · 底部导航` 三个变体（首页/记录/复盘 激活）对应
`core/ui/component/RoundTraceScaffold.kt` 的 `NavigationBar`：
每项 = 24dp 图标 + 12sp 文字，激活态 `primary`，非激活 `onSurfaceVariant`，条高 72dp（含 1dp 分隔线）。

---

## 3. 问题清单（按阻塞度排序）

> 每条都给了「证据 → 建议动作 → 是否阻塞」。编码侧不需要回复确认，直接按建议改或按自己的判断改，
> 改完在 `decisions.md` 记一行即可；本条不重复开会讨论。

### R1 · 现场记录的操作区在 360dp 上放不下（🔴 阻塞可用性）

**证据**：`RecordScreen.kt` 的 `ActionRow` 一行放 4 个控件：
3 个按钮 `Modifier.weight(1f)` + 1 个 48dp 撤销按钮，间距 8dp。

可用宽度 = 360 − 32（页边距）− 24（3 个间距）− 48（撤销）= **256dp**，每按钮 **85dp**。
Material3 `Button` 默认水平内边距 `ButtonDefaults.ContentPadding` = 24dp × 2，
内容区仅剩 **37dp ≈ 2 个汉字**；而按钮文案是 `记录组队提案` / `记录组队投票` / `记录任务牌`（6/6/5 字，15sp）。
结论：**必然换行或被裁切**。

**设计稿的处理**：拆成两行（不新增任何字符串）
- 行 1：`记录组队提案`（主按钮，含 18dp 图标）+ `撤销`（48×48 图标按钮）
- 行 2：`记录组队投票`（outlined）｜`记录任务牌`（outlined），各占一半，文案降到 **13sp**

**建议动作**：把 `ActionRow` 改成两层 `Row`，outlined 按钮文案用 13sp（或换成 `提案/投票/任务牌` 短文案 + 图标，但需要新增字符串，不推荐本轮做）。
**阻塞**：是（PRD「所有已展示按钮必须有效且可读」，当前状态点不到/看不清）。

**参考骨架**（只是结构建议，字符串与既有 enabled 逻辑全部沿用，不新增资源）：

```kotlin
@Composable
private fun ActionRow(state: RecordUiState, onOpenProposal: () -> Unit, onOpenVote: () -> Unit,
                      onOpenMission: () -> Unit, onUndo: () -> Unit) {
    val decision = state.currentProposal?.decision
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
        // 行 1：主操作（提案）+ 撤销
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
            verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onOpenProposal, shape = ButtonShape,
                modifier = Modifier.weight(1f).heightIn(min = Dimens.MinTouchTarget)) {
                Text(stringResource(R.string.record_action_propose))
            }
            OutlinedButton(onClick = onUndo, enabled = state.timeline.isNotEmpty(), shape = ButtonShape,
                modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)) {
                Icon(Icons.AutoMirrored.Outlined.Undo,
                    contentDescription = stringResource(R.string.record_action_undo))
            }
        }
        // 行 2：投票 + 任务牌（各占一半，文案 13sp 保证 6 字排得下）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
            OutlinedButton(onClick = onOpenVote, enabled = state.currentProposal != null,
                shape = ButtonShape, modifier = Modifier.weight(1f).heightIn(min = Dimens.MinTouchTarget)) {
                Text(stringResource(R.string.record_action_vote), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onOpenMission, enabled = decision == ProposalDecision.APPROVED,
                shape = ButtonShape, modifier = Modifier.weight(1f).heightIn(min = Dimens.MinTouchTarget)) {
                Text(stringResource(R.string.record_action_mission), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
```

行的可用宽度核对：行 2 每个按钮 = (328 − 8) / 2 = **160dp**，减 24dp×2 内边距 = 112dp ≥ 6 字 ×13sp ≈ 78dp，**富余 34dp**；
行 1 主按钮 = 328 − 8 − 48 = **272dp**，充足。

### R2 · ~~8 人局投票时第二行座位格会被拉宽~~ —— **已撤回，代码是对的**（⚪ 无需改动）

**撤回原因**：复核 `SeatPicker.kt` 第 61 行 `chunked(SEATS_PER_ROW)` 后，第 84–85 行已有

```kotlin
repeat(SEATS_PER_ROW - rowSeats.size) {
    Box(modifier = Modifier.weight(1f))
}
```

即第二行不足 5 格时用**等宽空盒补齐**，8 人局的 6/7/8 号与第一行 1–5 号严格同列同宽（均 ≈59dp）。
设计稿 04 画板的形态与代码一致，**不需要任何改动**。

> 之所以先留在这里：记录一次「设计侧误判已被代码证伪」，避免后面有人照着旧结论去改 `SeatPicker`。

### R3 · 保存状态只有一个出口（🟡 文案重复风险）

**证据**：`DESIGN_SPEC §4.1` 写「TopBar 右侧放保存状态」，但 `RoundTraceScaffold.kt` 的 `TopAppBar`
只有标题槽；实际保存状态由 `RecordScreen` 列表项里的 `SaveStatusBar` 承担。

**设计稿的处理**：**只保留内容区那一处**（任务进度条下方一行「✓ 已保存」），顶栏不放。
**建议动作**：保持现状，不要再往 TopAppBar 加保存状态；若要加，删掉内容区那处，二者只留一个。

### R4 · 提案卡的「任务牌」区还没数据时的表达

**证据**：设计稿 03 里任务牌区块显示「失败牌 —— 未记录」+ 未知图标（`unknown` 色 + 图标 + 文字三重表达）。
代码侧 `ProposalCard` 已有 `failCount == null` 分支与 `unrecorded_placeholder` 字符串。
**建议动作**：无（确认一致），仅提示：**未记录时不要回退成「0 张」**，`null` 与 `0` 语义必须分开。

### R5 · 深色与 1.3x 字体没有设计侧证据

设计稿本轮只出浅色（深色 Token 已在 `core/ui/theme/Color.kt` 落地，逐值与 DESIGN_SPEC §1.2 对齐）。
深色与 1.3x 的**真实渲染**必须由编码侧在设备上截图，设计侧不重复出第二套深色稿，避免双份维护。

### R6 · 复盘卡把 `ruleId` 原样打在界面上（🔴 阻塞观感）

**证据**：`core/ui/component/EvidenceCard.kt:126` → `Text(text = finding.ruleId)`。
用户看到的是 `A05_TEAM_CONTAINS_EVIL`，不是人话。

**建议动作**：加 `ruleLabel(ruleId)` 映射到 `R.string.rule_a01..a12`（14 条已在
`docs/handoff/strings_delta.xml` 备好），未命中回退 `rule_unknown`。
函数放 `core/ui/`（领域层不拼文案，D6），引用 `AvalonEvidenceAnalyzer.RULE_Axx` 常量而非字面量。

**阻塞**：是（这是复盘页最核心的一条信息，第一眼看到的却是内部枚举名）。

### R7 · 复盘卡把事件 id 原样打在界面上（🟡 建议改）

**证据**：`EvidenceCard.kt:139–146` → `sourceEventIds.joinToString(", ") { it.raw }`。
`EventId.raw` 由 `IdGenerator` 生成，用户看不懂。

**建议动作**：非展开态显示「证据事件 N 条」，展开态再列 id（DESIGN_SPEC §4.8 本来就有展开区）。
**阻塞**：否，但同属「机器语言漏到界面」，和 R6 一起改最省事。

### R8 · 刺杀座位只能选前 5 个（🔴 阻塞，7–10 人局录不全）

**证据**：`feature/reveal/RevealScreen.kt:132` 与 `:143`
```kotlin
(1..state.playerCount).take(5).forEach { seat -> ... }   // 出现两次（刺客 / 目标）
```
`.take(5)` 把座位截断到 1–5。8 人局里 6/7/8 号**根本无法被选为刺客或刺杀目标**，
而阿瓦隆恰恰是 7–10 人局才常见 —— 这条会导致刺杀结果录不进去，进而判不出胜负。

**建议动作**：去掉 `.take(5)`，直接用 `SeatPicker`（它已处理一行 5 列 + 空位补齐，见 R2 的撤回说明），
或 `(1..state.playerCount)` 全量渲染 + 换行。座位数一律取 `state.playerCount`，不要出现常量 5。

### R9 · 刺杀座位没有选中态（🟡 阻塞可用性）

**证据**：同文件 133–150 行，两排 `OutlinedButton` 只显示数字，没有 `selected` 参数、没有底色变化。
用户点「3 号」后界面毫无反馈，接着点目标座位时无法确认刺客选的是谁。

**建议动作**：复用座位选中视觉（`primaryContainer` 底 + 2dp `primary` 描边 + `onPrimaryContainer` 文字），
两组各加一行标题（`reveal_assassin_label` / `reveal_target_label` 分开显示，而不是「刺客 / 刺杀目标」挤一行）。

> 两条已同步写进 `DESIGN_SPEC.md` §5.1（新增的揭晓页规格）。

---

## 4. Token 对照（设计稿 ↔ `core/ui/theme`）

| 设计稿用途 | Hex | 代码常量 | Material 槽位 |
|---|---|---|---|
| 页面底色 | `#F7F8FA` | `LightBackground` | `background` |
| 卡片 | `#FFFFFF` | `LightSurface` | `surface` |
| 未选中座位/次级容器 | `#EEF1F6` | `LightSurfaceVariant` | `surfaceVariant` |
| 主文字 | `#171B23` | `LightOnSurface` | `onSurface` |
| 次要文字 | `#667085` | `LightOnSurfaceVariant` | `onSurfaceVariant` |
| 描边/未选中分段 | `#D6DBE5` | `LightOutline` | `outline` |
| 主操作 | `#3956D8` | `LightPrimary` | `primary` |
| 选中浅底 | `#E4E9FF` | `LightPrimaryContainer` | `primaryContainer` |
| 成功 | `#168A61` / `#E2F4EC` | `LightSuccess` / `LightSuccessContainer` | `ExtendedColors.success*` |
| 失败 | `#D64C4C` / `#FBE7E7` | `LightError` / `LightErrorContainer` | `error` |
| 未知 | `#8A94A6` / `#EDF0F4` | `LightUnknown` / `LightUnknownContainer` | `ExtendedColors.unknown*` |
| 推测/点缀 | `#C6903D` / `#FAF0DE` | `LightAccent` / `LightAccentContainer` | `secondary` |
| 字体 | Noto Sans SC（稿）／`FontFamily.Default`（码） | `RoundTraceTypography` | — |

> ⚠️ **配色变更（2026-10-30）**：主色已从蓝 `#3956D8` 改为**硅粉 `#AD4D68`**，全量 Token 与新值见
> `docs/PALETTE_SILICON_PINK.md`（含逐常量 `Color.kt` 迁移 diff 与实测对比度）。
> **6 张画板仍是旧蓝色，尚未重上色**——按 PALETTE 文档第 5 节的顺序，等 `Color.kt` 替换完成、
> 设备上确认无误后，再一次重上色，避免出现「一半蓝一半粉」的中间态。
> 下表是旧蓝色时期的 Token 对照，**色值已过期，语义位仍然有效**。

> 稿子里用 Noto Sans SC 只是为了预览近似系统字体；**以 `FontFamily.Default` 为准，不要引入字体文件**。

---

## 5. M4 交接口（复盘页）—— 设计先行，编码侧按此施工

画板 06 对应 `DESIGN_SPEC §4.9 / §4.10`。工程里暂无 `feature/review`，
`Routes.REVIEW` 现在只渲染 `EmptyState`（`RoundTraceNavHost.kt` 第 116–127 行），M4 替换这里的空态即可。

### 5.1 已有可复用件（**2026-10-30 15:17 按主线真实代码核对，不是推测**）

| 需要的东西 | 真实类型 / 位置 | 备注 |
|---|---|---|
| 视角枚举 | `Perspective { PUBLIC, OBSERVER_AT_TIME, GOD_AFTER_REVEAL }` · `core/model/Enums.kt:185` | 与设计稿三段 公开/当时/上帝 **1:1** |
| 结论确定性 | `EvidenceLevel { RULE_CERTAIN, OBSERVER_CERTAIN, INFERENCE, UNKNOWN }` · `Enums.kt:192` | 与设计稿四档徽标 **1:1**，无需再设计 |
| 结论数据结构 | `EvidenceFinding(label, level, explanation, ruleId, sourceEventIds, relatedSeats, relatedQuestNo)` · `Knowledge.kt:213` | 卡片四段 = label→level→ruleId→explanation |
| 信息不足项 | `UnknownItem(code, questNo, seats)` + `KnowledgeSnapshot.hasUnknowns` · `Knowledge.kt:200` | 提示条文案由 `code` 映射，**不要硬编码中文** |
| 视角快照 | `KnowledgeSnapshot(at, mode, observerSeat, observerRole, observerRoleIsReconstructed, seatKnowledge, findings, unknowns, canOpenGodView, …)` | 见下方 **新增状态** |
| 视角引擎 | `KnowledgeProvider.snapshot(match, observer, at, mode)` | 纯函数，三道闸门已写在接口注释 |
| 证据分析 | `ReplayAnalyzer.analyze / analyzeAt` | `analyzeAt` 防「自己拼快照时用了全量事件」 |
| 坏人上下界 | `CountBounds(minEvil, maxEvil)` + `bounds(...)` · `core/knowledge/TeamCountBounds.kt` | **只有上下界，不允许输出「某号必坏」** |
| 时间线 | `TimelineEntry(phaseLabel, title, detail, unrecordedFields, sourceEventIds, eventType)` · `core/replay/TimelineEntry.kt` | `unrecordedFields` = 「—— 未记录」那一行 |
| 徽标 / 空态 | `StatusBadge`（四态三重表达）、`EmptyState` | 直接复用，别新画 |

**设计稿原本没有、但模型里有的一格**（已补进规格，画板待重上色时一并加）：
`KnowledgeSnapshot.observerRoleIsReconstructed == true` 时，视角说明行下方加第二行：

> 文案：`review_perspective_reconstructed` =「角色为结局后补录，以下信息按规则重建，非当时所知」
> 样式：**12sp / `accent` 色 / 行高 18sp**，紧跟视角说明行，间距 4dp，不加图标。
> 意义：TDD §5.2 第 3 条要求区分「当时真的知道」与「按规则重建」。只差这一行，否则两种信息在界面上无法分辨。

### 5.2 需要新建的 UI 件（3 个，都进 `core/ui/component/`）

1. `PerspectiveSwitcher.kt` — 三段 `公开 / 当时 / 上帝`
   - `上帝` 在 `status != FINISHED_EVIL/GOOD && status != AWAITING_ASSASSINATION` 时 `alpha = 0.4f` + 禁用，点击给 Snackbar；
   - 高度 44dp，容器 `surfaceVariant` + 12dp 圆角 + 3dp 内边距，选中项 `surface` 底 + `primary` 文字（设计稿 06 已定稿）。
2. `EvidenceCard.kt` — 四段：结论（15sp Medium）→ 确定性徽标 → 证据来源（12sp `unknown`）→ 解释（13sp `onSurfaceVariant`）
   - 徽标四档：`规则确定`(success) / `本人可知`(primary) / `推测`(**accent，必须有「推测」二字 + 虚线图标**) / `信息不足`(unknown)；
   - 卡片 16dp 圆角、16dp 内边距、`elevation 1dp`（与 `MatchCard` 同档）。
3. `KnowledgeBanner.kt`（可选，上帝视角横幅）— `accentContainer` 底、`accent` 文字，一行说明「上帝视角：已揭晓真实身份」。

### 5.3 文案：**已经做成文件，不要手敲**

`docs/handoff/review_strings.xml` —— 26 条，合并进 `values/strings.xml` 即可（key 无前缀冲突）。
key 与真实类型一一对应：`Perspective` → `review_perspective_*`、`EvidenceLevel` → `review_level_*`、
`ruleId`（A01–A12）→ `review_rule_a01..a12`、`UnknownItem.code`（4 个常量，`AvalonKnowledgeProvider.kt:605–614`）→ `review_unknown_*`。

两条硬规则（建议直接写进代码注释）：

1. `ruleId` / `code` 匹配不上时用 `review_fallback_unknown` 兜底，**绝不把原始 code 显示给用户**
   （界面出现 `A05_TEAM_CONTAINS_EVIL` 属于事故）；
2. `canOpenGodView == false` 时「上帝」段**禁用而非隐藏**，否则用户以为产品没这个能力。

> 旧的 12 条草案（`review_confidence_*` 等）已作废，以 `docs/handoff/review_strings.xml` 为准。

### 5.4 验收（设计侧只验这几条，不重复跑测试）

- [ ] 三门分段里 `上帝` 在未揭晓时**必须**是禁用态，长这样：40% 透明 + 点击有提示，而不是隐藏；
- [ ] 「当前视角：…」这行**常驻**，切换视角时跟着变，不允许只出现在 Toast 里；
- [ ] `推测` 与 `规则确定` **不同色、不同图标形状**（虚线 vs 实心勾），扫一眼能分；
- [ ] 信息不足时必须出现提示条，且不产出任何「确定」结论；
- [ ] `observerRoleIsReconstructed == true` 时，视角说明行下方出现「按规则重建」那行（12sp `accent`），
      与「当时真的知道」在文字上能区分开；
- [ ] 上帝视角下页面顶部有 `accentContainer` 横幅（与「当时」在视觉上不可能混淆）。

> M5 的导出入口在复盘页顶栏右侧（设计稿 06 的上传箭头图标），弹窗正文用 `review_export_dialog_body`。

### 5.5 M5 导入导出 · 弹窗规格（此前完全没有定义，先定下来免得临时拍脑袋）

**导出（`AlertDialog`，标题「导出对局」）**

- 正文 = `export_dialog_body`：`将包含：公开事件 / 不含：真实身份、私人笔记`
- 正文下方一行灰色小字：`共 N 条事件 · M 名玩家 · 生成时间 YYYY-MM-DD HH:mm`（让用户知道导出的是哪一版）
- 三个动作：`复制 JSON`（主）/ `保存到文件`（次，走 SAF `ACTION_CREATE_DOCUMENT`，已配 `file_paths.xml`）/ `取消`
- **硬约束**：`Perspective.GOD_AFTER_REVEAL` 下的私有事实**一律不进导出包**；导出前不弹二次确认（正文已经说明内容），但复制成功后用 Snackbar 给「已复制」反馈。

**导入（从首页 overflow 或复盘页进入，两段式）**

1. 选文件 → **预览摘要**：`1 局 · 8 人 · 2026-10-30 21:40 · 12 条事件`，底部主按钮 `导入`；
2. 冲突（`matchId` 已存在）→ 二选一：`覆盖本机对局` / `作为副本导入`，默认**选副本**（破坏性更小）；
3. 结果页（同一弹窗内切换）：`已导入 1 局，跳过 0 条` / 失败时写明原因（`JSON 结构不符` / `规则校验未通过`）。

**三条不允许的做法**

- 不允许「静默覆盖」已有对局；
- 不允许把解析异常直接抛到界面（必须映射成一句人话 + 可展开的技术详情）；
- 不允许导入时自动补全缺失字段（未知就留未知，导入不是修复工具）。

**状态**：加载（转圈 + 「正在解析…」）/ 错误（原因 + 重试）/ 空（文件里没有对局 → 「这个文件里没有可导入的对局」）。

---

## 6. 变更流程（防止反复返工）

1. 设计改动 → 先改**本文件**的对应条目（含 changelog），再改稿；
2. 编码实现与本文件不符 → 在 `docs/decisions.md` 记一条「与 DESIGN_SYNC 的差异 + 理由」，不要静默偏离；
3. 一次同步只处理一张画板，避免「稿改了但代码没跟」的悬空状态。

## 7. 与主线的同步节奏

主线每推完一个里程碑，设计侧只做三件事，做完就停：

1. **复核对照表**：新代码是否与 §2 的画板一致；不一致就改 §2 状态列并写进 changelog；
2. **撤回过期结论**：任何「建议改动」若已被代码证伪（如 R2），**必须在原条目上标注撤回 + 证据行号**，
   不能直接删——删掉等于让别人再踩一次；
3. **只补一个交接口**：把下一个里程碑需要新建的组件 / 字符串 / 验收项写进 §5，别的都不碰。

不同步的东西（避免双份维护）：构建日志、测试数字、APK 体积、真机截图——这些以 `TEST_REPORT.md` 为唯一来源，
本文件只在「结论影响设计」时引用一句，不复制细节。

## 8. Changelog

| 日期 | 变更 | 影响 |
|---|---|---|
| 2026-10-30 v1.0 | 初版：6 张画板（首页/创建/记录/投票/任务牌/复盘）+ 3 个导航变体；提出 R1–R5 | R1 需编码侧改 `ActionRow` |
| 2026-10-30 v1.1 | 对齐主线 M0–M3 已完成的基线；**R2 撤回**（`SeatPicker` 第 84–85 行已用 `Box(weight(1f))` 补齐空位，误判）；R1 补参考骨架与宽度验算；§5 升级为 M4 施工交接（复用件/新建件/12 条字符串/5 条验收） | 编码侧 M4 可直接照 §5 施工；**不要**再改 `SeatPicker` 列宽 |
| 2026-10-30 v1.2 | 新增配色方案 v2（硅粉）：`docs/PALETTE_SILICON_PINK.md` + 设计稿「色彩 · 硅粉 v2」页；§4 标注旧蓝色 Token 已过期 | 编码侧只需替换 `Color.kt` 一个文件；画板暂不重上色 |
| 2026-10-30 v1.3 | 按主线 15:17 的真实 M4 代码重写 §5.1（`Perspective` / `EvidenceLevel` / `EvidenceFinding` / `UnknownItem` / `CountBounds` 全部对得上，**四档徽标无需再设计**）；补 `observerRoleIsReconstructed` 这个设计稿缺的状态；文案改为交接件 `docs/handoff/review_strings.xml`（26 条） | 编码侧 M4 直接照 §5 + handoff 施工；画板上这行**尚未绘制**，规格见 §5.1 |
| 2026-10-30 v1.4 | 主线 15:23 已实现 `feature/replay` / `reveal` / `claim` 与 `PerspectiveSwitcher` / `EvidenceCard`；**删除 `review_strings.xml`**（与既有 80+ 条 key 重复，改为 `strings_delta.xml` 只补 14 条缺口）；新增 **R6**（`ruleId` 原样上屏，阻塞）与 **R7**（事件 id 原样上屏） | 编码侧照 R6/R7 改；画板与代码的 5 处差异已在 handoff README §3 认账，以代码为准 |
| 2026-10-30 v1.5 | 新增 **R8**（`RevealScreen` 用 `.take(5)` 截断刺杀座位，7–10 人局录不全，阻塞）与 **R9**（刺杀座位无选中态）；`DESIGN_SPEC.md` 补 §5.1 揭晓页规格、§1 加「色值已被硅粉取代」提示；派工单同步 A5 / B5 / B6 | 编码侧 A5 与 R8 同一处，改一次即可 |
