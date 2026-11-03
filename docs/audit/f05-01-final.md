# F05-01 玩家行为时间线 · 最终验收报告

> 日期：2026-11-03
> 基线：`main@ad70ea4`（74 个测试类 / 573 条 JVM 用例 / 0 失败）
> 本轮范围：**只做 v0.5.0 的 F05-01**；不开发狼人杀、「最终真相对照」、快速记录；不改未裁决的队长依赖规则
> 结论：**功能 PASS，测试 PASS，Lint PASS，APK PASS；`PackageDebug` 首次因文件占用失败一次、重跑通过（见 §6.3）**

---

## 1. 结论摘要

| 项 | 判定 | 依据 |
|---|---|---|
| 只读 `PlayerTraceBuilder`（纯函数、零 Android 依赖） | **PASS** | `core/replay/PlayerTraceBuilder.kt`，无 Room / Context / 协程，输入输出全为模型对象 |
| 视角隔离唯一出口 | **PASS** | 只调 `VisibleEventPolicy.forPerspective`，类内无第二套可见性判据；私有声称/私人笔记均由**归属**决定可否产出 |
| 汇总票不虚构个人票 | **PASS** | `votesBySeat[seat] == null` 时不产出 `CAST_VOTE`；`onlySummaryVoteNeverProducesPerSeatVotes` |
| 失败牌不归因到人 | **PASS** | `MissionResolved` 只产「任务队员」，`failCount` 只进文案；`null`（未记录）与 `0`（确定零失败牌）分开 |
| 修订历史 | **PASS** | 先剔 `retracted` 再聚合；`retractedVersionsAreNotShown` |
| 时间边界（B31） | **PASS** | `PrivateNoteAdded` 用 `availableFromOrder`，其余用 `occurredOrder`；条目锚点同取 `availableFromOrder`，点击只往回拨、不拨未来 |
| 点击跳转原复盘位置 | **PASS** | 走既有 `ReplayAction.SelectPosition(occurredOrder)`，未新增 action / 路由 |
| 私有信息来源标记 | **PASS** | `PlayerTraceItem.sourceVisibility` → UI 渲染「来自私有记录 · 仅本人可见」 |
| Room v4 / JSON v1/v2 / B29 / B31 / B34 / B35 / B37 | **未退化** | 本轮零 schema 改动、零导出格式改动、零写入路径改动 |
| `docs/decisions.md` F 节（队长依赖规则） | **未改动** | `git diff ad70ea4..acc1b58 -- docs/decisions.md` 为空 |

---

## 2. 提交映射（必读：提交信息与内容不一致）

本轮 F05-01 的代码与测试，是被**并发运行的另一个工作流**（UI 刷新：UX-01/07/09）用 `git add -A` 连带提交的，
因此版本库里 F05-01 的落地提交挂着 UX 的标题。仓库禁止强制推送，**未做历史重写**，在此如实记录映射：

| 提交 | 提交信息（原文） | 实际内容 | 归属 |
|---|---|---|---|
| `5d90638` | `feat(ux): UX-01 RECORDS/REVIEW tabs show match list directly` | **F05-01 主体**：`PlayerTraceBuilder` / `PlayerTraceModels` / `PlayerTraceSection` / `ReplayUiState` / `ReplayViewModel` / `ReplayScreen` / `TimelineBuilder`（3 处可见性放开）/ `strings.xml` + 3 个测试类 + 2 份 F05-01 文档；**同时含** UX-01 的 `RoundTraceNavHost.kt` 与 `docs/UX_REFRESH_PHASE_A.md` | 混合 |
| `9b96332` | `feat(ux): UX-07 SeatPicker press scale animation` | **F05-01 P1 修复**（私有声称越权、队长自提名去重、来源可见性、`MatchBuilder` 夹具）+ 6 条新用例；**同时含** UX-07 的 `SeatPicker.kt`；**并误带入** 2 个 QA 临时探针 | 混合 |
| `e48944d` | `feat(ux): UX-07 SeatPicker press scale animation` | **只有** 2 个 QA 临时探针（427 行） | 误入 |
| `9e55464` | `test(qa): 移除误提交的临时探针测试` | 删除 `TempQA*` 4 个文件，不删任何既有用例 | 本轮补正 |
| `acc1b58` | `docs(f05-01): 补私有声称、来源可见性与队长自提名的验收项` | F13/F14/F15 验收口径 + 实施文档同步 | 本轮补正 |
| `5b97a37` | `feat(ux): UX-09 edit preview dialog before/after card styling` | 另一工作流的对话卡片样式，**不含 F05-01** | 非本轮 |

