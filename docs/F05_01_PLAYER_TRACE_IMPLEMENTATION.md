# F05-01 玩家行为时间线 · 实现（v0.5.0）

- 基线提交：`ad70ea4`（= `origin/main`）
- 需求：`docs/F05_01_PLAYER_TRACE_REQUIREMENTS.md`
- 本轮范围：**只做 F05-01**，未触碰 Room schema、DAO 写路径、`observeBundle`、NX-06 编辑事务、`docs/decisions.md` F 节

## 1. 文件清单

### 新增（生产）

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/roundtrace/app/core/replay/PlayerTraceModels.kt` | 展示模型 + 3 个枚举（类别 / 关系 / 事实等级） |
| `app/src/main/java/com/roundtrace/app/core/replay/PlayerTraceBuilder.kt` | **纯函数**：事件 + 座位 + 时间 + 视角 → 记录列表 |
| `app/src/main/java/com/roundtrace/app/feature/replay/PlayerTraceSection.kt` | Compose 视图（座位 / 任务 / 类别筛选 + 列表 + 空态） |

### 新增（测试）

| 文件 | 条数 |
|---|---|
| `app/src/test/java/com/roundtrace/app/core/replay/PlayerTraceBuilderTest.kt` | 18（P1 修复后 **23**） |
| `app/src/test/java/com/roundtrace/app/feature/PlayerTraceViewModelTest.kt` | 7 |
| `app/src/test/java/com/roundtrace/app/feature/PlayerTraceSectionTest.kt` | 9（P1 修复后 **10**） |

#### P1 修复新增的 6 条（`PlayerTraceBuilderTest`）

| 用例 | 钉住什么 |
|---|---|
| `privateClaimIsInvisibleToNonOwnerObserver` | 观察者不是发言者时，看任何座位都不出现私有声称条目 |
| `privateClaimNeverAppearsInPublicTrace` | 公开视角下私有声称产出 0 条（硬红线） |
| `privateClaimDoesNotProduceMentionedOnOtherSeatsTrace` | 私有声称不从「被提及」侧门进他人轨迹；GOD 同样；本人有且仅有 1 条 `SPEAKER` |
| `privateDerivedItemsCarrySourceVisibility` | 私有笔记/私有声称的 `sourceVisibility == PRIVATE_OBSERVER`，公开来源为 `PUBLIC` |
| `selfNominatedLeaderProducesExactlyOneItem` | 队长自提名同一事件只 1 条且 `detail` 含「本人也在提案队伍中」；非队长队员仍有自己的 `NOMINATED` |
| `PlayerTraceSectionTest.privateSourceTagIsRenderedOnlyForPrivateItems` | 私有来源渲染「来自私有记录 · 仅本人可见」，公开来源不渲染 |

### 修改

| 文件 | 改动 | 风险 |
|---|---|---|
| `app/src/test/java/com/roundtrace/app/util/MatchBuilder.kt`（测试工具） | `playerClaimed()` 增加可选参数 `visibility`，替换原先硬编码的 `PUBLIC` | 只改测试夹具，不动生产代码；默认值保证既有调用不变 |
| `core/replay/TimelineBuilder.kt` | 3 个标签函数 `private` → `internal`（角色 / 发言类型 / 判断类型），供 `PlayerTraceBuilder` 复用同一张标签表 | 仅可见性，行为零变化 |
| `feature/replay/ReplayUiState.kt` | 新增 4 个字段 + 3 个 action | 默认值保证既有构造点不受影响 |
| `feature/replay/ReplayViewModel.kt` | 新增 3 路筛选流与 `playerTraceItems` 派生；`findings/timeline/trace` 共用同一份可见历史 | 组合 5 路 `combine`，未新增 DB 读取 |
| `feature/replay/ReplayScreen.kt` | 页内「视角复盘 / 玩家时间线」切换 + 子视图接入 | 不新增 NavHost 路由 |
| `core/replay/PlayerTraceModels.kt`（P1） | `PlayerTraceItem` 增加 `sourceVisibility`（默认 `PUBLIC`） | 默认值保证既有构造点不受影响 |
| `core/replay/PlayerTraceBuilder.kt`（P1） | `item()` 写入来源事件可见性；私有声称只在本人轨迹产出；队长自提名只出 1 条 | 收紧，不新增可见信息 |
| `feature/replay/PlayerTraceSection.kt`（P1） | 私有来源渲染「来自私有记录 · 仅本人可见」 | 仅新增一个标签 |
| `res/values/strings.xml` | 新增 13 条文案（P1 再加 1 条 `replay_trace_private_source`，共 14） | — |

## 2. 纯函数契约

```kotlin
PlayerTraceBuilder.build(
    events: List<MatchEvent>,
    playerSeat: SeatId,
    atOrder: Int,
    mode: Perspective,
    observerSeat: SeatId?,
    playerCount: Int,
    questFilter: Int? = null,
    categoryFilter: Set<PlayerTraceCategory>? = null
): List<PlayerTraceItem>
```

`core/replay` 内**零 Android / Room / DAO / Compose import**（与 `TimelineBuilder` 同标准）。
非法座位（`playerSeat !in 1..playerCount`、`playerCount <= 0`）返回空列表，不抛异常。

### 2.1 执行顺序（顺序本身就是安全边界）

```
座位合法性 → VisibleEventPolicy.forPerspective → !retracted → 时间窗
          → 按 occurredOrder 排序 → 座位聚合 → 任务/类别筛选 → 稳定排序
