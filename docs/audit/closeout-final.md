# RoundTrace P0/P1 收口终态

日期：2026-11-03
对比基线：`main@9d94040`（验收报告 `RoundTrace_Acceptance_2026-11-03.md`）
本轮 HEAD：`4da27a4`（已推送至 `origin/main`，远端 Actions 全绿）
环境：Windows / JDK 17.0.13 (Microsoft) / Gradle wrapper / 16 逻辑核

## 1. 总判定

| 需求 | 判定 | 说明 |
|---|---|---|
| RT-NX06-SAFE 历史安全编辑（P0） | **PASS** | 预览→确认两段式、单事务原子提交、取消零写入；QA 两轮对抗性验收发现 3 个缺陷已修复并回归 |
| RT-NX02-SNAP 响应式一致性（P1） | **PASS** | 失效信号 + 单事务四表读；旧实现上 3 条用例可复现混搭快照 |
| RT-NX03-RETRY 首页可恢复（P1） | **PASS** | `catch` 在单次查询流内，重试真正重建订阅；7 条用例 |
| RT-NX03-PERF 复盘性能证据（P1） | **PARTIAL** | 快照调用次数有硬断言（PUBLIC=1）；耗时仅同环境对照，**无优化前后对照**（基线已含复用路径），5 项标 `NOT MEASURED` |
| RT-NX01-EDGE 推论边界（P1） | **PASS** | 非法前提抑制数量结论；16 条边界用例；12 条 ruleId 不变 |
| RT-CI-REGRESS 新提交保持可回归 | **PASS** | 远端 Actions `4da27a4` 全绿，11 步全 success；从其 `test-report` artifact 复核 71 类 / 543 用例 / 0 失败 |
| RT-NX05-DEVICE 设备验收 | **CANCELLED** | 按要求取消，不作为发布闸门 |

## 2. 实测数据（本地，2026-11-03）

| 项 | 值 |
|---|---|
| JVM 测试 | **71 类 / 543 用例 / 0 失败 / 0 错误 / 0 跳过** |
| `lintDebug` | **0 error / 52 warning** |
| `assembleDebug` | 通过 |
| APK | `app/build/outputs/apk/debug/app-debug.apk`，20,646,989 B，versionCode 4 / versionName 0.4.0（debug 签名） |

对比基线 446 用例：**+97**（NX-06 +44、NX-03/NX-01 +34、NX-02 +11、QA 缺陷回归 +8），未删除任何既有用例，未放宽任何既有断言（唯一调整的 3 处既有断言在 NX-06 首轮，全部为「加前置步骤 + 补更强约束」，已在 `closeout-progress.md` §1 记录）。

**关键新增测试类（合计 96 条，另 1 条为既有类内追加）**

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `AtomicHistoricalEditTest` | 20 | 真 Room + Robolectric，P0 原子性/回滚/过期预览/重复确认/无物理删除 |
| `EditImpactPlannerTest` | 18 | 纯函数规划器，含 D1 归属与 D2 零变化回归 |
| `EvidenceBoundaryTest` | 16 | NX-01 非法前提、人数/任务号越界、双失败门槛 |
| `RecordHistoricalEditViewModelTest` | 14 | 预览态/取消/提交五分支/D3 假成功回归 |
| `ObserveBundleConsistencyTest` | 11 | NX-02 单事务快照 + 四条跨表不变量 |
| `ReplaySnapshotReuseTest` | 9 | 复盘快照复用、PUBLIC 仅一次 |
| `HomeViewModelTest` | 7 | NX-03 首页 Loading/Error/Empty/Data、连续失败后恢复、切换筛选不返旧数据 |
| `EvidencePremiseTest` | 表驱动 | 前提推导的各种牌数/结果组合 |
| `ReplayAnalyzePerformanceTest` | 2 | 调用次数硬断言 + 耗时采样（不作为门槛） |

**两处测量差异说明（不影响判定，如实记录）**：APK 体积本机 20,646,989 B、QA 独立构建 20,587,857 B（构建时点/中间产物不同）；`lintDebug` 本机 52 warning + 2 information、QA 构建 51 warning + 2 information，**两者均 0 error**，差异为 lint 基线缓存与报告生成时点差异。

## 3. P0 硬门槛核对

