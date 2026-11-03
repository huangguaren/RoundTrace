# RoundTrace P0/P1 收口进展（逐项证据）

日期：2026-11-03
基线：`main@9d94040`（验收报告所指 HEAD）
当前：`main@c5def57`（本地，待推送）
环境：Windows / JDK 17.0.13 (Microsoft) / Gradle wrapper / 16 逻辑核；
命令：`./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain '-Pkotlin.compiler.execution.strategy=in-process'`

## 0. 数字总览（本次实测，来自 test-results XML 汇总）

| 指标 | 基线 `9d94040` | 收口 `c5def57` | 差值 |
|---|---|---|---|
| JVM 测试类 | 63 | **71** | +8 |
| JVM 用例 | 446 | **543** | +97 |
| 失败 / 错误 / 跳过 | 0 / 0 / 0 | **0 / 0 / 0** | — |
| `lintDebug` | 0 error | **0 error / 52 warning** | — |
| `assembleDebug` | 通过 | **通过** | — |
| APK | — | `app/build/outputs/apk/debug/app-debug.apk`，20,646,989 B，versionCode 4 / 0.4.0 | — |

新增 97 条构成：NX-06 +44（3 类）、NX-03/NX-01 +34（4 类）、NX-02 +11（1 类）、QA 缺陷回归 +8（0 新类）。

## 1. Phase A：NX-06 历史安全编辑（P0）

### 1.1 代码证据

| 要求 | 实现位置 | 说明 |
|---|---|---|
| 依赖分析纯函数 | `core/data/edit/EditImpactPlanner.kt`（628 行新增） | 零 Android/Room 依赖；输入 bundle+tokens+target+replacement，输出密封结果 |
| 提案依赖只按同任务同提案 + 归属判定 | 同上 `proposalFindings` | 任务结果只归属于「该任务最后一个 APPROVED 决议」；无决议时降 `REVIEW_ONLY`，不猜 |
| 投票冲突不改决议（B35） | 同上 `voteFindings` | 只产出 `REVIEW_ONLY`；冲突信号复用 `ReplayEngine` 的 `DECISION_VOTE_CONFLICT` |
| 任务结果只按终局性变化 | 同上 `missionFindings` | 非终局→终局撤后续结构性事件；终局→非终局只提示；终局性不变 0 撤销 |
| 单事务原子提交 | `core/data/RoomMatchRepository.kt` `applyConfirmedEdit` | 单 `withTransaction`；事务内重读+重算；令牌逐条比对；`markRetracted` 每项 `check(affected==1)`；投影只重算一次 |
| 确认前零写入 | `feature/record/RecordViewModel.kt` `commit()` | 编辑态只 `requestEditPreview` 后 return，无任何仓储写调用 |
| 取消保留草稿 | 同上 `CancelPendingEdit` | 只清 `pendingEditFlow`，不碰 `draftFlow`/`editSessionFlow` |
| 业务结果判定 | 同上 `ConfirmPendingEdit` | 按 `EditApplyResult` 的 Success/StalePreview/Conflict/Failed 分支，不看 `Result.isSuccess` |
| UI 预览确认 | `feature/record/RecordScreen.kt` | 删除回滚确认与「知道了」弹窗；新增影响预览对话框；`requiresManualReview && isDestructive` 不渲染确认按钮 |

### 1.2 A01–A18 用例映射（实测通过）

