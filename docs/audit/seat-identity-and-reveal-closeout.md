# 玩家身份展示与揭晓角色配额整改 · 验收报告

> 日期：2026-11-04（验收）/ 2026-11-05（QA 收口与远端核验）
> 基线：`main@5d8ee56412bf00bdef62d02c4da97cbc3a981838`（对应 Actions run 41616758965）
> 需求：`02_Requirements_PRD.md`（UX-ID-01/02/03、REVEAL-Q-01，ID-01～ID-19）
> 实施：`03_Implementation.md`；基线核查：`01_Acceptance_Baseline.md`
> 分支：`feat/seat-identity-reveal`（独立 worktree，禁止 `git add -A`，逐提交显式文件清单，无强制推送）
> **最终代码 HEAD：`main@dc31640cf4ba4f2811c1cb357367c0492fce7039`**（对应 Actions run 41943994048，success）；文档提交 `17ee069`（仅文档、不影响构建，叠在 dc31640 之上）

---

## 1. 结论摘要

| 阶段 | 判定 | 提交 | 说明 |
|---|---|---|---|
| Phase A 统一玩家展示组件 | **PASS** | `53fa291` | 纯函数映射 + `SeatIdentityView` 复用 `SeatAvatarImage` |
| Phase B 复盘页完整身份接线 | **PASS** | `f16db3a` | 结构化座位格式化，默认输出逐字兼容，原文/序号/票数不变 |
| Phase C 发言页两处 SeatPicker | **PASS** | `b62d838` | 统一解析器、座位号常驻、真实 seat 写入、草稿不清空 |
| Phase D 结局揭晓角色配额 | **PASS** | `1c4c812` | 纯函数配额来自本局 `roleSlots`；计划≠真相只警告+确认 |
| Phase E 渲染级断言补充 | **PASS** | `da79550` | ID-17 警告/确认与揭晓行身份的 Compose 断言 |
| QA 审查收口（P1/P2） | **PASS** | `dc31640` | 统一测试/生产格式化口径、横滑宽度、ID-18 重复点击、ID-09 三视角切换；并修复一处缺失 participants 的测试回归 |
| 全量回归 / Lint / APK | **PASS** | 本地 + 远端实测 | 87 类 / 698 用例 / 0 失败；Lint 0 error；APK 20,786,501 B |
| 远端 CI 与产物 | **PASS** | run 41943994048 | `dc31640` actions success；test-report 13378710604 / app-debug 13377762076 |

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
| ID-09 | PUBLIC→OBSERVER/GOD 不泄漏 | **PASS** | `publicPerspectiveStillCannotSeePrivateNotesWithLabels`；`formattingNeverIntroducesRoleOrAlignmentFacts`；`ReplayIdentityViewModelTest.switchingPerspectiveDoesNotChangeNicknamesOrLeakFacts`；`switchingAcrossAllThreePerspectivesNeverLeaksPrivateRecords`（三视角循环切换，身份映射与私人笔记可见性零泄漏）；既有 `PerspectiveLeakExitsTest` 全通过 |
| ID-10 | 修订/回滚重放，计数顺序不变 | **PASS** | `formattingDoesNotChangeItemCountOrderOrRetractedFiltering`；既有 `RevisionOrderSemanticsTest`、`RevisionIntegrityAuditTest` 全通过 |
| ID-11 | 私人笔记 B31 | **PASS** | `privateNoteTargetsUseNicknamesButKeepB31VisibilityMoment`（at=0 不可见、at=1 可见且定位点=1）；既有 F05-01 B31 用例全通过 |
| ID-12 | 8 人局忠臣 3 | **PASS** | `RevealRoleQuotaPolicyTest.threeLoyalServantsCanBeAssignedToThreeDifferentSeats` / `threeLoyalServantsAreNotBlockedBySetBasedUniqueness`；`RevealQuotaViewModelTest.threeLoyalServantsCanBeSelectedOnThreeSeats` |
| ID-13 | 梅林 1 | **PASS** | `merlinHasOneSlotAndSecondSeatIsFull`（`1/1`） |
| ID-14 | 改选释放配额 | **PASS** | `switchingRoleReleasesTheOldQuota`；`editingSeatsOwnRoleNeverLocksItselfOut`；`RevealQuotaViewModelTest.switchingRoleReleasesTheOldQuota` |
| ID-15 | UNKNOWN 多名 | **PASS** | `unknownIsAlwaysSelectableAndNeverConsumesQuota`（`hasAutofill=false`）；`RevealQuotaViewModelTest.unknownNeverAutofillsAndNeverConsumesQuota` |
| ID-16 | 非标准自定义配置 | **PASS** | `quotaComesFromThisMatchRoleSlotsNotFromStandardPreset`；`repeatedRoleSlotsAreSummedNotOverwritten`；`negativeOrEmptySlotsAreClampedToZero` |
| ID-17 | 计划≠真相，警告+确认 | **PASS** | `overPlanAssignmentIsAWarningNotABlockedWrite` / `planNeverOverridesRecordedTruth`；`RevealQuotaViewModelTest.overPlanTruthRequiresExplicitConfirmationBeforeWrite` / `changingRowsAfterConfirmationInvalidatesIt`；`RevealScreenIdentityTest.planMismatchShowsExplicitWarningAndConfirmationButton` |
| ID-18 | 失败/重复点击不部分写入 | **PASS** | `RevealQuotaViewModelTest.failedWriteKeepsDraftAndDoesNotPartiallyReveal`；`repeatedRevealClickDoesNotWriteTwice`（连续两次 + 揭晓后第三次均不重复写库，原子仓储只留最终状态）；既有 `AtomicWriteViewModelTest.repeatedRevealDuringSaveMakesOnlyOneAtomicCall` / `failedRevealResultKeepsInputAndDoesNotPretendToBeSaved` 全通过 |
| ID-19 | 既有全量回归 + Lint + APK + CI | **PASS** | 87 类 / 698 用例 / 0 失败 / 0 错误 / 0 跳过；`lintDebug` 0 error / 58 warning / 2 information；`assembleDebug` 成功；远端 run 41943994048 success（见 §7） |

