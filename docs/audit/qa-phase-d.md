# QA 阶段 D 独立验收报告（V01–V12）

> 验收人：QA（Edward / 严过关）
> 时间：2026-11-02
> 仓库：`C:\Users\HK-info\WorkBuddy\2026-10-30-11-53-57\RoundTrace`
> 受验 HEAD：`30e328a`（`ec83007` + 一个 docs 提交）
> 本报告的改动范围：仅新增 `app/src/test/java/com/roundtrace/app/audit/`（6 个文件）与本文件。
> （报告定稿后，工程师的 F1–F5 改动会出现在同一工作区，不属于本报告范围。）
> **未修改任何生产代码、未改他人测试断言、未 commit。**
>
> **2026-11-02 修订（主理人裁定级别）**：缺陷编号已重排，原编号见 §4 的「原编号」列。
> 变化：原 P2-1（迁移 CTE）→ **P1-1**；原 P2-4（保存窗口吞提交）→ **P1-2**；
> 原 P2-2（备份误报）→ **P2-1**；原 P2-3（时间线缺发言文本）→ **P2-2**。
> P1-1 的定级依据是「**可由 RT-002 修复前的历史数据触达**」，见 §3.1 的「历史可达性」。
>
> **2026-11-02 复验结论（窗口内实测，HEAD=`ae34350`，F1–F5 已合入）**：
> **4 条缺陷全部关闭**（P1-1 / P1-2 / P2-1 / P2-2），V12 补缝完成。
> 实测 **356 tests / 0 failed / 0 skipped，52 个测试类**；其中审计用例 **48 条全绿**。
> 逐项证据见 §8。

---

## 0. 结论表（12 行）

| 编号 | 场景 | 判定 | 证据 |
|---|---|---|---|
| **V01** | v3→v4 升级 | **PASS**（真机项 NOT RUN） | `MigrationAuditTest.v01_migration_backfills_columns_and_room_validates`：`user_version=4`、两列已加、无链事件回填 `(order,0)`、索引存在、**Room 打开通过 schema 校验**且 `listActive` 返回 5 条按逻辑位置升序。instrumentation 版（`MigrationV3ToV4Test`）**NOT RUN**（无设备） |
| **V02** | 旧库连续修订链 | **PASS** | `MigrationAuditTest.v02_three_level_chain_shares_position_with_increasing_seq`：三层链 `a0/a1/a2` → `logicalPosition=1` 且 `revisionSeq=0/1/2`；dormant 位置 `b0=4/0`；active 恰为 `a2,c0`；INV-1 重复位置数=0；审计视图 5 行/位置 1 有三个版本。`v02b`：迁移后新事件落在位置 4，**不复用**被撤销的位置 2 |
| **V03** | WAL 有近期写入时备份 | **PASS（正例）/ NOT RUN（busy 分支）** | `BackupWalAuditTest.v03_backup_includes_committed_frames_that_only_exist_in_wal`：真把 WAL 撑到 20632 字节 → **朴素复制只拿到 `[e1]`（丢 e2）**，`backupBeforeMigration` 拿到 `[e1,e2]`、仍为 v3、无 v4 列。checkpoint busy 分支本机造不出来（见 §3.2） |
| **V04** | 修改中间历史记录 | **PASS** | `RevisionIntegrityAuditTest.v04_revising_third_of_five_keeps_position_and_order`：5 条 → 修订第 3 条后 active 仍 5 条、`occurredOrder=1..5`、第 3 条是新版本 id、第 1/2/4/5 未变、时间线 `sourceEventIds` 指向新版本、审计视图 6 行且位置 3 恰好 2 版本 1 撤销 |
| **V05** | 保存中途抛异常 | **PASS** | `RevisionIntegrityAuditTest.v05_rejected_revision_writes_nothing_and_keeps_old_fact`：被拒修订后 DB「指纹」（id/位置/版本/retracted）**逐字相同**、无孤儿行。UI 层由 `RecordEditSessionTest.commitFailureKeepsInputAndEditSession` 覆盖（FAILED + 输入保留 + 编辑态保留） |
| **V06** | 重复点击 / 并发修订 | **PASS（仓储）/ 发现 P1（UI）** | `RevisionIntegrityAuditTest.v06_second_revision_on_same_stale_target_fails_cleanly`：同 target 第二次修订 → `INVALID_SUPERSEDE_TARGET`、该位置 1 条 active、只写 1 行、版本号只 +1。并发版见工程师 `RevisionConcurrencyTest`。**新发现见 P1-2**：草稿自动保存期间点「确认提案」会被静默丢弃（`CommitDuringDraftSaveAuditTest`，实测 `commitResult=null`、事件 10→10、状态回到 SAVED） |
| **V07** | 修改影响终局的任务 | **PASS** | `RevisionIntegrityAuditTest.v07_revising_decisive_mission_updates_status_keeps_revealed_at`：三次失败→FINISHED_EVIL 且已揭晓；把第 3 任务改成成功后 `matches.status == replay 派生 == IN_PROGRESS`、`revealedAt` 逐字不变、bundle 中第 3 任务变 SUCCESS/0 张失败牌 |
| **V08** | 选点前/后复盘 | **PASS** | `PerspectiveRevisionAuditTest.v08_revised_vote_is_invisible_before_its_position`：修订后 `at=2` 看不到位置 3 的任何版本、也不出现「赞成 4」；`at=3` 只见修订版（旧版被过滤）。`v08b`：末位仍是 5（修订不拉长时间线） |
| **V09** | 私有声称/笔记参与修订 | **PASS** | `PerspectiveRevisionAuditTest.v09*`：修订后的私有声称不进 `publicFacts`、默认 JSON 导出不含新/旧版本、勾选后才出现；上帝视角仍看到 4 号=MORDRED 且 private 声称**不混进 publicFacts**；`v09c` 修订版笔记仍受 `availableFromOrder` 闸门约束、旧版不因「还在库里」而复活 |
| **V10** | JSON 导出再导入 | **PASS** | `ExportImportRevisionAuditTest.v10`：修订过的对局导出 → `schemaVersion=1`、`eventCount` 等于 active 数、无 `retracted:true` 行、含新版本 id；导入后「事件形状」(类型@位置+关键字段) 与源**逐条相同**、`occurredOrder` 1..N 连续、status 与源相同且等于重放派生、不覆盖原对局。`v10b`：把 `schemaVersion` 改成 99 → `Rejected` |
| **V11** | 页面重建/进程被杀 | **PASS（JVM 代理）/ NOT RUN（真机杀进程）** | 工程师 `RecordViewModelTest`：`draftIsRestoredOnViewModelRebuild`、`restoreDoesNotOverwriteUserInput`、`corruptedDraftFallsBackToEmpty`、`restoreDoesNotScheduleSave`、`restoreDoesNotOverwriteClearedInput`；`RecordEditSessionTest`：`restoringEditDraftWithValidTargetEntersEditMode`、`restoringEditDraftWithInvalidTargetKeepsInputAndFlags`、`commitFailureKeepsInputAndEditSession`。真实 `kill -9` + 冷启动 **NOT RUN** |
| **V12** | 一次性刷新中断后重启 | **PARTIAL** | 仓储契约 PASS：工程师 `ProjectionRefreshTest`（`refreshRealignsStatusButLeavesUpdatedAtUntouched`、`refreshIsIdempotent`、`failedMatchIsReportedAndPreventsMarker` 等 6 例）。**缺口：`AppContainer.kt:103-132` 那段「isComplete → 写标记」没有测试缝**，只能代码走查（见 §4） |