```

「先视角后聚合」是硬约束：反写会让私有字段先进内存，再靠输出分支兜底，
任何一处漏兜底就是泄漏。

### 2.2 逐事件映射

| 事件 | 产出条目 | 关系 | 事实等级 |
|---|---|---|---|
| `QuestStarted` | 当 `leaderSeat == seat`：队长（开启任务） | `LED_QUEST` | RECORDED |
| `TeamProposed` | 队长优先：`leaderSeat == seat` → 队长（提出队伍）；否则 `seat in teamSeats` → 被提名上车。**同一事件只出 1 条**（P1 修复，见 §2.3） | `PROPOSED_TEAM` / `ON_PROPOSED_TEAM` | RECORDED |
| `TeamVoteRecorded` | **仅**当 `votesBySeat[seat] != null`：个人投票 | `CAST_VOTE` | 选择为「未知」时 `UNKNOWN`，否则 `RECORDED` |
| `MissionResolved` | **仅**当 `teamSeats != null && seat in teamSeats`：任务队员 | `ON_MISSION_TEAM` | RECORDED（`verified == false` 时文案带「未核实」） |
| `PlayerClaimed`（公开） | `speakerSeat == seat`：发言；否则 `seat in targetSeats`：被提及 | `SPOKE` / `NAMED_BY_OTHER` | RECORDED |
| `PlayerClaimed`（`PRIVATE_OBSERVER`） | **仅**当 `speakerSeat == seat`：发言。**禁止**对被提及者产出条目（P1 修复，见 §2.3） | `SPOKE` | RECORDED |
| `PrivateNoteAdded` | 仅当 `observerSeat == seat`：私人判断 | `OWN_BELIEF` | RECORDED |
| 其它（决议 / 刺杀 / 终局 / 弃局） | 不产出 | — | — |

文案里的边界声明是**需求的一部分**，不是修饰：

- 队长：「仅为队长身份记录，不推断阵营」
- 被提名：「被提议上车 ≠ 实际执行任务」
- 任务队员：`failCount` 已知时写「（不推断是谁出的）」
- 声称：「声称内容不等于身份事实」/ 普通发言「仅为本人说法」

`null` 与 `0` 的区分落在文案上：`failCount == null` → 「失败牌数：未记录」，
`0` → 「失败牌数：0（确定零失败牌）」。

### 2.3 P1 修复（QA 验收后追加的两处）

1. **私有声称口径对齐私人笔记**。`PRIVATE_OBSERVER` 的 `PlayerClaimed` 只在**发言者本人**
   的轨迹里产出 `SPOKE`，不再从「被提及」这条侧门进到别人的参与记录。
   判据是**记录者给的可见性标记**，不是「这句话有没有发生过」：标成私有就是不想让它
   被当成公开历史。GOD 视角同样遵守（私有声称不进他人轨迹）。
2. **来源可见性一路带到 UI**。`PlayerTraceItem.sourceVisibility` 原样带上来源事件的
   `EventVisibility`（默认 `PUBLIC`），卡片在私有来源时渲染「来自私有记录 · 仅本人可见」。
   没有这个标记时，私有声称与公开声称产出的文案字面完全一致，用户会把「只有我记得的东西」
   当成公开历史复述 —— 这正是本次要堵的洞。
3. **队长自提名不再重复**。`leaderSeat == seat && seat in teamSeats` 时同一事件只发 1 条
   `LEADER/PROPOSED_TEAM`，`detail` 追加「 · 本人也在提案队伍中」；
   非队长但在队伍里的座位仍各自有 1 条 `NOMINATED`。

配套：`MatchBuilder.playerClaimed()` 增加可选参数 `visibility`（原先硬编码 `PUBLIC`，
导致「私有声称」这条路径在任何夹具里都造不出来 —— 零覆盖正是这么来的）。

## 3. 时间边界（B31）

`PrivateNoteAdded` 用 `availableFromOrder` 判定可见性，其余事件用 `occurredOrder`。

条目的 `occurredOrder` 字段对私人笔记取 `availableFromOrder`，原因：
补录笔记的物理位置（`occurredOrder`）晚于它生效的时刻，若把物理位置交给
「点击跳转」，用户在 `at = 100` 时点一条 `occurredOrder = 900` 的笔记，
复盘位置会被推到未来 —— 那既不是「当时」，也把玩家的判断放到了它生效之前。
B31 已经把 `availableFromOrder` 定义为权威可见时刻，这里沿用同一定义。

## 4. ViewModel

```kotlin
ReplayUiState.playerTraceSeat: SeatId?
ReplayUiState.playerTraceQuestFilter: Int?
ReplayUiState.playerTraceCategoryFilter: PlayerTraceCategory?
ReplayUiState.playerTraceItems: List<PlayerTraceItem>
```

- 3 个筛选流先合成 `TraceFilters`，再与 `observeBundle / requestedOrder / requestedMode / godViewBlocked`
  做 5 路 `combine`，全部在**同一个 bundle 快照**里派生：没有新增 DB 读取、没有跨快照缓存（B29）。
- 默认座位：观察者合法 → 观察者；否则 1 号；显式选择越界 → 回落到默认（UI 座位条只列 1..人数，选中态必须对得上）。
- `canOpenGodView == false` 时请求上帝视角仍走既有拦截（模式不变 + `godViewBlocked`），
  玩家时间线不会因为「想看全部」而绕开闸门。

## 5. Compose

- `PlayerTraceSection` 无状态：三个筛选与列表数据全部由外部传入，视图不做过滤、不做推断。
- `ReplayScreen` 顶部新增页内切换（两个 `FilterChip`），**不新增 NavHost 路由**；
  点击条目 → `ReplayAction.SelectPosition(order)` + 切回主视图。
- 玩家时间线视图内**保留视角切换**：私人判断是否出现完全取决于视角，
  把开关藏到另一个视图会让人误判「这个人没有记录」。
- 座位条 / 任务条 / 类别条用 `LazyRow`（10 人局也不会溢出），列表用 `LazyColumn`。
- 卡片三行：类别 + 任务 + 事实等级 + 私有来源标记 → 摘要 → 明细；
  `RECORDED` 不挂事实等级标签，`UNKNOWN` 显式标注，私有来源另挂
  「来自私有记录 · 仅本人可见」。
- 空态文案：「该玩家在当前时间范围内暂无可展示的记录」。

## 6. 验证结果（本地，2026-11-03，含 P1 修复后复测）

```bash
export JAVA_HOME=/c/Users/HK-info/android-build/jdk
./gradlew :app:testDebugUnitTest --no-daemon   # BUILD SUCCESSFUL
./gradlew :app:lintDebug --no-daemon           # BUILD SUCCESSFUL
./gradlew :app:assembleDebug --no-daemon       # BUILD SUCCESSFUL
```

### 6.1 F05-01 首轮（提交 `5d90638`）

| 项 | 基线 `ad70ea4` | 首轮 | 变化 |
|---|---|---|---|
| JVM 测试类 | 74 | **77** | +3 |
| JVM 用例 | 573 | **607** | +34 |
| 失败 / 错误 / 跳过 | 0 / 0 / 0 | **0 / 0 / 0** | — |
| `lintDebug` | 0 error / 52 warning | **0 error / 52 warning** | 无新增 |

新增 34 条的构成：`PlayerTraceBuilderTest` 18 + `PlayerTraceViewModelTest` 7 + `PlayerTraceSectionTest` 9。

### 6.2 P1 修复后复测（HEAD `5d90638` + 本轮改动，另含他人并发改动）

| 项 | 值 |
|---|---|
| JVM 测试类 / 用例 | **81 类 / 630 条**（其中本轮新增 6 条） |
| 失败 / 错误 / 跳过 | **0 / 0 / 0** |
| `lintDebug` | **0 error / 57 warning / 2 info** |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` = **20,815,585 B** |