| 门槛 | 结果 | 证据 |
|---|---|---|
| 确认前零历史写入 | 通过 | `previewOfHistoricalEditWritesNothing`（真库全行比对）、`savingHistoricalEditOnlyPreviewsAndWritesNothing`（`applyCalls==0`） |
| 取消保留草稿且零写入 | 通过 | `cancelAfterPreviewLeavesHistoryAndDerivedStateUntouched`、`cancelKeepsDraftAndEditSessionWithoutWriting` |
| 确认后单事务「修订 + 精确依赖撤销」 | 通过 | `confirmedEditWritesRevisionAndRetractionsInOneTransaction`；失败注入整体回滚 `injectedFailureRollsBackEveryWriteOfTheEdit` |
| 非依赖事件不被误删 | 通过 | D1/D2 修复后的 6 条回归：任务结果归属判定、相同内容零影响、后续任务不受影响 |
| 并发/过期确认 | 通过 | `stalePreviewIsRejectedWithoutAnyWrite`、`doubleConfirmSucceedsAtMostOnce`、`staleTokenSetIsRejectedAsInconsistentSnapshot` |
| 失败/取消不误报成功 | 通过 | `repositoryFailureNeverReportsSaved`、`undoReportingRepositoryErrorIsNotShownAsSaved`、`deleteEventReportingRepositoryErrorIsNotShownAsSaved` |
| 无物理删除、审计链保留 | 通过 | `noPhysicalDeleteHappensAnywhere`；`mark_retracted` 为唯一删除语义 |
| 不可编辑类型被拒（B35/B37） | 通过 | `nonEditableEventTypeIsRejected`、`nonEditableTypeIsRejected` |

## 4. QA 独立验收（两轮）

**第 1 轮**（`2d7f7f2`，全量 524 全绿）判定 NX-06 **FAIL**，发现 3 个「绿灯掩盖的真实缺陷」：
- D2（P0）：打开历史提案直接保存 → 撤销 3 条真实记录（最易触发）；
- D1（P0）：改被否决的提案 → 误撤另一提案的任务结果；
- D3（P1）：`undo`/`deleteEvent` 把仓库业务错误报成「已保存」。

**根因共性**：测试夹具每个任务只有一次提案（D1 隐藏）、fake 只抛异常而真实仓储返回错误（D3 隐藏）、规划器不比较新旧内容（D2）。修复时同时补上了多提案夹具与「返回 error 不抛异常」的 fake 开关，堵住同类缺陷再次被掩盖的路径。

**第 2 轮结论**（对 `c5def57` 的独立回归，QA 自建 `TempR2*.kt` 探针，完成后已删除、未入库）：**NX-06 由 FAIL 转为 PASS**。

| 复核项 | QA 手法 | 结论 |
|---|---|---|
| D1 归属判定 | 同一任务两条提案（1 否决 + 2 通过），编辑被否决的 1 | 只动提案 1 自身那条的历史写入，**提案 2 的任务结果（MISSION_RESOLVED）与决议（APPROVED）保持 active**，撤 0 条 |
| D2 零变化短路 | 打开历史提案 → 不改任何字段 → 直接保存 | `EditImpactPlan.affected` 为空；**0 条撤销、0 次历史表扫描写入**；`applyCalls == 0` |
| D3 假成功 | fake 返回 `AppendResult.error`（**不抛异常**） | `savedMessage == null`、`errorMessage != null`，**不再显示「已保存」** |
| 反向风险（是否修过头） | 改「人数」使已成立任务结果失效 | 合法的依赖撤销**仍会发生**，未被短路逻辑吃掉 |
| 原子性 | 失败注入 / 同一 token 二次确认 | 全部回滚、第二次 `Success` 与 `StalePreview` 互斥命中 |
| 不变量 | 四条跨表不变量 | 全部稳定，无 B06 撕裂 |
| 全量回归 | 543 用例 | **543/543 通过，0 失败 0 跳过** |

**遗留的 P2（不影响 PASS 判定，已登记为技术债见 §5.8）**：`leaderSeat` 单侧变更仍会以 `LEADER_CHANGED` 撤销整队——这是 `EditImpactPlanner` 的**既有设计选择**（队长变更后整队信息不再可信），**不是本轮回归引入的缺陷**；若产品希望「只改队长、保留队伍」，需改规划器语义，属独立一轮。

**尚未被测试覆盖的两条行为**（QA 主动指出，避免「绿灯=全覆盖」的误读）：`MissionResolved` 事件编辑后的影响预览分组、以及 `REVIEW_ONLY` 标签在 UI 上的呈现；两者都只有代码路径证据（见 §5.1）。

## 5. 已知限制与残余技术债

