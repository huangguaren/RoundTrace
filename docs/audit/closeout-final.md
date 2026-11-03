# RoundTrace P0/P1 收口终态

日期：2026-11-03
对比基线：`main@9d94040`（验收报告 `RoundTrace_Acceptance_2026-11-03.md`）
本轮 HEAD：`c5def57`（本地）
环境：Windows / JDK 17.0.13 (Microsoft) / Gradle wrapper / 16 逻辑核

## 1. 总判定

| 需求 | 判定 | 说明 |
|---|---|---|
| RT-NX06-SAFE 历史安全编辑（P0） | **PASS** | 预览→确认两段式、单事务原子提交、取消零写入；QA 两轮对抗性验收发现 3 个缺陷已修复并回归 |
| RT-NX02-SNAP 响应式一致性（P1） | **PASS** | 失效信号 + 单事务四表读；旧实现上 3 条用例可复现混搭快照 |
| RT-NX03-RETRY 首页可恢复（P1） | **PASS** | `catch` 在单次查询流内，重试真正重建订阅；7 条用例 |
| RT-NX03-PERF 复盘性能证据（P1） | **PARTIAL** | 快照调用次数有硬断言（PUBLIC=1）；耗时仅同环境对照，**无优化前后对照**（基线已含复用路径），5 项标 `NOT MEASURED` |
| RT-NX01-EDGE 推论边界（P1） | **PASS** | 非法前提抑制数量结论；16 条边界用例；12 条 ruleId 不变 |
| RT-CI-REGRESS 新提交保持可回归 | **PENDING** | 本地 `testDebugUnitTest` / `lintDebug` / `assembleDebug` 全通过；GitHub Actions 需推送后核对新 HEAD |
| RT-NX05-DEVICE 设备验收 | **CANCELLED** | 按要求取消，不作为发布闸门 |

## 2. 实测数据（本地，2026-11-03）

| 项 | 值 |
|---|---|
| JVM 测试 | **71 类 / 543 用例 / 0 失败 / 0 错误 / 0 跳过** |
| `lintDebug` | **0 error / 52 warning** |
| `assembleDebug` | 通过 |
| APK | `app/build/outputs/apk/debug/app-debug.apk`，20,646,989 B，versionCode 4 / versionName 0.4.0（debug 签名） |

对比基线 446 用例：**+97**（NX-06 +44、NX-03/NX-01 +34、NX-02 +11、QA 缺陷回归 +8），未删除任何既有用例，未放宽任何既有断言（唯一调整的 3 处既有断言在 NX-06 首轮，全部为「加前置步骤 + 补更强约束」，已在 `closeout-progress.md` §1 记录）。

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

**第 2 轮**：见 §7（回归结论）。

## 5. 已知限制与残余技术债

1. **Compose 渲染无自动断言**：仓库无 `createComposeRule` 依赖。影响预览对话框的分组渲染、标签配色、按钮隐藏、长列表滚动只有代码路径与 `strings.xml` 证据，需人工目视验收。
2. **「打开编辑→直接保存」仍写入一条内容相同的新修订**（旧版本被 retract，`revisionSeq` +1）。不影响其他记录，只是审计链多一跳。彻底关闭需在 UI 禁用无变更提交，而当前无 Compose 测试基建锁定该行为，故未改。
3. `RecordAction.RollbackToQuest` / `DeleteEvent` 已无 UI 入口但仍保留（前者为仓储修复路径，后者被既有测试引用）。清理建议单独一轮，避免波及无关测试。
4. `observeBundle` 改为 `mapLatest` 后，中间态快照会被跳过——任何「一次写入必发射 N 次」的假设不再成立。当前代码库无此依赖。
5. 四个 DAO 的单表 `observe*` 方法在生产路径已无调用方（保留作为 DAO 契约，`androidTest` 仍用 `observeById`）。
6. 未做实体设备、低端机、超大历史（>1000 事件）压测；NX-05 已取消。
7. 性能无优化前后对照：基线 `9d94040` 已包含快照复用路径，旧版本不可复现，故 `NOT MEASURED`。

## 6. 与验收报告（9d94040）的差异

| 验收报告结论 | 本轮结果 |
|---|---|
| NX-06 FAIL：`commit()` 直接写库、`PendingEdit` 未接通、无按 ID 原子撤销 | 已修：预览阶段零写入、结构化 `EditImpactPlan`、`applyConfirmedEdit` 单事务原子入口 |
| NX-02 FAIL：四路 `combine` 混搭 | 已修：单失效信号 + 单事务四表读；旧实现可复现缺陷 |
| NX-03 首页 FAIL：`.catch` 在外层 | 已修：`.catch` 移入单次查询流；连续失败可恢复 |
| NX-03 复盘 PASS（源码路径） | 保留并补定量证据 |
| NX-01 PARTIAL：边界未充分验证 | 已补：16 条边界用例 + 非法前提抑制 |

## 7. CI 状态

- 本地：`:app:testDebugUnitTest` PASS、`:app:lintDebug` PASS、`:app:assembleDebug` PASS（2026-11-03）。
- 远端：**待推送后核对**。推送后须以新 HEAD 的 Actions 运行为准，不使用此前绿色结果。

## 8. 结论

P0 的失败模式（历史记录被无意删除、失败显示假成功）已在实现与测试层面关闭，并经过独立 QA 两轮对抗性验证。P1 的三项（一致性、可恢复重试、推论边界）通过；性能项为 PARTIAL（调用次数可证、耗时对照不可复现）。唯一未闭环的是远端 CI 与人工目视验收。