**汇总**：PASS 11（V01–V11）/ PARTIAL 1（V12）/ 本轮无 FAIL；
本机 **NOT RUN** 项 3 个：V01 的 instrumentation 迁移、V11 的真机杀进程、V03 的 checkpoint-busy 分支。
新发现缺陷：**2 个 P1**（迁移 CTE 跨局污染、保存窗口静默吞提交）、**2 个 P2**（备份误报成功、时间线缺发言文本）、1 个 P3；无 P0。

---

## 1. 我新增的测试（20 条 / 6 个类，全绿）

| 文件 | 用例数 | 覆盖 |
|---|---|---|
| `audit/MigrationAuditTest.kt` | 5 | V01、V02、V02b、跨局脏链对抗（v01b / v01b2） |
| `audit/BackupWalAuditTest.kt` | 2 | V03（WAL 正例 + 无库不产生产物） |
| `audit/RevisionIntegrityAuditTest.kt` | 4 | V04、V05、V06、V07 |
| `audit/PerspectiveRevisionAuditTest.kt` | 5 | V08、V08b、V09、V09b、V09c |
| `audit/ExportImportRevisionAuditTest.kt` | 2 | V10、V10b |
| `audit/CommitDuringDraftSaveAuditTest.kt` | 2 | V06 UI 侧（保存窗口） |

夹具复用：`LegacyV3Database`（从 `schemas/3.json` 反向生成真实 v3 库，我不改它）、`AuditSupport.kt`（上一轮的 `MutableClock` / `AuditEvents`）、`FakeMatchRepository`、`MainDispatcherRule`。

**运行结果**