1. **Compose 渲染无自动断言**：仓库无 `createComposeRule` 依赖。影响预览对话框的分组渲染、标签配色、按钮隐藏、长列表滚动只有代码路径与 `strings.xml` 证据，需人工目视验收。
2. **「打开编辑→直接保存」仍写入一条内容相同的新修订**（旧版本被 retract，`revisionSeq` +1）。不影响其他记录，只是审计链多一跳。彻底关闭需在 UI 禁用无变更提交，而当前无 Compose 测试基建锁定该行为，故未改。
3. `RecordAction.RollbackToQuest` / `DeleteEvent` 已无 UI 入口但仍保留（前者为仓储修复路径，后者被既有测试引用）。清理建议单独一轮，避免波及无关测试。
4. `observeBundle` 改为 `mapLatest` 后，中间态快照会被跳过——任何「一次写入必发射 N 次」的假设不再成立。当前代码库无此依赖。
5. 四个 DAO 的单表 `observe*` 方法在生产路径已无调用方（保留作为 DAO 契约，`androidTest` 仍用 `observeById`）。
6. 未做实体设备、低端机、超大历史（>1000 事件）压测；NX-05 已取消。
7. 性能无优化前后对照：基线 `9d94040` 已包含快照复用路径，旧版本不可复现，故 `NOT MEASURED`。
8. **`leaderSeat` 单侧修改会撤销整队（P2）**：`EditImpactPlanner` 认为「队长换了，原队伍信息不再可信」，因此 `LEADER_CHANGED` 会连带撤销 `TEAM_PROPOSED`/`TEAM_VOTE_RECORDED`。这是刻意语义而非缺陷，但对「只改队长、队伍照旧」的场景偏保守（会多一次人工重录）。QA 第 2 轮标注，待产品决策。
9. **两条行为缺自动化覆盖**：`MissionResolved` 编辑的影响预览分组、`REVIEW_ONLY` 标签的 UI 呈现——只有代码路径 + `strings.xml` 证据，与 §5.1 的 Compose 基建缺口同源。

## 6. 与验收报告（9d94040）的差异

| 验收报告结论 | 本轮结果 |
|---|---|
| NX-06 FAIL：`commit()` 直接写库、`PendingEdit` 未接通、无按 ID 原子撤销 | 已修：预览阶段零写入、结构化 `EditImpactPlan`、`applyConfirmedEdit` 单事务原子入口 |
| NX-02 FAIL：四路 `combine` 混搭 | 已修：单失效信号 + 单事务四表读；旧实现可复现缺陷 |
| NX-03 首页 FAIL：`.catch` 在外层 | 已修：`.catch` 移入单次查询流；连续失败可恢复 |
| NX-03 复盘 PASS（源码路径） | 保留并补定量证据 |
| NX-01 PARTIAL：边界未充分验证 | 已补：16 条边界用例 + 非法前提抑制 |

## 7. CI 状态（已核对远端，非沿用旧绿灯）

推送范围：`9d94040..4da27a4`（41 个提交之后的 5 个收口提交），远端 `main` 已跟上。

**远端 Actions 运行（`4da27a4c4ba0801f2c7c7d3d3dade5b8a0330b0f`）**

| 项 | 值 |
|---|---|
| Run ID | 41569499184（attempt 1） |
| URL | https://github.com/huangguaren/RoundTrace/actions/runs/41569499184 |
| 触发器 | `push` → `main` |
| 总判定 | **success**（03:00:00Z 起，03:07:29Z 完成，约 7 分 29 秒） |
| 步骤 | Set up job / checkout / setup-java / Setup Gradle / chmod gradlew / Remove CN mirrors / **Run unit tests** / **Run lint** / **Build debug APK** / Upload test report / Upload APK —— 11 步全部 `success` |
| Artifact `test-report` | id 13200912443，75,909 B，未过期；**已下载核对 XML：71 类 / 543 用例 / 0 失败 / 0 错误 / 0 跳过**，与本地完全一致 |
| Artifact `app-debug` | id 13200693085，19,633,733 B（zip 压缩体积，本地 APK 为 20,646,989 B），未过期 |
| Artifacts 页 | https://github.com/huangguaren/RoundTrace/actions/runs/41569499184#artifacts |

> 说明：`9d94040` 的旧绿色运行不作为本轮证据；以上数字取自上面这次**针对新 HEAD** 的运行，并从其 `test-report` artifact 内 XML 重新汇总，而非只看 job 颜色。

**第二个提交（`b622ec3`，同样已核对）**：Run 41571720361，`success`，03:21:31Z → 03:29:37Z（约 8 分 6 秒），11 步全 success；artifact `test-report`(76,037 B, id 13202052093)、`app-debug`(19,634,172 B, id 13201967249)。URL https://github.com/huangguaren/RoundTrace/actions/runs/41571720361

> `b622ec3` 及其后的提交均为 **docs-only**（只改 `docs/`），代码树与 `4da27a4` 完全一致，因此其运行结果可视为同一代码状态的重复验证。

**本地（2026-11-03）**：`:app:testDebugUnitTest` PASS（71 类 / 543 用例）、`:app:lintDebug` PASS（0 error / 52 warning）、`:app:assembleDebug` PASS。

## 8. 结论

P0 的失败模式（历史记录被无意删除、失败显示假成功）已在实现与测试层面关闭，并经过独立 QA 两轮对抗性验证。P1 的三项（一致性、可恢复重试、推论边界）通过；性能项为 PARTIAL（调用次数可证、耗时对照不可复现）。远端 CI 已在新 HEAD 上跑绿并从 artifact 复核用例数；唯一未闭环的是 **Compose 渲染级与真机目视验收**（无测试基建，见 §5.1、§5.6）。