**对后续维护的影响**：用 `git log --grep=F05` 找不到 F05-01 的真实起点，请以 `docs/F05_01_*` 与本文档为准。
**教训**：同一工作区存在并发提交方时，提交必须带显式 pathspec（`git commit -- <paths>`），不要 `git add -A`。

---

## 3. 新增代码与文件清单

| 文件 | 类型 | 行数 | 说明 |
|---|---|---|---|
| `core/replay/PlayerTraceModels.kt` | 新增 | ~100 | `PlayerTraceItem` / `PlayerTraceCategory`(7) / `PlayerTraceRelationship`(8) / `PlayerTraceFactLevel`(3，`INFERRED` 永不产出) |
| `core/replay/PlayerTraceBuilder.kt` | 新增 | ~318 | 纯函数 `object`，顺序：座位合法性 → 视角 → 剔 `retracted` → 时间窗 → 排序 → 座位聚合 → 筛选 |
| `feature/replay/PlayerTraceSection.kt` | 新增 | ~267 | `internal` 无状态视图；座位 `LazyRow` / 任务筛选 / 类别筛选 / 列表 `LazyColumn`（`PLAYER_TRACE_LIST_TAG`）/ 空态 / 私有来源标签 |
| `feature/replay/ReplayUiState.kt` | 改 | +20 | 4 个字段 + 3 个 action |
| `feature/replay/ReplayViewModel.kt` | 改 | +105 | 3 路筛选流合成 `TraceFilters` 后与 bundle/order/mode/blocked 做 5 路 `combine`；`visibleEvents` 只过滤一次，供 findings/timeline/playerTrace 共用 |
| `feature/replay/ReplayScreen.kt` | 改 | +211/-? | 页内「视角复盘 / 玩家时间线」切换，**不新增路由**；点击 → `SelectPosition` + 切回主视图 |
| `core/replay/TimelineBuilder.kt` | 改 | 18 | `roleLabel` / `claimTypeLabel` / `beliefTypeLabel` 由 `private` 改 `internal`，复用同一张标签表，行为零变化 |
| `res/values/strings.xml` | 改 | +14 | `replay_trace_*` / `replay_mode_*`，含 `replay_trace_private_source` |
| `app/src/test/.../util/MatchBuilder.kt` | 改（测试夹具） | +14 | `playerClaimed()` 增加可选 `visibility`；**原先硬编码 `PUBLIC`，导致私有声称路径在任何夹具里都造不出来** |

---

## 4. 验收矩阵

| 编号 | 验收点 | 结果 | 证据用例 |
|---|---|---|---|
| F02 | 只读、不写库、不改事件 | PASS | 类为纯函数；`ReplayViewModel` 无新增 `MatchRepository` 写方法调用 |
| F03 | 队长 / 提出队伍 / 被提名是不同事实 | PASS | `leaderRecordDoesNotImplyAlignment`、`nominationAndMissionTeamAreNotTheSameVehicle` |
| F04 | 个人投票只认 `votesBySeat` | PASS | `onlySummaryVoteNeverProducesPerSeatVotes`、`perSeatVotesAreAttributedToTheRightSeat` |
| F05 | 声称 ≠ 身份事实 | PASS | `claimedRoleIsNeverWrittenAsIdentityFact` |
| F06 | 私人笔记只在本人轨迹 | PASS | `observerSeesOnlyOwnPrivateNotes`、`godViewShowsPrivateNotesOnlyOnTheOwnersTrace` |
| F07 | 修订版本不重复出现 | PASS | `retractedVersionsAreNotShown` |
| F08 | 时间上界 | PASS | `eventsAfterTheTimeBoundAreExcluded` |
| F09 | B31 可见时刻 | PASS | `privateNoteVisibilityUsesAvailableFromOrderOnly` |
| F10 | ViewModel 默认座位 / 筛选 / 视角 / 位置联动 | PASS | `PlayerTraceViewModelTest`（7 条） |
| F11 | 渲染与交互 | PASS | `PlayerTraceSectionTest`（10 条） |
| F12 | 页内接线不新增路由 | PASS | `replayScreenRoutesTraceItemClickToSelectPosition` |
| F13 | 私有声称只在发言者本人轨迹 | PASS | `privateClaimIsInvisibleToNonOwnerObserver`、`privateClaimNeverAppearsInPublicTrace`、`privateClaimDoesNotProduceMentionedOnOtherSeatsTrace` |
| F14 | 来源可见性带到 UI | PASS | `privateDerivedItemsCarrySourceVisibility`、`privateSourceTagIsRenderedOnlyForPrivateItems` |
| F15 | 队长自提名不重复 | PASS | `selfNominatedLeaderProducesExactlyOneItem` |