```
:app:testDebugUnitTest --no-daemon --tests "com.roundtrace.app.audit.*"   → 36/0（含上一轮 16 条）
:app:testDebugUnitTest --no-daemon（全量）                                 → 325 tests / 0 failed / 0 skipped，45 个测试类
```
（基线 305/39 → 本轮 +20 条 / +6 类；另 2 个临时探针文件已删除，未计入。）

### 关于"别只复跑工程师跑过的东西"

三处刻意的独立设计：

1. **V03 用「朴素复制」做对照**：如果只断言「备份能打开、行数对」，WAL 在连接关闭时已被 checkpoint，测试永远是绿的——那是假绿。必须先证明「不做 checkpoint 会丢数据」，才能证明修复是承重的。
2. **V01b/V01b2 造跨对局 `supersedesId`**：这是我读了迁移 SQL 才想到的攻击面（两条递归 CTE 都不按 `matchId` 分组）。
3. **V06 用「阻塞式装饰仓储」**把 `SaveStatus=SAVING` 的时间窗口固定住——不这么做，这个窗口只有几毫秒，永远测不到。

---

## 2. 关键实现点的代码走查（不是只跑测试）

| 点 | 位置 | 结论 |
|---|---|---|
| 迁移四条语句顺序 | `RoundTraceDatabase.kt:MIGRATION_3_4` | 加列 → 回填 position → 回填 seq → 建索引，与 `4.json` 的 `defaultValue="0"` 逐字一致（不一致会抛 `Migration didn't properly handle`） |
| 读路径排序键 | `MatchEventDao.kt:listActive` | `ORDER BY logicalPosition, revisionSeq`，正确 |
| 位置分配 | `MatchEventDao.appendWithSlot` | `MAX(logicalPosition)+1`，**含 retracted 行**（否则会复用废弃位置） |
| 修订槽位 | `MatchEventDao.reviseWithSlot` | 复用旧 `logicalPosition`、`seq+1`、**先撤销后插入**且 `check(affected==1)` |
| 事务内复核 | `RoomMatchRepository.kt:252-260` | 事务内再查 `findActiveById`（含 `retracted=0`）+ 类型比对，失败抛 `RevisionConflictException` → 回滚 |
| 冲突→错误码 | `RoomMatchRepository.kt:286-293` | 两种异常都映射成 `INVALID_SUPERSEDE_TARGET`，不把异常抛给 UI |
| 投影刷新失败 | `RoomMatchRepository.kt:496-524` | 逐局 `runCatching`，失败进 `failed` 列表；`isComplete = failed.isEmpty()`；**不写标记**由 `AppContainer` 决定 |
| 刷新不动时间戳 | `RoomMatchRepository.kt:543-555` + `MatchDao.updateStatusKeepTimestamp` | `touchUpdatedAt=false` 时不动 `updatedAt` |
| 备份钩子位置 | `RoundTraceDatabase.build()` 第一行 | 在 `databaseBuilder` **之前**（`onOpen` 太晚）——我复跑了钩子用例，`build()` 返回时 `.bak` 已存在 |
| 去重 | `RoomMatchRepository.buildBundle` | `compareBy(position).thenByDescending(revisionSeq)` + `distinctBy(position)`，且 **decode 之前**去重（领域 `MatchEvent` 没有 `revisionSeq` 字段） |
| INV-1 自检 | `RoundTraceDatabase.findDuplicateActivePositions` | 只 `Log.w`、不修复、不阻断（设计取舍合理） |

---

## 3. 对抗性检查结果

### 3.1 迁移回填的 CTE 不按 `matchId` 分组 —— **P1-1**

`MIGRATION_3_4` 的两条递归 CTE 都是全局链：

```sql
WITH RECURSIVE chain(id, rootOrder) AS (
    SELECT id, occurredOrder FROM match_events WHERE supersedesId IS NULL
    UNION ALL SELECT child.id, chain.rootOrder FROM match_events child
      JOIN chain ON child.supersedesId = chain.id )
```

`JOIN` 只按 `supersedesId = id`，**没有 `child.matchId = chain.matchId`**。

**复现路径（`MigrationAuditTest.v01b2`，实测输出）**：

```
[AUDIT-D] 撞车场景：mB active=[f1@1/0, f2@1/1]，重复位置数=1
```

- v3 数据：`mA.e1(order1)`；`mB.f1(order1)`；`mB.f2(order2, supersedesId=mA.e1)`（跨局脏链）
- 迁移后 `f2.logicalPosition = 1`（继承的是 mA 的链根），与 `mB.f1` **同位**
- 后果：`mB` 出现同一位置两条 active，INV-1 被破坏；启动期自检只 `Log.w`；`listActive` 会把两条都读出来交给 replay（可能双计）

