# RoundTrace 下一阶段技术实施文档（AI Coding 执行版）

**日期**：2026-11-03  
**仓库**：https://github.com/huangguaren/RoundTrace  
**起始参考 HEAD**：`9d940404afff48e459e13e457131173e71534de0`  
**配套 PRD**：`RoundTrace_Next_Requirements_2026-11-03.md`  
**原则**：只按当前工作区真实代码增量实施，不回滚已有绿色功能，不要求 NX-05 设备验收。

## 0. 开工检查与停止条件

1. `git status --short && git branch --show-current && git rev-parse HEAD && git log -5 --oneline`；先 `git fetch origin`，核对远端新变更，不覆盖用户本地未提交内容；分支开发，例如 `fix/nx06-atomic-edit-closeout`。
2. 阅读 `docs/decisions.md`（B29、B34、B35、B37）、`docs/NX06_ROLLBACK_REQUIREMENTS.md`、`docs/audit/next-round-final.md` 与现有仓库接口 / Robolectric 测试。
3. 先在当前基线运行 `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`，记录真实测试数、错误、耗时、提交 SHA；没有环境条件则写 `NOT RUN`，不能虚构。
4. 严禁 `git reset --hard`、`push --force`、自动删除用户工作区文件。不得删除用来复现 BUG 的失败测试。
5. 每阶段独立提交并报告 DONE / PARTIAL / BLOCKED。完成后验证远端 CI。发布或覆盖 `main` 须遵守仓库权限与保护规则。

## 1. Phase A：NX-06 安全历史编辑（最高优先，先测试）

### A1. 现状与精确修改位置

- `feature/record/RecordUiState.kt`：已有 `PendingEdit` 与 `ImpactWarning`，但 UI 状态没有完整待确认预览对象；只声明 `ConfirmPendingEdit` / `CancelPendingEdit` 不算实现。
- `feature/record/RecordViewModel.kt`：正常 `commit()` 仍直接写库后提示；独立 `ConfirmPendingEdit` 分支的 `runCatchingCancellable` 仅检查 `Result.isSuccess`，**未检查返回 `AppendResult.isSuccess`**，缺少在途防重复、并发校验和草稿保留完整契约。
- `feature/record/RecordScreen.kt`：仍显示“知道了”的事后弹窗；没有确认/取消待编辑动作；保留了旧 `showRollbackConfirm` 分支。
- `core/data/MatchRepository.kt` / `RoomMatchRepository.kt`：现有 `reviseEvent()`、`retractEvent()`、`retractAfterOrder()` 分散，缺少按指定事件 ID 的原子复合写入口。
- `core/database/dao/MatchEventDao.kt`：已有 `reviseWithSlot()` 与 `markRetracted(matchId,id)`，可在仓库外层事务中复用其原子语义；不得改用物理 `DELETE`。

### A2. 先补领域纯函数 `EditImpactPlanner`

建议新增 `core/data/edit/EditImpactPlanner.kt`（若现有分层更适合放纯领域层，按项目约定移动）。输入：当前一致 `MatchBundle`、被修订旧事件 ID、替换事件；输出稳定且可测试的 `EditImpactPlan`：

```kotlin
// 结构示意，按项目已有数据模型调整；不要照搬成与接口冲突的代码。
data class EditImpactPlan(
    val matchId: MatchId,
    val targetId: EventId,
    val replacement: MatchEvent,
    val expectedActiveRevision: List<ExpectedActiveEvent>,
    val affected: List<AffectedEvent>,
    val manualReviewReasons: List<String>
)

data class AffectedEvent(
    val eventId: EventId,
    val questNo: Int?,
    val reason: String,
    val operation: ImpactOperation // RETRACT / REVIEW_ONLY
)
```

- 决策依赖要同时考虑 **同任务 + 同提案**、通过/否决事实、任务结果、终局有效性，不允许 `logicalPosition > cutoff` 全选。
- 对 `TeamVoteRecorded` 与不可编辑决议 `TeamProposalResolved` 冲突，按 B35 标 `REVIEW_ONLY`，**绝不自动修订或删除决议**；自动处理不明时阻断确认或交明确二次操作，不隐瞒矛盾。
- 对 `MissionResolved` 修改，比较事件前后的 reducer/replay 状态：胜负计数变化本身不要求全部删除后续任务；只有确实失去有效前提的事件才能进入 `RETRACT`。如果规则无法可靠证明，`manualReviewReasons` 非空并拒绝破坏性提交。
- 对缺失、跨局、被撤销目标、不支持的类型，返回明确失败；没有影响时 `affected=[]`，不要伪造条目。
- **测试先行**：同提案依赖、其他提案/任务独立性、任务结果成功→失败、结果未变化、投票与不可改决议冲突、未记录队伍等。

