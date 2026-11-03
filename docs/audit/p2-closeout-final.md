# RoundTrace P2 收口报告（体验与测试覆盖）

- 日期：2026-11-03
- 基线：`main@d94da57cf91197da5391a681399b662cad1c541e`（P0/P1 收口终态，远端 CI 全绿）
- 本轮范围：**只做 P2**——不再改 NX-06 / NX-02 / NX-03 / NX-01 的已通过架构
- 环境：Windows 11 / JDK 17（`/c/Users/HK-info/android-build/jdk`）/ Gradle wrapper / 16 逻辑核
- NX-05 设备验收：**CANCELLED**，本轮未重新引入，也不作为任何阶段闸门

## 1. 总判定

| 阶段 | 内容 | 判定 | 依据 |
|---|---|---|---|
| Phase 1 | 无变化编辑保护（审计链防空转） | **PASS** | 规划器业务等价闸门 + `EditApplyResult.NoChange`；13 条真 Room 用例；4 处既有断言同步收紧 |
| Phase 2 | 影响预览 Compose UI 自动化 | **PASS** | `EditImpactPreviewDialog` 抽为 `internal`；11 条 Robolectric + `createComposeRule` 断言（分组/标签/按钮隐藏/滚动/取消/防双击/无影响项） |
| Phase 3 | `leaderSeat` 语义待裁决 | **PASS（仅文档）** | `docs/decisions.md` 新增 F 节「待裁决（未生效）」；**代码与依赖判定零改动** |
| Phase 4 | 性能与文档 | **PARTIAL** | 结构性代价证据（规则推进次数）可复现；耗时仅同构建两路径对照，**无优化前后对照**；5 项 `NOT MEASURED` |
| Phase 5 | 回归 / 提交 / 远端验收 | **PASS** | 74 类 / 573 用例 / 0 失败；lint 0 error；Debug APK 产出；远端 Actions 见 §7 |

**一句话**：P2 的两项真实缺陷（无变化编辑仍写一跳修订、影响预览无渲染级断言）都已关闭；队长语义按预期**只记录、不擅自改**；性能仍然给不出「快了 X%」，如实标 `PARTIAL`。

## 2. 实测数据（本地，2026-11-03）

| 项 | 上一轮（`d94da57`） | 本轮 | 变化 |
|---|---|---|---|
| JVM 测试类 | 71 | **74** | +3 |
| JVM 用例 | 543 | **573** | +30 |
| 失败 / 错误 / 跳过 | 0 / 0 / 0 | **0 / 0 / 0** | — |
| `lintDebug` | 0 error / 52 warning / 2 info | **0 error / 52 warning / 2 info** | 无新增 |
| `assembleDebug` | 通过 | **通过** | — |
| Debug APK | 20,646,989 B | **20,647,837 B** | +848 B |

新增 30 条的构成：

| 测试类 | 新增 | 类型 |
|---|---|---|
| `NoChangeEditProtectionTest` | 13 | 真 Room + Robolectric（事务与零写入） |
| `RecordEditImpactPreviewTest` | 11 | Compose 渲染（Robolectric + `createComposeRule`） |
| `EditImpactPlanCostTest` | 4 | 纯函数（规则推进次数 + 同机对照） |
| `RecordHistoricalEditViewModelTest` | +2 | 状态机（无变化提示、提示后可继续编辑） |

**未删除任何既有用例**。有 4 条既有断言被**收紧**（不是放宽）：

| 用例 | 旧断言 | 新断言 |
|---|---|---|
| `EditImpactPlannerTest.savingIdenticalProposalContent*` | 计划 Ready 且受影响为空 | `Rejected(NO_BUSINESS_CHANGE)` |
| `EditImpactPlannerTest.missionEditWithIdenticalOutcome*` | 同上 | 同上 |
| `AtomicHistoricalEditTest.resubmittingIdenticalProposalContent*` | 确认 `Success` 且不撤销 | `NoChange`，且历史表零新增 |
| `AtomicHistoricalEditTest.missionEditWithSameOutcome*` | 确认 `Success` 且 active 数不变 | `NO_BUSINESS_CHANGE` + `NoChange` + active 数不变 |

## 3. Phase 1：无变化编辑保护

### 3.1 缺陷本质