---

## 6. 本地验证（实测，非推断）

```bash
export JAVA_HOME=/c/Users/HK-info/android-build/jdk
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
# BUILD SUCCESSFUL in 4m 21s
```

| 项 | 基线 `5d8ee56` | 本轮 `dc31640`（含 QA 收口） |
|---|---|---|
| JVM 测试类 | 74 类（P2 收口）/ 613 条基线口径 | **87 类** |
| JVM 用例 | 613 | **698**（+85，其中 +81 来自 Phase A–E，+4 来自 QA 收口补测） |
| 失败 / 错误 / 跳过 | 0 / 0 / 0 | **0 / 0 / 0** |
| `lintDebug` | 0 error | **0 error / 58 warning / 2 information**；指向本轮新文件 **0 条**（58 条全是既有 `UnusedResources` / `IconLauncherShape` 等） |
| Debug APK | — | **20,786,501 B**（debug 签名，**非 Release 包**） |

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

> 不以前一轮绿色 CI 冒充本轮。下表为**本轮最终 HEAD `dc31640`** 的真实 Actions 核验。

| 项 | 值 |
|---|---|
| Actions run | **41943994048** |
| 结论 | **success**（workflow `Android CI`，`.github/workflows/android.yml`，event=push） |
| run URL | `https://github.com/huangguaren/RoundTrace/actions/runs/41943994048` |
| `test-report` artifact | id **13378710604**（95,583 B zip） |
| `app-debug` artifact | id **13377762076**（19,713,088 B zip，内含 debug APK） |

CI 跑的任务与本地一致（`:app:testDebugUnitTest` + `:app:lintDebug` + `:app:assembleDebug`），
workflow 任一任务失败即整体 fail，故 `success` 等同于「三任务全绿」。
本机与 CI 同源同任务，本地实测 698/0/0、Lint 0 error、APK 20,786,501 B 与远端一致。

> 注：GitHub 制品下载 API（`/actions/artifacts/{id}/zip`）对未鉴权请求返回 401，
> 本环境无写入 token，故未拉取远端 XML 逐条复核；但 `success` 结论已等价于三任务全部通过，
> 制品 ID 已列出供用户自行下载。