### A3. 仓库事务 API

新增语义明确的 `applyConfirmedEdit(plan)` 或类似方法，在 `MatchRepository` 声明（纯领域 DTO，不暴露 Room Entity）。Room 实现：

1. 单个 `database.withTransaction { ... }` 内读取目标和必要事件集合，校验 matchId、事件类型、仍 active 的目标 ID、当前修订版本及快照令牌。
2. 在事务内部 **重新计算影响集合** 并与用户确认的列表严格比较。不得只信任 UI 传来的 `affectedIds`；若不一致，返回 `STALE_PREVIEW` / `CONFLICT` 且零写入。
3. 复用现有 `EventRevisionPolicy.check` 和 `eventDao.reviseWithSlot()`，正确沿用 `logicalPosition`、`revisionSeq`、`supersedesId`。
4. 针对已确认 `RETRACT` 列表逐个 `eventDao.markRetracted(matchId,id)`，要求每项命中恰好 1 行；遇到 0 行抛受控冲突异常，使整个事务回滚。
5. 事务内只重算一次 matches 投影、更新时间；若内部 `AppendResult.error != null` 不能报告成功。失败时留痕且不吞协程取消。
6. 并发双确认最多成功一次。所有事件历史仍保留，禁止 `DELETE FROM match_events`。

**快照令牌设计**：只比较 `updatedAt` 不足以保护并发；应根据本局当前 active 事件的稳定 ID、逻辑位置、修订序列等快照标识，或在事务中逐行复核全部影响关系。所有检验与写入同一事务，以防检查后又被写入改变。若 `MatchEvent` 不含 `revisionSeq`，从 DAO 实体读取/组装预期 token，不能虚构字段。

### A4. ViewModel 和 Compose 交互

设计状态机：

```text
BROWSING_HISTORY -> EDITING_DRAFT -> PREVIEW_READY
                                      | Cancel -> EDITING_DRAFT (zero history writes)
                                      | Confirm -> COMMITTING
                                                     | success -> SAVED
                                                     | stale/conflict -> EDITING_DRAFT + re-preview
                                                     | db failure -> EDITING_DRAFT + error
```

- 普通事件新建流程不受干扰；历史浏览期间只可打开已有可编辑事件，禁止新增。
- 用户点击历史编辑“保存”时只计算 `EditImpactPlan` 并放进 `RecordUiState.pendingEdit`，**绝不调用仓库写历史方法**。
- 对话框列修改前后值、按任务/提案分组的受影响条目、删除/仅提示标签、原因；按钮：“取消”“确认修改并重置 N 条”；有人工复核冲突时不要展示可执行的破坏性确认按钮。
- `CancelPendingEdit`：关闭预览、保留草稿和编辑会话，数据库历史不变。
- `ConfirmPendingEdit`：把 `isCommitting=true` 在调度协程前设置，禁止多次提交；调用复合仓库 API；检查**业务返回结果**，只有成功才清草稿/关闭弹窗；失败/冲突保留输入、提示重新确认。
- 移除无入口的 `showRollbackConfirm` 和不再允许的无差别 `RollbackToQuest` UI 入口；如保留底层低级回滚 API，必须限定用途并有测试。
- 历史任务模式下事件列表应与所选任务一致；当前游戏 `phase/status` 是真实进度，编辑面板字段按历史任务派生，不让用户误以为正在新增后续提案。

### A5. NX-06 必测矩阵（至少 18 条）

| ID | 用例 | 验证重点 |
|---|---|---|
| A01 | 点击历史任务并退出 | match_events、status、order 不变 |
| A02 | 进入历史后尝试新增 | 禁止并有清晰反馈 |
| A03 | 修改提案预览依赖 | 仅同提案的真实依赖事件 |
| A04 | 修改投票与决议一致 | B35 不改既有决议 |
| A05 | 修改投票与决议冲突 | 阻断自动改决议并明确提示 |
| A06 | 修改任务结果前后相同 | 不多删任何事件 |
| A07 | 修改任务结果改变终局前提 | 列出真正失效事件 |
| A08 | 无影响预览 | 0 条撤销且可以确认 |
| A09 | 点击取消 | DB 历史、派生状态零写入 |
| A10 | 取消后继续编辑 | 草稿完整保留 |
| A11 | 点击确认 | 修订 + 精确撤销同事务 |
| A12 | 中途注入 SQL 失败 | 所有 SQL 回滚，旧事件仍有效 |
| A13 | 预览期间被别人修订 | STALE_PREVIEW，零写入 |
| A14 | 连点两次确认 | 最多成功一次 |
| A15 | 使用另一局事件 ID | 不修改任何一局 |
| A16 | 修订链与重放 | logicalPosition 不变、revisionSeq 增长、旧版不可见 |
| A17 | 无关笔记/声称 | 不被撤销且隐私不泄漏 |
| A18 | 冲突/错误仓库返回 | `AppendResult.error` 不得显示 SAVED |

