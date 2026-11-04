# 玩家身份展示与揭晓角色配额整改 · 验收报告

> 日期：2026-11-04
> 基线：`main@5d8ee56412bf00bdef62d02c4da97cbc3a981838`（对应 Actions run 41616758965）
> 需求：`02_Requirements_PRD.md`（UX-ID-01/02/03、REVEAL-Q-01，ID-01～ID-19）
> 实施：`03_Implementation.md`；基线核查：`01_Acceptance_Baseline.md`
> 分支：`feat/seat-identity-reveal`（独立 worktree，禁止 `git add -A`，逐提交显式文件清单，无强制推送）

---

## 1. 结论摘要

| 阶段 | 判定 | 提交 | 说明 |
|---|---|---|---|
| Phase A 统一玩家展示组件 | **PASS** | `53fa291` | 纯函数映射 + `SeatIdentityView` 复用 `SeatAvatarImage` |
| Phase B 复盘页完整身份接线 | **PASS** | `f16db3a` | 结构化座位格式化，默认输出逐字兼容，原文/序号/票数不变 |
| Phase C 发言页两处 SeatPicker | **PASS** | `b62d838` | 统一解析器、座位号常驻、真实 seat 写入、草稿不清空 |
| Phase D 结局揭晓角色配额 | **PASS** | `1c4c812` | 纯函数配额来自本局 `roleSlots`；计划≠真相只警告+确认 |
| Phase E 渲染级断言补充 | **PASS** | `da79550` | ID-17 警告/确认与揭晓行身份的 Compose 断言 |
| 全量回归 / Lint / APK | **PASS** | 本地实测 | 87 类 / 694 用例 / 0 失败；Lint 0 error；APK 20,677,037 B |
| 远端 CI 与产物 | 见 §7（推送后核验回填） | — | 不以前一轮绿色 CI 冒充本轮 |

---

## 2. 基线核查的重要修正

基线 `5d8ee56` 之前的三笔并发 UX 提交（`1984e35` / `a94589c` / `5d8ee56`）**已经做过表面接线，但都不是完整实现，且零测试**：

| 补丁 | 实际内容 | 缺陷 |
|---|---|---|
| `1984e35 fix(claim)` | `ClaimUiState` 加 `seatLabels`/`seatAvatars`，`ClaimViewModel` 用 `associate`，两处 `SeatPicker` 传参 | 空白昵称/URI 未归一化；有头像时昵称不可见；contentDescription 不含完整昵称；无测试 |
| `a94589c fix(reveal)` | `RevealUiState` 加 `roleQuota`/`usedCount`/`isRoleFull`，Screen 显示 `used/quota` | `roleSlots.associate{}` **会覆盖重复 roleId**（应合计）；`isRoleFull` 未排除当前座位；**超额硬拒绝**，违反「计划≠真相只警告不阻断」；无纯函数；无测试 |
| `5d8ee56 fix(replay)` | `PlayerTraceSection` 座位 Chip 用昵称文本 | **只有文字、没有头像**；`PlayerTraceBuilder`/`TimelineBuilder` 文案仍是纯座位号；无结构化引用；无测试 |

因此本轮不是「从零开始」，也不是「补几张截图」，而是**在这些补丁之上做正确实现 + 补齐自动化证据**。
基线核查文档判定为 FAIL/PARTIAL 的项，与上述结论一致。

---

## 3. 变更文件清单（25 个）