**期望 vs 实际**：期望「子行继承**本对局**链根位置」；实际「继承全局链根位置」。

**可达性（这一条决定了它是 P1 而不是 P2）**：

- **今天写不出来**：`reviseEvent` 现在校验同局（`RoomMatchRepository.kt:222`）；导入时 `supersedesId` 只会重映射到**同一次导入内**的新 id（`importBundle` 的 `oldToNewEventId`），所以新增数据不会产生跨局链。
- **但历史数据可能已经脏了**。RT-002 修复前（`ee20e3d^`）的 `reviseEvent` **没有任何对局归属校验**：

  ```kotlin
  val targetEntity = eventDao.findById(targetEventId.raw)   // 只按 id 查，不比对 matchId
      ?: return failed(matchId, RepositoryError.INVALID_SUPERSEDE_TARGET)
  val check = EventRevisionPolicy.check(target, replacement, sameMatch = true)
  //                                                                 ^^^^^^^^^^^^^^^^ 硬编码 true，不是校验
  ...
  codec.encode(replacement, matchId, assigned).copy(supersedesId = targetEventId.raw)
  //                                    ^^^^^^^ 新行落在 A 局，supersedesId 指向 B 局的行
  ```

  也就是说：那段时间里只要发生**一次**「拿 B 局的事件 id 去修订 A 局」（无论来自 UI 缺陷、脚本还是手工写库），
  库里就会留下一条**跨局 `supersedesId`**，而 v3→v4 迁移会把它的位置算成 B 局的链根 → INV-1 被破坏。
- 结论：风险不在「现在还能触发」，而在「**磁盘上可能已经存在**这种链，而迁移是一次性、不可逆的」。
  用户不会得到任何提示（只有启动期 `Log.w`），却会看到同一位置两条 active 一起参与重放。

**建议**：给两条 CTE 的 `JOIN` 加 `AND child.matchId = chain.matchId`（并在 `chain` 里带出 `matchId`）。代价近似为零，收益是「一次性不可逆操作对脏数据也不产生 INV-1 违反」。

### 3.2 WAL 备份：checkpoint 的返回值被丢弃 —— **P2-1（真机未验证）**

`RoundTraceDatabase.readUserVersionWithCheckpoint`：

```kotlin
runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
```

`PRAGMA wal_checkpoint` 的三元组 `(busy, log, checkpointed)` **完全没有读**；随后 `dbFile.copyTo(target)` 只复制主库文件、不含 `-wal`。

- **正例已验证**（§0 V03）：本机能让 WAL 真实 pending（20632 字节），朴素复制确实丢数据，备份拿到了全部数据。
- **busy 分支 NOT RUN**：Robolectric 的 SQLite 是多连接共享句柄，探针实测 `busy=0`（即使我持有读事务），造不出 busy。
- **代码结论**：若真机上 checkpoint 返回 busy（例如其它进程/连接持读锁），备份会**缺最近事务**，却仍然 `Log.i("迁移前备份完成")`。注释里那句「不会比不 checkpoint 更差」在这个分支上是反的。

**建议**（不阻塞本轮）：读取 `busy` 标志；`busy != 0` 时要么重试、要么把 `.bak` 命名为 `.bak.incomplete` 并 `Log.w`，让「备份存在」不再等价于「备份可用」。

### 3.3 时间线不显示声称的自由文本 —— **P2-2**

`TimelineBuilder.kt:137-152` 构造 `PlayerClaimed` 条目时：`title = claimTitle(event)`（如「3 号发言（怀疑）」）、`detail = 发言类型 · 目标`，**没有用到 `event.text`**。两个渲染端（`TimelineEventCard.kt:88-114`、`RecordTimelineSection.kt:119-130`）也只渲染 `title` / `detail` / 未记录字段 / 来源事件 id。

**复现**：`RevisionIntegrityAuditTest.v04` 中第 3 条事件的 `text = "修订后 3"`，时间线第 3 条的 title/detail 里都找不到该字符串（该用例最终改为断言 `sourceEventIds` 指向新版本）。

**影响**：PRD FR-06 要求时间线能回放「关键发言」；现在只有标签和对象，用户当时写的话看不到（修订前后都看不到）。

### 3.4 草稿保存窗口内提交被静默丢弃 —— **P1-2（本轮最有价值的发现）**

`RecordViewModel.commit()`（`:468`）：

```kotlin
if (saveStatusFlow.value == SaveStatus.SAVING) return
```

`SaveStatus.SAVING` 被**草稿自动保存**（`persistDraftNow()`，`:264`）和**提交写库**（`:470`）共用。