建议 `app/src/test/.../data/AtomicHistoricalEditTest.kt` 使用 Robolectric 真实 Room，`app/src/test/.../feature/RecordHistoricalEditViewModelTest.kt` 使用协程 test scheduler 和可注入仓库；避免仅 Mock UI 字符串而没有数据库断言。

### A6. Phase A 完成闸门

18 条专项测试、相关历史修订/回滚/导出与视角回归全部通过；用户取消零历史写入；确认只处理真实依赖 ID；并发/失败原子性成立；无被绕过的旧删除入口。**不能以 CI 绿色代替这些专门测试。**

## 2. Phase B：NX-02 响应式同一快照

**当前版本是 Room 2.6.1。** 此版本优先核验 `androidx.room.invalidationTrackerFlow(...)` 扩展是否可编译，不得盲用 Room 2.7+ 才正式出现的 `InvalidationTracker.createFlow(...)`；或者使用 `@Transaction` / `@Relation` 单个原子 DAO 查询。实现应先在现有工具链编译验证。官方参考：https://developer.android.com/reference/androidx/room/RoomDatabase 及 https://developer.android.com/jetpack/androidx/releases/room 。

推荐伪代码：

```kotlin
// 伪代码，必须在 Room 2.6.1 中验证 import、参数及触发时序
fun observeBundle(id: MatchId): Flow<MatchBundle?> =
    database.invalidationTrackerFlow(
        "matches", "participants", "role_assignments", "match_events"
    ).mapLatest {
        database.withTransaction { readBundleLocked(id) }
    }.distinctUntilChanged()
```

核心不是 `distinctUntilChanged()`，而是 **同一次数据库事务读取全部四表**。若使用 `@Transaction @Relation` 替代方案，也要证明一份发射来自同一次查询事务，并涵盖四张表的失效通知。不能通过全局 `Mutex` 或缓存掩盖数据版本问题；对局变化不能停在旧结果。

专项测试：初始订阅；参与者姓名变更；角色揭晓/权限变化；新增/撤销/修订事件；对局删除变 `null`；并发写入发生时每份发射的 `matches.status`、`roles`、`events` 相互一致；两个订阅者；取消旧收集；重复更新不出现明显多余 UI 发射。采用 `CompletableDeferred` / `TestCoroutineScheduler` + Robolectric Room 或受控事务阻塞点复现，不做靠 `Thread.sleep` 运气触发的测试。

## 3. Phase C：NX-03 首页重试和复盘性能

### C1. 首页状态重新建模

`HomeViewModel` 的 `.catch` 必须放在 `flatMapLatest` 里面创建的单次查询流上，而不是整个组合流末尾。示意：

```kotlin
combine(filterState, retryTrigger) { filter, attempt -> filter to attempt }
    .flatMapLatest { (filter, _) ->
        repository.observeSummaries(filter.toStatusFilter())
            .map<HomeMatches, HomeLoadState> { HomeLoadState.Data(it) }
            .onStart { emit(HomeLoadState.Loading) }
            .catch { emit(HomeLoadState.Error(it.message ?: "查询失败")) }
    }
```

> 泛型 `HomeMatches` 仅表示现有类型占位，不可直接复制编译。实际需用已有 `MatchSummary` 类型，并保持 `HomeUiState` 的筛选、空态及 rename/delete 错误处理契约。

测试 T-C01 首次失败重试成功；T-C02 失败两次第三次成功；T-C03 切换筛选不闪回旧结果；T-C04 多 collector 底层订阅预期；T-C05 重试时 Loading/错误消息准确；T-C06 `StateFlow` 收集停止后重新订阅正常。

### C2. 复盘性能与隐私回归

`ReplayViewModel` 已使用 `analyzer.analyze(snapshot, visibleHistory)`，保留该路径。测试 PUBLIC 快照计算次数 1 次；个人/上帝视角按需要计算，未揭晓不得打开上帝视角；时刻/视角快速切换不泄漏后来事实；撤销/修订后分析即时更新；不引入跨 `eventRevision` 的全局缓存。