上一轮已修掉「打开编辑直接保存 → 撤销 3 条真实记录」（D2），但**写入路径仍在**：
`commit()` 在编辑态只出预览，用户点确认后 `applyConfirmedEdit` 会照常执行
`reviseWithSlot` —— 旧版本 `retracted = 1`、新版本 `revisionSeq + 1`、`matches.updatedAt` 刷新。
结果：审计链多一跳内容完全相同的新版本，「这条记录被改过吗」这个问题从此答错。

### 3.2 修法（三个层次，一个判定源）

1. **判定源**：`EditImpactPlanner.plan()` 在令牌校验**之前**做业务等价比较，等价即
   `Rejected(EditPlanRejection.NO_BUSINESS_CHANGE)`。放在规划器而不是 UI，是因为
   `applyConfirmedEdit` 会在新事务里**重算**同一份计划 —— 预览与确认天然共用一道闸，
   不存在「UI 拦住了、仓储照样写」的 TOCTOU 缝隙。
2. **结果语义**：新增 `EditApplyResult.NoChange`。它 ≠ `Failed`：
   一个是「不需要写」，一个是「写失败」。混在一起会让 UI 提示错误文案，也会让测试无法区分。
3. **呈现**：`EditFailureReason.NO_CHANGES` → 文案「内容未修改，未生成新的修订记录」，
   不弹预览、不清草稿、保留编辑会话（用户还能接着改）。

### 3.3 业务等价口径（只比玩家能改的事实）

| 事件 | 参与比较 | 刻意排除 |
|---|---|---|
| `TeamProposed` | `leaderSeat`、`teamSeats`（排序后比较） | `eventId`、`recordedAt`、`occurredOrder`、`supersedesId`、`visibility`、`retracted` |
| `TeamVoteRecorded` | `votesBySeat`、`approveCount`、`rejectCount`、`unknownVoteCount`、`playerCountAtVote` | 同上 |
| `MissionResolved` | `failCount`、`observedOutcome`、`teamSeats`（`null` 与空列表不等价）、`teamSizeExpected` | 同上，外加 `verified`（reducer 回填的可复核性标记，不是用户输入） |

两个容易写错的地方，各有专门用例钉住：

- **顺序不代表变化**：`teamSeats` 是集合语义，`[1,2,3]` 与 `[3,1,2]` 是同一支队伍；
- **`null` ≠ `0`**：`failCount = null` 是「没记」，`0` 是「确定没出失败牌」。
  补录是真实修改，必须允许进预览 —— 用 `Int?` 直接比较，绝不 `?: 0`。

### 3.4 覆盖清单（`NoChangeEditProtectionTest`，13 条，真 Room）

| 用例 | 钉住什么 |
|---|---|
| `noChangeProposalIsRejectedAndWritesNothing` | 提案无变化 → 拒绝；行集合、`retracted`、`revisionSeq`、`updatedAt` 全部不变 |
| `noChangeVoteIsRejectedAndWritesNothing` | 投票同上 |
| `noChangeMissionIsRejectedAndWritesNothing` | 任务结果同上 |
| `reorderedTeamIsStillNoChange` | 队伍换录入顺序仍判无变化 |
| `reorderedMissionTeamIsStillNoChange` | 任务队员换顺序仍判无变化 |
| `missionFailCountUnknownVersusZeroIsARealChange` | `null → 0` 是真实修改，必须能预览 |
| `voteApproveCountUnknownVersusZeroIsARealChange` | 票数 `null → 0` 同理 |
| `changingOnlyPlayerCountAtVoteIsARealChange` | 改「投票时人数」算业务修改（影响半数口径） |
| `realTeamChangeStillPreviewsAndCommits` | 合法修改仍走预览 → 单事务；旧行 retracted、新行 `revisionSeq = 1`、逻辑位置继承 |
| `resolvingUnknownMissionCountsAsChangeButKeepsPeerRows` | 补录不改变终局时不连带撤销他人 |
| `noChangeCheckRunsBeforeTokenValidation` | 直接确认（令牌过期）也判 `NoChange` 且零写入 |
| `missingTargetStillReportsTargetNotFoundInsteadOfNoChange` | 闸门不掩盖「目标已失效」 |
| `nonEditableEventTypeIsStillRejectedBeforeNoChangeCheck` | B35/B37 白名单优先于「无变化」 |