说明：

- 81 类 / 630 条里含 QA 的 2 个临时探针类（`TempQALeakProbeTest` 4 条、`TempQALeakVmProbeTest` 3 条）
  与其它并发改动；**我的净新增是 6 条**（`PlayerTraceBuilderTest` 18→23、`PlayerTraceSectionTest` 9→10）。
- lint 的 57 warning 全部是 `UnusedResources`（`R.string.records_empty_*` 一类的既有未引用文案，
  由 UX-01 的 Tab 重构产生）；**本轮新增的 14 条 `replay_trace_*` 文案全部被引用，零新增警告**。
- APK 体积与首轮不可直接比较：期间 HEAD 前进到 `5d90638`（UX-01 重构移除了占位页），基线不同。

### 6.3 被改写的既有断言（逐条列出，均为「收紧/改写为新语义」，未删除、未放宽、未跳过）

| 文件 | 用例 | 旧断言 | 新断言 | 为什么 |
|---|---|---|---|---|
| `PlayerTraceBuilderTest` | `nominationAndMissionTeamAreNotTheSameVehicle` | 4 号被提名 2 条（含「队长自提名」那次） | 4 号被提名 **1 条**（任务 1 第 2 次提案）；任务 2 那次改为断言「只出 1 条 `LEADER` 且 `detail` 含『本人也在提案队伍中』」 | 队长自提名不再重复发「被提名」（§2.3-3）；新断言比旧的更具体 |
| `qa/TempQALeakProbeTest` | `observerPerspectiveMentionedItemContent` | 观察者（=声称者）看 5 号时有 1 条「被提及」；GOD 下 5 号有 1 条 | 5 号 **0 条**；改为断言「1 号本人有且仅有 1 条 `SPEAKER` 且 `sourceVisibility == PRIVATE_OBSERVER`」；GOD 下同样 0 条 | 私有声称口径对齐（§2.3-1），新期望更严格 |
| `qa/TempQALeakProbeTest` | `mentionedItemDoesNotMarkPrivateSource` | 私有来源的「被提及」与公开来源文案完全一致（记录缺陷） | 私有声称 **0 条**「被提及」；本人 `SPEAKER` 的 `sourceVisibility == PRIVATE_OBSERVER`；公开来源 `MENTIONED` 的 `sourceVisibility == PUBLIC` | 缺陷已修，探针断言同步到新语义 |
| `qa/TempQALeakVmProbeTest` | `observerPerspectiveMentionedItemStaysMinimal` | OBSERVER 下 5 号有 1 条「被提及」 | 5 号 **0 条**且仍保留公开 `NOMINATED`（防空列表假通过）；切到 1 号本人有 1 条 `SPEAKER` 且带私有来源 | 同上 |

