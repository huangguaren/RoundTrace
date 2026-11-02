# RoundTrace 代码审计终态报告（final-audit）

> 审计基线：`a6da7c4`（v0.3.0）→ 修复后：`35b1b30` + 阶段 E 提交
> 审计输入：`RoundTrace_Code_Audit_2026-10-31.md`（RT-001 ～ RT-031）
> 执行方案：`docs/IMPLEMENTATION_PLAN_AI_CODING.md`（阶段 A–E + V01–V12 验证矩阵）
> 本轮实测：**356 个 JVM 单测 / 52 个测试类 / 0 失败 / 0 跳过**；`lintDebug` 0 error；`assembleDebug` 通过
> 独立验收：`docs/audit/qa-phase1.md`（4 个 P0）、`docs/audit/qa-phase-d.md`（V01–V12 + 复验）

## 0. 一页结论

| 终态 | 条数 | 说明 |
|---|---|---|
| **fixed** | 12 | 本轮真实修复并有自动化测试钉住 |
| **partial** | 2 | 主体已修，但仍有残留缺口，已写明 |
| **deferred** | 19 | 有意推迟，理由逐条写明（不是遗忘） |
| **新增发现并已修** | 6 | 本轮在实施与验收过程中新发现的问题（编号 N-*） |
| **新增发现未修** | 3 | N-*，均为 P2/P3，列入后续 |

**规则权威性确认**：`docs/decisions.md` B34（结果明确即计数，`verified` 仅表可复核性）在本轮全程保持不变，
未被任何「优化」改回旧规则；B36（v4 预发布可就地修正）、B37（可编辑事件限定 3 类）为本轮新增裁决。

## 1. RT-001 ～ RT-031 逐条终态

| ID | 级别 | 终态 | 依据 |
|---|---|---|---|
| RT-001 | P0 | **fixed** | `MatchDao.updateStatusOnly` 不再覆盖 `revealedAt`；`ee20e3d`/`48a7144`；QA E2E-01 |
| RT-002 | P0 | **fixed** | `markRetracted(matchId,id)` + `check(affected==1)`；`ee20e3d`；QA 跨对局四例 |
| RT-003 | P0 | **fixed** | v4 拆 `logicalPosition`/`revisionSeq` + 读路径已切；`a24be16`；`docs/audit/migration-plan.md` §17 |
| RT-004 | P0 | **fixed** | `recomputeProjectionLocked` 覆盖 append/revise/retract/import；`8108686`；QA E2E-05/06 |
| RT-005 | P0 | **fixed** | `core/replay/VisibleEventPolicy` 作唯一出口（含复盘时间线）；`017b98e`/`73ffc84`；QA 5 出口 × 7 例 |
| RT-006 | P1 | **fixed** | 草稿恢复 + `draftTouchedByUser` 判据；`fe6c408`；QA V11 |
| RT-007 | P1 | **fixed** | 事务化 `reviseEvent`（事务内复核 + 三条件撤销）；`5f11b30`；QA V05/V06 |
| RT-008 | P1 | deferred | 揭晓仍分「存角色」「写揭晓时刻」两次事务。**未修**：数据一致但非原子，需要单独的 `revealMatch` 端口改造 |
| RT-009 | P1 | deferred | 头像仍用 `GetContent` + 吞掉 `takePersistableUriPermission` 异常。**未修**：属 P1 批次，需与头像组件一起改 |
| RT-010 | P1 | deferred | 三处 `remember { decodeStream }` 仍在主线程同步解码。**未修**：需抽 `SeatAvatar` 异步组件 |
| RT-011 | P1 | deferred | `backup_rules.xml` / `data_extraction_rules.xml` 仍未覆盖 `files/datastore/`。**未修**：与 RT-009/010 同批 |
| RT-012 | P1 | deferred | JSON 往返仍只带 seat/name/color，座位布局与头像引用未导出。**未修**：需版本化 ExportBundle |
| RT-013 | P1 | deferred | 导入 JSON 仍无大小/条数/字符串长度上限。**未修**：属 P1 批次，与 RT-012 一起做 |
| RT-014 | P1 | deferred | 回滚仍逐条 `retractEvent`。**未修**：需 `retractAfterOrder` 原子批量 |
| RT-015 | P1 | **partial** | A12 与 B34 的语义冲突已在 `decisions.md`/README 统一；**A04「失败牌为 0」的实际推论文案仍未改** |
| RT-016 | P1 | deferred | `ReplayViewModel` 仍有多次 snapshot。**未修**：性能项，需 before/after 测量 |
| RT-017 | P1 | deferred | `HomeViewModel` 仍双重订阅 `summaries`。**未修**：性能项，与 RT-016 同批 |
| RT-018 | P2 | deferred | `EventCodec` 与 `MatchJsonCodec` 的 payload 映射仍各写一套。**有意不合并**：内部存储契约与对外导出契约必须隔离（见 §3） |
| RT-019 | P2 | deferred | `AvalonReducer`/`AvalonRules`/各 Screen 仍是千行级。**未修**：需先有覆盖测试再拆 |
| RT-020 | P2 | deferred | `GameType.WEREWOLF` 仍是占位。**有意保留**：本轮不实现狼人杀 |
| RT-021 | P2 | 保留 | `analysis_reports` 表/DAO 闲置但**不可直接删**（删表需显式迁移）。保留 |
| RT-022 | P2 | deferred | 启动路由仍有固定 `delay(1500)`。**未修**：属体验项，需按初始化状态触发 |
| RT-023 | P2 | deferred | release 仍 `isMinifyEnabled = false`。**有意推迟**：开启 R8 必须独立发布任务并保留 mapping |
| RT-024 | P2 | **fixed** | README/ARCHITECTURE/decisions/TEST_REPORT 的旧规则与旧数字已统一；见本轮阶段 E |
| RT-025 | P2 | deferred | `EventRevisionPolicy.isInSupersedeChain` 语义与命名不符、疑似无调用。**未修**：需先做引用分析再决定删或改 |
| RT-026 | P2 | deferred | `observeBundle` 组合 4 个 DAO Flow、`appendEvent` 预校验在事务外。**未修**：TOCTOU 窗口极小，需并发压测证据 |
| RT-027 | P2 | deferred | `testImplementation(kotlinx-serialization-json)` 仍与 `implementation` 重复。**未修**：属 Phase 4 依赖清理 |
| RT-028 | P2 | deferred | 分享文件仍存缓存目录且无统一清理入口。**未修**：需定义过期删除策略 |
| RT-029 | P2 | deferred | 证据分析对畸形事件仍可能给 `RULE_CERTAIN`。**未修**：需畸形输入测试后分级 |
| RT-030 | P2 | deferred | 仍无 `.github/workflows/`。**未修**：CI 属 Phase 4/5 |
| RT-031 | P1(上架) | deferred | `targetSdk/compileSdk` 仍 34。**有意推迟**：仅内部安装不受 Play 政策约束；升级须独立 PR 并回归 Android 15/16 行为变更 |