**复现（`CommitDuringDraftSaveAuditTest.v06_commit_is_silently_dropped_while_draft_save_is_in_flight`）**：
用装饰仓储把 `saveDraft` 卡住 → 触发一次草稿保存（`SAVING`）→ 点「确认提案」。

实测输出：

```
[AUDIT-D] 草稿保存中提交：saveStatus=SAVING，commitResult=null，事件数=10→10
[AUDIT-D] 对照组提交结果：Succeeded，saveStatus=SAVED
```

- 提交**完全没发生**（10→10），`commitResult = null`（UI 收不到任何结果，不会提示）；
- 草稿保存放行后 `saveStatus` 变成 **SAVED** —— 用户看到的是「已保存」，而提案根本没写进去。

**影响**：现场快速操作时（选完队员马上点确认）概率不低；后果是**用户以为记录成功**（直到复盘发现缺一条提案）。
按主理人裁定定为 **P1**：属于「界面显示成功但数据没落」这一类，优先级高于数据没丢、
因为用户不会再去检查——他会直接开始下一轮。

**为什么不是「点了两次」那么简单**：连点两次提交被忽略是**正确**行为（防重复）；
问题在于 `SAVING` 这个标志同时代表「草稿在存」和「提交在写」，于是**一次合法的首次提交**
也会被当成重复点击丢掉。

**建议**：给「提交中」单独一个标志（如 `commitInFlight`），草稿保存只置 `SAVING` 但**不阻塞 commit**；或 commit 时若正在草稿保存则先 `await` 它而不是丢弃。

---

## 4. 缺陷清单（按主理人裁定后的级别排序）

| 级别 | 编号 | 原编号 | 位置 | 现象 | 影响 | 建议修法 |
|---|---|---|---|---|---|---|
| **P1** | **P1-1** | 原 P2-1 | `RoundTraceDatabase.kt` `MIGRATION_3_4`（两条递归 CTE） | 回填不按 `matchId` 分组；跨局 `supersedesId` 会让子行继承**他局**链根位置 | **迁移不可逆**。RT-002 修复前的版本允许写跨局链（`ee20e3d^` 的 `reviseEvent` 无归属校验、`sameMatch = true` 是硬编码），所以真实 v3 库**可能已经脏**；迁移后 INV-1 被破坏、只 `Log.w`、两条 active 一起进 replay（可能双计） | 两条 CTE 的 `JOIN` 加 `AND child.matchId = chain.matchId`，并把 `matchId` 带进 `chain`。**另配一条只读诊断查询**（跨局链 → `Log.w` 打 `matchId + eventId`），**不自动修数据** |
| **P1** | **P1-2** | 原 P2-4 | `RecordViewModel.kt:468` + `:264` | `SaveStatus.SAVING` 被草稿自动保存与提交写库共用；草稿保存期间点「确认提案」被 `return` 丢弃 | 用户看到最终状态 **SAVED**，提案却没写 → 「显示成功但数据没落」。实测 `commitResult=null`、事件 10→10 | 拆成两个标志（如 `commitInFlight`），草稿保存**不阻塞** commit；或 commit 先 `await` 草稿保存而不是丢弃 |
| **P2** | **P2-1** | 原 P2-2 | `RoundTraceDatabase.readUserVersionWithCheckpoint` / `backupBeforeMigration` | `PRAGMA wal_checkpoint(TRUNCATE)` 的 `(busy,log,ckpt)` 被整个丢弃，随后只复制主库文件 | 真机 busy 时会得到「缺最近事务的备份」，却仍 `Log.i("迁移前备份完成")`；备份存在 ≠ 备份可用 | 读 `busy`；`busy != 0` 时重试或改名为 `.bak.incomplete` 并 `Log.w` |
| **P2** | **P2-2** | 原 P2-3 | `TimelineBuilder.kt:137-152` + `TimelineEventCard.kt:88-114` + `RecordTimelineSection.kt:119-130` | 声称的 `text`（用户手写的关键发言）从不进入 `title`/`detail` | 记录页与复盘页都回放不出「当时说了什么」，与 FR-06 冲突（导出侧本来有，口径也不一致） | 把 `text` 放进 `detail`（或新增 `note` 字段）；**三处口径要一起改**（含 `MarkdownExporter.kt:288-297`） |
| P3 | P3-1 | — | `RoundTraceDatabase.warnOnDuplicateActivePositions` | INV-1 违反只 `Log.w`、不修复、不阻断 | 用户侧零感知，脏数据一直被读出来参与重放 | 至少在复盘页给一条「数据可能异常」提示（不自动改历史） |

无 P0。上一轮的 RT-002/001/004/005 结论在本轮未出现回退（V05/V06/V07 的用例同时覆盖了它们的边界）。