| ID | 用例 |
|---|---|
| A01 浏览零写库 | `previewOfHistoricalEditWritesNothing`、`exitingHistoryModeRestoresLatestPositionWithoutWriting` |
| A02 禁止新增 | `historyModeRejectsNewEvents` |
| A03 同提案依赖 | `proposalEditExposesSameProposalDependenciesOnly`、`proposalEditRetractsOnlySameProposalDependencies` |
| A04 投票一致 | `voteEditConsistentWithDecisionDoesNotTouchResolution` |
| A05 投票冲突 | `voteEditConflictingWithDecisionIsReviewOnlyAndNeverRewritesResolution` |
| A06 结果前后相同 | `missionEditWithSameOutcomeDeletesNothing`、`resubmittingIdenticalProposalContentRetractsNothing`（D2 回归） |
| A07 结果改变终局 | `missionEditThatMakesMatchDecidedRetractsOnlyImpossibleEvents` |
| A08 无影响 | `savingIdenticalProposalContentHasNoImpact`（D2）、`missionEditChangingOnlyCountsRetractsNothing` |
| A09 取消零写入 | `cancelAfterPreviewLeavesHistoryAndDerivedStateUntouched`、`cancelKeepsDraftAndEditSessionWithoutWriting` |
| A10 取消保留草稿 | `cancelKeepsDraftAndEditSessionWithoutWriting`、`reopeningSameTargetAfterCancelKeepsUserInput` |
| A11 确认同事务 | `confirmedEditWritesRevisionAndRetractionsInOneTransaction` |
| A12 注入失败回滚 | `injectedFailureRollsBackEveryWriteOfTheEdit` |
| A13 过期预览 | `stalePreviewIsRejectedWithoutAnyWrite`、`stalePreviewRefreshesPlanAndKeepsInput`、`staleTokenSetIsRejectedAsInconsistentSnapshot` |
| A14 双确认一次 | `doubleConfirmSucceedsAtMostOnce`、`doubleConfirmIssuesOnlyOneWrite` |
| A15 跨局隔离 | `crossMatchEventIdCannotBeEditedInEitherMatch` |
| A16 修订链语义 | `noPhysicalDeleteHappensAnywhere` + 既有修订/重放回归 |
| A17 无关笔记/声称 | `missionEditThatMakesMatchDecidedRetractsOnlyImpossibleEvents`（`PLAYER_CLAIMED`/`PRIVATE_NOTE_ADDED` 不撤） |
| A18 业务失败不假成功 | `repositoryFailureNeverReportsSaved`、`conflictKeepsInputAndDoesNotReportSuccess`、`undoReportingRepositoryErrorIsNotShownAsSaved`、`deleteEventReportingRepositoryErrorIsNotShownAsSaved` |

### 1.3 QA 对抗性验收发现的缺陷（已修复，含修复前后对比）

| 缺陷 | 修复前行为 | 修复后行为 | 回归用例 |
|---|---|---|---|
| D2（P0） | 打开历史提案直接保存 → 撤销投票/决议/任务结果 3 条真实记录 | `affected=[]`，三行 `retracted=false` | `resubmittingIdenticalProposalContentRetractsNothing` 等 3 条 |
| D1（P0） | 改被否决的提案 1 → 撤销了提案 2 的任务结果 | 按「最后一个 APPROVED 决议」判定归属；提案 1 不再动任务结果，提案 2 仍正确撤销 | `editingRejectedProposalDoesNotRetractMissionOfTwinTeamApprovedProposal` 等 3 条 |
| D3（P1） | `undo`/`deleteEvent` 忽略 `AppendResult.error`，失败显示 `SAVED` | 业务结果双重判定 + `runCatchingCancellable`；fake 增加「返回 error 不抛异常」开关 | `undoReportingRepositoryErrorIsNotShownAsSaved` 等 2 条 |

## 2. Phase B：NX-02 响应式单事务快照（P1）

- 实现：`RoomMatchRepository.observeBundle` = `invalidationTrackerFlow(4 表, emitInitialState=true)` → `mapLatest { withTransaction { readBundleLocked } }` → `distinctUntilChanged`。
- API 事实：`room-ktx 2.6.1` 的 `RoomDatabaseKt.invalidationTrackerFlow(RoomDatabase, String[], boolean)` 经 `javap` 核实存在；无需 `ExperimentalRoomApi`。
- 先红后绿：旧实现上 11 条新用例中 **3 条失败**（B04a/B06/B07），抓到 `status=DRAFT` 与重放 `IN_PROGRESS` 撕裂、`revealedAt` 非空但角色全 `HIDDEN`。
- B06 每次发射校验 4 条跨表不变量：questsStarted 计数、participants 人数、角色揭晓⟺revealedAt、status⟺事件重放结果。
- 用例：`b01`–`b09`（11 条，`ObserveBundleConsistencyTest`）。