### 7.1 QA 独立只读审查与收口（PARTIAL → PASS）

本轮交付后由独立 QA 角色（只读、不修改代码）做了红/蓝对抗式审查，结论 **PARTIAL**，
列出 1 个 P1 + 数条 P2/P3；所有项已在 `dc31640` 收口，并重跑全量验证通过。

**P1（已修）：测试与生产的格式化口径不一致。**
- 现象：生产 `ReplayViewModel` 无条件启用 `formatSeatInSentence`，测试却用 `formatSeat`，
  导致「无昵称对局的座位列表文案已被改动却无人验证」，且 ID-06 的降级断言对生产口径失真。
- 修复：`SeatIdentity.hasAnyNames` 闸门——**没有任何昵称的对局**传 `null` 回调，逐字保持基线
  「1 / 2 / 3 号」；有昵称才启用句式内格式化。新增 `productionFormatterIsTheOneUsedByTests`
  （钉死测试用的就是生产用的那个回调）与 `matchWithoutAnyNicknameKeepsBaselineWordingVerbatim`
  （空 participants + 生产回调逐字断言）。

**P2（已修）：**

1. `SeatTraceChip` 长昵称可能撑爆 5–10 人横滑 `LazyRow` → 加 `Modifier.widthIn(max = 168.dp)`，
   昵称在界内单行省略、完整昵称仍由 `SeatIdentityView` 的 `contentDescription` 提供。
2. ID-18 重复点击无针对性用例 → 新增 `RevealQuotaViewModelTest.repeatedRevealClickDoesNotWriteTwice`
   （连续两次 + 揭晓后第三次均不重复写库）。
3. ID-09 缺「三视角循环切换不泄漏」的针对性用例 → 新增
   `ReplayIdentityViewModelTest.switchingAcrossAllThreePerspectivesNeverLeaksPrivateRecords`
   （PUBLIC/OBSERVER_AT_TIME/GOD_AFTER_REVEAL 三方循环，身份映射稳定且 PUBLIC 时间线零私人笔记）。

**P2/P3（已修：一处测试回归）：**
- `switchingAcrossAllThreePerspectivesNeverLeaksPrivateRecords` 在 QA 撰写时自建 `FakeMatchRepository`
  **漏写 `updateParticipants`**，导致 `seatIdentity.display(1).label` 解析成占位「1号」而非「小明（1号）」，
  首次收口构建 698 中 1 失败。已在 `dc31640` 补回 8 个座位参与者后通过。

**已知取舍 / 未完全改（记录在 §8，不冒充 PASS 门槛）：**

1. `RevealScreen` 对**超额项不禁用 `canSelect`**（下拉仍可点选，由底部「计划≠真相」警告 + 独立确认放行）。
   这是有意保留「按真实身份记录」路径，与 PRD「默认不能误占额」的措辞存在张力——
   取舍说明：**默认不强制禁用，但超额必有明确警告与二次确认，绝不静默误占**。
2. `SeatTraceChip` 外层 `FilterChip` 与内层 `SeatIdentityView` 各有一层 `contentDescription`，
   读屏可能读两遍（外层「查看 N 号的记录」+ 内层完整昵称/座位）。属轻微 a11y 冗余，未合并。
3. `EvidenceCard` / `AvalonKnowledgeProvider` / `MarkdownExporter` 等仍硬编码「N 号」，未接入统一身份组件，
   属已知 UX 差异（复盘正文卡片/导出件未逐人显示头像与昵称），未改。
4. `RevealViewModel.saveAssassination()` 的 `SAVING` 闸门在 `launch` 内（既有代码，本轮未动）；
   `RevealUiState.isRoleFull` / `RevealRoleQuotaPolicy.Decision.canSelect` 目前 UI 未强制使用，
   超额拦截改由 `planMismatchWarning` 路径承担。

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
4. 远端 CI 结果见 §7 / 文末追加（run 41943994048，success）。

---

## 追加（最终核验回填）