## 2. 本轮新发现并已修（N-*）

| ID | 级别 | 问题 | 修复 |
|---|---|---|---|
| N-1 | **P1** | 草稿自动保存期间提交被静默吞掉（`saveStatus == SAVING` 兼任提交闸门），用户看到「已保存」但事件没写 | `5bdb1ac`：独立 `isCommitting` 闸门；`saveStatus` 退回纯展示 |
| N-2 | **P1** | 迁移 CTE 不按 `matchId` 分组，跨局链会继承他局位置、破坏 INV-1 | `5f13775`：JOIN 加 `child.matchId = chain.matchId`；配只读启动诊断 |
| N-3 | P2 | 备份在 WAL checkpoint busy 时仍报「迁移前备份完成」 | `9a7fac1`：`BackupOutcome.Incomplete` + 日志降级，不再报完成 |
| N-4 | P2 | 时间线不显示 `PlayerClaimed.text`，「关键言论」写的话记录页与复盘页都看不到 | `c401951`：原文进 detail（>60 字截断），可见性判断不变 |
| N-5 | P2 | 一次性投影刷新逐局吞异常、仍写标记 → 坏局永久停在旧结论 | `856cd38`：任一失败即不写标记 + 记录失败 matchId |
| N-6 | P2 | 弹层用**当前轮**的人数/门槛校验草稿，编辑早期任务时提交按钮永远点不亮 | `ec83007`：改按 `editSession?.questNo` 计算 |

## 3. 本轮新发现未修（列入后续）

| ID | 级别 | 问题 | 建议 |
|---|---|---|---|
| N-7 | P3 | `AppContainer` 写投影标记的逻辑原先无测试缝（改成「不管 isComplete 就写」也不会红） | 已修：`ProjectionRefreshCoordinator` 纯函数 + 真值表 4 例（`caf4fdf`） |
| N-8 | P3 | 跨局 `supersedesId` 链的**存量**数据只做告警不修复 | 保留告警；是否修复需人工评估，**不得自动改历史数据** |
| N-9 | P3 | `TimelineBuilder.claimDetail` 原 `ifEmpty { "公开发言" }` 是不可达分支 | 已修：删除并注明理由（阶段 E） |

## 4. 有意「不合并 / 不删除」的三处（避免后续误判为疏漏）

1. **`EventCodec` 与 `MatchJsonCodec` 不合并**（RT-018）：内部存储契约与对外导出契约是**两个不同的契约**，
   合并会让导出格式被内部实现绑架。只共享纯 JSON 标量 helper 与测试夹具。
2. **`analysis_reports` 表保留**（RT-021）：删表需要显式 migration 与迁移测试，
   属于独立任务，不能顺手删。
3. **`material-icons-extended` 暂不删**（审计 §3）：官方已不再维护，但替换为本地 VectorDrawable 需要
   逐个 icon 核对与可访问性回归，属于独立 PR（RT-027 同批）。

## 5. 未验证项（NOT RUN，如实记录）

| 项 | 原因 | 影响 |
|---|---|---|
| `connectedDebugAndroidTest`（含 `MigrationV3ToV4Test`） | 本机无设备/模拟器 | **`@ColumnInfo(defaultValue)` 与 `ALTER TABLE DEFAULT` 的一致性只有间接证据**（4.json 实测 + 迁移后库可正常打开），严格校验需 `runMigrationsAndValidate` |
| 真机杀进程后草稿/编辑目标恢复 | 无设备 | V11 用 ViewModel 重建 + 持久化草稿做代理验证 |
| WAL checkpoint-busy 真机分支 | Robolectric 共享句柄，造不出 busy | 已用注入式测试钉住判定分支；真机行为未验 |
| Compose 像素级 / 字体放大 / 分享面板 | 无设备 | UI 只有编译期与状态层保证 |
| GitHub Actions CI | 未配置 | 所有验证仍依赖本地执行 |

## 6. 发版约束（必须遵守）

- **v4 与逻辑顺序修复必须同一个 versionCode 一起发**：Step 1（v4 迁移）与 Step 3（读路径切换）
  单独发版等于「承担全部 schema 风险、零用户收益」。本轮两者同在 `versionCode 4`。
- **不能直接装回只支持 v3 的旧 APK**：Room identityHash 变化后旧版打不开数据库。
  回退只能恢复 `.bak.v3` 备份，且会丢失备份之后的记录。
- 备份可能因 WAL busy 被标记为「不完整」，日志会明确降级；**恢复前必须先确认数据范围**。