### 新增（11 个）
| 文件 | 说明 |
|---|---|
| `core/ui/identity/SeatDisplay.kt` | 纯 Kotlin `SeatDisplay` / `SeatIdentity` / `SeatDisplayResolver`，seat 唯一键，脏数据收敛，不承载角色真相 |
| `core/ui/identity/SeatIdentityView.kt` | 复用 `SeatAvatarImage` 的统一「头像+昵称+常驻座位号」视图，含完整无障碍说明 |
| `feature/reveal/RevealRoleQuotaPolicy.kt` | 纯函数配额策略：合计 `roleSlots`、排除当前座位、UNKNOWN 不占额、差异只警告 |
| `test/.../core/ui/identity/SeatDisplayResolverTest.kt` | 14 条纯 Kotlin |
| `test/.../core/ui/identity/SeatIdentityViewTest.kt` | 6 条 Compose |
| `test/.../core/replay/SeatAwareReplayTextTest.kt` | 13 条纯 Kotlin（结构化文案 + 原文护栏） |
| `test/.../feature/ReplayIdentityViewModelTest.kt` | 4 条 ViewModel |
| `test/.../feature/PlayerTraceIdentitySectionTest.kt` | 8 条 Compose |
| `test/.../feature/ClaimIdentityViewModelTest.kt` | 5 条 ViewModel |
| `test/.../feature/ClaimScreenIdentityTest.kt` | 6 条 Compose |
| `test/.../feature/reveal/{RevealRoleQuotaPolicyTest,RevealQuotaViewModelTest,RevealScreenIdentityTest}.kt` | 12 + 8 + 5 = 25 条 |

### 修改（14 个）
`core/replay/PlayerTraceBuilder.kt`、`core/replay/TimelineBuilder.kt`（可选 `seatLabel` 回调）、
`core/ui/component/SeatPicker.kt`（座位号常驻 + 无障碍）、
`feature/replay/{ReplayUiState,ReplayViewModel,ReplayScreen,PlayerTraceSection}.kt`、
`feature/claim/ClaimViewModel.kt`、
`feature/reveal/{RevealUiState,RevealViewModel,RevealScreen}.kt`、
`res/values/strings.xml`（2 条差异确认文案）。

---

## 4. 核心设计决策

### 4.1 为什么用「可选纯格式化回调」而不是字符串替换
`PlayerTraceBuilder.build(..., seatLabel: ((SeatId)->String)? = null)` 与
`TimelineBuilder.build(..., seatLabel = ...)`：

- `null` → **逐字沿用旧文案**，既有 613 条用例与输出零变化（专测 `defaultBuildIsByteIdenticalToLegacyWhenNoLabelProvided`）；
- 非 `null` → 只在 `leaderSeat` / `teamSeats` / `speakerSeat` / `targetSeats` / `observerSeat` 等**模型字段**的展示位置调用；
- 任务序号、票数、失败牌数、`PlayerClaimed.text` 自由文本**永远不经过回调**。
  `ID-07` 专测「我觉得 2号 很可疑，第 2 任务有 2 张失败牌」逐字保留。
- 全库无 `Regex.replace` / `String.replace` 用于身份格式化。

### 4.2 两种座位文案口径（避免无谓回归）
- `SeatIdentity.formatSeat` → 「阿华（2号）」，无昵称「2号」，用于 UI Chip / 列表；
- `SeatIdentity.formatSeatInSentence` → 有昵称「阿华（2号）」，**无昵称沿用旧文案「2 号」**（带空格）。
  既有 `PlayerTraceViewModelTest.defaultTraceSeatIsObserverSeat` 断言「7 号」，直接换口径会破坏它。
  这不是妥协：UI Chip 与句子是两种展示位置，各自稳定即可。

### 4.3 揭晓配额：计划 ≠ 真相
权威契约在 `ARCHITECTURE.md §2.2`、§9.3-3，`GameConfig.kt:3-9` 与 `RoleAssignment.kt:3-8`
（**注意：`docs/decisions.md` 没有这三条的专门条款**，基线文档里若引用 decisions B 编号需更正）。

- 配额**只**取 `bundle.config.roleSlots`，按 `roleId` 合计多个 `RoleSlotSpec.count`（旧 `associate` 会覆盖）；
- `REVEALABLE_ROLES` 仍取 `AvalonPreset.roleDisplayOrder`（仅展示顺序，不用于取数量，B27）；
- `usedByOtherSeats` 排除当前座位 → 重选自己已占用的角色不会被「1/1 已满」锁死；
- UNKNOWN 恒不占额、不自动补齐；
- **超额不硬禁用**：`planMismatchWarning` 明确警告 + `ConfirmPlanMismatch` 独立确认后才放行 `revealMatch()`；
  行变化后确认作废；**从不修改 `roleSlots`，也不篡改已录入的 `roleAssignments`**。