- 最终 HEAD：`main@dc31640cf4ba4f2811c1cb357367c0492fce7039`
- 对应 Actions run：**41943994048**（success）
- test-report artifact：13378710604；app-debug artifact：13377762076
- 本地与远端一致：87 类 / 698 用例 / 0 失败 / 0 错误 / 0 跳过；Lint 0 error / 58 warning / 2 information；APK 20,786,501 B
- QA 独立审查已由 PARTIAL 收口为 PASS（见 §7.1），所有 P1/P2 项与一处测试回归均已修复并验证。

---

## 追加二：UX-ID-P2 质量优化（2026-11-05）

> 本节记录**后继独立一轮** UX-ID-P2，与上文（基线 `5d8ee56` → `dc31640`）不是同一轮。
> 基线：`main@0df89df`（= 上一轮 QA 收口后的 698 用例口径）。
> 追加段落只做增量质量优化，不重做已验收功能、不改写历史、不强制推送。

### 目标与本轮解决的既有遗留

UX-ID-P2 专门收口上文「已知限制 / 未解决问题」里的 4 项，并把身份展示补齐到主时间线与证据卡片：

| 上文条目 | 本轮处理 | 阶段 |
|---|---|---|
| §8(2)「主时间线卡片未加头像」 | 结构化 `actorSeat` + 行为人头像（仅对明确单一行为人的事件） | Phase A |
| §8(3) / §9(2)「EvidenceCard 仍硬编码 N 号」 | 证据卡片 `relatedSeats` 显示真实昵称 + 座位号 | Phase B |
| §7.1(2)「筛选 Chip 双层 contentDescription，读屏可能读两遍…未合并」 | 合并为单条描述 + `mergeDescendants` | Phase C |
| §8(4)「计划差异警告在底部需滚动…未提供顶部常驻横幅」 | 警告移至列表顶部首屏可见 | Phase D |
| §9(3)「decisions.md 没有计划≠真相专门条款」 | 追加 B38 契约索引条款 | Phase E |

### 阶段提交（逐阶段独立提交，显式 pathspec，正常 push）

| 阶段 | 提交 | 内容与护栏 |
|---|---|---|
| A 主时间线行为人 | `5be657e` | `TimelineEntry.actorSeat` 可选字段；`TimelineBuilder` 只对 `leaderSeat`/`speakerSeat`/`observerSeat`/`assassinSeat` 填值，**汇总票/决议/任务结果/终局/弃局一律 `null`，不臆造行为人**；`TimelineEventCard` 仅在有映射时渲染 28dp 头像。新增 11 条 `TimelineActorSeatTest`（含计数/顺序/文案不变断言） |
| B 证据卡片昵称 | `f1c3f62` | `EvidenceCard` 只用 `SeatIdentity.formatSeats` 格式化 `relatedSeats`；**`label`/`explanation`/任务编号/票数原样不动，全库无正则替换**。新增 3 条 `EvidenceCardNicknameTest`（含「原文绝不改写」护栏） |
| C 无障碍与交互 | `2e64e32` | `SeatIdentityView` 增 `includeContentDescription`（默认 `true`，既有调用零变化）；`SeatTraceChip` 合并为单条 `contentDescription`（「查看 N 号的记录，阿华，N号座位」）+ `mergeDescendants=true` 吸收内层文字节点；既有 4 处精确匹配改 `substring` 以适配合并描述。新增 `seatChipMergesIdentityIntoSingleContentDescription` 钉住「无独立内层节点」 |
| D 揭晓体验 | `d950241` | 计划差异警告卡从底部（8–10 行之后）移到列表顶部；补渲染 `RevealUiState.errorMessage`（此前 ViewModel 已设置、UI 从不渲染，导致未确认时点揭晓静默失败）。新增 `revealBlockedErrorIsShownWhenUnconfirmed`。**保留**：计划≠真相、UNKNOWN 不自动补齐、二次确认只放行本次写入、原子写入闸门 |
| E 完整验收 | 本段落 + `decisions.md` B38 | 全量单测 + Lint + Debug APK + 远端 Actions 核验 |

**未破坏的硬约束**（本轮全程零改动）：Room v4 schema / `MIGRATION_3_4`、JSON 导入导出 v1/v2、B29（不做分析缓存）、B31（`availableFromOrder` 视角）、B34（结果明确即计数）、B35/B37（历史事件仅 3 类可编辑）、历史编辑事务与撤销链、视角隔离唯一出口 `VisibleEventPolicy.forPerspective`、隐私隔离。NX-05 真机设备验收**继续取消**，不作为门槛。

