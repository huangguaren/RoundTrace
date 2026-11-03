# F05-01 玩家行为时间线 · 需求（v0.5.0）

- 文档状态：已实现（v0.5.0 第一项）
- 对应实现：`docs/F05_01_PLAYER_TRACE_IMPLEMENTATION.md`
- 上游依据：`docs/decisions.md` B29 / B31 / B34 / B35 / B37；`docs/audit/p2-closeout-final.md` §8-6

## 1. 背景

复盘页现在有两种「看整局」的方式（视角分析 + 时间线），但都**以事件为中心**。
真实使用里最常见的问题是「1 号到底上过几次车」「他这次投的什么票」——
用户只能在几十条时间线里靠肉眼找，而且很容易把「被提名」读成「实际参与任务」，
把「7 号声称派西维尔」读成「7 号是派西维尔」。

## 2. 目标

以 **1–N 号玩家**为中心，列出他有**证据支持**的参与记录，每条可跳到对应复盘时刻。

三条不可妥协的性质：

1. **不把推测当事实**：能不显示就不显示，显示时必须标注事实等级。
2. **不泄漏私有信息**：私人笔记只对合法观察者可见，且不进入公开事实。
3. **可追溯**：每条记录都能指回唯一来源事件与时间位置。

## 3. 非目标（本轮明确不做）

- 「最终真相对照」视图（当时判断 vs 终局真相）；
- 快速记录 / 语音录入 / 狼人杀规则；
- 多局统计、玩家画像、跨局聚合；
- 任何形式的写操作（不新增事件、不修改历史、不动 `observeBundle` / 编辑事务）；
- 撤销 / 编辑入口（沿用 B35/B37 的白名单，本视图只读）。

## 4. 数据源与展示边界

| 类别 | 数据源字段 | 可显示 | 禁止 |
|---|---|---|---|
| 队长 | `QuestStarted.leaderSeat`、`TeamProposed.leaderSeat` | 该座位被**记录**为队长 / 提案者 | 不得据此推断阵营 |
| 被提议上车 | `TeamProposed.teamSeats` | 在**被提议的**队伍中 | 不得写成「实际参与任务」 |
| 执行任务 | `MissionResolved.teamSeats` | 记录为任务队员（注明是否核实） | 不得从失败牌推测是谁出的 |
| 个人组队投票 | `TeamVoteRecorded.votesBySeat[seat]` 非空 | 明确赞成 / 反对 / 未知 | **只有汇总票时不得分摊到个人** |
| 声称 / 发言 | `PlayerClaimed.speakerSeat`、`targetSeats` | 「X 号说了…」与「X 号被提及…」分开 | 声称 ≠ 身份事实 |
| 私人判断 | `PrivateNoteAdded.observerSeat`、`targetSeats` | 仅合法观察者本人可见 | 不得进公开事实或他人视角 |
| 私有声称（`PRIVATE_OBSERVER` 的 `PlayerClaimed`） | `speakerSeat` | 仅**发言者本人**的轨迹里可见 | 不得对被提及者产出「被提及」条目；GOD 视角同样（F13） |

不产出条目的数据：`TeamProposalResolved`（无人身指向）、`AssassinationRecorded`、
`MatchFinalized`、`MatchAbandoned`（对局级事件，不属于「某个玩家的参与记录」）。

## 5. 事实等级

| 等级 | 含义 | UI 表现 |
|---|---|---|
| `RECORDED` | 事件明确记录了该字段 | 正常展示，不加标签 |
| `UNKNOWN` | 事件存在，但字段被记录为「未知」（如逐人票选了「未知」） | 卡片上显式加「（记录为未知）」 |
| `INFERRED` | 从记录推导 | **当前实现永不产出**；任何推测型记录必须先改需求 |

## 6. 时间与视角规则

1. **先按视角过滤，再做座位聚合**（顺序不可反）。
   - `PUBLIC`：只看 `EventVisibility.PUBLIC`；
   - `OBSERVER_AT_TIME`：公开 + 属于该观察者的私有事件；
   - `GOD_AFTER_REVEAL`：全部。