- 差异判定精确化：`实际 > 计划`（已超额）即使未录完也警告；
  `实际 < 计划` 只在**全部座位录完**时才报，避免把「还没填完」误报成「计划与真相不符」，
  保住既有 `AtomicWriteViewModelTest` 的不完整揭晓路径（T56⑤ 允许信息不足）。

### 4.4 隐私与只读
身份映射只从 `MatchBundle.participants` 读 `seat` / `displayName` / `avatarUri`，
不读 `roleAssignments`、阵营、真相。视角过滤仍由 `VisibleEventPolicy.forPerspective` 唯一出口决定，
B31 `availableFromOrder`、修订 `retracted` 过滤、事件计数与排序均未改动。

---

## 5. 验收矩阵（ID-01～ID-19）

| ID | 场景 | 判定 | 自动化证据 |
|---|---|---|---|
| ID-01 | 已有昵称/头像 | **PASS** | `SeatDisplayResolverTest.namedAndAvatarSeatShowsNameAvatarAndSeat`；`SeatIdentityViewTest.showsAvatarNameAndSeatNumberTogether`；`PlayerTraceIdentitySectionTest.seatChipShowsNameAndSeatNumberTogether`；`RevealScreenIdentityTest.revealRowsShowAvatarNicknameAndSeatNumber` |
| ID-02 | 两个玩家同名 | **PASS** | `duplicateNamesRemainDistinctBySeat`（A）；`speakerAndMentionedKeepDistinctSeatsWhenNamesCollide`；`ClaimIdentityViewModelTest.duplicateNamesAreNeverUsedAsIdentityKeys`；`RevealScreenIdentityTest.duplicateNamesAreDistinguishableBySeat` |
| ID-03 | 无头像 / URI 撤权 | **PASS** | `missingOrBlankAvatarUriFallsBackWithoutCrash`；`SeatIdentityViewTest.invalidAvatarUriFallsBackWithoutCrash`；`ClaimIdentityViewModelTest.seatIdentityIsDerivedFromParticipantsWithFallbacks`；`SeatAvatarImage` 本身有 RT-009/010 撤权回退测试 |
| ID-04 | 5 人 / 10 人 | **PASS** | `fiveAndTenPlayerRostersKeepEverySeat`（5..10 逐档）；`PlayerTraceIdentitySectionTest.tenPlayerRosterScrollsToLastSeat`（横滑可达最后座位） |
| ID-05 | 发言者单选 / 目标多选 | **PASS** | `ClaimScreenIdentityTest.clickingSpeakerGridReturnsTheRawSeatNumber` / `clickingTargetGridReturnsTheRawSeatNumber`；`savingWritesRawSeatNumbersForClaim` / `savingPrivateNoteStillUsesRawSeatNumbers` |
| ID-06 | 复盘提案名单、任务序号不变 | **PASS** | `SeatAwareReplayTextTest.proposalTeamListShowsEveryNicknameWithSeat` / `missionTeamListAlsoUsesNicknamesAndKeepsFailCardNumber` / `timelineVoteCountsAndQuestNumbersAreNotSeatReferences` |
| ID-07 | 自由文本含「2号」不改写 | **PASS** | `freeTextContainingSeatNumberIsNeverRewritten` / `timelineFreeTextAlsoStaysVerbatim`；既有 `TimelineClaimTextAuditTest`、`PlayerClaimedTimelineTextTest` 全通过 |
| ID-08 | 同名不同 seat 归属 | **PASS** | `speakerAndMentionedKeepDistinctSeatsWhenNamesCollide`；`ReplayIdentityViewModelTest.seatIdentityComesFromTheSameBundleSnapshot` |
| ID-09 | PUBLIC→OBSERVER/GOD 不泄漏 | **PASS** | `publicPerspectiveStillCannotSeePrivateNotesWithLabels`；`formattingNeverIntroducesRoleOrAlignmentFacts`；`ReplayIdentityViewModelTest.switchingPerspectiveDoesNotChangeNicknamesOrLeakFacts`；既有 `PerspectiveLeakExitsTest` 全通过 |
| ID-10 | 修订/回滚重放，计数顺序不变 | **PASS** | `formattingDoesNotChangeItemCountOrderOrRetractedFiltering`；既有 `RevisionOrderSemanticsTest`、`RevisionIntegrityAuditTest` 全通过 |
| ID-11 | 私人笔记 B31 | **PASS** | `privateNoteTargetsUseNicknamesButKeepB31VisibilityMoment`（at=0 不可见、at=1 可见且定位点=1）；既有 F05-01 B31 用例全通过 |
| ID-12 | 8 人局忠臣 3 | **PASS** | `RevealRoleQuotaPolicyTest.threeLoyalServantsCanBeAssignedToThreeDifferentSeats` / `threeLoyalServantsAreNotBlockedBySetBasedUniqueness`；`RevealQuotaViewModelTest.threeLoyalServantsCanBeSelectedOnThreeSeats` |
| ID-13 | 梅林 1 | **PASS** | `merlinHasOneSlotAndSecondSeatIsFull`（`1/1`） |
| ID-14 | 改选释放配额 | **PASS** | `switchingRoleReleasesTheOldQuota`；`editingSeatsOwnRoleNeverLocksItselfOut`；`RevealQuotaViewModelTest.switchingRoleReleasesTheOldQuota` |
| ID-15 | UNKNOWN 多名 | **PASS** | `unknownIsAlwaysSelectableAndNeverConsumesQuota`（`hasAutofill=false`）；`RevealQuotaViewModelTest.unknownNeverAutofillsAndNeverConsumesQuota` |
| ID-16 | 非标准自定义配置 | **PASS** | `quotaComesFromThisMatchRoleSlotsNotFromStandardPreset`；`repeatedRoleSlotsAreSummedNotOverwritten`；`negativeOrEmptySlotsAreClampedToZero` |
| ID-17 | 计划≠真相，警告+确认 | **PASS** | `overPlanAssignmentIsAWarningNotABlockedWrite` / `planNeverOverridesRecordedTruth`；`RevealQuotaViewModelTest.overPlanTruthRequiresExplicitConfirmationBeforeWrite` / `changingRowsAfterConfirmationInvalidatesIt`；`RevealScreenIdentityTest.planMismatchShowsExplicitWarningAndConfirmationButton` |
| ID-18 | 失败/重复点击不部分写入 | **PASS** | `RevealQuotaViewModelTest.failedWriteKeepsDraftAndDoesNotPartiallyReveal`；既有 `AtomicWriteViewModelTest.repeatedRevealDuringSaveMakesOnlyOneAtomicCall` / `failedRevealResultKeepsInputAndDoesNotPretendToBeSaved` 全通过 |
| ID-19 | 既有全量回归 + Lint + APK + CI | **PASS**（本地）/ 见 §7（远端） | 87 类 / 694 用例 / 0 失败；`lintDebug` 0 error；`assembleDebug` 成功 |

