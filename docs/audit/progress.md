# Phase 1 · P0 修复进度（工程师：寇豆码）

基线：`main` @ `a6da7c4`，208 单测 / 0 失败 / lint 0 error。
本轮范围：RT-002、RT-001、RT-004、RT-005（RT-003 由架构师出 RFC，本轮不动 order 语义）。

---

## 提交一览

| # | 提交 | 任务 | 类型 |
|---|---|---|---|
| 1 | `ee20e3d` | RT-002 DAO 级对局归属 | fix(data) |
| 2 | `48a7144` | RT-001 `revealedAt` 回归 | test(data) |
| 3 | `8108686` | RT-004 状态投影统一重算 | fix(data) |
| 4 | `017b98e` | RT-005 视角可见性统一 | fix(knowledge) |
| 5 | `73ffc84` | RT-005 补漏：复盘时间线按视角过滤 | fix(replay) |
| 6 | （本提交） | 最终三件套验证 + 本文档定稿 | docs(audit) |

均**未 push**。

---

## 验证命令与结果

```bash
export JAVA_HOME="/c/Users/HK-info/android-build/jdk"
export ANDROID_HOME="/c/Users/HK-info/android-build/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

| 项 | 基线 | 本轮结束 |
|---|---|---|
| 单测用例 | 208 | **229**（+21） |
| 失败 | 0 | **0** |
| lint | 0 error | **0 error / 54 warning**（与基线同类，未新增） |
| APK | 20.4 MB | `app-debug.apk` **20,595,897 bytes**（最终三件套全量构建） |
| 构建 | SUCCESSFUL | **BUILD SUCCESSFUL in 2m 4s** |

### 失败名（过程中的红→绿记录）

| 任务 | 先写的失败测试 | 红态报错 |
|---|---|---|
| RT-002 | `markRetractedIsScopedByMatchId` 等 4 例 | `No parameter with name 'matchId' found`（API 变更 + 行为缺口） |
| RT-004 | `retractingQuestEventRecomputesMatchStatus` | `expected:<IN_PROGRESS> but was:<FINISHED_EVIL>` |
| RT-004 | `revisingQuestEventRecomputesMatchStatus` | `expected:<IN_PROGRESS> but was:<FINISHED_EVIL>` |
| RT-004 | `importBundleStoresDerivedStatusNotDraft` | `expected:<FINISHED_GOOD> but was:<DRAFT>` |
| RT-005 | `privateClaimDoesNotProducePublicClaimLabel` | `实际=[CLAIMED_MERLIN]`（私有声称漏进公开标签） |

RT-001 的实现已在半成品里改好（`updateStatusOnly`），本轮只补回归测试，故无红态。

---

## 改动文件

### 生产代码

| 文件 | 改动 |
|---|---|
| `core/database/dao/MatchEventDao.kt` | `markRetracted(matchId, id): Int` 按对局限定；`reviseSupersede` 改为「先撤销、未命中则抛异常回滚、再插入」 |
| `core/database/dao/MatchDao.kt` | 新增 `updateStatusOnly` / `updateRevealedAt`（半成品已含，本轮沿用） |
| `core/data/RoomMatchRepository.kt` | 事件归属校验；新增 `recomputeProjectionLocked` 并在 4 个写路径统一调用；`importBundle` 不再落 DRAFT |
| `core/replay/VisibleEventPolicy.kt` | **新增**：可见性唯一出口（纯 Kotlin） |
| `core/knowledge/AvalonKnowledgeProvider.kt` | `publicFacts` / `claimLabels` / `unknowns` 统一走 PUBLIC 过滤 |
| `core/knowledge/AvalonEvidenceAnalyzer.kt` | `analyze` 内部按 `snapshot.mode` + `observerSeat` 过滤历史 |

### 测试代码

| 文件 | 新增用例 |
|---|---|
| `test/.../data/RoomMatchRepositoryRobolectricTest.kt` | +10（RT-002 ×4、RT-001 ×3、RT-004 ×3） |
| `test/.../knowledge/VisibilityLeakTest.kt` | **新增 9 例**（RT-005） |
| `test/.../database/MatchDaoRobolectricTest.kt`、`androidTest/.../MatchDaoTest.kt` | 仅同步 `markRetracted` 新签名，无新增用例 |

---

## 根因

### RT-002 · 事件写入不按对局限定

`mark_events` 的所有写操作只按 `id` 定位。`reviseSupersede` 更是「先插入替代事件、再撤销旧事件」，
两步都没有 matchId 条件：一旦 `oldEventId` 属于别的对局，就会在 A 局插入一条替代事件、
B 局的事件一条也没撤销，**留下两份互相矛盾的「同一事实」且无人报错**。
事件 id 是 UUID 时碰撞概率极低，但 SQL 层完全没有阻止这件事——这是「概率保护」而非「正确性保护」。

### RT-001 · `appendEvent` 把 `revealedAt` 写回 null

状态回写用的是 `updateStatus(id, status, revealedAt, now)` 并固定传 `revealedAt = null`，
于是「揭晓身份后补录任何一条事件」都会把 `matches.revealedAt` 抹成 null，
`canOpenGodView` 永久关闭。修法是把状态回写拆成 `updateStatusOnly`（只动 status/updatedAt），
`revealedAt` 只由 `revealAllRoles` / `setStatus` 显式管理。

### RT-004 · 状态投影只在 append 一处重算

`matches.status` 有四个写入入口，只有 `appendEvent` 做了重算：
`reviseEvent` / `retractEvent` 改完历史后状态不更新（撤销一次任务失败，首页仍显示「已结束」），
`importBundle` 一律落 `DRAFT`（导入一场已结束的对局，首页显示「草稿」）。
根因不是某处写错，而是**同一件事有四个实现**。修法是抽出唯一的
`recomputeProjectionLocked`，四个入口共用，并且强制走 `updateStatusOnly` 以免重蹈 RT-001。

### RT-005 · 可见性边界没有单一出口

「谁能看到哪条事件」原来散在三处（`buildPublicFacts`、`claimLabels`、`analyze`），
三处都只做了时间裁剪。于是一条 `PRIVATE_OBSERVER` 的 `PlayerClaimed`
会变成公开视角下的 `CLAIMED_MERLIN` 标签与 A08 结论——**不会崩，只会安静泄漏**。
修法是引入 `VisibleEventPolicy` 作为唯一出口，三处共用；
`analyze` 内部再过滤一次，因为它是公开 API，传全量事件是最容易犯的错。

---

## 与任务书的偏差（需主理人确认）

1. **RT-004 测试场景**：任务书写「三次成功 + 一次失败 → 撤销那条失败 → status == IN_PROGRESS」。
   但三次成功后状态是 `AWAITING_ASSASSINATION`，撤销一个失败任务不会让它变回 `IN_PROGRESS`。
   我改用**等价且能证明缺陷的场景**：三次任务失败 → `FINISHED_EVIL` → 撤销/修订其中一条 →
   `IN_PROGRESS`。断言仍是「DB 状态 == 重放派生状态」。
2. **RT-001 的 `setStatus`**：核查后确认它**已经**把 `revealedAt` 写库（走 `updateStatus` 四参版本），
   无需改造，只补了断言把它钉住。
3. **RT-005 的「观察者能看到自己的私人事件」**：`KnowledgeSnapshot` 里没有「私有事件列表」字段
   （`subjectiveBeliefs` 只承载笔记），因此断言落在**分析结果**上：
   观察者本人的 `A08` 会引用该私有声称，他人与公开视角不会。若产品要求 UI 直接列出私有事件，
   需要给快照加字段，属于 schema 变更，本轮未做。

---

## 剩余风险

1. **`recomputeProjectionLocked` 会覆盖手工 `setStatus`**。
   若将来有人用 `setStatus(...)` 把对局手工标成 `ABANDONED` 而没有写 `MATCH_ABANDONED` 事件，
   下一次任意事件写入都会被重放结果覆盖回 `IN_PROGRESS`。
   当前所有 `ABANDONED` 都经由事件产生，暂时安全；建议后续把「手工状态」与「派生状态」拆成两列，
   或在前置层禁止无事件的 `setStatus`（需要架构师裁决，属 RT-003 同族）。
2. **`PRIVATE_OBSERVER` 的 `TeamProposed` / `TeamVoteRecorded` 没有归属座位**。
   `ownerSeatOf` 对它们返回 `null`，policy 归为「只有上帝可见」。
   这是安全侧默认，但如果将来真的要做「队长私记提案」，需要给事件加 `ownerSeat` 字段。
3. **（已修）`ReplayViewModel` 的时间线没有按视角过滤**。
   `TimelineBuilder` 本身保持无视角参数（任务书要求，服务「本人记录」），
   但复盘页拿的是同一份 `bundle.events`：公开视角会把私人笔记渲染进时间线。
   修法是在**调用侧**先过 `VisibleEventPolicy`（不改 `TimelineBuilder` 签名），
   并补两例测试：`t58_public_timeline_hides_private_notes` /
   `t58_observer_timeline_shows_own_private_note`。
   **教训**：把过滤放在「唯一出口」之后，仍要检查每个调用方是否都经过了那个出口。
4. **`match_events` 没有 `(matchId, id)` 复合索引**。`markRetracted` 现在是
   `WHERE id = :id AND matchId = :matchId`，主键是 `id`，查询仍走主键索引，性能无影响；
   但语义上「事件 id 全局唯一」这件事现在同时靠主键与应用层保证，未在 schema 层显式声明。
5. **lint 仍有 54 warning**（与基线同类，主要是 `UnusedAttribute` 等）。本轮未新增。

---

# Phase 2 · P2-1 修复 + RT-003 Step 1

基线：`8dd50cd`（245 单测 / 0 失败）。

## 提交一览

| # | 提交 | 内容 |
|---|---|---|
| 1 | `5efc864` | P2-1：观察者保留自己的 CLAIMED_* 标签 |
| 2 | `21a153e` | build(test)：schemas 挂 test/androidTest assets |
| 3 | `759e133` | RT-003 Step 1：加列 + v4 迁移 + 双写 |

均未 push。`21a153e` 是对 `af6866b` 的 amend（原提交注释里 `schemas/*.json` 的 `/*` 触发 Kotlin 嵌套块注释，脚本编译失败；amend 后该 commit 自身可构建）。

## 验证命令与结果

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
BUILD SUCCESSFUL in 2m 15s
```

| 项 | 基线 | 现在 |
|---|---|---|
| JVM 单测 | 245 | **260**（+15） |
| 失败 | 0 | **0** |
| lint | 0 error | **0 error**（BUILD SUCCESSFUL 即 abortOnError 通过） |
| APK | — | `app-debug.apk` 20,494,329 bytes |
| `assembleDebugAndroidTest` | — | BUILD SUCCESSFUL（仪器测试源码可编译） |

### 用例数变化明细

| 来源 | 数量 |
|---|---|
| P2-1 `PerspectiveLeakExitsTest` | +4（245→249） |
| T2 `MigrationBackfillManualTest` | +4 |
| T3 `DatabaseBackupTest` | +1 |
| T4 `EventSlotAssignmentTest` | +6 |
| **合计** | **+15 → 260** |

T1 `MigrationV3ToV4Test` 的 7 例不计入 JVM 数（已下沉 androidTest，见下）。

## 新增测试名

**P2-1** `PerspectiveLeakExitsTest`：
`observerKeepsOwnPrivateClaimLabel`、`observersPrivateClaimDoesNotLeakToOtherSeats`、
`publicFactsStillExcludeThePrivateClaim`、`godViewSeesEveryonesClaims`

**T2** `MigrationBackfillManualTest`：
`migratingLegacyFileBackfillsPlainEvents`、`migratingLegacyFileBackfillsRevisionChain`、
`migratingOrphanChainDoesNotThrow`、`migratingEmptyLegacyFileWorks`

**T3** `DatabaseBackupTest`：`v3BackupFileIsReadableAndMatchesSource`

**T4** `EventSlotAssignmentTest`：
`t4a_appendingTwentyKeepsBothSequencesAligned`、`t4b_revisingPositionFiveWritesNewSeqAndRetractsOld`、
`t4c_revisingAgainIncrementsSeqAndKeepsPosition`、`t4d_appendAfterRevisionUsesMaxPositionPlusOne`、
`t4e_retractedPositionIsNeverReused`、`t4f_slotsAreIsolatedPerMatch`

**T1（androidTest，NOT RUN）**：`t1a`…`t1g` 共 7 例，断言与 migration-plan §15.3 逐条对应。

## T1 状态：**NOT RUN**（不是通过，也不是失败）

- 在 `src/test` 下用 Robolectric 跑 `MigrationTestHelper` 时，7 例全部抛
  `java.io.FileNotFoundException: Cannot find the schema file in the assets folder`。
- 根因：`isIncludeAndroidResources = true` 只合并 **main** 变体的 assets；
  `sourceSets["test"].assets.srcDir("$projectDir/schemas")` 里的 schema 在 Robolectric
  运行时不可见（Room 只认 instrumentation 的 assets）。
- 处置：按 migration-plan **§15.5 应急方案**把 T1 整体下沉到
  `app/src/androidTest/java/com/roundtrace/app/database/MigrationV3ToV4Test.kt`
  （`@RunWith(AndroidJUnit4::class)`，断言一条未删），`assembleDebugAndroidTest` 已验证可编译。
- JVM 侧的回填正确性由 **T2** 承担（不碰 `InstrumentationRegistry`：「手工 v3 夹具 + Room 打开触发迁移」）。
- **未覆盖的缺口**：`@ColumnInfo(defaultValue)` 与 `ALTER TABLE DEFAULT` 的一致性校验只在
  `MigrationTestHelper.runMigrationsAndValidate` 里做，纯 JVM 无法验证。
  本轮用「4.json 里 `defaultValue` 实测为 `0`」+「T2 能成功打开迁移后的库（Room 打开时会做
  `TableInfo` 比对）」双重间接保证；**严格校验仍需一台设备跑 T1**。

## 改动文件

### 生产代码

| 文件 | 改动 |
|---|---|
| `core/knowledge/AvalonKnowledgeProvider.kt` | `buildSeatKnowledge` 新增 `observerVisibleEvents`；本人那一格用它，其余仍用 `publicOnlyEvents` |
| `core/database/dao/MatchDao.kt` | 删除 `updateRevealedAt`（零调用点，是 `revealedAt` 被写 null 的复发入口） |
| `core/database/entity/MatchEventEntity.kt` | 2 列（`@ColumnInfo(defaultValue = "0")`）+ 1 索引 |
| `core/database/RoundTraceDatabase.kt` | version 4 + `MIGRATION_3_4` |
| `core/database/EventCodec.kt` | `encode` 2 个默认参数；`decode` 取 `logicalPosition` |
| `core/database/dao/MatchEventDao.kt` | `appendWithSlot` / `reviseWithSlot`（替换旧方法）+ `EventSlot` / `RevisionSeed` + 2 条查询 |
| `core/data/RoomMatchRepository.kt` | 3 个调用点 |

### 测试代码

| 文件 | 改动 |
|---|---|
| `test/.../knowledge/PerspectiveLeakExitsTest.kt` | 新增 4 例 |
| `test/.../database/LegacyV3Database.kt` | 新增：从 `schemas/3.json` 的 DDL 反向生成 v3 夹具 |
| `test/.../database/MigrationBackfillManualTest.kt` | 新增 4 例 |
| `test/.../database/DatabaseBackupTest.kt` | 新增 1 例 |
| `test/.../data/EventSlotAssignmentTest.kt` | 新增 6 例 |
| `androidTest/.../database/MigrationV3ToV4Test.kt` | 从 `src/test` 移入（7 例，NOT RUN） |
| `test/.../database/MatchDaoRobolectricTest.kt`、`test/.../data/RoomMatchRepositoryRobolectricTest.kt`、`test/.../audit/CrossMatchWriteIsolationTest.kt`、`androidTest/.../database/MatchDaoTest.kt` | **仅签名适配**，期望值一处未改 |

### 生成物

`app/schemas/.../4.json` **已提交**（`git status` 干净）；`1/2/3.json` 保留。

## 根因

### P2-1 · 观察者丢自己的标签

RT-005 把 `claimLabels` 的事件源一刀切成 `publicOnlyEvents`，堵住了「别人的私有声称外溢」，
但也把「本人私有声称」一起滤掉了 → `seatKnowledge[4].labels` 只剩 `[SELF]`。
根因不是过滤方向错，而是**「公开」与「本人可见」被当成了同一件事**。

### RT-003 Step 1 · 两个顺序混为一列

`occurredOrder` 同时承担「时间线位置」与「写入序号」两个语义。
一旦发生修订，两者必然分叉：修订要在时间线原位置生效，但写入序号必须递增。
Step 1 的处置是**新增列 + 开始写对值**，不动读路径，因此行为零变化。

## 与任务书的偏差

1. **T1 下沉 androidTest**（automated fallback：migration-plan §15.5 明确授权）。
2. **`21a153e` 用了 amend**：原提交的注释里 `schemas/*.json` 的 `/*` 触发 Kotlin 嵌套块注释，
   脚本编译失败。为保证「每个 commit 自身可构建」，对该本地未 push 的提交做了 amend，
   并在 commit message 里写明原因。
3. **T3 的语义**：`DatabaseBackupTest` 验证的是「备份产物可只读打开、版本为 3、行数与主库一致」，
   即**可恢复性**。产品侧目前**没有「迁移前自动备份」的钩子**（需改 Application 启动路径，
   超出 Step 1 的 6 文件清单）。这一点必须在验收时明确，不能理解成「App 会自动备份」。

## 剩余风险

1. **T1 未在真机跑过** → `defaultValue` 一致性、迁移后 `TableInfo` 校验严格意义上未验证。
   一旦有设备，必须先跑 `connectedDebugAndroidTest --tests *MigrationV3ToV4Test`。
2. **自动备份未实现**：迁移前不会自动生成 `roundtrace.db.bak.v3`。当前用户若从 v3 升级到 v4，
   没有任何回退点（只能靠重装）。建议单列任务，在 Application 启动处接 `MIGRATION_3_4` 前备份。
3. **`logicalPosition` 目前无人读**：Step 3 之前它是「只写」列。若写入路径有 bug，
   要等到 Step 3 才会暴露。T4 的 6 例是当前唯一的护栏。
4. **`nextLogicalPosition` 含 retracted**：撤销后位置不复用，因此位置会「越用越大」，
   但不会产生空洞（空洞只可能出现在「位置已分配但行被物理删除」，而本项目禁止 DELETE）。
5. **`decode` 的 `occurredOrder` 已改为取 `logicalPosition`**：Step 1 内两者对所有新建数据相等，
   对历史含修订数据则不等 —— 读路径仍按列 `occurredOrder` 排序，故行为不变；
   但**任何依赖「领域 occurredOrder == 列 occurredOrder」的新代码都会踩坑**，Step 3 前需注意。

---

## Phase 2 修正：Step 1 读语义回退（`7fd8758`）

`759e133` 里我把 `EventCodec.decode` 的领域 `occurredOrder` 改成了 `logicalPosition`，
**这提前切了读路径**，违反 Step 1「行为与今天完全一致」的定义。已在 `7fd8758` 回退。

### 为什么必须回退

`ReplayEngine` 是**按领域 `occurredOrder`** 做过滤与排序的：

```kotlin
.filter { upTo == null || it.occurredOrder <= upTo }
.sortedWith(compareBy({ it.occurredOrder }, { it.recordedAt }, { it.eventId.raw }))
```

一旦 `decode` 改指 `logicalPosition`：

1. **`upTo` 语义提前改变** —— 一条「逻辑位置 5 / 写入序号 6」的修订事件会被
   `replay(upTo = 5)` 算进去（今天不会）。这属于 Step 3 才允许定义的行为。
2. **升级即改变历史解读** —— v3 老数据迁移后，任何一次 `recomputeProjectionLocked`
   都可能让对局状态自己变；而 Step 1 承诺升级后行为不变。

### 改法

- `decode` 仍取 `entity.occurredOrder`；`logicalPosition` 继续**照常写入**，但**全库零读取**。
- 新增冻结测试 `step1_decodedOccurredOrderStillEqualsColumnNotLogicalPosition`
  （`EventSlotAssignmentTest`）：断言修订后新事件的领域 `occurredOrder == 列 occurredOrder`
  且 `!= logicalPosition`、`replay(upTo=5)` 不含它而 `upTo=6` 才含、bundle 顺序仍按列（修订排末尾）。
  **Step 3 切读路径时它会变红——那正是切换信号，届时改写期望值并去掉函数名里的 `step1_`。**

### 「全库无任何地方读 logicalPosition」的 grep 证据

```bash
grep -rn "logicalPosition" app/src/main --include=*.kt
```

主代码里的出现**只剩五类**，没有一类是「读事件行来做展示或排序」：

| 位置 | 性质 |
|---|---|
| `MatchEventEntity.kt` 字段声明 + `Index(...)` | 结构定义 |
| `EventCodec.encode` 的 `logicalPosition = logicalPosition` | **写入** |
| `MatchEventDao.nextLogicalPosition`（`SELECT MAX(logicalPosition)+1`） | 写入侧分配 |
| `MatchEventDao.findRevisionSeed`（投影 `logicalPosition, revisionSeq`） | 写入侧种子（用于复用位置 + 递增 revisionSeq） |
| `RoundTraceDatabase.MIGRATION_3_4` 的 ALTER / UPDATE / CREATE INDEX | 迁移 DDL |

唯一的 `entity.logicalPosition` 字面出现在 `EventCodec.kt` 的**注释**里（Step 3 的提示），
不是代码。

### 验证

```bash
./gradlew clean
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
BUILD SUCCESSFUL in 3m 22s
TOTAL tests=261 failures=0
APK 20,460,378 bytes
```

用例数 260 → **261**（+1 冻结测试）。

---

## Phase 2 收尾：B2 迁移前备份 + B3 启动自检

| commit | 内容 |
|---|---|
| `7707149` | **B2**：`backupBeforeMigration` + `build()` 前置调用 + 4 例测试 + `.gitignore` 加 `.kotlin/` |
| `674d95a` | **B3**：`findDuplicateActivePositions` / `warnOnDuplicateActivePositions` + `onOpen` 回调 + 6 例测试 |

### 备份调用的位置（主理人问的行号）

`app/src/main/java/com/roundtrace/app/core/database/RoundTraceDatabase.kt`：

| 行号 | 内容 |
|---|---|
| **294** | `backupBeforeMigration(context, name)` ← **在 builder 之前** |
| 296 | `return Room.databaseBuilder(` |
| 305 | `.addCallback(...)`（B3 的 `onOpen` 自检） |

行号关系即可证明：备份不是挂在 `onOpen` 上的（那会在迁移之后才触发）。

### 测试名

**B2**（`DatabaseBackupTest`，+4）：
`buildBacksUpLegacyDatabaseBeforeMigration`（**只调 `build()` 不 open** 就断言 `.bak.v3` 已存在——以此证明没挂在 onOpen 上）、`backupSkipsWhenNameIsNull`、`backupSkipsMissingDatabaseWithoutCrashing`、`inMemoryDatabaseNeverProducesBackup`

**B3**（`Inv1SelfCheckTest`，+6）：
`healthyDatabaseHasNoDuplicates`、`detectsTwoActiveEventsSharingPosition`、`retractedVersionsSharingPositionAreNotDuplicates`、`duplicatesAreIsolatedPerMatch`、`duplicatesAreCappedAtTwenty`、`selfCheckNeverThrowsAndDoesNotBlockStartup`（并断言自检**不改任何行**）

### 验证

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
BUILD SUCCESSFUL in 2m 15s
TOTAL tests=271 failures=0
lint 0 error；APK 20,561,927 bytes
```

用例数 261 → **271**（+4 B2、+6 B3）。

### 两处实现取舍

1. **备份用「直接读文件」而不是 `RoomDatabase.Callback`**：`onOpen` 在迁移**之后**才触发，
   挂在上面等于备份一个已被改过的库。所以只能放在 `databaseBuilder` 之前，靠
   `SQLiteDatabase.openDatabase(...OPEN_READWRITE)` + `PRAGMA user_version` 判断版本。
2. **备份前先 `PRAGMA wal_checkpoint(TRUNCATE)`**：本库启用 WAL，最新事务可能还在 `-wal` 里。
   不 checkpoint 就复制会得到「缺最近事务」的备份——这种备份比没有更危险，因为它看起来是好的。
   checkpoint 本身包了 `runCatching`：即使因其它连接占用未能完全合并，也继续复制（
   仍是某个一致状态），并在外层 `runCatching` 兜底，**全程不抛、不阻断启动**。

### B3 为什么只记录不修复

自检跑在 `onOpen` 里，抛异常 = 用户彻底打不开 App；「带着脏数据继续跑」至少还能导出、
还能靠 B2 的备份恢复。自动「修」等于改历史（本项目禁止 DELETE），风险远大于收益。
所以只 `Log.w` 前 20 条样本，处置权交给后续版本与备份恢复。

### 顺带修的一处文档不一致

`build(context, name: String? = null)` 的原 KDoc 写「测试可传 null 走内存库」，
但实现里 `null` 是回落到 `DATABASE_NAME`（内存库走的是 `buildInMemory`）。
已把 KDoc 改成「`null` = 不启用迁移前备份，库名仍回落到 DATABASE_NAME」，
避免下一个人照着旧注释误用。

---

# Phase 3 · 阶段 A（RT-006 收口）+ 阶段 B（逻辑顺序切换）

| commit | 内容 |
|---|---|
| `fe6c408` | **A**：RT-006 草稿恢复（含 A-1 判据修正、A-2 两个测试） |
| `a24be16` | **B-1**：读路径切到逻辑位置 |
| `3d8ea46` | **B-2**：一次性投影刷新 |

基线 `9e1b568`（271）→ 现 **290 用例 / 0 失败 / lint 0 error**。

## A：RT-006 收口

`draftTouchedByUser` 标记取代「草稿是否为空」的判据（`RecordViewModel.kt:76/144/212`）：
用户「选中又全部取消」是一次有效操作，不能被旧草稿覆盖。
新增 `restoreDoesNotOverwriteClearedInput`、`draftWithUnknownFieldsStillDecodes`。

## B-1：读路径切换（唯一改变用户可观察行为的一步）

| 文件 | 改动 |
|---|---|
| `EventCodec.decode` | 领域 `occurredOrder` 取 `logicalPosition`（整步唯一开关） |
| `MatchEventDao` | `observeActive`/`listActive`/`listAll` 排序改 `(logicalPosition, revisionSeq)`；`listActiveUpTo` 改比 `logicalPosition` 且**参数改名** |
| `RoomMatchRepository.buildBundle` | 实体层先按 `(position ASC, revisionSeq DESC, recordedAt ASC, id ASC)` 排序再 `distinctBy(position)`；去重必须在 decode 前（领域事件没有 revisionSeq） |
| `MatchBundle` | 五条顺序契约写进 KDoc（含「`size` 不保证等于 `MAX(position)`」） |
| `ReplayEngine`/`TimelineBuilder` | **零代码改动**，只改 `@param upTo` 注释（原描述切换后是假的） |
| `MatchRepository.AppendResult` | `occurredOrder` 裁定为**写入序号**并写死理由 |

测试：冻结用例翻转为 `step3_decodedOccurredOrderFollowsLogicalPosition`；
新增 `RevisionOrderSemanticsTest`（U1–U4）+ `MatchBundleOrderContractTest`（五契约 + 去重确定性）。

## B-2：一次性投影刷新

`SettingsRepository.projectionOrderModelVersion`（DataStore，默认 0）+
`MatchRepository.recomputeAllProjections()` + `AppContainer` 后台触发。

**关键取舍**：新增 `MatchDao.updateStatusKeepTimestamp`（只改 status，不碰 updatedAt）。
若走 `updateStatusOnly`，所有老对局的「最近活动时间」会被刷成现在、首页排序全乱——
一个「数据修复」把列表搞乱，比不修更难解释。`refreshRealignsStatusButLeavesUpdatedAtUntouched`
专门钉这一点。

## 验收

```
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
BUILD SUCCESSFUL
TOTAL tests=290 failures=0    lint 0 error
git status --short app/schemas   → （无输出，纯代码改动）
```

**K01–K10 回归**（§17.6 要求逐条对比）：`com.roundtrace.app.knowledge.*` 共 **48 例 / 0 失败**，
各类用例数与切换前**完全一致**（9/4/4/4/4/10/4/9），**没有任何期望值被修改**——
这是「顺序切换没有破坏视角隔离」最硬的证据。

## 过程中的两个真实缺陷（都是我自己写测试时踩的）

1. `ProjectionRefreshTest` 跨对局复用同一批事件 id → `SQLiteConstraintException`
   （`match_events.id` 是全局主键）。改为按对局加 tag 前缀。
2. 同一用例先前用 `matchEventDao.appendWithSlot` 直插事件，不触发 `recomputeProjectionLocked`，
   `matches.status` 停在 `DRAFT`，导致「三次失败应为 FINISHED_EVIL」的前提不成立。
   改为走真实写入路径 `repository.appendEvent`。

## 剩余风险

1. **T1 `MigrationV3ToV4Test` 仍 NOT RUN**（androidTest，无设备）：`defaultValue` 一致性的严格校验
   只有 `runMigrationsAndValidate` 能做。
2. **`recomputeAllProjections` 目前未做进度/中断保护**：几千局时会在后台跑较久；
   失败不写标记、下次重试，但没有断点续传。
3. **`projectionOrderModelVersion` 写成功但刷新其实部分失败的情形**：当前实现是
   「逐局 runCatching，全部跑完就写标记」，**单局失败会被吞掉**。若某局数据损坏导致重放抛异常，
   标记仍会被写成 1，该局永久停在旧结论。**这是本轮引入的真实缺陷**，
   建议下一轮改为「有任一失败就不写标记」。