## 3. Phase C：NX-03 首页重试与复盘效率（P1）

- `HomeViewModel`：`catch` 移入 `flatMapLatest` 内单次查询流；`QueryState{Loading,Data,Error}` 自带 filter（避免组合闪回）。用例 `HomeViewModelTest` 7 条：首败恢复、连续两败第三次成功、筛选不闪回、双收集者单订阅、错误文案、停止后重收集、操作错误出口。
- 复盘：`ReplayViewModel` 保留 `analyze(snapshot, visibleHistory)`；`ReplaySnapshotReuseTest` 9 条断言 PUBLIC=1 次快照、同视角重选 0 次、个人≤2、上帝≤2、未揭晓 0 次。
- 性能：`ReplayAnalyzePerformanceTest`（预热 5 + 采样 40）+ `docs/audit/performance-next-round.md`；同环境两次运行差 2 倍 → 不做耗时门槛、不承诺百分比；5 项标 `NOT MEASURED`。

## 4. Phase D：NX-01 异常输入边界（P1）

- `AvalonEvidenceAnalyzer` 增加前提闸门：人数不在 5–10 / 任务号不在 1–5 → 抑制 A01/A03/A05/A06 的数量与门槛结论；failCount 越界 → 抑制 A06；座位越界/重复（任务与提案两处）→ 抑制依赖名单的推论。
- 数据质量提示统一 `A12-dq-*`（`ruleId` 仍为 A12，`ALL_RULE_IDS` 恰好 12 条）。
- 关键修复：非法人数下 `bounds()` 曾产出「这支 5 人队至少含 5 名坏人」的 `RULE_CERTAIN`，现被三重闸门抑制。
- 用例：`EvidenceBoundaryTest` 16 条（人数 4/11、任务号 0/6、牌数 −1/99/超队伍、重复/越界/少人/多人/缺失、冲突、7–10 人第 4 任务 1 失败仍成功、牌数缺失但结果已知、B34 计数保留）。

## 5. 提交清单（9d94040 → c5def57）

| SHA | 内容 |
|---|---|
| `2e5328e` | feat(data): 历史编辑影响范围纯函数 EditImpactPlanner |
| `9cc9e15` | feat(data): 历史编辑原子确认入口 planHistoricalEdit / applyConfirmedEdit |
| `10e6022` | fix(record): 历史编辑改为预览→确认两段式，确认前零历史写入 |
| `3b0af71` | fix(home): recoverable query retries |
| `13cb859` | test(replay): snapshot reuse and performance evidence |
| `2481e16` | fix(knowledge): suppress conclusions from invalid premises |
| `163cde5` | fix(knowledge): keep data-quality wording precise per invalid field |
| `6f44db0` | test(data): reproduce mixed snapshot from combined flows |
| `ec25057` | fix(data): observe bundle from one transaction per invalidation |
| `92996ce` | fix(data): 提案编辑只按真实依赖撤销（D1 归属 + D2 零变化） |
| `9a90438` | fix(record): 撤销/删除如实上报仓库业务错误（D3） |
| `2f73029` / `2d7f7f2` / `6cdc6ff` / `c5def57` | 四个 `--no-ff` 合并提交 |

## 6. 未覆盖 / 未验证

- Compose 渲染级断言：仓库无 `createComposeRule` 基础设施，对话框分组、配色、按钮隐藏只有代码路径与字符串资源证据。
- NX-05 设备验收：按要求取消，未执行真机/模拟器验收。
- 性能「优化前 vs 优化后」耗时对照：基线已含 probe 复用，旧版本不可复现 → `NOT MEASURED`。
- 数据集：未做实体设备、低端机、超大历史（>1000 事件）压测。