## 4. Phase 2：影响预览的渲染级断言

### 4.1 上一轮的结论为什么可以改

上一轮写的是「仓库无 `createComposeRule` 依赖」。**这句是错的**：
`app/build.gradle.kts` 一直有 `testImplementation(libs.androidx.compose.ui.test.junit4)` 与
`debugImplementation(libs.androidx.compose.ui.test.manifest)`，`ProposalSheetTest` 也早就在用。
真正缺的只有一步：`EditImpactPreviewDialog` 是 `private`，测试调不到。

本轮把它改为 `internal`（**只放宽可见性，UX、文案、布局零改动**），随后补 11 条断言。

### 4.2 断言清单（`RecordEditImpactPreviewTest`，11 条）

| 用例 | 钉住什么 |
|---|---|
| `rendersGroupsAndBothOperationTags` | 「第 1 任务 · 第 1 次提案」/「第 2 任务」两个分组标题；`将撤销`、`仅提示` 各出现 2 次（汇总行 + 条目） |
| `rendersBeforeAndAfterSummaries` | 修改前/后摘要原样渲染 |
| `reviewOnlyOnlyShowsReviewTagAndNoConfirmResetCount` | 只有提示项时 `将撤销` 仅出现 1 次（汇总行的「0 条」），确认按钮是「确认修改」而非「重置 N 条」 |
| `unresolvedDestructiveCaseHidesConfirmButton` | 不可判定 **且** 会撤销 → 两种确认文案都不存在；取消仍可点并回调 |
| `unresolvedButNonDestructiveCaseKeepsConfirmButton` | 不可判定但不撤销 → 仍可确认（不因提醒而拦死） |
| `longAffectedListIsScrollableToLastItem` | 24 条清单可滚动到最后一条，且确认按钮显示 24 |
| `cancelDoesNotTriggerConfirm` | 取消只回调 `onCancel` |
| `confirmButtonIsDisabledWhileSaving` | 保存中按钮 disabled（防双击） |
| `confirmButtonIsEnabledWhenIdle` | 空闲时可点且回调一次 |
| `noImpactShowsNoResetHintInsteadOfEmptyList` | 无影响项时显示「无需重置其他记录」，不出现汇总行 |
| `titleNamesTheQuestBeingEdited` | 标题带任务号 |

**边界声明（写死在测试文件注释里，避免被当成真机验收）**：Robolectric 不等于真机，
不验证像素、字号、触摸命中；NX-05 仍然取消。这些断言证明的是「该出现的出现、不该出现的没出现」，
不是「长得好看」。

## 5. Phase 3：队长语义（只记录，不改）

`docs/decisions.md` 新增 **F 节「待裁决（未生效）」**，与 A–E 的已生效裁决物理分开。
核心内容：

- **现状**：`leaderSeat` 或 `teamSeats` 任一变化 → 同提案投票/决议 RETRACT（任务结果视归属）。
- **方案 A（默认，保留现行为）**：改动少、撤销不静默；代价是改一次队长要重录投票与决议。
- **方案 B（只保留队员相关下游）**：撤销从 3 降到 0–1；但要求 `leaderSeat` 在规则层只是展示字段。
- **本轮核实的关键事实**：这个前提**不成立** ——
  `AvalonRules` 会用上一轮队长推导 `expectedLeader` 并校验 `QUEST_STARTED.leaderSeat`，
  `AvalonReducer` 还把它投影到 `currentLeaderSeat` / `quest.leaderSeat`。
  （同时核实：`FIVE_CONSECUTIVE_REJECTS` 只看 `consecutiveRejections`，与队长无关 —— 这里最容易写错。）
- **结论**：维持方案 A；若产品要放宽，必须先改**规则层**，不能在编辑层偷偷改依赖判定。

代码与依赖判定**零改动**，当前行为继续由既有回归测试钉住。

## 6. Phase 4：性能测量边界

### 6.1 结构性证据（与机器无关，可作硬结论）

`EditImpactPlanCostTest` 用包装 reducer 计数（`initialState` / `step` / `replay`）：

| 路径 | `step` 调用 | 含义 |
|---|---|---|
| 无变化（提案 / 任务结果） | **0** | 闸门在任何规则推进之前返回 |
| 有变化（任务 1→0 失败牌） | **> 0** | 必须重放才能判断是否进入终局 |