**P1 与 P2 的定级差异（便于排期）**

- P1-1：**已经在用户磁盘上**、且修复窗口只有「升级前」这一次——迁移跑完就不可逆，所以必须在发版前修。
- P1-2：**每个用户每次现场记录都可能遇到**，且失败是静默的（用户不会自查）。
- P2-1 / P2-2：都能被用户侧现象兜住（备份缺数据还有主库；时间线缺文本还能看导出），且不阻断主流程。

---

## 5. 我实际运行了什么 / 没运行什么

**实际运行（本机，非引用他人结论）**

```bash
export JAVA_HOME=/c/Users/HK-info/android-build/jdk
export ANDROID_HOME=/c/Users/HK-info/android-build/sdk
gradle :app:testDebugUnitTest --no-daemon --tests "com.roundtrace.app.audit.*"   # 36 / 0
gradle :app:testDebugUnitTest --no-daemon                                        # 325 / 0 / 0
```
- 计数取自 `app/build/test-results/testDebugUnitTest/*.xml` 的 `tests/failures/errors/skipped` 求和。
- 全部是 **JVM + Robolectric（`@Config(sdk=[34])`）**，跑的是真实 Room/SQLite 与真实事务。
- 另写过 2 个一次性探针（`WalProbeTest` / `WalProbe2Test`）确认 Robolectric 的 `journal_mode` 与 checkpoint 行为，**已删除**，不在交付物里。

**没有运行（如实声明）**

| 项 | 原因 |
|---|---|
| `app/src/androidTest`（含 `MigrationV3ToV4Test`、`MatchDaoTest`） | 无设备/模拟器 → **NOT RUN** |
| 真机 `kill -9` + 冷启动恢复（V11） | 无设备；以「ViewModel 重建 + 持久化草稿」代理验证 |
| WAL checkpoint busy 分支（V03 负例） | Robolectric 单进程共享句柄，造不出 busy（探针实测 busy=0）→ **NOT RUN** |
| `AppContainer` 写投影标记的实际行为（V12） | 无测试缝（逻辑写在 `init` 块里）；只做代码走查 |
| Compose UI 像素级 / 单手操作 | 无 Compose 测试、无人工点按；P2-2/P1-2 的界面表现是从代码与状态推断的 |

---

## 6. 剩余风险

1. **迁移是单向不可逆的**：P1-1 修好前，任何「带跨局链的脏库」执行升级都会留下 INV-1 违反；
   `1.json~3.json` 的备份是唯一退路，而备份本身还有 P2-1（busy 时缺最近事务）。两条建议同批处理。
2. **V12 的标记逻辑没有任何自动化保护**：`AppContainer` 里那 8 行如果被改成「不管 `isComplete` 就写标记」，
   现有 305 条测试**一条都不会红**。主理人已采纳「抽成纯函数」的建议，抽完必须配一条
   「`isComplete=false` → 不写标记」的用例，否则缝等于没补。
3. **P1-2 是时序缺陷**：修的时候容易改成「commit 等待草稿保存」→ 又引入主线程等待。
   建议只改标志位语义（`commitInFlight` 与 `draftSaving` 分开），不要加锁等待。
4. **P2-2 涉及展示层**：改动会影响记录页 / 复盘页 / Markdown 导出三处口径
   （导出侧本来就有 claim 文本，`MarkdownExporter.kt:288-297`），改完必须三处一致。
5. 本轮没有验证 **10 人局 / 5 人局**的迁移与修订路径（我只用 8 人局）。
   位置/版本逻辑与人数无关，但如果有人把人数写进排序键就会漏。
6. **P1-1 的存量数据风险已被「版本从未发布」消除，但需要诊断确认**（2026-11-02 主理人裁定后修正）：
   `app/build.gradle.kts:22` 仍是 `versionCode = 3` / `versionName = "0.3.0"`，即**v4 从未发布**。
   因此不存在「用户先升到坏 v4、再升到修好的 v4」的中间态——任何真实用户都是
   **v3 直接用修好的 CTE 升级**，跨局子行会在 `JOIN` 失配后回落到自身 `occurredOrder`，
   不会再把别局的链根位置算进来。跑过坏 v4 的只有我们本地构建。
   - 残余风险只有一条窄缝：回落到 `occurredOrder` 时，理论上仍可能与**本局既有**的
     `logicalPosition` 撞值（`occurredOrder` 在本局内唯一，但它可能等于某条事件的逻辑位置）。
     概率极低，但不能说「零」。
   - 因此主理人把「只读自检查询」采纳为**诊断项**（与 B3 的 `warnOnDuplicateActivePositions` 同族），
     命中只 `Log.w` 打印 `matchId + eventId` 对，**不做自动修复**——自动改历史数据比不修更危险。
     我同意这个处置：数据修复必须有明确的用户授权路径，不能在启动期悄悄改。