---

## 6. 本地验证（实测，非推断）

```bash
export JAVA_HOME=/c/Users/HK-info/android-build/jdk
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
# BUILD SUCCESSFUL in 4m 21s
```

| 项 | 基线 `5d8ee56` | 本轮 `da79550` |
|---|---|---|
| JVM 测试类 | 74 类（P2 收口）/ 613 条基线口径 | **87 类** |
| JVM 用例 | 613 | **694**（+81） |
| 失败 / 错误 / 跳过 | 0 / 0 / 0 | **0 / 0 / 0** |
| `lintDebug` | 0 error | **0 error / 57 warning / 2 information**；指向本轮新文件 **0 条**（57 条全是既有 `UnusedResources` / `IconLauncherShape` 等） |
| Debug APK | — | **20,677,037 B**（debug 签名，**非 Release 包**） |

每阶段均先写失败测试并留红灯证据：
- Phase A 红灯：`Unresolved reference 'SeatDisplayResolver'` 等；
- Phase B 红灯：21 条 `No parameter with name 'seatLabel'` / `Unresolved reference 'seatIdentity'`；
- Phase C 红灯：`expected null, but was:<  >`（空白昵称未归一化）；
- Phase D 红灯：15 条 `Unresolved reference 'RevealRoleQuotaPolicy'`。