> 这里踩过一个坑并已修正：`ReplayEngine.replay()` 内部走 `initialState` + 逐条 `step`，
> **不调用** `GameReducer.replay()`。第一版只数 `replayCalls`，得到「有变化也是 0」的**假证据**，
> 断言当场失败。计数口径现已写进测试注释。

沿用上一轮的硬断言：**PUBLIC 快照调用恰好 1 次**（`ReplayAnalyzePerformanceTest`），本轮未放宽。

### 6.2 同机对照观察（同构建、两条路径，**不是优化前后**）

夹具：8 人局 / 2 个任务 / 10 条事件；预热 5 次，采样 40 次（`System.nanoTime()`）。

| 路径 | median | p95 | min | max |
|---|---|---|---|---|
| 无变化（直接拒绝） | 26.3 µs | 40.6 µs | 23.1 µs | 42.0 µs |
| 有变化（含规则重放） | 310.5 µs | 676.0 µs | 227.4 µs | 719.6 µs |

同一次运行内相差约 **11.8 倍**；但同一台机器两次运行同一 stage 的 median 曾相差 2 倍
（见 `performance-next-round.md` §2），所以**这些数字不能当作验收门槛，也不能换算成「提升 X%」**。
它们的用途只有一个：说明新闸门把无变化路径的成本压到了「不触发规则重放」的量级。

### 6.3 仍然 `NOT MEASURED`

| 项 | 原因 |
|---|---|
| 「优化前 vs 优化后」耗时对照 | 基线 `d94da57` 已含快照复用路径，旧版本无法在本工作区复现 |
| 设备端帧时间 / 首屏时间 | NX-05 取消，且无可用设备 |
| 内存占用与分配次数 | JVM 单测无稳定堆采样手段 |
| 数据库 IO 在总耗时中的占比 | 夹具刻意不过 Room，隔离纯计算成本 |
| Compose 首帧渲染耗时 | Robolectric 的渲染管线不等价于真机 |

## 7. Phase 5：回归与远端

本地三步全绿（2026-11-03）：

```bash
./gradlew :app:testDebugUnitTest --no-daemon   # 74 类 / 573 用例 / 0 失败
./gradlew :app:lintDebug --no-daemon           # 0 error / 52 warning / 2 info
./gradlew :app:assembleDebug --no-daemon       # BUILD SUCCESSFUL
```

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，20,647,837 B，`versionCode 4 / versionName 0.4.0`。
  **这是 debug 签名包，不是正式 release 包**；通过功能验收 ≠ 已上架。
- 远端 Actions 与 `test-report` XML 复核结果见提交后的回填（本文件 §7 会在推送并核对后补 URL）。

## 8. 剩余风险与已知限制

1. **Compose 渲染仍不等于真机**：本轮补的是语义级断言（文本出现/按钮存在/可滚动），
   不覆盖字体裁切、深色模式对比度、超大字号、横屏。仍需人工目视。
2. **`EditImpactPreviewDialog` 可见性放宽为 `internal`**：这是为了让测试调得到；
   风险是包内其它代码可以误用。已通过「只有测试引用」的约定约束，未加 lint 规则。
3. **`NoChange` 与业务错误共用 `RecordCommitResult.Failed` 通道**：
   UI 文案已按 `reason` 分流（中性提示），但类型上仍叫 "Failed"。
   彻底区分需要再拆一个 `RecordCommitResult` 分支，会影响既有 7 条测试的断言，本轮不做。
4. **队长语义（P-1）待产品裁决**：当前行为不改，见 §5 与 `docs/decisions.md` F 节。
5. **性能仍无优化前后对照**：见 §6.3。
6. **`v0.5.0` 未启动**：按要求只在收口完成后评估，「玩家行为时间线 / 当时判断 vs 最终真相」尚未动工。

## 9. 与上一轮报告的关系

- `docs/audit/closeout-final.md`（P0/P1 终态）**保留原文**，只在末尾追加指向本文件的增量说明，
  不改写历史的测试数据与判定，避免两份报告互相矛盾。
- `docs/TEST_REPORT.md` 顶部「最新汇总」更新到 573，正文各阶段历史基线照旧保留。
- `docs/audit/performance-next-round.md` 追加 P2 章节，原有 NX-03 数据不动。