2. **时间上界 `at`**：普通事件 `occurredOrder <= at`。
3. **B31 例外**：`PrivateNoteAdded` 只看 `availableFromOrder <= at`，不看 `occurredOrder`。
   私人笔记条目的**跳转定位点**取 `availableFromOrder`（它的权威可见时刻），
   否则点一下会把复盘位置推到当前时间之后。
4. **只读 active 版本**：`retracted == true` 一律不出现；同一逻辑位置只有一条 active。
5. **顺序**：按 `occurredOrder` 升序；同一事件的多条关系按来源事件 id 稳定排序。

## 7. 交互

- 复盘页顶部增加页内切换：「视角复盘 / 玩家时间线」，**不新增导航路由**。
- 玩家时间线包含：座位选择条（1..人数）、任务筛选（全部 / 第 1–5 任务）、类别筛选（全部 + 7 类）、记录列表。
- 点击条目 → 调用既有 `ReplayAction.SelectPosition(occurredOrder)` 并把视图切回复盘主视图
  （停留在列表里看不到「跳」的效果）。
- 默认座位：观察者座位合法则选中观察者，否则 1 号；越界选择回落到默认。
- 空态文案固定：**「该玩家在当前时间范围内暂无可展示的记录」**。

## 8. 验收标准

| 编号 | 验收点 | 证据 |
|---|---|---|
| F02 | 一次任务多次提案：提名队伍与执行队伍各自准确，不混成一辆车 | `PlayerTraceBuilderTest.nominationAndMissionTeamAreNotTheSameVehicle` |
| F03 | 逐人票可归属；只有汇总票时个人记录不出现「投了赞成/反对」 | `onlySummaryVoteNeverProducesPerSeatVotes`、`perSeatVotesAreAttributedToTheRightSeat` |
| F04 | 发言者 vs 被提及者分别出现且关系不同；`claimedRole` 不写成真实身份 | `speakerAndMentionedAreSeparateRelations`、`claimedRoleIsNeverWrittenAsIdentityFact` |
| F05 | PUBLIC 无私人笔记；OBSERVER 只见自己的；GOD 可见 | `publicPerspectiveSeesNoPrivateNotes`、`observerSeesOnlyOwnPrivateNotes`、`godViewShowsPrivateNotesOnlyOnTheOwnersTrace` |
| F06 | `availableFromOrder > at` 的笔记不出现，`<=` 才出现 | `privateNoteVisibilityUsesAvailableFromOrderOnly` |
| F07 | `retracted = true` 的旧版本不出现；同一逻辑位置只有一条 active | `retractedVersionsAreNotShown` |
| F08 | 非法座位 / `teamSeats = null` / `failCount = null` 与 `0` 区分 | `illegalSeatReturnsEmptyListInsteadOfThrowing`、`unknownMissionTeamMeansNoMissionMember`、`missionFailCountUnknownIsNotZero` |
| F09 | 时间上界之外的事件不出现 | `eventsAfterTheTimeBoundAreExcluded` |
| F10 | ViewModel 默认座位、三个筛选、视角联动、位置联动 | `PlayerTraceViewModelTest`（7 条） |
| F11 | 渲染：选中态、筛选变化、空态、点击回调、长列表滚动、惰性渲染 | `PlayerTraceSectionTest`（9 条） |
| F12 | 页内接线：点击条目走既有 `ReplayAction.SelectPosition(occurredOrder)`，不新增动作/路由 | `replayScreenRoutesTraceItemClickToSelectPosition` |
| F13 | 私有声称（`PRIVATE_OBSERVER` 的 `PlayerClaimed`）只在发言者本人轨迹出现，不从「被提及」侧门进他人轨迹 | `privateClaimIsInvisibleToNonOwnerObserver`、`privateClaimNeverAppearsInPublicTrace`、`privateClaimDoesNotProduceMentionedOnOtherSeatsTrace` |
| F14 | 条目的来源可见性必须带到 UI，私有来源渲染「来自私有记录 · 仅本人可见」 | `privateDerivedItemsCarrySourceVisibility`、`PlayerTraceSectionTest.privateSourceTagIsRenderedOnlyForPrivateItems` |
| F15 | 队长自提名时同一事件只出 1 条队长记录（detail 标注本人也在队伍中），非队长队员仍有各自 1 条 | `selfNominatedLeaderProducesExactlyOneItem` |
