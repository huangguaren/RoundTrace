# RoundTrace GitHub 最新验收报告

**验收时间**：2026-11-03（依据当时 GitHub 可见状态）  
**仓库**：https://github.com/huangguaren/RoundTrace  
**分支**：`main`  
**HEAD**：`9d940404afff48e459e13e457131173e71534de0`  
**对比基线**：`0ccd53b3d7c2d5add052eebc5bbc3d56b0348670`  
**审查方式**：GitHub 连接器远端只读检查提交差异、源码、CI 运行、已有测试文件和交付文档；未在本地重新构建 APK，未执行独立设备测试（NX-05 已取消）。

## 1. 总结

**工程构建与持续集成：PASS；核心业务最终验收：FAIL（仍有 P0 缺陷）。**

最新 HEAD 对比前一基线仅包含 1 个提交，修改 `RecordUiState.kt` 和 `RecordViewModel.kt`，无新增测试文件。提交标题为“revert premature edit preview that broke existing edit tests”，正文表明恢复编辑模式直接写库；此行为与 NX-06“用户确认前不得写入历史”要求不一致。

## 2. 分项判定

| 编号 | 判定 | 验收依据 | 待办 |
|---|---|---|---|
| NX-06 历史安全编辑 | **FAIL / P0** | `commit()` 仍直接调用 `repository.reviseEvent` 后才显示 `impactWarning`；`PendingEdit` / `ConfirmPendingEdit` / `CancelPendingEdit` 已声明但没有从 UI 进入的完整链路；未提供按事件 ID 的单事务修订与选择性撤销 | 先预览，再确认；依赖分析；数据库原子性；取消零历史写入；并发防护 |
| NX-02 读取一致性 | **FAIL / P1** | `observeBundle()` 仍 `combine()` 四个独立 DAO Flow；尾部 `.distinctUntilChanged()` 仅去重，不保证四表同一快照；`getBundle()` 已事务化属于既有有效修复 | 单一失效信号 + `withTransaction` 整体读取，受控并发测试 |
| NX-03 首页重试 | **FAIL / P1** | `HomeViewModel` 的 `.catch` 仍包在整个 UI 状态收集链外面，上游失败后可能终结该收集；仅递增 retryTrigger 不保证恢复 | 将异常处理放在每次可重新创建的查询流内，覆盖错误→重试→成功 |
| NX-03 复盘快照复用 | **PASS（源码路径）** | `ReplayViewModel` 使用 `analyzer.analyze(snapshot, visibleHistory)`，未再通过 `analyzeAt()` 创建第三次快照 | 保留既有路径，补定量测量与快速切换验证 |
| NX-01 证据边界 | **PARTIAL** | 现有 `EvidencePremiseTest` 覆盖 `failCount=99/-1`；其他非法任务号、人数及不完整队伍的证据等级未充分验证 | 扩充安全边界测试，不误写 RULE_CERTAIN |
| NX-04 GitHub CI | **PASS** | `9d94040` 对应 Actions run 41415216570，`testDebugUnitTest`、`lintDebug`、`assembleDebug` 和两项上传成功 | 保持绿色，无须重构 |
| NX-05 设备验收 | **CANCELLED** | 用户明确取消 | 不作为发布闸门；不删除已有测试 |

## 3. 当前特别需要注意的源码问题

### P0-1：预览对象与 UI 未连通

最新新增 `PendingEdit` 数据类及 `pendingEditFlow`，但 `RecordScreen.kt` 没有调用 `ConfirmPendingEdit` / `CancelPendingEdit` 的按钮或事件，`RecordViewModel.kt` 也缺少给 `pendingEditFlow` 赋值的常规准备步骤。既有 `commit()` 仍先直接写库，故所谓“待确认编辑”不是可运行的安全流程。

### P0-2：确认分支错误判断业务成功

`ConfirmPendingEdit` 分支仅使用 Kotlin `Result.isSuccess` 判断 `repository.reviseEvent()` 是否成功。仓库方法返回 `AppendResult`，可能调用完成但内部 `error != null`；应当检查 `result.getOrNull()?.isSuccess == true`（或等价的显式业务结果）。该分支还缺少与通用 `isCommitting` 一致的防重入、失败时保留输入、失效快照重新预览等流程。

### P0-3：不支持“指定事件原子撤销”

当前仓库提供 `reviseEvent()` 和 `retractAfterOrder()`，没有能够在一个事务内完成“事件修订 + 仅撤销受影响事件 ID + 状态投影重算”的公开操作。不能把“修改后重新计算”当作“按需重置依赖事件”，也不能使用逻辑位置截断替代精确撤销。

### P1-1：四路 Flow 混合读

`RoomMatchRepository.observeBundle()` 对 `matches`、`participants`、`role_assignments`、`match_events` 分别订阅，再 `combine`。每路 SQL 读到的时点可能不同。即使 WAL 开启、`distinctUntilChanged` 存在，也不构成跨表一致性证明。

### P1-2：首页重试的上游收集生命周期

`HomeViewModel` 将 `.catch` 放在整个 `combine` 之后；一旦异常使收集结束，点击重试并不保证恢复。应通过可重建的内部查询流表达 Loading / Data / Error。

## 4. CI 证据

- Run：https://github.com/huangguaren/RoundTrace/actions/runs/41415216570
- `:app:testDebugUnitTest`：`BUILD SUCCESSFUL in 3m 10s`
- `:app:lintDebug`：`BUILD SUCCESSFUL in 1m 1s`
- `:app:assembleDebug`：`BUILD SUCCESSFUL in 1m 8s`
- JVM 测试报告：https://github.com/huangguaren/RoundTrace/actions/runs/41415216570/artifacts/13127322407
- Debug APK：https://github.com/huangguaren/RoundTrace/actions/runs/41415216570/artifacts/13127402138
- CI 成功说明已有测试与构建通过；**不证明未新增专项测试的 P0 功能已实现**。本次未解包 XML 逐项统计精确用例数，报告不采用未经核实的“440+”作为精确值。

## 5. 验收决定与下一步

**业务收口不予通过；保留当前稳定可构建基线，优先执行下轮 P0 / P1。**

1. P0：NX-06 完整实现安全历史编辑、影响分析及原子写入；先加失败测试。
2. P1：NX-02 消灭四路 Flow 混合快照风险。
3. P1：NX-03 修复首页查询失败重试，并补可复核性能数据。
4. P2：NX-01 补边界测试与错误证据等级。
5. CI 只做新 HEAD 复验；NX-05 继续取消。

**功能路线图**：以上闸门通过后，再进入 v0.5.0 的玩家行为时间线、“当时判断 vs 最终真相”、快速记录等产品需求；狼人杀仍按独立模块另行设计。