---

## 7. 待执行的复验计划（等主理人通知，只验这 4 条 + V12）

> 原则：**不重跑全矩阵**，只对改动点做针对性验证，并确认既有 20 条审计用例不回退。

| 项 | 复验内容 | 通过标准 |
|---|---|---|
| **P1-1** | ① 改 `MigrationAuditTest.v01b2` 的期望：跨局链**不得**再撞车（`f2` 应回落到自己的 `occurredOrder`，mB 重复位置数=0）；② 新增「同名位置但不同 matchId」的链，确认各算各的；③ 新增「诊断查询」的用例：造一条跨局链 → 查询必须命中并带出 `matchId + eventId`，且**不修改任何行**（前后 `rows`/槽位逐字相同） | 三条全绿，且 `v01b`（跨局链继承他局位置）的特征化断言按新行为更新 |
| **P1-2** | 现有 `CommitDuringDraftSaveAuditTest.v06_commit_is_silently_dropped_while_draft_save_is_in_flight` 反向断言：窗口内提交**必须真的落库**（`commitResult is Succeeded` 且事件 +1），且 `saveStatus` 最终为 SAVED；对照组保持不变 | 该用例改断言后通过；另补一条「连点两次只写一条」的用例防止修过头 |
| **P2-1** | `BackupWalAuditTest` 增一条：`readUserVersionWithCheckpoint` 在 `busy` 时**不得**产出被记为成功的备份（可用 `ShadowLog` 断言没有「备份完成」日志，或断言产出 `.incomplete`） | 新用例绿；原 `v03` 正例仍绿 |
| **P2-2** | `RevisionIntegrityAuditTest.v04` 加断言：时间线 `title + detail` 必须包含 `event.text`；并补一条「Markdown 导出文本 == 时间线文本」的口径一致性用例 | 两条绿；`MarkdownExporterTest` 不回退 |
| **V12** | 新增 `ProjectionMarkerAuditTest`：对抽出来的纯函数做真值表（`isComplete=true → 写`、`false → 不写`、空库 → 写）；若函数签名变了则以实现为准，但**必须**覆盖「有失败时不写」 | 新用例绿；`ProjectionRefreshTest` 6 例不回退 |

**复验不通过时的处理**：直接报 FAIL + 复现路径，不替工程师改期望值。

---

## 8. 复验结果（2026-11-02 · 窗口内实测）

> 受验 HEAD：**`ae34350`**（`5bdb1ac` F1 / `5f13775` F2 / `9a7fac1` F3 / `c401951` F4 / `ae34350` F5）
>
> ⚠ **F 链 SHA 在我验证期间被重写过一次**（早先的 `5bca511…caf4fdf` → 现在的 `5bdb1ac…ae34350`，
> 内容上 F2 多了一组「跨局链诊断查询」+75 行）。我的**最终全量 356/0 跑在重写之后**，
> 所以本文所有数字对应的是当前这组 SHA；引用的早期 SHA 一律作废。
> 命令：`gradle :app:compileDebugUnitTestKotlin` → `:app:testDebugUnitTest --tests "com.roundtrace.app.audit.*"` → `:app:testDebugUnitTest`

### 8.1 逐项判定

