# RoundTrace 代码审计终态报告（final-audit）

> 审计基线：`a6da7c4`（v0.3.0）→ 阶段 E 基线：`35b1b30`；本轮 P1 实施基于 `3fa458c`，代码尚在工作区
> 审计输入：`RoundTrace_Code_Audit_2026-10-31.md`（RT-001 ～ RT-031）
> 执行方案：`docs/IMPLEMENTATION_PLAN_AI_CODING.md`（阶段 A–E + V01–V12 验证矩阵）
> 最新实测（2026-11-02）：**433 个 JVM 单测 / 62 个测试类 / 0 失败 / 0 跳过**；模拟器仪器测试 **24/24 通过**；`lintDebug` 0 error；`assembleDebug` 通过。历史阶段 E 结果见 `docs/TEST_REPORT.md` §12，当前复验见 §13。
> 独立验收：`docs/audit/qa-phase1.md`（4 个 P0）、`docs/audit/qa-phase-d.md`（V01–V12 + 复验）

## 0. 一页结论

| 终态 | 条数 | 说明 |
|---|---|---|
| **RT fixed** | 15 | RT-001～007、RT-008～014、RT-024，均有实现与测试证据 |
| **RT partial** | 1 | RT-015，规则语义已统一，A04 文案仍待处理 |
| **RT deferred** | 14 | 有意推迟，原因逐条写明 |
| **RT retained** | 1 | RT-021，因迁移约束保留 |
| **新增发现已修** | 9 | N-1～N-7、N-9、N-10，均有实现或测试证据 |
| **新增发现待评估** | 1 | N-8，仅告警存量跨局链；不自动改写历史数据 |

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
| RT-008 | P1 | **fixed** | `revealMatch` 在单个 Room transaction 内写身份、首次 `revealedAt` 与状态投影；SQLite 触发器失败测试验证整体回滚。`AtomicMatchOperationsTest`、`StatusProjectionTest` |
| RT-009 | P1 | **fixed** | 头像选择改用 `OpenDocument` 并保存持久读取授权；异步解码前验证来源可读，撤权或删除后不使用旧缓存。`AvatarLoadCacheTest` |
| RT-010 | P1 | **fixed** | 三处座位头像合并为异步采样解码组件；限制并发和缓存预算，切换 URI 时不短暂显示旧头像。`AvatarDecodeTest`、`AvatarSamplingTest`、`AvatarDecodeGuardTest` |
| RT-011 | P1 | **fixed** | Auto Backup 与设备传输规则排除 `file/datastore/`；规则审计测试覆盖两套 XML。`DataStoreBackupRulesTest` |
| RT-012 | P1 | **fixed** | JSON schema 升至 v2，导出版携带座位布局和头像 URI 引用，继续接受 v1；头像图片本体不打包，无当前设备授权时导入清空引用并报告数量。`ImportBoundaryTest`、`JsonCodecTest` |
| RT-013 | P1 | **fixed** | 导入加入 UTF-8 字节、数组条数、参与人数、文本长度和嵌套深度上限；文件选择按字节预算流式读取，超限停止且不落库。`ImportBoundaryTest`、`ImportTextReaderTest` |
| RT-014 | P1 | **fixed** | `retractAfterOrder` 单事务按 `matchId + logicalPosition` 批量撤销并重算投影；缺少目标任务时拒绝回滚，失败整体回滚。`AtomicMatchOperationsTest`、`AtomicWriteViewModelTest` |
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
| N-7 | P3 | 投影完成标记缺少可测试的判定缝 | `ProjectionRefreshCoordinator` 纯函数 + 4 条真值表用例（阶段 E） |
| N-9 | P3 | 时间线声称详情的空值回退是不可达分支 | 删除死分支并注明理由（阶段 E） |
| N-10 | P1 | 提案队长星标与上车成员状态耦合；重复点击队长会清空队长，无法稳定让队长上车 | 独立队长选择/更换模式与成员集合；普通点击只切换上车状态，支持队长上车/下车、换队长保留成员、重复点幂等。`ProposalSheetTest` 8 例 + 模拟器触控 3 例 |