## 7. 取舍（需产品确认，已按「宁可少显示、不可越权」落子）

1. **私人笔记进不进时间线**：进，但**只出现在笔记主人自己的轨迹里**。
   即使上帝视角，也不会把「1 号私下怀疑 2 号」放到 2 号的记录里 ——
   那是 1 号的行为，放进 2 号的参与记录等于暗示 2 号知情。
   （`godViewShowsPrivateNotesOnlyOnTheOwnersTrace` 钉住。）
5. **私有声称同样「只在本人轨迹」**（P1 修复）：`PRIVATE_OBSERVER` 的 `PlayerClaimed`
   不再对被提及者产出条目，与私人笔记同一保守口径（§2.3-1）。
   首轮我在这里的判断是错的（当时理由「声称是真实发生过的发言」），已按「判据 = 可见性标记」纠正。
6. **私人笔记的跳转锚点**：取 `availableFromOrder` 而不是 `occurredOrder`（见 §3）。
   代价是条目 `occurredOrder` 与来源事件的物理位置不同 —— 但它保证了「跳过去一定看得见、且不会越过当前时刻」。
3. **汇总票不产生任何个人记录**：即使「赞成 5 / 反对 3」是明确的，也不摊派。
   宁可少一条，也不给「1 号投了赞成」这种没证据的断言。