### 6.1 过程中修复的 3 个真实回归（不掩盖）
1. `PlayerTraceViewModelTest.defaultTraceSeatIsObserverSeat`：传 `seatLabel` 后无昵称座位变「7号」，既有断言要求「7 号」→ 新增 `formatSeatInSentence`；
2. `AtomicWriteViewModelTest` 三条揭晓用例：差异判定把「未录完」误报成不一致、拦截了揭晓 → 精确化 `planMismatch`；
3. `RevealRoleQuotaPolicy` 编译期：`roleDisplayName` 是 `@Composable` 不能进纯函数、两个 Map 构造签名冲突 → 改纯 `roleLabel` + 伴生工厂 `fromQuotaMap`。

---

## 7. 远端 CI 与产物

_推送与 Actions 核验结果在推送完成后回填，见文末「追加」。不以前一轮绿色 CI 冒充本轮。_

---

## 8. 已知限制 / 未覆盖面（不冒充真机验收）

1. **Compose 断言跑在 Robolectric**：只验证「该出现的出现、不该出现的没出现、能滚动」，
   不覆盖字体裁切、深色模式对比度、超大字号、横屏像素。NX-05 真机验收已取消，本轮不恢复为门槛。
2. **主时间线卡片未加头像**：`TimelineEventCard` 拿到的是格式化后的 `TimelineEntry`，
   本轮按 `03_Implementation.md §2.4` 的最小兼容方案只在**结构化文案**层显示「昵称（N号）」，
   未新增 `actorSeat` 字段与时间线卡片头像（避免改动 `TimelineEntry` 模型波及其它视图）。
   玩家筛选 Chip、行为卡片文案、揭晓行、发言页两处 SeatPicker 已含头像。
   **若产品要求主时间线卡片也逐人显示头像，需要单独一轮**。
3. **小屏 SeatPicker 昵称显示**：56dp 格子里昵称以单行省略 + 座位号常驻角标 + 完整无障碍说明呈现；
   没有「放大浮层显示完整昵称」，与 `03_Implementation.md §1` 的可选建议一致。
4. **计划差异的 UI 位置**：警告与确认卡在揭晓列表底部（8 行之后），需滚动可见；
   未提供顶部常驻横幅。
5. **性能未测**：身份映射是 O(人数) 纯函数、无缓存（B29），未做数百事件规模基准测试；
   本轮无可复现的大样本，**不编造耗时数字**。
6. **类别筛选仍是单选**：PRD 未要求多选；`PlayerTraceBuilder` 已接受 `Set`，扩展只需改 UI 状态类型。

---

## 9. 未解决问题

1. 基线三笔 UX 补丁的提交信息与实际内容基本相符，但**零测试**；本轮已补齐 81 条，未改写历史（禁止强制推送）。
2. 部分既有文案有三套「N 号 / N号 / N 号」拼法，本轮通过统一组件与 `formatSeatInSentence` 收敛到两种稳定口径，
   但未做全库文案大统一（会波及大量既有断言）。
3. `docs/decisions.md` 没有「计划≠真相 / UNKNOWN 不补齐」的专门条款，权威出处是
   `ARCHITECTURE.md §2.2`、§9.3-3 与源码类注释；建议后续把该契约补进 decisions.md 便于检索。
4. 远端 CI 结果见文末追加。

---

## 追加（推送后回填）

_待填：最新 HEAD 的 Actions run / 结论 / test-report XML 实测数字 / app-debug artifact ID。_