### 4.1 P1 修复（QA 独立验收后暴露的三个缺陷）

| # | 缺陷 | 严重度 | 修法 |
|---|---|---|---|
| 1 | **私有声称越权**：`VisibleEventPolicy.ownerSeatOf(PlayerClaimed) = speakerSeat`，OBSERVER 视角下观察者本人的私有声称会通过过滤，而条目聚合的「被提及」分支会在**别人座位**的轨迹里产出条目，文案与公开声称一字不差 | 高（私有信息泄漏） | 私有声称只在 `playerSeat == speakerSeat` 时产 `SPEAKER`；判据是**记录者给的可见性标记**，不是「这句话有没有发生过」 |
| 2 | 私有来源无标记，用户会把「只有我记得的东西」当公开历史复述 | 中 | `PlayerTraceItem.sourceVisibility` 原样带来源可见性；UI 渲染「来自私有记录 · 仅本人可见」 |
| 3 | 队长自提名产出两条冗余记录（`LEADER/PROPOSED_TEAM` + `NOMINATED`） | 低（体验） | 同一事件只出 1 条 `LEADER`，`detail` 追加「 · 本人也在提案队伍中」；非队长队员仍各自 1 条 |

缺陷 1 是**工程师自查**发现的（不是 QA 用例抓到的）：根因是 `MatchBuilder.playerClaimed()` 硬编码 `PUBLIC`，
34 条用例里没有任何一条真的造出过私有声称 —— 这一类「夹具把边界焊死」的漏测值得在后续功能里重点防。

---

## 5. 测试

### 5.1 统计（本地实测，`acc1b58`）

| 项 | 基线 `ad70ea4` | 本轮 `acc1b58` | 变化 |
|---|---|---|---|
| JVM 测试类 | 74 | **77** | +3 |
| JVM 用例 | 573 | **613** | **+40** |
| 失败 / 错误 / 跳过 | 0 / 0 / 0 | **0 / 0 / 0** | — |
| `lintDebug` | 0 error / 52 warning / 2 info | **0 error / 56 warning / 2 info** | +4 warning，见 §5.3 |
| Debug APK | 20,647,837 B | **20,643,373 B** | −4,464 B（debug 签名，非 release） |

### 5.2 新增 40 条的构成

| 文件 | 条数 | 覆盖 |
|---|---|---|
| `PlayerTraceBuilderTest` | **23** | 归属正确性（8）、视角隔离（5）、B31 与时间边界（2）、修订（1）、脏数据（3）、排序与筛选（2）、事实等级（2） |
| `PlayerTraceViewModelTest` | **7** | 默认座位、切座位、非法座位回落、筛选收窄、空筛选不崩、视角切换影响轨迹、位置收窄 |
| `PlayerTraceSectionTest` | **10** | 选中态、点击切座位、任务筛选、类别筛选、空态、点击回调、长列表滚动与惰性渲染、滚动后点击仍取本条、页面路由、私有来源标签 |

其中 P1 追加 6 条：`privateClaimIsInvisibleToNonOwnerObserver`、`privateClaimNeverAppearsInPublicTrace`、
`privateClaimDoesNotProduceMentionedOnOtherSeatsTrace`、`privateDerivedItemsCarrySourceVisibility`、
`selfNominatedLeaderProducesExactlyOneItem`、`PlayerTraceSectionTest.privateSourceTagIsRenderedOnlyForPrivateItems`。

### 5.3 Lint 的 4 条增量

`0 error`；warning 52 → 56，全部为 `UnusedResources`（37 条，`records_empty_*` 等 UX-01 Tab 重构后未引用的既有文案）。
**指向 F05-01 任一文件的问题为 0 条**（`grep -c PlayerTrace lint-results-debug.xml` = 0）；
本轮新增的 14 条 `replay_trace_*` 文案全部被引用，零新增警告。
4 条增量的来源是并发工作流改动的 `RecordScreen.kt` / `CreateMatchScreen.kt` 相关资源，不属于本轮。

### 5.4 既有断言改动

**没有删除、没有跳过、没有放宽**任何既有用例。唯一一处既有断言被**收紧**：
`nominationAndMissionTeamAreNotTheSameVehicle` 原先允许 4 号出现 2 条「被提名」（含队长自提名那次），
现改为 1 条并额外断言「队长那条 `detail` 含『本人也在提案队伍中』」—— 新断言比旧的更具体。

---

## 6. 本地验证（隔离工作区，2026-11-03 17:09–17:26）

### 6.1 命令

```bash
git worktree add --detach ../RoundTrace-verify acc1b58
export JAVA_HOME=/c/Users/HK-info/android-build/jdk
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain
```