### 全量验证（实测，对应最终代码 HEAD `d950241`）

```bash
export JAVA_HOME=/c/Users/HK-info/android-build/jdk
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# BUILD SUCCESSFUL in 3m 40s（三项任务全绿）
```

| 项 | 上一轮 `dc31640` | 本轮 `d950241` |
|---|---|---|
| JVM 测试类 | 87 | **89** |
| JVM 用例 | 698 | **714**（+16：A +11 / B +3 / C +1 / D +1） |
| 失败 / 错误 / 跳过 | 0 / 0 / 0 | **0 / 0 / 0** |
| `lintDebug` | 0 error / 58 warning / 2 information | **0 error / 58 warning / 2 information**（无新增） |
| Debug APK | 20,786,501 B | **20,930,020 B**（debug 签名，非 Release 包） |

用例数 698 → 714 与四个阶段新增测试条数逐条对账一致，无既有断言被删除或弱化。
`Failed to create image decoder with message 'incomplete input'` 为 Robolectric 下头像占位的良性解码日志，非测试失败。

### 远端 CI 与产物（本轮最终 HEAD 实测，非沿用上一轮）

| 项 | 值 |
|---|---|
| Actions run | **41990349857**（run #26，`Android CI`，event=push，head_sha=`d950241…`） |
| 结论 | **completed / success**（workflow 内 `:app:testDebugUnitTest` + `:app:lintDebug` + `:app:assembleDebug`，任一失败即整体 fail） |
| run URL | `https://github.com/huangguaren/RoundTrace/actions/runs/41990349857` |
| `test-report` artifact | id **13398481359**（96,125 B） |
| `app-debug` artifact | id **13397917581**（19,716,246 B zip，内含 debug APK） |

> 制品下载 API 需鉴权（未鉴权 401），本环境无写入 token，未拉取远端 XML 逐条复核；
> `success` 已等价于 CI 三任务全部通过，制品 ID 列出供自行下载。

### 本轮仍保留的已知限制（不冒充 PASS）

1. `EvidenceCard` 已显示昵称，但复盘**正文**卡片（非证据卡片）与 `MarkdownExporter` 导出件仍未逐人显示头像；
   本轮只收口了 `relatedSeats` 的座位引用展示。
2. `REVEALABLE_ROLES` 下拉对超额项仍不硬禁用（有意保留「按真实身份记录」路径），
   依赖顶部警告 + 独立二次确认，绝不静默误占额。
3. Compose 断言仍跑在 Robolectric，不覆盖像素裁切、深色模式对比度、超大字号；
   真机验收 NX-05 继续取消。

### 最终 HEAD 的远端核验回填（2026-11-05）

**代码交付 HEAD = `d950241`**；其后的 `b036fd2` 及本段回填均为**仅改 `docs/` 的文档提交**，
不参与 Android 构建。每次 push 都会触发 CI，下表是 E 阶段文档提交 `b036fd2` 的完整核验结果
（**不以上方代码 HEAD `d950241` 的 run 冒充文档提交的结果**）：

| 项 | 值 |
|---|---|
| Actions run | **41991648831**（run #27，`Android CI`，event=push，head_sha=`b036fd2…`） |
| 结论 | **completed / success**（job `build` 于 `2026-11-05T08:10:11Z` 完成） |
| 步骤逐项 | Run unit tests ✅ / Run lint ✅ / Build debug APK ✅ / Upload test report ✅ / Upload APK ✅ |
| run URL | `https://github.com/huangguaren/RoundTrace/actions/runs/41991648831` |
| `test-report` artifact | id **13397629409**（94,785 B） |
| `app-debug` artifact | id **13398088433**（19,716,418 B zip，内含 debug APK） |

代码 HEAD `d950241` 的 run **41990349857**（run #26）亦为 success，
`test-report` 13398481359 / `app-debug` 13397917581 —— 两次 run 均为三任务全绿，
本地全量验证（`89 类 / 714 用例 / 0 失败`）与之同源同任务。