4. **座位越界回落到默认**而不是报错：人数可能被改小或导入了别的对局，
   静默回到默认比抛异常/留下一个不存在的选中态更安全。
5. **`TeamProposalResolved` 不产条目**：决议没有「谁」的指向，列在某个人的轨迹里会变成暗示。

## 8. 已知未覆盖的边界

1. **Compose 断言仍非真机**：Robolectric 只验证「该出现的出现、不该出现的没出现、能滚动」，
   不覆盖字体裁切、深色模式对比度、超大字号、横屏。需人工目视一次。
2. **未做「当时判断 vs 最终真相」**：F05 的第二半（v0.5.0 后续项）未动工；
   本轮 `INFERRED` 等级只是预留位，没有任何产线代码会产出它。
3. **`claimedRole` 与真实身份的交叉校验**：本视图只展示「声称」，不判断对错；
   对错属于揭晓后的对照视图（未实现）。
4. **私人笔记的「被判断对象」不会出现在对方轨迹里**（见 §7-1），
   如果产品希望「我怀疑过谁」也能从对方视角追溯，需要新增独立视图，不能塞进本表。
5. **类别筛选是单选**：PRD 只要求「类别筛选」，未要求多选；如需多选，
   `PlayerTraceBuilder` 已接受 `Set<PlayerTraceCategory>`，只需改 UI 状态类型。
6. **未纳入类别的事件**：刺杀 / 终局 / 弃局（对局级），以及「组队决议」（无人物指向）。
   若要做「全局人物参与度」，需要单独定义口径。
7. **性能**：全量重算，未做缓存（B29）。夹具规模为几十条事件；若单场进入数百条，
   需要重新评估（B29 的触发条件）。
8. **未修（team-lead 已决定列为已知限制，本轮范围外）**：
   - `TimelineBuilder` 的主时间线仍用 `occurredOrder <= upTo`，**不认 B31**
     （QA P2-2）：跨视图的「可见时刻」口径不一致，改它会波及既有用例；
   - 跳转后主视图没有滚动/高亮（QA P2-3）：`SelectPosition(occurredOrder)` 已被实测正确，
     视觉反馈留下一轮；
   - QA 的 2 个临时探针文件仍在 `app/src/test/java/com/roundtrace/app/qa/`
     （team-lead 说已删，实际未删）；我把其中 3 条断言改写为新语义保留，
     是否删除由 team-lead / QA 决定。