| 项 | 判定 | 证据（实测） |
|---|---|---|
| **P1-1 迁移 CTE** | **PASS（已关闭）** | `MigrationAuditTest.v01b_cross_match_supersedes_chain_falls_back_to_own_order`：跨局链不再继承他局位置，`f2 → logicalPosition=3, revisionSeq=0`；`v01b2_..._no_longer_collides_with_local_position`：`mB active=[f1@1/0, f2@2/0]`，同位置重复数 **0**，位置集合 `[1,2]` 无重号 |
| **P1-2 提交被吞** | **PASS（已关闭）** | `CommitDuringDraftSaveAuditTest.v06_commit_during_draft_save_window_still_persists`：草稿保存窗口内提交 `commitResult=Succeeded`，第 3 任务 `TeamProposed` `0→1`；`v06b_double_submit_writes_exactly_one_event`：连点两次仍只有 1 条提案（防重复未被拆掉）；对照组 `v06_control` 仍绿 |
| **P2-1 备份 busy 误报** | **PASS（已关闭）** | `BackupOutcomeAuditTest` 6 条：`busy=1 → Incomplete(reason 含 busy=1)`；`busy=0 → Complete`；库不存在 → `NotNeeded`；busy 时**仍生成备份文件**（降级可用）但**不报完成** |
| **P2-1 边界（主理人点名）** | **PASS · 保守** | `f3_unknown_checkpoint_result_is_conservative`：`WalCheckpointResult.UNKNOWN`（`busy=-1`）→ `isComplete=false` → **`Incomplete`**，**不是** `Complete`。另 `f3_checkpoint_throwing_degrades_to_incomplete`：checkpoint 直接抛异常也退化为 `Incomplete`。**结论：拿不到 PRAGMA 结果时走保守分支，未回到「误报成功」** |
| **P2-2 时间线缺发言文本** | **PASS（已关闭）** | `TimelineClaimTextAuditTest` 5 条：`detail = "怀疑 · 我觉得 3 号在保 5 号"`（原文进入 detail）；180 字长文被截断为 ≤60 + `…` 且不整段塞入；纯空白原文不产生空 detail 也不残留空白；角色声称 `title="2 号声称自己是梅林"`（保留「声称」语义）；`f4_does_not_change_visibility`：私有声称在公开视角**仍然为空**、本人可见、他人不可见 |
| **P2-2 端到端** | **PASS** | `RevisionIntegrityAuditTest.v04` 新增断言（走真实 Room 编解码）：修订后 `detail` 含新原文、**不含**旧版本原文 |
| **V12 标记纯函数** | **PASS（补缝完成）** | `BackupOutcomeAuditTest.v12_projection_marker_truth_table`：`shouldRefresh(0,1)=true`、`(1,1)=false`、`(2,1)=false`；`shouldWriteProjectionMarker`：全成功且未标记 → `true`；**任一局失败 → `false`**；已标记 → `false`；空库 → `true` |
| **§7-③ 跨局链诊断查询** | **PASS（由工程师补测）** | 新增 `CrossMatchSupersedeCheckTest`（3 条，已含在 356 内）：跨局链**能被查出来**（命中 1 条），且「不得增删任何事件行」「不得改写 `supersedesId`」——正是我 §7 里定死的「只读」通过标准 |
| **既有 20 条审计用例** | **无回退** | 审计包 11 个类 **48 条全绿**（含阶段 1 的 `CrossMatchWriteIsolationTest` 5 / `PerspectiveLeakExitsTest` 7 / `StatusProjectionTest` 4） |

### 8.2 数字（与工程师自报口径的差异）

| 口径 | 数值 | 说明 |
|---|---|---|
| 实测执行 | **356 tests / 0 failed / 0 skipped / 52 类** | 取自 `app/build/test-results/testDebugUnitTest/*.xml` 求和，**与 `@Test` 注解总数 356 完全一致**（无参数化/忽略） |
| 其中审计包 | **48 条（11 类）** | 含阶段 1 的 16 条 + 阶段 D 的 21 条 + 本次复验新增 11 条 |
| 其中非审计 | **308 条** | 356 − 48 |
| 工程师自报「代码树 321 / 全量 342」 | 与实测不符 | 我用 `git grep @Test` 只统计**已跟踪文件**得到 **324**（我的 6 个审计测试文件是未跟踪的新文件）。他的 321 应是**某个较早时点、且只数已跟踪文件**的口径，随提交增长；差异来源是**统计方法与时间点**，不是用例丢失 |

### 8.3 复验中新发现的小问题（P3，不阻塞）

- `TimelineBuilder.claimDetail` 末尾的 `ifEmpty { "公开发言" }` 是**不可达兜底**：`claimTypeLabel(...)` 永远返回非空串，所以 `meta` 部分不会为空，`detail` 不可能落到 `ifEmpty`。实测无原文时 `detail = "怀疑"`。
  建议：要么删掉这个分支（避免读者误以为存在「完全无内容」的路径），要么把 `meta` 为空的情况显式建模。**不影响功能**。

### 8.4 环境备注（不影响结论，供后续复现参考）

窗口内构建仍出现多次**非代码**失败，均已排除且不影响上表结论：

1. `Could not flush incremental caches ... kspCaches/debugUnitTest/symbolLookups: lookups.tab` → 删除 `app/build/kspCaches` 后恢复；
2. `Daemon compilation failed: Connection to the Kotlin daemon has been unexpectedly lost` → Gradle 自动回退到无 daemon 编译，测试照常执行；
3. `Unable to delete ... test-results/.../output.bin`（早前并发窗口遗留）→ 重跑即恢复。

**判定原则**：以上都表现为「构建工具自身报错」，不是断言失败；每次都以「XML 里的 tests/failures 实测数」为准，不用 Gradle 控制台的 BUILD SUCCESSFUL/FAILED 单点判断。