## 3. 本轮新发现待评估

| ID | 级别 | 问题 | 建议 |
|---|---|---|---|
| N-8 | P3 | 跨局 `supersedesId` 链的**存量**数据只做告警不修复 | 保留告警；是否修复需人工评估，**不得自动改历史数据** |

## 4. 有意「不合并 / 不删除」的三处（避免后续误判为疏漏）

1. **`EventCodec` 与 `MatchJsonCodec` 不合并**（RT-018）：内部存储契约与对外导出契约是**两个不同的契约**，
   合并会让导出格式被内部实现绑架。只共享纯 JSON 标量 helper 与测试夹具。
2. **`analysis_reports` 表保留**（RT-021）：删表需要显式 migration 与迁移测试，
   属于独立任务，不能顺手删。
3. **`material-icons-extended` 暂不删**（审计 §3）：官方已不再维护，但替换为本地 VectorDrawable 需要
   逐个 icon 核对与可访问性回归，属于独立 PR（RT-027 同批）。

## 5. 历史未验证项（截至阶段 E；本轮结果见 §6）

| 项 | 原因 | 影响 |
|---|---|---|
| `connectedDebugAndroidTest`（含 `MigrationV3ToV4Test`） | 阶段 E 结束时没有可用设备 | 已由 §6 所列模拟器仪器测试补验迁移；真机迁移仍未验证 |
| 真机杀进程后草稿/编辑目标恢复 | 无设备 | V11 用 ViewModel 重建 + 持久化草稿做代理验证 |
| WAL checkpoint-busy 真机分支 | Robolectric 共享句柄，造不出 busy | 已用注入式测试钉住判定分支；真机行为未验 |
| Compose 像素级 / 字体放大 / 分享面板 | 无设备 | UI 只有编译期与状态层保证 |
| GitHub Actions CI | 未配置 | 所有验证仍依赖本地执行 |

## 6. P1 批次与提案队长交互复验（2026-11-02）

本轮完成 RT-008～RT-014 七项 P1，另修复 N-10 队长/成员选择冲突。实现与测试细节见 RT 表和 `docs/TEST_REPORT.md` §13。

| 验证 | 结果 |
|---|---|
| 全量 JVM 单测 | **433 / 433 通过**，62 个测试类，0 失败、0 跳过 |
| 新增对抗/边界用例 | 揭晓与回滚事务 9；ViewModel 原子入口 7；JSON 边界 13；流式文件读取 6；头像与备份规则 34；提案弹窗 8 |
| Android 仪器测试 | **24 / 24 通过**，API 34 模拟器；含 v3→v4 严格迁移校验及 3 条真实触控队长交互 |
| `lintDebug` | 0 error、51 warning、3 information |
| Debug APK | versionCode 4 / 0.4.0，20,600,470 bytes |

仍需真机补验：SAF 授权在重启/撤权/跨设备后的真实行为，系统字体放大与 TalkBack，以及 WAL checkpoint-busy 的设备级连接占用场景。模拟器触控测试覆盖布局范围，不等于像素或无障碍验收。

## 7. 发版约束（必须遵守）

- **v4 与逻辑顺序修复必须同一个 versionCode 一起发**：Step 1（v4 迁移）与 Step 3（读路径切换）
  单独发版等于「承担全部 schema 风险、零用户收益」。本轮两者同在 `versionCode 4`。
- **不能直接装回只支持 v3 的旧 APK**：Room identityHash 变化后旧版打不开数据库。
  回退只能恢复 `.bak.v3` 备份，且会丢失备份之后的记录。
- 备份可能因 WAL busy 被标记为「不完整」，日志会明确降级；**恢复前必须先确认数据范围**。