### C3. 性能测量

在相同硬件/代码提交/样本规模下，记录优化前后：Home cold Flow 订阅次数，知识快照调用次数，单次典型复盘计算耗时（建议至少 30 次，记录 median/p95），内存/事件数范围、构建模式、计时方法。若无法复现实验则留 `NOT MEASURED`，绝不自造提升幅度。结果写入 `docs/audit/performance-next-round.md`。

## 4. Phase D：NX-01 异常输入保障

在 `EvidencePremiseTest` 基础上增加至少：非法玩家数（4 / 11）、任务号（0 / 6）、失败牌 `99 / -1`（既有保留）、不完整队伍却有失败牌、重复/越界 seat、失败牌与观察结果矛盾、7–10 人第 4 任务 1 张失败牌仍成功、缺失 `failCount` 但 `observedOutcome` 已知、已知结果仍计胜负。凡依赖畸形数据的 `RULE_CERTAIN` 必须抑制或重新限定可证实范围；其余 A01–A12、K01–K10、B34、时间边界、视角隔离测试必须保持通过。

## 5. Phase E：合并、CI 与交付

本轮不调整已有绿色 CI，新增 HEAD 推送后必须取得：

- `:app:testDebugUnitTest` PASS；`:app:lintDebug` PASS；`:app:assembleDebug` PASS；若 CI 外部原因失败必须分类，不得改写报告说 PASS。
- GitHub Actions URL、Commit SHA、测试报告/Debug APK artifacts；准确测试条数以 XML 报告汇总为准。
- `docs/audit/closeout-progress.md`：逐项测试/代码证据；`docs/audit/closeout-final.md`：每项 PASS/PARTIAL/FAIL/NOT RUN，已知限制；`docs/audit/performance-next-round.md`：性能实验。
- 5 个建议提交：`test(record): reproduce unsafe historical edit`; `fix(record): atomic confirmed edit`; `fix(data): transactional bundle observation`; `fix(home): recoverable query retries`; `test(audit): boundaries and performance evidence`。按真实改动拆分，不能提交不会编译的中间状态到主分支。
- `git diff --check`，最终报告列出旧与新行为、未完成原因和已知问题；未经明确允许不 force-push，不擅自发布新版本号或 Release。

## 6. AI Coding 一键启动指令

> 复制以下内容给编码 Agent，并附上本实施文档和 PRD 文件。

```text
你是 RoundTrace 项目的 Android/Kotlin 高级工程师，负责 2026-11-03 P0/P1 最终收口。

仓库：https://github.com/huangguaren/RoundTrace
审计参考：main@9d940404afff48e459e13e457131173e71534de0

请完整阅读 RoundTrace_Next_Requirements_2026-11-03.md 和
RoundTrace_Next_Implementation_2026-11-03.md。先检查最新 origin/main、工作区，
保护已有修改，确认此前绿色 CI 的范围。

按 Phase A → B → C → D → E 逐项真正修改代码、先写失败测试、修复、回归、分阶段提交。
不得只输出分析与计划，也不得在未实现之前标记 DONE。

P0 硬门槛：历史编辑确认前零历史写入；取消保留草稿；确认后单 Room 事务修订+精确依赖撤销；
并发冲突重新确认；失败/取消不误报成功；移除未使用的 PendingEdit 死代码或完整接通。

P1：observeBundle 单事务四表快照，不再依靠四路 combine 去重冒充一致；
首页查询异常后可真正重新订阅；复盘快照优化有量化证据；补齐证据推论边界。

NX-05 独立设备验收已取消；NX-04 保持现有 CI，全量回归后核对新 HEAD 的 GitHub Actions。
保留 Room v4、JSON v1/v2、B29/B34/B35/B37、隐私边界、已有事件审计链及全部现有测试。

最终产出改动后的测试统计、每阶段 commits、GitHub Actions URL、APK artifact、
closeout-progress/final/performance 三份报告以及残留风险，诚实标记未跑项目。
```

## 7. v0.5.0 后续开发衔接（非本轮实现）

P0/P1 全过之后另建 `docs/V05_REQUIREMENTS.md` 与 `docs/V05_IMPLEMENTATION.md`，先实现“按座位的玩家行为时间线”，后实现“当时判断 vs 最终真相”，最后实现“快速记录”；狼人杀独立模块待后续版本，不与阿瓦隆 reducer 混写。