在**独立 worktree** 上验证的原因：同一仓库里另有工作流在跑 Gradle（17:03 / 17:08 均有写入），
上一轮 QA 就是被并发构建弄坏了 Kotlin 增量缓存（`cacheable_bak_qa`），三次 lint 全部失败。
隔离后本次一次性跑通，`cacheable_bak_qa` 也已删除。

### 6.2 结果

| 项 | 实测 |
|---|---|
| `testDebugUnitTest` | **BUILD SUCCESSFUL**；XML 77 个、613 条、0 失败、0 错误、0 跳过 |
| `lintDebug` | **BUILD SUCCESSFUL**；0 error / 56 warning / 2 information |
| `assembleDebug` | **BUILD SUCCESSFUL**；`app-debug.apk` = 20,643,373 B（`0.4.0` 线，debug 签名） |

### 6.3 一次真实失败（不掩盖）

首轮 `:app:packageDebug` 失败：

```
java.io.IOException: Failed to create '...\RoundTrace-verify\app\build\outputs\apk\debug\app-debug.apk'
```

生成的是 **0 字节**文件，磁盘剩余 84 GB，排除空间问题；判定为 Windows 文件占用/杀软扫描干扰（同一秒另一工作流也在写 APK）。
`rm -f` 后单跑 `:app:assembleDebug` **28 秒通过**，产物 20,643,373 B。
**这不是代码问题**，但结论里必须带上：APK 是第二次运行产出的。

---

## 7. 远端 CI 与产物

### 7.1 已完成的运行

| 运行 | HEAD | 结论 | 说明 |
|---|---|---|---|
| [41603187596](https://github.com/huangguaren/RoundTrace/actions/runs/41603187596) | `5d90638` | **success** | F05-01 主体（提交信息误标 UX-01） |
| [41605859191](https://github.com/huangguaren/RoundTrace/actions/runs/41605859191) | `e48944d` | **success** | 含 P1 修复 + 探针 |
| [41589288099](https://github.com/huangguaren/RoundTrace/actions/runs/41589288099) | `ad70ea4` | **success** | 基线（74 类 / 573 条） |

### 7.2 本轮 HEAD 的运行

见本文档末尾「追加」一节（推送后回填，不在推送前编造）。

---

## 8. 已知限制（本轮不修，已与 team-lead 确认）

| # | 限制 | 影响 | 为什么不修 |
|---|---|---|---|
| L1 | 主时间线 `TimelineBuilder` 仍用 `occurredOrder <= upTo`，**不认 B31**（QA P2-2） | 点一条 B31 笔记跳到 `at=3` 后，主时间线看不到这条；两视图「可见时刻」口径不一致 | 改它会波及既有时间线用例与「当前时刻」的既有语义，需要单独一轮并配产品口径 |
| L2 | 跳转后主视图无滚动/高亮（QA P2-3） | 用户点了条目、视图切回去了，但看不出落在哪 | 纯视觉反馈，`SelectPosition(occurredOrder)` 已被实测正确 |
| L3 | Compose 断言跑在 Robolectric 上 | 不覆盖字体裁切、深色模式对比度、超大字号、横屏 | 需人工目视一次；无设备验收（NX-05 已取消，不重设门槛） |
| L4 | 性能未测 | 本视图全量重算、无缓存（B29）；夹具仅几十条事件 | 无「数百条事件」的真实样本，**不编造耗时数字**；触发条件写进 B29 |
| L5 | 类别筛选是单选 | 不能「同时看投票 + 发言」 | PRD 只要求「类别筛选」；`PlayerTraceBuilder` 已接受 `Set`，改 UI 状态即可扩展 |
| L6 | 「当时判断 vs 最终真相」未做 | 只展示声称，不判断对错 | 属 F05 另一半，本轮范围外 |

---

## 9. 未解决问题

1. **提交信息与内容不符**（§2）：`5d90638` / `9b96332` 挂着 UX 标题却装着 F05-01。禁用强制推送，不做重写；已在本文档建立映射。
2. **并发提交的风险仍在**：另一工作流会在同一 worktree 里 `git add -A`。后续提交一律带显式 pathspec。
3. **`TempQA*` 探针已从版本库删除**（`9e55464`），但其 3 条断言已改写为正式用例保留在 `PlayerTraceBuilderTest` / `PlayerTraceSectionTest` 中，覆盖未降低。
4. **L2 / L3 需要一次人工目视**，建议与 UX 刷新轮的视觉验收合并做，不单开设备验收。

---

## 追加（推送后回填）

_待填：本轮 HEAD 的 Actions 运行 ID / 结论 / 测试 XML 实测数字 / `app-debug` artifact ID。_
