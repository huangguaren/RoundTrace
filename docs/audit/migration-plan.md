# RT-003 修订事件逻辑顺序 —— 迁移方案 RFC

> 编号：RT-003（P0-D）
> 作者：架构师（软件架构组）
> 时间：2026-10-31
> 基线：分支 `main`。审计基线 `a6da7c4`；**实施基线 `ee20e3d`（`fix(data): scope event writes by matchId`，即 RT-002 已合入）**——本文档所有行号引用均基于 `ee20e3d` 复核过
> 性质：**设计文档**，不含业务代码改动。所有 SQL 均可直接执行。
> 前置阅读：`docs/audit/baseline.md`、`docs/ARCHITECTURE.md` §2.3/§4.5/§6/§9.3-12

---

## 0. 结论（TL;DR）

**推荐方案 A 的轻量变体 A1：在 `match_events` 上新增不可变列 `logicalPosition`（逻辑位置）与 `revisionSeq`（版本号），`occurredOrder` 降格为"写入序号"、只写不读。**

- **不需要改领域模型、不需要改 UI、不需要改导出 JSON** —— 这是本方案最关键的性质。修复点集中在「DAO 的 3 条 SQL + `EventCodec.encode` 的两个默认参数 + 仓储写入路径 + 迁移」。
- `upTo` 语义 = **逻辑位置**（`logicalPosition`）。领域层的 `MatchEvent.occurredOrder` 由 `EventCodec.decode` 填成 `logicalPosition`，因此 `ReplayEngine` / `TimelineBuilder` / `GamePosition` / 复盘选点**代码零改动**，但语义从"写入顺序"恢复为 ARCHITECTURE §2.3 原本定义的"游戏内发生顺序"。
- **不建议暂缓**（理由见 §2.3），但**必须**按 §8 的 4 阶段 expand→migrate→switch→contract 顺序落地，任何单阶段都可独立发布与回退。

一句话：**根因不是"修订被排到末尾"这个现象，而是 `occurredOrder` 一个字段同时承担了"逻辑顺序"和"写入顺序"两种语义。修法是拆成两列，而不是在仓储层打补丁。**

推荐等级：**A1 采纳 / B 否决 / B0（纯仓储重排）作为应急止血备选，不单独交付。**

---

## 1. 问题机理复核（先确认事实，再谈方案）

### 1.1 代码事实（逐条已核对行号）

| # | 位置 | 事实 |
|---|---|---|
| F1 | `core/database/dao/MatchEventDao.kt:56-59` | `nextOccurredOrder` = `COALESCE(MAX(occurredOrder),0)+1`，即**分配的是写入序号**，而列注释（`entity/MatchEventEntity.kt:38`）写的是"游戏内顺序，**唯一排序键**" —— 语义与实现不符 |
| F2 | `MatchEventDao.kt:101-113` | `reviseSupersede` 复用 `nextOccurredOrder`，修订事件拿到 `MAX+1` 并 `markRetracted(oldEventId)` |
| F3 | `core/data/RoomMatchRepository.kt:236-246` | 仓储在事务内调 `reviseSupersede`，只把 `supersedesId` 指向目标事件，**不携带任何"原位置"信息** → 位置信息在写入时即丢失，事后无法从新事件自身恢复 |
| F4 | `core/replay/ReplayEngine.kt:29-34` | 重放严格按 `occurredOrder` 升序，`upTo` 过滤也是 `occurredOrder <= upTo` |
| F5 | `core/database/dao/MatchEventDao.kt:29-48` | 三条读查询全部 `ORDER BY occurredOrder ASC`（`observeActive` / `listActive` / `listActiveUpTo`） |
| F6 | `core/replay/TimelineBuilder.kt:42-46` | 时间线同样按 `occurredOrder` 排序与过滤 |
| F7 | `core/database/EventCodec.kt:233-238` | `decode` 把 `entity.occurredOrder` 直接灌进领域事件，领域层因此"信任"了这个错误值 |

### 1.2 故障链（为什么"看起来能跑"）

```
用户修订第 1 轮某事件
  → reviseSupersede 分配 order = MAX+1 = 24（第 5 轮之后）
  → 新事件 retracted=0，旧事件 retracted=1
  → listActive 返回 [2..23, 24]（顺序上"第 1 轮的修订"落在最后）
  → ReplayEngine 按 order 重放：reducer 收到 order=24 的"第 1 轮 QUEST_STARTED"
      → currentQuestNo 已经是 5 或已 ENDED
      → 命中 QUEST_OUT_OF_ORDER / PROPOSAL_AFTER_RESOLVED / EVENT_AFTER_MATCH_FINISHED
      → 事件被降级或整条无效（内容丢失）
  → TimelineBuilder 把这条修订渲染在第 5 轮之后（时间线错位）
  → replay(upTo = 第1轮末尾) 完全看不到这条修订（位置视图漏内容）
```

### 1.3 影响面清单（修复必须覆盖到的消费方）

| 消费方 | 受影响方式 | 是否需要在 A1 中改代码 |
|---|---|---|
| `ReplayEngine.replay()` | 顺序错 → 结论错 | **否**（靠 `decode` 填对 `occurredOrder` 即自动修正） |
| `ReplayEngine.replay(upTo)` | 部分重放语义错位 | **否**（同上）；但注释必须改写（§7） |
| `TimelineBuilder.build(events, upTo)` | 时间线错位 | **否**（同上） |
| `RoomMatchRepository.getBundle()` | bundle 内事件顺序错 | **否**（`ReplayEngine` 会重排；但建议同步改 DAO 的 `ORDER BY`，见 §6） |
| `MatchSummaryRow.questsStarted`（`MatchDao.kt:67-76`） | `COUNT(*) WHERE eventType='QUEST_STARTED' AND retracted=0` | **否**（修订后旧版 retracted=1，计数仍为 1，正确） |
| `MarkdownExporter` | 依赖 bundle 顺序 | **否** |
| `MatchJsonCodec` 导出 | `ExportEvent.occurredOrder` 语义 | **否**（导出写逻辑顺序，见 §7.3） |
| `TimelineEntry` / `ReplayUiState.at` / 复盘选中位置 | 位置漂移导致选中跳走 | **否**，且修复后**变好**（位置稳定） |

> 结论：**领域层与表现层零改动**。这既是 A1 的最大优点，也是它风险可控的根本原因。

---

## 2. 方案对比

### 2.1 方案 A1（推荐）：同表加 `logicalPosition` + `revisionSeq`，`occurredOrder` 降格为写入序号

**列语义（新的唯一权威定义，必须写进 `MatchEventEntity` 的 KDoc）**

| 列 | 含义 | 谁分配 | 可变性 | 读路径是否使用 |
|---|---|---|---|---|
| `logicalPosition` | **事件在时间线上的逻辑位置**（同一事件的所有版本共享此值） | DAO（追加 = `MAX(logicalPosition)+1`；修订 = **继承目标事件**） | **不可变** | ✅ 唯一排序键 |
| `revisionSeq` | 该位置的第几版，从 1 开始 | DAO（追加 = 1；修订 = 目标 `+1`） | 不可变 | ✅ 仅作 tie-break 与审计 |
| `occurredOrder` | **历史写入序号**（第几条被插入） | DAO（`MAX(occurredOrder)+1`，即现有实现不动） | 不可变 | ❌ **只写不读**（除迁移与审计） |

**写入规则（两条，无例外）**

```
append(event):   occurredOrder = MAX(occurredOrder)+1
                 logicalPosition = MAX(logicalPosition)+1     -- 追加 = 尾部新位置
                 revisionSeq = 1

revise(target, newEvent):
                 occurredOrder = MAX(occurredOrder)+1          -- 写入序号继续递增
                 logicalPosition = target.logicalPosition      -- ★ 继承原位置
                 revisionSeq = target.revisionSeq + 1          -- ★ 版本递增
                 newEvent.supersedesId = target.id             -- 现有行为不变
                 target.retracted = 1                          -- 现有行为不变
```

**关键不变量（4 条，全部可 SQL 校验）**

- **INV-1**：`SELECT COUNT(*) FROM match_events WHERE matchId=? AND retracted=0 GROUP BY logicalPosition HAVING COUNT(*)>1` 恒为 0（每个逻辑位置至多一条有效版本）。**这是 `retracted` 过滤天然保证的**——因此读路径不需要任何窗口函数或"取最大版本"的收敛逻辑。
- **INV-2**：有效版本（`retracted=0`）的 `logicalPosition` 集合在去掉修订后**连续无空洞**，且 `MAX(logicalPosition) <= COUNT(*)`。
- **INV-3**：`revisionSeq >= 1`；`supersedesId IS NOT NULL` ⇒ `revisionSeq >= 2`。
- **INV-4**：领域层拿到的 `MatchEvent.occurredOrder` 恒等于该行的 `logicalPosition`（由 `EventCodec` 保证）。

**优点**

1. 单表、无 JOIN；`match_events` 一局只有几十行，规范化收益为零。
2. 迁移是"加列 + 回填 + 加索引"，**不重建表、不动外键、不动 payload/visibility**。
3. 现有 `retracted` 语义与所有读查询的 `WHERE retracted=0` 完全复用，读路径只需**替换排序键**。
4. 领域模型 `MatchEvent` 的 8 个公共字段**一个都不用加**（`occurredOrder` 语义回归即达成目标）。
5. 完全可回退：旧索引 `(matchId, occurredOrder)` 保留，回滚只需把读路径的 `ORDER BY` 改回去。

**缺点 / 代价**

1. 表内有两个"顺序"列，**必须靠文档 + 代码规范约束"只写不读"**，否则后来者会误用（缓解：`@Deprecated` 级别的 KDoc 警示 + 启动自检断言 INV-4）。
2. 同一逻辑位置保留多条历史版本，行数 = 事件数 + 修订数（实际增量极小）。
3. **无法在 v4 加唯一约束** `UNIQUE(matchId, logicalPosition, revisionSeq)`：若历史存在脏数据会让迁移直接失败 = 用户打不开 App。故 v4 用**非唯一索引**，唯一性靠 INV-1 与自检（见 §5.4、§12-R2）。

### 2.2 方案 B（否决）：另建 `event_revisions` 表

`match_events` 变"事件头"（`id` + `logicalPosition`），新表 `event_revisions` 存每版的 `payloadJson/recordedAt/visibility/supersedesId/retracted`。

**否决理由（3 条，均为否决级）**

1. **迁移不可逆且高风险**：需要把 6 列数据搬到新表、重建外键、并让 `supersedesId` 语义从"指向事件"变成"指向版本"。任何一步出错都要重建数据库。而 A1 的迁移是纯加法。
2. **读路径全面改写**：`observeActive` 这类 `Flow` 查询要么改成 `@Relation` 两次查询，要么写裸 SQL JOIN；`EventCodec`、`Mapping`、`getBundle` 全部要适配"头 + 版本"两级结构。改动面是 A1 的 5–8 倍。
3. **收益为零**：`match_events` 单局 < 100 行、全库 < 1 MB。规范化带来的存储收益在这个量级上不存在，只带来复杂度。

> 如果未来出现"同一事件版本需要独立生命周期（例如分叉修订）"，那时再评估 B；当前不需要。

### 2.3 方案 B0（应急止血备选，不推荐作为最终方案）：仅仓储层重排，不动 Schema

**做法**：`getBundle` 读到 active events 后，按 `supersedesId` 回溯链根，把领域事件的 `occurredOrder` 改写成链根的 `occurredOrder`，在内存里排序。

**可行性是真实的**（我不会为了推迁移而否定它）：

- INV-1 同样成立（旧版本都 `retracted=1`），所以内存重排能收敛；
- 现有代码**没有任何**"用领域 `occurredOrder` 反查 DB 行"的路径（都是按 `id`），所以领域值与列值不一致不会崩。

**但仍不推荐作为最终方案，理由（都是硬伤）**

1. **必须读 retracted 行才能拿到链根**：`listActive` 不返回被修订的旧事件，链根已在其中 → 必须额外 `listAll`，等于每次读对局都要扫全表（含历史版本）。规模小，可接受，但这是明确的性能/语义债。
2. **`listActiveUpTo(order)` 永久失效**：SQL 层无法按逻辑位置裁剪，只能全量读再内存过滤。当前无调用点，但这是"埋着的坑"。
3. **不修复根因**：任何未来直连 DB 的查询（统计、分页、导出 SQL、DBA 手工排查）都会再踩一次；误导后来者的根源（列注释说谎）依然存在。
4. **`MAX(occurredOrder)+1` 的序号空间会被修订无限拉稀**，长期看 `occurredOrder` 会失去"第几条录入"的意义。

**结论：B0 只作为 §8 Phase-0 的"照妖镜"使用** —— 先用它写一组**与实现无关的语义单测**（相同输入 → 相同排序结果），再让 A1 去满足同一组测试。这能把"迁移是否正确"变成可判定问题。**不单独作为交付物。**

---

## 3. 数据模型变更（方案 A1 的全部 Schema 改动）

### 3.1 `MatchEventEntity` 新增两列

```kotlin
/**
 * `match_events` 表（ARCHITECTURE §4.5 / TASKS.md T29 / RT-003）。
 *
 * 顺序语义（RT-003 起，唯一权威）：
 * - [logicalPosition]：事件在时间线上的**逻辑位置**，同一事件的所有修订版本共享此值。**不可变**。
 * - [revisionSeq]：该位置的版本号，从 1 开始。**不可变**。
 * - [occurredOrder]：历史**写入序号**（第几条被插入）。**只写不读**，仅供迁移与审计。
 *   领域层的 `MatchEvent.occurredOrder` 由 `EventCodec.decode` 填成 [logicalPosition]。
 */
@Entity(
    tableName = "match_events",
    foreignKeys = [ /* 不变 */ ],
    indices = [
        // 新增：读路径唯一排序键（非唯一索引，理由见 RFC §5.4）
        Index(value = ["matchId", "logicalPosition", "revisionSeq"]),
        // 保留：写入序号分配（MAX(occurredOrder)）与迁移/审计仍会用到
        Index(value = ["matchId", "occurredOrder"]),
        Index(value = ["matchId", "eventType"]),
        Index(value = ["supersedesId"])
    ]
)
data class MatchEventEntity(
    @PrimaryKey val id: String,
    val matchId: String,
    /** 写入序号，只写不读 */
    val occurredOrder: Int,
    /** 逻辑位置，不可变，读路径唯一排序键 */
    @ColumnInfo(defaultValue = "0") val logicalPosition: Int = 0,
    /** 版本号，从 1 开始 */
    @ColumnInfo(defaultValue = "1") val revisionSeq: Int = 1,
    /* 其余列不变 */
)
```

> **必读**：`@ColumnInfo(defaultValue = ...)` 不可省略。SQLite 给非空表 `ADD COLUMN ... NOT NULL` 时**强制要求 DEFAULT**，而 Room 在迁移后会比对 `TableInfo.defaultValue`；两侧不一致会抛 `IllegalStateException: Migration didn't properly handle match_events`。这是本方案最容易翻车的一处。

### 3.2 索引命名（Room 生成规则，必须逐字一致）

```
index_match_events_matchId_logicalPosition_revisionSeq
```

现有三索引名（来自 `app/schemas/.../3.json`，迁移中不要误删）：

```
index_match_events_matchId_occurredOrder     ← 保留
index_match_events_matchId_eventType         ← 保留
index_match_events_supersedesId              ← 保留
```

### 3.3 数据库版本

`RoundTraceDatabase`：`version = 3` → **`4`**，`addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`。
`exportSchema = true` 不变 → 构建后自动产出 `app/schemas/.../4.json`，**必须提交仓库**。

---

## 4. Room v3 → v4 迁移 SQL（可直接执行）

```kotlin
/** v3→v4（RT-003）：拆分「逻辑顺序」与「写入顺序」。 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // —— ① 加列（非空 + DEFAULT 双写，见 RFC §3.1 的必读提示） ——
        db.execSQL("ALTER TABLE `match_events` ADD COLUMN `logicalPosition` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `match_events` ADD COLUMN `revisionSeq` INTEGER NOT NULL DEFAULT 1")

        // —— ② 回填 logicalPosition：沿 supersedesId 链取链根的 occurredOrder ——
        db.execSQL(
            """
            WITH RECURSIVE chain(id, rootOrder) AS (
                SELECT id, occurredOrder FROM `match_events` WHERE supersedesId IS NULL
                UNION ALL
                SELECT child.id, chain.rootOrder
                  FROM `match_events` AS child
                  JOIN chain ON child.supersedesId = chain.id
            )
            UPDATE `match_events`
               SET logicalPosition = COALESCE(
                     (SELECT rootOrder FROM chain WHERE chain.id = `match_events`.id),
                     occurredOrder)
            """.trimIndent()
        )

        // —— ③ 回填 revisionSeq：同一链上的深度，根 = 1 ——
        db.execSQL(
            """
            WITH RECURSIVE chain(id, seq) AS (
                SELECT id, 1 FROM `match_events` WHERE supersedesId IS NULL
                UNION ALL
                SELECT child.id, chain.seq + 1
                  FROM `match_events` AS child
                  JOIN chain ON child.supersedesId = chain.id
            )
            UPDATE `match_events`
               SET revisionSeq = COALESCE(
                     (SELECT seq FROM chain WHERE chain.id = `match_events`.id), 1)
            """.trimIndent()
        )

        // —— ④ 建立读路径索引（IF NOT EXISTS 保证可重复执行） ——
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_match_events_matchId_logicalPosition_revisionSeq` " +
                "ON `match_events` (`matchId`, `logicalPosition`, `revisionSeq`)"
        )
    }
}
```

### 4.1 为什么这段 SQL 满足"幂等 / 可回滚 / 不丢数据"

| 要求 | 满足方式 |
|---|---|
| **幂等** | ②③ 是**确定性重算**（同样的 `supersedesId` 图必然算出同样的值），重复执行结果不变；① 由 Room 的版本号保证只跑一次；④ 用了 `IF NOT EXISTS`。**注意**：`ALTER TABLE ADD COLUMN` 本身不可重复执行，若手工重跑会报 `duplicate column name`——这是预期行为（不是缺陷），见 §9.2 的重跑流程。 |
| **可回滚** | 迁移**只做加法**：不删列、不改列、不重建表、不动外键、不动 `retracted`。回滚 = 恢复备份文件 + 回滚 APK（§9.1）。**不要**试图用 `DROP COLUMN` 回退：SQLite 只在 3.35+（Android 12+）支持，minSdk 26 的设备会失败。 |
| **不丢数据** | 全程无 `DELETE` / `UPDATE` 覆盖业务列（②③ 只写新列）。`COUNT(*)` 前后必须相等（§10 验证点 V5）。 |
| **孤儿链安全** | 若某行 `supersedesId` 指向不存在的行（历史脏数据 / 早期导入残留），递归 CTE 到不了根 → `COALESCE` 回退为自身 `occurredOrder`，`revisionSeq` 回退为 1。**不报错、不中断迁移**。 |
| **空库安全** | 0 行时两条 `UPDATE` 是 no-op（§10 验证点 V7）。 |
| **SQLite 版本** | 递归 CTE 自 SQLite 3.8.3 起支持；`WITH ... UPDATE` 同样支持。minSdk 26（Android 8.0）自带 SQLite 3.18+，安全。详见 §12-R1 的降级预案。 |

### 4.2 迁移前后自检 SQL（给出但不写在迁移里）

迁移**不做**数据修正以外的任何判断（避免"迁移失败 = App 打不开"）。下面三条用于测试与启动自检：

```sql
-- INV-1：每个逻辑位置至多一条有效版本（结果必须为空）
SELECT matchId, logicalPosition, COUNT(*) AS n
  FROM match_events WHERE retracted = 0
 GROUP BY matchId, logicalPosition HAVING n > 1;

-- INV-2：逻辑位置无空洞（max = 有效事件数）
SELECT matchId, MAX(logicalPosition) AS maxPos,
       SUM(CASE WHEN retracted = 0 THEN 1 ELSE 0 END) AS activeCnt
  FROM match_events GROUP BY matchId;

-- INV-3：修订行必须 revisionSeq >= 2
SELECT id FROM match_events WHERE supersedesId IS NOT NULL AND revisionSeq < 2;
```

---

## 5. 读路径改造范围（**全部改动点清单**）

> 原则：**只改"排序/过滤键"，不改任何业务判断。** 共 6 处代码 + 1 处签名默认值。

### 5.1 `EventCodec.encode`：加两个带默认值的参数（关键的单点改动）

```kotlin
fun encode(
    event: MatchEvent,
    matchId: MatchId,
    occurredOrder: Int,                 // 写入序号（保持现有实参不变）
    logicalPosition: Int = occurredOrder, // ★ 追加场景默认等于写入序号
    revisionSeq: Int = 1
): MatchEventEntity
```

- **追加路径 / 导入路径**（`appendEvent`、`importBundle`）**一行都不用改**：默认参数让 `logicalPosition == occurredOrder`，正是追加语义。
- **修订路径**显式传值。
- `decode`：`occurredOrder = entity.logicalPosition`（原为 `entity.occurredOrder`，`EventCodec.kt:238` 及 10 处 `base.occurredOrder` 全部随之受益，因为都从 `base` 取）。

### 5.2 `MatchEventDao`：3 条查询 + 2 条分配 + 1 个签名

| 方法 | 现状 | 改为 |
|---|---|---|
| `observeActive`（`:29-33`） | `ORDER BY occurredOrder ASC` | `ORDER BY logicalPosition ASC, revisionSeq ASC` |
| `listActive`（`:35-39`） | 同上 | 同上 |
| `listAll`（`:41-42`） | 同上 | 同上（审计视图要能按版本序列读） |
| `listActiveUpTo`（`:44-48`） | `WHERE occurredOrder <= :order` | `WHERE logicalPosition <= :logicalPosition`（**参数改名**，避免调用方误传写入序号） |
| `maxOccurredOrder` / `nextOccurredOrder` | — | **保留不动**（写序号分配） |
| **新增** `nextLogicalPosition` | — | `SELECT COALESCE(MAX(logicalPosition),0)+1 FROM match_events WHERE matchId = :matchId` |
| **新增** `findRevisionSeed(matchId, id)` | — | 返回被修订行的 `(logicalPosition, revisionSeq)`，带 `matchId` 条件，供 `reviseWithSlot` 在**同一事务内**读取 |

### 5.3 两个复合写方法：`entityProvider` 改为接收完整"槽位"

```kotlin
/** 事务内分配好的写入槽位。 */
data class EventSlot(
    val occurredOrder: Int,
    val logicalPosition: Int,
    val revisionSeq: Int
)

// 追加
@Transaction
open suspend fun appendWithSlot(
    matchId: String,
    entityProvider: (EventSlot) -> MatchEventEntity,
    now: Long
): EventSlot

// 修订（目标不存在/不属本对局时抛 check 失败并回滚，与 ee20e3d 的现有行为一致）
@Transaction
open suspend fun reviseWithSlot(
    matchId: String,
    oldEventId: String,
    entityProvider: (EventSlot) -> MatchEventEntity,
    now: Long
): EventSlot
```

`reviseWithSlot` 内部顺序（**必须在事务内、必须原子**）：

```
1. seed = findRevisionSeed(matchId, oldEventId)      -- 带 matchId 条件
   check(seed != null) { "修订目标不属于本对局或已失效" }
2. slot = EventSlot(
       occurredOrder   = nextOccurredOrder(matchId),
       logicalPosition = seed.logicalPosition,        -- ★ 继承
       revisionSeq     = seed.revisionSeq + 1         -- ★ 递增
   )
3. insert(entityProvider(slot))
4. affected = markRetracted(matchId, oldEventId)      -- RT-002 收口，保留
   check(affected == 1)
5. touchMatch(matchId, now)
6. return slot
```

> 保留 `appendWithOrder` / `reviseSupersede` 的旧签名作为 `@Deprecated` 转发（若工程想减少改动面），但**必须**让它们内部走新实现，否则会写出 `logicalPosition=0` 的脏行。

### 5.4 `RoomMatchRepository`：2 个调用点

| 位置 | 改动 |
|---|---|
| `appendEvent`（`:157-200` 附近） | `appendWithOrder` → `appendWithSlot`；`codec.encode(event, matchId, slot.occurredOrder, slot.logicalPosition, slot.revisionSeq)` |
| `reviseEvent`（`:236-246`） | `reviseSupersede` → `reviseWithSlot`；目标校验统一走 `findRevisionSeed(matchId, …)` + `check`（**保留 ee20e3d 的失败语义**，不再额外引入 `INVALID_SUPERSEDE_TARGET` 的第二条路径） |
| `importBundle`（`:408-417`） | **零改动**（`newOrder = index+1` 经默认参数自动得到 `logicalPosition = index+1, revisionSeq = 1`，正是"导入即扁平化修订"的正确语义） |
| `getBundle` / `buildBundle` | **零改动**（顺序由 DAO 的 `ORDER BY` 保证） |

### 5.5 `ReplayEngine` / `TimelineBuilder`：**零代码改动，但注释必须改**

`ReplayEngine.kt:26` 的 `@param upTo 只重放 occurredOrder <= upTo 的事件` 与 `TimelineBuilder.kt:40` 的同类注释，必须改写为：

> `upTo`：逻辑位置上界（`logicalPosition`）。领域事件的 `occurredOrder` 已完成该映射（见 `EventCodec`），因此此处仍以 `occurredOrder` 命名。

**不改代码的理由**：领域层 `occurredOrder` 已等于 `logicalPosition`，排序与过滤自动正确。改代码反而会引入两套字段。

### 5.6 明确**不需要**改的地方（防止过度改造）

`GamePosition`、`ReplayUiState.at`、`TimelineEntry.occurredOrder`、`ExportModels.ExportEvent.occurredOrder`、`MarkdownExporter`、`KnowledgeProvider`、`AvalonEvidenceAnalyzer`、`MatchSummaryRow` —— 全部保持原样。

### 5.7 `retractEvent` 的语义缺口（**发现，但不属于本次迁移**）

现状：`retractEvent(id)` 只置 `retracted=1`。

在 A1 之下会产生一个新问题：**"撤销一次修订"会连带丢掉整个逻辑位置**——
`rev1(retracted) ← rev2(retracted) ← rev3(active)`，撤销 rev3 后该位置**没有任何 active 版本**，事件从时间线上消失，而用户预期是"退回 rev2"。

- **本次不做**：改它会触及 `EventRevisionPolicy` 与撤销 UI，属独立条目。
- **建议另立 RT-006**：`retractEvent` 命中"修订版本"时，除置 `retracted=1` 外，还要把**父版本**（`supersedesId` 指向者）恢复为 `retracted=0`，并写一条 `REVISION_UNDONE` 审计（或复用 `supersedesId` 链表达）。在 RT-006 落地前，UI 侧必须明确提示"撤销修订将同时移除该事件"。

---

## 6. `upTo` 语义与"两种历史视图"的定义（回答问题 3 的核心）

### 6.1 正式定义

| 视图 | 定义 | 排序键 | 是否含被取代的旧版本 | P0 是否实现 |
|---|---|---|---|---|
| **CORRECTED（纠正后的历史）** | 每个逻辑位置只取**当前有效版本**（`retracted=0`），按 `logicalPosition` 升序 | `(logicalPosition, revisionSeq)` | 否 | ✅ **唯一实现的视图**，即现状 + 本次修复 |
| **AS_RECORDED（当时实际录入）** | 按 `occurredOrder`（写入序号）排列，**包含**已被 `retracted` 的旧版本 | `(occurredOrder)` | 是 | ❌ 不实现（无消费方） |

### 6.2 `upTo` 的语义

**`upTo` = 逻辑位置上界**，含义是"把时间停在用户看到的第 N 个事件之后"。定义为 `logicalPosition <= upTo`。

这带来一个必须写清楚的语义收益：**修订不再改变历史长度**。修复前，修订一次会让"第 1 轮末尾"的 `upTo` 值失效（因为尾部多了一条）；修复后 `upTo=7` 永远是"第 7 个逻辑位置"，复盘选中位置不会漂移。

### 6.3 为什么不实现 AS_RECORDED

1. **没有消费方**：`retractEvent` 目前无"撤销栈 UI"，没有页面需要"我什么时候改了什么"。
2. **数据已被保留**：旧版本行只被 `retracted` 标记，**从不物理删除**，所以将来随时可以用同一个 `listAll` + `ORDER BY occurredOrder` 实现，不需要再迁移。
3. 加 `view` 参数会传导到 `ReplayEngine.replay` 的所有调用点（≥8 处）与全部测试夹具，收益不成立。

**若主理人要求现在就要审计视图**：改动量约 1 人日，做法是 `TimelineBuilder.buildAsRecorded(events = listAll(matchId))`，仅在"对局详情/编辑历史"页展示，**不得**进入 reducer 与复盘。

---

## 7. 导出 / 导入 JSON v1 兼容

### 7.1 结论：**导出结构零改动**

`ExportEvent.occurredOrder`（`core/export/ExportModels.kt:103`）的既有语义是"事件顺序"。修复后 `bundle.events[i].occurredOrder == logicalPosition`，所以：

- 导出侧**不改一行代码**，字段值与语义自动变成"逻辑顺序"（这正是 v1 契约里"事件顺序"的本意）；
- **不新增** `revisionSeq` 字段。理由：v1 导出只含**有效版本**（`listActive`），修订链已被扁平化，`revisionSeq` 恒为 1，写出来是噪声；且基线约束"不改 JSON v1 契约"应从严解释为"字段集合不变"。
- 若将来确实需要在导出里表达修订链，那应当是 `schemaVersion = 2` 的独立提案，**不在 RT-003 范围**。

### 7.2 导入旧文件（v1，无新字段）

- `MatchJsonCodec.DEFAULT_JSON` 已有 `ignoreUnknownKeys = true`（`MatchJsonCodec.kt:632`）→ 新版本读旧文件无影响。
- `importBundle` 重排为 `newOrder = index + 1`（`RoomMatchRepository.kt:412`），经 `encode` 默认参数得到 `logicalPosition = newOrder, revisionSeq = 1` → **自动满足 INV-1/INV-3**。
- `supersedesId` 若指向文件内不存在的事件，现有代码已降级为 `null`（`:415`）→ 不产生孤儿链。
- **无需任何导入侧回填代码。**

### 7.3 反向兼容（新导出 → 旧版本 App 读）

旧版本 App 不认识新列（列在 DB，不在 JSON），JSON 也未变 → 旧 App 读新导出文件**行为与今天完全一致**（仍会把修订排到末尾，因为它用 `occurredOrder` 字段，而该字段现在承载逻辑顺序 → 实际上**旧 App 读新文件反而变正确了**）。

---

## 8. 分阶段落地顺序（回答问题 5）

> 每阶段独立可发布、独立可回退。**Phase 2 与 Phase 3 之间必须有一次完整回归**。

### Phase 0（0.5 天）：用 B0 的语义先钉住测试（不改生产代码）

- 在 `app/src/test/.../replay/RevisionOrderSemanticsTest.kt` 写 3 条**与实现无关**的语义断言：
  1. 8 人局跑完 5 个任务 → 修订第 1 轮某事件的队伍 → `replay(...)` 得到的 `quests[0]` 队伍必须等于修订后的值，且 `issues` 中**不出现** `QUEST_OUT_OF_ORDER` / `PROPOSAL_AFTER_RESOLVED`；
  2. `replay(upTo = 第1轮最后一个逻辑位置)` 必须**包含**该修订的影响；
  3. `TimelineBuilder.build(events, upTo = null)` 中，该修订条目的位置索引必须与修订前一致（位置稳定）。
- 这 3 条**现在必须是红的**（红是验收前提，证明测试有效）。
- **回退**：无生产代码变更，`git checkout -- app/src/main`。

### Phase 1（0.5 天）：Schema 扩展 + 迁移（**读路径仍用旧列**）

- Entity 加 2 列（§3.1）、Database v4 + `MIGRATION_3_4`（§4）、`app/schemas/4.json` 提交。
- **此阶段业务行为完全不变**（读路径仍 `ORDER BY occurredOrder`），Phase 0 的 3 条测试**仍然红**——这是预期的。
- 验证命令：

```bash
export JAVA_HOME="/c/Users/HK-info/android-build/jdk"
export ANDROID_HOME="/c/Users/HK-info/android-build/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :app:testDebugUnitTest --tests "com.roundtrace.app.database.MigrationV3ToV4Test"
./gradlew :app:assembleDebug   # 确认 schemas/4.json 已生成并 git add
```

- **回退**：`git revert` 该提交；已安装的设备需卸载或恢复备份（因为 DB 已升到 v4 而旧 APK 期望 v3 → 旧 APK 会因 `user_version` 更高而**不会**自动降级，Room 会静默继续用 v4 库但缺少 schema 校验 → 实际上旧 APK 读 v4 库会因 `identityHash` 不匹配抛异常）。**因此 Phase 1 不要单独发版**，与 Phase 2 一起发。

### Phase 2（1 天）：写入双写正确化（**读路径仍用旧列**）

- §5.1 `EventCodec.encode` 默认参数、§5.3 两个 `…WithSlot` 方法、§5.4 两个调用点。
- 此阶段：新写入的行 `logicalPosition/revisionSeq` 正确；读仍按 `occurredOrder` → Phase 0 的 3 条测试**仍红**。
- 新增单测：`EventSlotAssignmentTest`（追加 → `logicalPosition == occurredOrder`；二次修订 → `revisionSeq=3` 且 `logicalPosition` 不变）。
- **回退**：把两个调用点切回 `appendWithOrder/reviseSupersede`（旧方法保留即可）。

### Phase 3（0.5 天）：切换读路径（**原子提交，可与 Phase 2 合并发版**）

- §5.2 六条 DAO 查询的 `ORDER BY` / `WHERE` / 新增 `nextLogicalPosition`。
- Phase 0 的 3 条测试**必须转绿**。
- 全量回归：

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

- **回退**：单文件改回 `ORDER BY occurredOrder`（`MatchEventDao.kt` 一个文件），无需动 DB。

### Phase 4（0.5 天）：收尾

- 删除/`@Deprecated` 旧 `appendWithOrder`/`reviseSupersede`（若已无调用方）。
- 启动自检（`AppContainer` 初始化时，**仅在 debug**）：跑 §4.2 的 INV-1/INV-3，命中则写日志（不上报、不弹窗）。
- 更新 `docs/ARCHITECTURE.md` §2.3 / §4.5 / §9.3-12 与新语义对齐；`docs/decisions.md` 追加一条 RT-003。
- 更新 `MatchEventEntity` 与 `TimelineBuilder`/`ReplayEngine` 的 KDoc（§5.5）。

**总工期估计**：3 天（含测试），其中只有 Phase 1+2+3 需要一起发版。

---

## 9. 迁移失败恢复（回答问题 6）

### 9.1 备份策略：迁移**前**做文件级备份（推荐，必须做）

在 `RoundTraceDatabase.build()` 中、`Room.databaseBuilder(...).build()` **之前**插入：

```
1. dbFile = context.getDatabasePath(name)
2. if (!dbFile.exists()) → 跳过（首次安装）
3. 读 user_version：
   a. 打开只读连接（SQLiteDatabase.openDatabase(path, null, OPEN_READONLY)）
   b. 若 user_version >= 4 → 跳过（已是新库）
   c. 执行 PRAGMA wal_checkpoint(TRUNCATE)  ← WAL 模式下必须，否则备份缺 -wal 里的数据
   d. 关闭只读连接
4. 复制到 <dbFile>.bak.v<user_version>（例：roundtrace.db.bak.v3）
5. 保留最近 1 份，超过则覆盖（避免磁盘膨胀）
```

要点：
- **必须在 Room 打开之前**做，`RoomDatabase.Callback.onOpen` 太晚（迁移已执行）。
- WAL 模式（`RoundTraceDatabase.kt:93` 已启用）→ 不 checkpoint 就会备份出"缺最近事务"的库，这是最常见的备份翻车点。
- 备份失败**不得**阻止 App 启动（`runCatching` 包住，失败只记日志）。

### 9.2 迁移失败时的行为（明确设计，不要"最大化保留"）

Room 迁移抛异常 → `IllegalStateException`。**不要** `fallbackToDestructiveMigration()`（基线硬约束）。

产品行为建议（需主理人确认）：

- **方案 R-1（推荐）**：捕获打开失败 → 展示一个独立"数据需要修复"页，提供两个明确选项：
  1. 「重试」（什么都不做，主要用于升级 App 后重开）；
  2. 「从备份恢复」（把 `roundtrace.db.bak.v3` 覆盖回主库，`user_version` 回到 3，再尝试用旧版本逻辑打开 → 实际上仍需新 APK 的迁移，故此项只在迁移可重入时有效）；
  3. 「导出原始文件」（把主库 + 备份复制到用户可见目录，让用户/我们手工救）。
- 禁止静默新建空库（那等于"悄悄删光用户数据"，违反 TDD §8）。

> 迁移 SQL 本身的设计目标是"**只能在一种情况下失败：磁盘/权限问题**"。因为 ②③ 是纯计算、无约束校验、无 `DELETE`，且索引是 `IF NOT EXISTS`。真正会失败的只有 ① `ADD COLUMN`（列已存在 → 说明 `user_version` 被外力改过）。

### 9.3 如何验证备份可读

```
1. SQLiteDatabase.openDatabase(bakPath, null, OPEN_READONLY) 不抛异常
2. PRAGMA user_version 必须 == 3
3. SELECT COUNT(*) FROM match_events 与 SELECT COUNT(*) FROM matches 与迁移后一致
4. SELECT COUNT(*) FROM match_events WHERE logicalPosition = 0 → 迁移后的主库必须为 0（备份库无此列，跳过）
```

第 1–3 步写成 `DatabaseBackupTest`（Robolectric 可跑：`ApplicationProvider` + 文件复制，无需设备）。

---

## 10. 测试与验收矩阵

### 10.1 迁移测试（`app/src/test/java/com/roundtrace/app/database/MigrationV3ToV4Test.kt`，Robolectric）

> **不要**直接用 `MigrationTestHelper`：它依赖 `InstrumentationRegistry`，在无设备的 JVM 环境下不稳。
> 用「手工造 v3 库 + 让 Room 打开并自动校验」的方式，等价且可离线跑。

**造 v3 夹具的 4 步（缺一不可）**

```
1. 用 SQLiteDatabase.openOrCreateDatabase(tmpFile) 建库
2. 从 app/schemas/.../3.json 抄 match_events 的 createSql，执行建表 + 3 条索引
3. 建 room_master_table 并写入 v3 的 identityHash（否则 Room 打开时报
   "Room cannot verify the data integrity"）：
      CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)
      INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '012b543edd3715ab981a6f8b5c812a1e')
   ← 该 hash 取自 app/schemas/.../3.json 的 database.identityHash
4. PRAGMA user_version = 3，关闭
```

**夹具数据**（覆盖回填的全部路径）

| 行 | occurredOrder | supersedesId | retracted | 期望 logicalPosition | 期望 revisionSeq |
|---|---|---|---|---|---|
| E1（根） | 1 | null | 1 | 1 | 1 |
| E2（E1 的一级修订） | 6 | E1 | 1 | **1** | **2** |
| E3（E1 的二级修订） | 7 | E2 | **0** | **1** | **3** |
| E4（独立事件） | 2 | null | 0 | 2 | 1 |
| E5（孤儿：supersedesId 指向不存在） | 3 | "ghost" | 0 | **3**（回退） | 1 |
| E6（另一对局同 id 前缀，隔离性） | 1 | null | 0 | 1 | 1 |

**验证点**

| ID | 断言 |
|---|---|
| V1 | `runMigrationsAndValidate` 等价行为：Room 打开后不抛 `IllegalStateException`（即 column + index + identity 全部匹配 4.json） |
| V2 | E2/E3 的 `logicalPosition == 1`、`revisionSeq == 2/3`（**链回填正确**） |
| V3 | E5（孤儿）`logicalPosition == 3`、`revisionSeq == 1`（**降级不报错**） |
| V4 | E6 的 `logicalPosition` 不受其他对局影响（`matchId` 隔离） |
| V5 | `COUNT(*) FROM match_events` 迁移前后相等；`payloadJson/visibility/retracted/recordedAt/eventType` 逐行相等（**不丢数据、不改业务列**） |
| V6 | 迁移后 INV-1 查询返回空 |
| V7 | 空库（0 行）执行迁移不报错 |
| V8 | 新索引存在：`SELECT name FROM sqlite_master WHERE type='index' AND name='index_match_events_matchId_logicalPosition_revisionSeq'` |
| V9 | 旧索引仍存在（`index_match_events_matchId_occurredOrder`），确认没被误删 |

### 10.2 行为回归（Phase 3 必须全绿）

| ID | 测试文件 | 断言 |
|---|---|---|
| B1 | `RevisionOrderSemanticsTest`（Phase 0 写的 3 条） | 修订后重放顺序正确、`upTo` 包含修订、时间线位置稳定 |
| B2 | `EventSlotAssignmentTest`（新） | 追加 → `logicalPosition == occurredOrder`；连续两次修订 → `revisionSeq = 3` 且 `logicalPosition` 不变 |
| B3 | `RoomMatchRepositoryRobolectricTest`（既有） | `reviseEvent` → `retracted` 双写不变；新增断言：`listActive` 顺序 = `logicalPosition` 顺序 |
| B4 | `AvalonReducerReplayTest`（既有，R14） | 修订后 `goodSuccessCount/evilFailureCount/status/issues` 按新事实重算（应比修复前**更准**，因为不再被顺序击穿） |
| B5 | `JsonCodecTest`（既有，D02） | 导出→导入往返一致；`ExportEvent.occurredOrder` 仍是逻辑顺序 |
| B6 | `MarkdownExporterTest`（既有） | 任务摘要表中的轮次顺序正确 |
| B7 | `MatchDaoRobolectricTest`（既有，D01） | `occurredOrder` 连续 1..N 的断言**保留**（写入序号仍连续），另加 `logicalPosition` 连续断言 |

### 10.3 命令与门禁

```bash
export JAVA_HOME="/c/Users/HK-info/android-build/jdk"
export ANDROID_HOME="/c/Users/HK-info/android-build/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :app:testDebugUnitTest --rerun-tasks   # 必须 ≥ 208 + 新增用例，0 失败
./gradlew :app:lintDebug                          # 必须 0 error
./gradlew :app:assembleDebug                      # 必须成功，产物 schema/4.json 已提交
# 有设备时：
./gradlew :app:connectedDebugAndroidTest
```

---

## 11. 风险清单与缓解

| ID | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R1 | 递归 CTE 在某些 OEM ROM 的 SQLite 实现上行为不一致（个别厂商替换过 SQLite） | 中 | ① 迁移测试在 Robolectric（SQLite 3.4x）与真机各跑一次；② 备好**降级 SQL**：把 ②③ 换成"最多 10 轮迭代 UPDATE"（每轮把子节点对齐父节点），见下方 §11.1 |
| R2 | 历史脏数据导致同 `logicalPosition` 有 2 条 active → 读路径出现重复事件 | 中 | v4 **不建唯一索引**（避免迁移失败）；改为启动自检 INV-1 + debug 日志；Phase 4 观察 2 周无命中，再在 v5 加唯一索引 |
| R3 | `@ColumnInfo(defaultValue)` 与迁移 SQL 的 DEFAULT 不一致 → Room 校验抛异常 | **高** | §3.1 的"必读"提示；V1 验证点专门覆盖 |
| R4 | 忘记改 `EventCodec.decode` 的 `base.occurredOrder`（10 处拷贝） | 中 | 这 10 处都从 `base` 派生，只要改 `base` 定义处（`EventCodec.kt:238`）即全覆盖；加 B2 断言 `decode(entity).occurredOrder == entity.logicalPosition` |
| R5 | Phase 1 单独发版导致旧 APK 遇到 v4 库 | **高** | §8 明确要求 Phase 1–3 合并发版；Phase 1 提交时在 PR 描述里标注"不可单独发布" |
| R6 | 修订链深度过大导致 CTE 递归开销 | 低 | 链深 = 同一事件的修订次数，实际 ≤ 5；SQLite 递归默认上限 1000 层 |
| R7 | 备份文件未 checkpoint 导致恢复后丢最近事务 | 中 | §9.1 步骤 3c 强制 `PRAGMA wal_checkpoint(TRUNCATE)`；`DatabaseBackupTest` 覆盖 |
| R8 | 撤销修订导致整个位置消失（§5.7） | 中 | 单列 RT-006；在 RT-006 之前 UI 提示"撤销修订会移除该事件" |
| R9 | 与 RT-002 对同一个 DAO 方法的并发修改冲突 | 中 | 见 §12 |

### 11.1 R1 的降级 SQL（若 CTE 在真机失败）

```sql
-- 等价于 §4 的 ②③，最多迭代 10 轮；每轮把「子节点」对齐「父节点」
-- Kotlin 侧循环执行，直到 changes() = 0 或轮次达 10
UPDATE `match_events` AS child
   SET logicalPosition = (SELECT p.logicalPosition FROM `match_events` p WHERE p.id = child.supersedesId),
       revisionSeq     = (SELECT p.revisionSeq + 1  FROM `match_events` p WHERE p.id = child.supersedesId)
 WHERE child.supersedesId IS NOT NULL
   AND EXISTS (SELECT 1 FROM `match_events` p
                WHERE p.id = child.supersedesId
                  AND p.logicalPosition <> 0
                  AND (child.logicalPosition <> p.logicalPosition
                       OR child.revisionSeq <> p.revisionSeq + 1));
```
首轮之前先执行一次：`UPDATE match_events SET logicalPosition = occurredOrder WHERE supersedesId IS NULL;`
（根节点先就位，迭代才有"父已正确"的不动点。）

> 注意：这个降级版依赖"父先于子被修正"，因此**必须**外层循环（Room 的 `migrate()` 里允许写 Kotlin 循环，用 `db.query(...).use { it.moveToFirst() }` 读 `changes()` 或直接查残留计数）。

---

## 12. 对 Phase 1 其余改动的依赖与合并顺序（**必读**）

### 12.1 与 RT-002 的关系（**已完成，不需要再等**）

审计期间 RT-002 已合入 `ee20e3d fix(data): scope event writes by matchId`，因此：

| 项 | 状态 |
|---|---|
| `markRetracted` 已带 `matchId` 且返回受影响行数 | ✅ 已就绪（`MatchEventDao.kt:71`：`markRetracted(matchId: String, id: String): Int`） |
| `reviseSupersede` 内的 `check(affected == 1)`（不属于本对局或已失效即抛异常回滚） | ✅ 已就绪（`:120-124`） |

**对 RT-003 的约束**：新建的 `reviseWithSlot` **必须原样保留**这两条语义，不得回退成"只按 id 更新"：

```kotlin
// reviseWithSlot 内
val affected = markRetracted(matchId, oldEventId)
check(affected == 1) { "修订目标不属于本对局或已失效：matchId=$matchId, oldEventId=$oldEventId" }
```

> 因此本文档 §5.3 里"`reviseWithSlot` 返回 `null` 表示目标不存在"的设计**改为**：先 `findRevisionSeed(matchId, oldEventId)`（带 `matchId` 条件）取种子；取不到直接抛 `check` 失败，与现有 `reviseSupersede` 行为保持一致。**不要**引入两套失败语义。

### 12.2 与 RT-004 的语义耦合（顺序建议：RT-003 **先**）

RT-004 是"状态投影统一重算（append/revise/retract/import）"。它每次重算都调 `ReplayEngine.replay(...)`，而 **RT-003 直接决定 replay 的输入顺序**。

- 若 RT-004 先合：它的重算基线建立在"顺序是错的"的数据上，回归断言会被迫写成"接受错误顺序"，之后 RT-003 合入时必须重写这些断言。
- **建议：RT-003（Phase 1–3）先合入，RT-004 在其上做投影统一。** 若主理人要求 RT-004 先走，则 RT-004 的测试**不得**断言"修订事件排在末尾"，只断言"状态与事件集合一致"。

### 12.3 与 RT-005 的依赖

RT-005（视角可见性：PUBLIC 不泄漏 PRIVATE）与顺序无关，**完全独立**，可并行。

### 12.4 工作区现状与提交拆分

`git status` 当前只剩主题资源类改动（`themes.xml` + `values-v31/`，属 LINT-001 修复）与 `docs/audit/` 未跟踪；**DB 层的 RT-001/RT-002 已随 `ee20e3d` 落地**，工作区干净，RT-003 可以直接在其上开工。

建议提交拆分（便于单独回滚）：

```
ee20e3d   fix(data): RT-002 事件写入按 matchId 收口              ✅ 已合入
commit 2  feat(db):  RT-003 Phase 1 —— 加列 + v4 迁移 + schema（不可单独发布）
commit 3  feat(db):  RT-003 Phase 2 —— 写入双写（EventSlot）
commit 4  fix(replay): RT-003 Phase 3 —— 读路径切到 logicalPosition
commit 5  docs/chore: RT-003 Phase 4 —— 注释、自检、文档
```

---

## 13. 明确**不在**本方案范围（硬约束确认）

| 约束 | 本方案是否触碰 |
|---|---|
| B34「结果明确即计数」 | ❌ 未触碰（`AvalonReducer` 一行不改；`verified` 逻辑不动） |
| 5–10 人角色矩阵 | ❌ 未触碰 |
| 7–10 人第 4 任务双失败牌门槛 | ❌ 未触碰（`AvalonRules.failThreshold` 不改） |
| JSON v1 契约 | ❌ 字段集合不变、语义不变（`occurredOrder` 仍是"事件顺序"） |
| 迁移链只能追加 | ✅ 仅在 `MIGRATION_2_3` 之后追加 `MIGRATION_3_4`，v1/v2/v3 迁移体一行不改 |
| `fallbackToDestructiveMigration()` | ❌ 继续禁用（`RoundTraceDatabase.kt:92-94` 不变） |
| 不写业务代码 | ✅ 本文档只有 SQL 与签名，无实现 |

---

## 14. 裁决记录（2026-10-31，主理人）

> 本节为**最终裁决**，优先级高于正文各章；与正文冲突时以本节为准。
> 原 §14「待主理人决策的 3 个点」已由本节 ① / ② / ③ 逐条关闭，不再保留独立章节。
> 方案主体（A1）**无变更**：字段语义、迁移 SQL、`upTo` 语义、JSON v1 兼容、分阶段顺序均照原稿执行。

### ① 迁移失败的产品行为：**分两档，P0 只做最小行为**

**裁决**

| 档位 | 要求 |
|---|---|
| **P0 最小行为（本期必须做到）** | 1. **不丢数据**：迁移全程无 `DELETE`、不重建表、只写新列（原 §4.1 已满足）；<br>2. **抛出可诊断错误**：迁移异常不得被吞掉、不得转成空库或默认值，异常信息需能定位到 `match_events` 与 `MIGRATION_3_4`；<br>3. **绝不 destructive fallback**：继续禁用 `fallbackToDestructiveMigration()`（原 §13 约束不变）；<br>4. **启动自检 INV-1**：debug 下跑原 §4.2 的 INV-1 查询，命中即写日志（不上报、不弹窗）；<br>5. **迁移前文件级备份**：保留原 §9.1 的备份步骤（`PRAGMA wal_checkpoint(TRUNCATE)` + 复制为 `roundtrace.db.bak.v3`），它属于"不丢数据"的兜底，**不属 P1 范围**。 |
| **P1 完整页（本期不做）** | 独立「数据需要修复」页 + 三选项（重试 / 从备份恢复 / 导出原始文件），即原 §9.2 的 R-1。需 UI 排期。 |

**影响章节**：§9.2 的推荐稿**降级为 P1 设计草案**，P0 以上表为准；§9.1 备份策略**保持强制**（它是 P0 第 5 条）；§9.3「备份可读」的验证项属 P0 测试范围（`DatabaseBackupTest`）。
**对工程师**：P0 阶段**不要**新建"数据需要修复"页，也不要为了"体验流畅"把迁移异常包成 `runCatching` 静默降级。

### ② AS_RECORDED 审计视图：**P0 不做，并锁定"旧版本行永不物理删除"为硬约束**

**裁决**

1. P0 只实现 CORRECTED 视图（原 §6.1 不变），不加 `view` 参数、不加 `TimelineBuilder.buildAsRecorded`。
2. **新增硬约束（写死，不得以后续优化为名违反）**：
   > `match_events` 中任何行**永不物理删除**（`DELETE`）；修订只允许 `UPDATE ... SET retracted = 1`。
   > 整局删除走 `matches` 的外键 `ON DELETE CASCADE`，这是唯一合法的物理删除路径。
   > 违反此约束 = 破坏未来审计视图的可实现性，且会使 v4 的数据无法回溯。

**影响章节**：§6.3 从"设计取舍"升级为"受约束的实现承诺"；§12.4 的"删除/`@Deprecated` 旧方法"仅指**代码方法**，不涉及任何 `match_events` 行删除；§13 硬约束表追加本条。
**对工程师**：任何"清理历史版本 / 压缩存储 / 定期归档"的需求，一律先提 RT 评审，禁止在仓储或迁移里顺手 `DELETE`。

### ③ RT-006（撤销修订导致逻辑位置消失）：**单列，P1，排在 RT-003 之后**

**裁决**

1. RT-006 独立立项，优先级 **P1**，**必须在 RT-003（A1）落地并观察稳定后**再开工——因为 A1 会放大该缺陷（逻辑位置成为显式概念后，"位置整体消失"变得可见且更易被用户察觉）。
2. RT-006 与 RT-003 **不同批交付**。
3. **补充事实（原 §5.7 表述不够完整，以此为准）**：

   > 该缺陷**在当前代码里同样存在**，不是 A1 引入的新问题。
   > 现有实现中，`retractEvent` 命中一个**已被修订过**的事件（父版本）时，该父版本本身已是 `retracted = 1`；再撤销它只会重复置 1，**该逻辑位置不会出现任何 active 事件**，事件同样从时间线上消失。
   > A1 只是让这种现象有了名字（逻辑位置），从而**更容易被发现**；根因是 `retracted` 只有一个布尔位，无法区分"被修订取代"与"被用户撤销"。

**影响章节**：§5.7 的结论不变但事实描述以本节第 3 条为准；§11-R8 的缓解措施"UI 提示"**在 P0 仍需保留**（RT-006 未落地前，用户必须被明确告知撤销修订会移除该事件）。
**对工程师**：P0 阶段不要试图在 `retractEvent` 里做"自动恢复父版本"的临时补丁——那会引入第二套撤销语义，与 RT-006 的正式设计冲突。

### ④ RT-004 顺序：**RT-004 先走，不等 RT-003**

**裁决**

1. **RT-004 先于 RT-003 合入**（主理人决定，覆盖原 §12.2 的"建议 RT-003 先"）。
2. **RT-004 的回归断言白名单**（只允许这两类）：

   | 允许 | 说明 |
   |---|---|
   | 不变量断言 | `matches.status` == `ReplayEngine` 派生状态（即"投影与重算一致"，与事件顺序无关） |
   | 顺序无关 fixture | 单任务对局、或事件顺序固定且**不含任何修订**的简单场景 |

3. **明令禁止**（防止把错误顺序固化成期望）：

   - ❌ 断言"修订事件排在时间线末尾"（`occurredOrder = MAX+1` 的现状）；
   - ❌ 断言"修订后 `upTo` 需要按写入序号调整"；
   - ❌ 任何以 `occurredOrder` 具体数值（而非逻辑相对关系）为期望的断言。

4. RT-003 合入时，只需**新增**顺序相关的正例断言（Phase 0 的 B1 三条），**不需要**回头改写 RT-004 的断言——这正是本裁决的收益。

**影响章节**：§12.2 的排序建议**作废**，以本节为准；§10.2 的 B1/B4 仍属 RT-003 的验收范围，但 B4（`AvalonReducerReplayTest`）只能做不变量断言，不得依赖修订顺序。

### ⑤ M4 视角引擎契约复核：**等 RT-005 合入后按当前 HEAD 重做**

**裁决**

1. **不在 RT-005 合入前开工**。RT-005 正在改 `buildPublicFacts` / `claimLabels` / `analyzeAt` 三处入口，此刻复核必然白做。
2. 触发条件：RT-005 合入 → 以**当时的 HEAD** 为基线重新收集证据（文件:行号）。
3. 交付物不变：`docs/PERSPECTIVE_REVIEW.md`，五条重点 + 三类系统性偏差 + 分级偏差清单。
4. 前置提醒（供 RT-005 落地后复核时优先验证）：原复核已确认过的高风险点仍适用——上帝视角开放条件的**数据来源**（应用对局当前状态，而非回放位置状态）、`KnowledgeBasis` 三态是否真的可达、派西维尔"无莫甘娜"分支在**快照层**是否被硬编码成两候选、`EvidenceFinding` 的证据可追溯性（空 `sourceEventIds` 不得标为确定级）。

**影响章节**：无（本节不改变 RFC 的技术内容，只是排期与外发记录）。

---

## 15. 实施清单：Step 1（加列 + 迁移 + 双写）

> 本节把原 §8 的 **Phase 1 + Phase 2 合并为「Step 1」**一次交付，可直接照做。
> **本节有一处数值约定变更**（`revisionSeq` 由 1 基改 0 基），生效范围：覆盖 §3.1 / §4 / §5.3 中的数值；其余技术结论不变。
> 本节与 §14 同级，冲突时以本节为准。

### 15.0 前置条件（不满足不要开工）

| # | 条件 | 理由 |
|---|---|---|
| P1 | **RT-004 已提交**（`recomputeProjectionLocked` 已在 `main`） | Step 1 要改 `RoomMatchRepository.appendEvent` / `reviseEvent` 的调用点，而 RT-004 正在改同两个函数（当前工作区 `M core/data/RoomMatchRepository.kt`），并行必冲突 |
| P2 | 工作区干净（`git status` 无 `app/src` 改动） | 避免把工程师的改动混进 Step 1 提交，导致无法单独 revert |
| P3 | 已跑过一次基线：`./gradlew :app:testDebugUnitTest --rerun-tasks` 全绿，记下**实际用例数 N₀** | 验收用 N₀ 而非写死的 208（`48a7144` 之后用例数已增加） |

### 15.1 数值约定（本节唯一一处对正文的覆盖）

**`revisionSeq` 采用 0 基：`0` = 原始版本，`1..n` = 第 n 次修订。**

| 对比项 | 0 基（本节采用） | 1 基（原 §3.1/§4） |
|---|---|---|
| 含义 | 该位置**被修订过几次** | 该位置**的第几版** |
| 无修订的旧数据 | 天然为 `0`（`DEFAULT 0` 即正确） | 需要回填成 1 |
| 与主理人验收断言 | 一致（`revisionSeq == 0`） | 冲突 |

随之更新的三条不变量（**替换原 §2.1 的 INV-3**）：

- **INV-3′**：`supersedesId IS NULL` ⇒ `revisionSeq == 0`；`supersedesId IS NOT NULL` ⇒ `revisionSeq >= 1`。
- **INV-1、INV-2、INV-4 不变**（每位置至多一条 active、位置无空洞、领域 `occurredOrder` == `logicalPosition`）。

### 15.2 逐文件改动（共 6 个代码/配置文件 + 1 个生成物）

#### ① `app/src/main/java/com/roundtrace/app/core/database/entity/MatchEventEntity.kt`

**改成**（全量替换该 `@Entity` 声明与类体，其余 KDoc 保留并追加 §3.1 的顺序语义说明）：

```kotlin
@Entity(
    tableName = "match_events",
    foreignKeys = [
        ForeignKey(
            entity = MatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["matchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        // 新增：读路径唯一排序键（Step 3 生效；Step 1 只建不用）
        Index(value = ["matchId", "logicalPosition", "revisionSeq"]),
        // 保留：写入序号分配 MAX(occurredOrder)、迁移、审计仍用
        Index(value = ["matchId", "occurredOrder"]),
        Index(value = ["matchId", "eventType"]),
        Index(value = ["supersedesId"])
    ]
)
data class MatchEventEntity(
    @PrimaryKey
    val id: String,
    val matchId: String,
    /** 历史**写入序号**（第几条被插入）。**只写不读**，仅供迁移与审计。 */
    val occurredOrder: Int,
    /** 事件在时间线上的**逻辑位置**；同一事件的所有修订版本共享。**不可变**。 */
    @ColumnInfo(defaultValue = "0")
    val logicalPosition: Int,
    /** 该位置被修订的次数（0 = 原始版本）。**不可变**。 */
    @ColumnInfo(defaultValue = "0")
    val revisionSeq: Int,
    val phaseKey: String,
    val eventType: String,
    val payloadVersion: Int = 1,
    val payloadJson: String,
    val visibility: String,
    val recordedAt: Long,
    val supersedesId: String?,
    val retracted: Boolean = false
)
```

必读三条：

1. `@ColumnInfo(defaultValue = "0")` 的字符串**必须逐字为 `"0"`**（不带引号包裹、不带空格）。SQLite `ALTER TABLE ... ADD COLUMN ... NOT NULL` 强制要求 DEFAULT，Room 迁移后会比对 `TableInfo.defaultValue`，两侧不一致直接抛 `IllegalStateException: Migration didn't properly handle match_events`。
2. 新增两个字段**不给 Kotlin 默认值**（`= 0` 都不写）。理由见 §15.6-D2：有默认值等于允许漏传，而漏传 `logicalPosition` 正是脏行的来源；编译期报错比运行期脏数据便宜。
3. 需要 `import androidx.room.ColumnInfo`。

#### ② `app/src/main/java/com/roundtrace/app/core/database/RoundTraceDatabase.kt`

**改成**：

```kotlin
@Database(
    entities = [ /* 6 个实体不变 */ ],
    version = 4,          // ← 3 改为 4
    exportSchema = true
)
```

并在 `companion object` 内新增（逐字）：

```kotlin
/** v3→v4（RT-003）：拆分「逻辑顺序」与「写入序号」。 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ① 加列（NOT NULL 必须带 DEFAULT；与 @ColumnInfo(defaultValue="0") 逐字一致）
        db.execSQL("ALTER TABLE `match_events` ADD COLUMN `logicalPosition` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `match_events` ADD COLUMN `revisionSeq` INTEGER NOT NULL DEFAULT 0")

        // ② 回填 logicalPosition：沿 supersedesId 链取链根的 occurredOrder
        db.execSQL(
            "WITH RECURSIVE chain(id, rootOrder) AS (\n" +
                "    SELECT id, occurredOrder FROM `match_events` WHERE supersedesId IS NULL\n" +
                "    UNION ALL\n" +
                "    SELECT child.id, chain.rootOrder\n" +
                "      FROM `match_events` AS child\n" +
                "      JOIN chain ON child.supersedesId = chain.id\n" +
                ")\n" +
                "UPDATE `match_events`\n" +
                "   SET logicalPosition = COALESCE(\n" +
                "         (SELECT rootOrder FROM chain WHERE chain.id = `match_events`.id),\n" +
                "         occurredOrder)"
        )

        // ③ 回填 revisionSeq：链深度 - 1（根 = 0）
        db.execSQL(
            "WITH RECURSIVE chain(id, depth) AS (\n" +
                "    SELECT id, 0 FROM `match_events` WHERE supersedesId IS NULL\n" +
                "    UNION ALL\n" +
                "    SELECT child.id, chain.depth + 1\n" +
                "      FROM `match_events` AS child\n" +
                "      JOIN chain ON child.supersedesId = chain.id\n" +
                ")\n" +
                "UPDATE `match_events`\n" +
                "   SET revisionSeq = COALESCE(\n" +
                "         (SELECT depth FROM chain WHERE chain.id = `match_events`.id), 0)"
        )

        // ④ 读路径索引（IF NOT EXISTS 保证可重复执行）
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_match_events_matchId_logicalPosition_revisionSeq` " +
                "ON `match_events` (`matchId`, `logicalPosition`, `revisionSeq`)"
        )
    }
}
```

并把 `build()` 里的 `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)` 改为：

```kotlin
.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
```

> **为什么用字符串拼接而不是 Kotlin 原始字符串**：`execSQL` 对多行字符串没问题，但原始字符串 `"""..."""` 里一旦出现 `$`（未来 SQL 加参数占位）会被 Kotlin 当模板插值；这里统一用 `\n` 拼接，杜绝隐性插值。这是本节唯一选定的写法（见 §15.6-D5）。

#### ③ `app/src/main/java/com/roundtrace/app/core/database/EventCodec.kt`

**③-1 `encode` 改为**（新增 2 个带默认值的参数；默认值必须让**未改动的调用点保持现有语义**）：

```kotlin
fun encode(
    event: MatchEvent,
    matchId: MatchId,
    occurredOrder: Int,
    logicalPosition: Int = occurredOrder,
    revisionSeq: Int = 0
): MatchEventEntity =
    MatchEventEntity(
        id = event.eventId.raw,
        matchId = matchId.raw,
        occurredOrder = occurredOrder,
        logicalPosition = logicalPosition,
        revisionSeq = revisionSeq,
        phaseKey = phaseKeyOf(event),
        eventType = event.type.name,
        payloadVersion = PAYLOAD_VERSION,
        payloadJson = encodePayload(event),
        visibility = event.visibility.name,
        recordedAt = event.recordedAt,
        supersedesId = event.supersedesId?.raw,
        retracted = event.retracted
    )
```

**③-2 `decode` 只改一行**：`BaseFields(... occurredOrder = entity.occurredOrder ...)` → `occurredOrder = entity.logicalPosition`。

> 下游 10 处 `occurredOrder = base.occurredOrder` **一律不动**（它们都从 `base` 派生，改一处全覆盖）。

#### ④ `app/src/main/java/com/roundtrace/app/core/database/dao/MatchEventDao.kt`

**④-1 新增两个类型**（放在文件顶层，`MatchEventDao` 类之外）：

```kotlin
/** 事务内分配好的写入槽位。 */
data class EventSlot(
    val occurredOrder: Int,
    val logicalPosition: Int,
    val revisionSeq: Int
)

/** 被修订事件的定位种子（@Query 投影）。 */
data class RevisionSeed(
    val logicalPosition: Int,
    val revisionSeq: Int
)
```

**④-2 新增两条查询**：

```kotlin
/** 下一个逻辑位置；**必须**含 retracted 行，否则撤销后会复用已废弃位置。 */
@Query("SELECT COALESCE(MAX(logicalPosition), 0) + 1 FROM match_events WHERE matchId = :matchId")
abstract suspend fun nextLogicalPosition(matchId: String): Int

@Query("SELECT logicalPosition, revisionSeq FROM match_events WHERE matchId = :matchId AND id = :id")
abstract suspend fun findRevisionSeed(matchId: String, id: String): RevisionSeed?
```

**④-3 用下面两个方法替换 `appendWithOrder` / `reviseSupersede`**（方法名更换、旧方法删除，不并存）：

```kotlin
@Transaction
open suspend fun appendWithSlot(
    matchId: String,
    entityProvider: (EventSlot) -> MatchEventEntity,
    now: Long
): EventSlot {
    val slot = EventSlot(
        occurredOrder = nextOccurredOrder(matchId),
        logicalPosition = nextLogicalPosition(matchId),
        revisionSeq = 0
    )
    insert(entityProvider(slot))
    touchMatch(matchId, now)
    return slot
}

@Transaction
open suspend fun reviseWithSlot(
    matchId: String,
    oldEventId: String,
    entityProvider: (EventSlot) -> MatchEventEntity,
    now: Long
): EventSlot {
    val seed = checkNotNull(findRevisionSeed(matchId, oldEventId)) {
        "修订目标不属于本对局或已失效：matchId=$matchId, oldEventId=$oldEventId"
    }
    val slot = EventSlot(
        occurredOrder = nextOccurredOrder(matchId),
        logicalPosition = seed.logicalPosition,
        revisionSeq = seed.revisionSeq + 1
    )
    insert(entityProvider(slot))
    // RT-002 收口：必须带 matchId，且必须校验受影响行数（不得退化）
    val affected = markRetracted(matchId, oldEventId)
    check(affected == 1) {
        "修订目标不属于本对局或已失效：matchId=$matchId, oldEventId=$oldEventId, affected=$affected"
    }
    touchMatch(matchId, now)
    return slot
}
```

`markRetracted` / `nextOccurredOrder` / `maxOccurredOrder` / `touchMatch` / 三条读查询**全部保持原样**（读查询的 `ORDER BY` 属 Step 3）。

#### ⑤ `app/src/main/java/com/roundtrace/app/core/data/RoomMatchRepository.kt`

**只改 3 个调用点**（`appendEvent` 内 2 处、`reviseEvent` 内 1 处）：

```kotlin
// appendEvent 内，自动补建 QUEST_STARTED：
eventDao.appendWithSlot(
    matchId = matchId.raw,
    entityProvider = { slot -> codec.encode(auto, matchId, slot.occurredOrder, slot.logicalPosition, slot.revisionSeq) },
    now = committedAt
)

// appendEvent 内，主事件：
val slot = eventDao.appendWithSlot(
    matchId = matchId.raw,
    entityProvider = { s -> codec.encode(event, matchId, s.occurredOrder, s.logicalPosition, s.revisionSeq) },
    now = committedAt
)
// 原 `var order: Int` 改为 `val slot: EventSlot`，沿用它的地方改 slot.occurredOrder
// AppendResult(occurredOrder = slot.occurredOrder)  ← 对外返回值语义不变

// reviseEvent 内：
val slot = eventDao.reviseWithSlot(
    matchId = matchId.raw,
    oldEventId = targetEventId.raw,
    entityProvider = { s ->
        codec.encode(replacement, matchId, s.occurredOrder, s.logicalPosition, s.revisionSeq)
            .copy(supersedesId = targetEventId.raw)
    },
    now = committedAt
)
```

`importBundle` / `getBundle` / `buildBundle` / `recomputeProjectionLocked` **零改动**。

#### ⑥ `app/build.gradle.kts`

在 `android { }` 块内新增（`MigrationTestHelper` 必须能从 test assets 读到 `schemas/*.json`，**当前缺失，不加则 T1 直接抛「Cannot find schema file」**）：

```kotlin
    sourceSets {
        getByName("test") { assets.srcDir("$projectDir/schemas") }
        getByName("androidTest") { assets.srcDir("$projectDir/schemas") }
    }
```

> 这是 Step 1 唯一一处构建脚本改动，且**纯增量**（不影响打包产物：assets 只进测试变体）。

#### ⑦ `app/schemas/com.roundtrace.app.core.database.RoundTraceDatabase/4.json`（生成物）

构建一次后由 KSP 自动产出，**必须 `git add` 提交**。缺失会导致 T1 在 CI/新机器上失败。同时**不要删** `1.json`/`2.json`/`3.json`（迁移测试需要 v3 schema 造库）。

### 15.3 Step 1 必增测试（4 个新文件 + 3 个既有文件同步改造）

#### T1 `app/src/test/java/com/roundtrace/app/database/MigrationV3ToV4Test.kt`（新增，主用例）

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationV3ToV4Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RoundTraceDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )
    // 每个用例用独立库名，避免互相污染
}
```

必须覆盖的 7 条断言（**逐条对应主理人要求**）：

| ID | 场景 | 断言 |
|---|---|---|
| **T1-a** | v3 写入 3 条**无修订**事件（order 1/2/3，`retracted=0`）→ 迁移到 4 | ① `logicalPosition` 依次 `== occurredOrder`（1/2/3）；② `revisionSeq` 全 `== 0`；③ `COUNT(*)` 迁移前后相等；④ 老事件**仍可读**：`payloadJson` / `eventType` / `recordedAt` / `visibility` / `retracted` 逐行与迁移前相同 |
| **T1-b** | v3 写入 1 条**两层修订链**：E1(order1,sup=null,retracted=1) ← E2(order6,sup=E1,retracted=1) ← E3(order7,sup=E2,retracted=0) | E1/E2/E3 的 `logicalPosition` **全为 1**；`revisionSeq` 依次 **0 / 1 / 2**；`retracted` 值不变（1/1/0） |
| **T1-c** | v3 写入 1 条**孤儿链**（`supersedesId='ghost'`） | `logicalPosition == occurredOrder`、`revisionSeq == 0`，**迁移不抛异常** |
| **T1-d** | 空库（0 行）→ 迁移 | 不抛异常，`COUNT(*)==0` |
| **T1-e** | 迁移后查 `sqlite_master` | 新索引 `index_match_events_matchId_logicalPosition_revisionSeq` 存在 |
| **T1-f** | 同上 | 旧索引 `index_match_events_matchId_occurredOrder` / `..._eventType` / `..._supersedesId` **仍存在**（确认没被误删） |
| **T1-g** | 对已迁移库**再执行一次**②③两条 UPDATE（用 `helper.createDatabase(name, 4)` 拿 `SupportSQLiteDatabase` 手工 execSQL） | 所有行的 `logicalPosition`/`revisionSeq` **值不变**（证明回填幂等） |

#### T2 `app/src/test/java/com/roundtrace/app/database/MigrationBackfillManualTest.kt`（新增，冗余保障）

**职责与 T1 不重叠**：T1 验证「Room 能跑完迁移且 schema 校验通过」，T2 验证「回填值在**不依赖 instrumentation** 的路径下也正确」。做法即原 §10.1 的 4 步（手工建 v3 库 + 写 `room_master_table` 的 `identityHash='012b543edd3715ab981a6f8b5c812a1e'` + `PRAGMA user_version=3` + 用 Room 打开触发迁移），断言同 T1-a/T1-b。

> 存在理由：若 T1 的 `MigrationTestHelper` 在纯 JVM 下不可用（见 §15.5 应急），T2 仍能守住回填正确性。

#### T3 `app/src/test/java/com/roundtrace/app/database/DatabaseBackupTest.kt`（新增）

按 §14-① 的 P0 第 5 条：迁移前生成 `roundtrace.db.bak.v3`；断言 ① 备份文件存在；② 只读打开后 `PRAGMA user_version == 3`；③ `SELECT COUNT(*) FROM match_events` 与主库一致。

#### T4 `app/src/test/java/com/roundtrace/app/data/EventSlotAssignmentTest.kt`（新增）

| ID | 场景 | 断言 |
|---|---|---|
| T4-a | 对空局连续追加 20 条 | `occurredOrder` 与 `logicalPosition` 均为 1..20；`revisionSeq` 全 0 |
| T4-b | 修订 `logicalPosition=5` 的事件 | 新行 `occurredOrder=21`、`logicalPosition=5`、`revisionSeq=1`；旧行 `retracted=1` |
| T4-c | 对 T4-b 的新版本**再修订** | `revisionSeq=2`，`logicalPosition` 仍 5 |
| T4-d | 修订后追加新事件 | 新事件 `logicalPosition=21`（`MAX(logicalPosition)+1`，**不受修订影响**）、`revisionSeq=0` |
| T4-e | 撤销某位置（`retractEvent`）后再追加 | 新事件**不复用**被撤销的 `logicalPosition`（`nextLogicalPosition` 不过滤 `retracted`） |
| T4-f | 跨对局隔离 | A 局修订不改变 B 局的 `logicalPosition`/`revisionSeq` |

#### T5–T7 既有测试同步改造（机械替换，非行为变更）

| 文件 | 改法 |
|---|---|
| `app/src/test/java/com/roundtrace/app/database/MatchDaoRobolectricTest.kt` | `appendWithOrder(...)` → `appendWithSlot(...)`；provider 参数由 `Int` 改 `EventSlot`；`val order = ...` → `val slot = ...; slot.occurredOrder`；断言消息里的 `appendWithOrder` 文案改为 `appendWithSlot` |
| `app/src/test/java/com/roundtrace/app/data/RoomMatchRepositoryRobolectricTest.kt` | `reviseSupersede(...)` → `reviseWithSlot(...)`（`reviseSupersedeRollsBackWhenTargetBelongsToAnotherMatch` 用例名保留，只换调用） |
| `app/src/androidTest/java/com/roundtrace/app/database/MatchDaoTest.kt` | 同 T5（该文件无设备时不执行，但**必须同步改**，否则接真机时才炸） |

> 这 3 个文件的改动**只允许是签名适配**。若某个断言需要改期望值，说明 Step 1 破坏了"行为不变"，必须停下来查，不允许"顺手更新期望值"。

### 15.4 不变量与禁止项

**Step 1 结束时必须成立**

| ID | 不变量 |
|---|---|
| S1 | 全库不存在 `logicalPosition = 0` 的行（迁移后与后续写入后都成立） |
| S2 | 每条 active 事件的 `logicalPosition` 在**同一 matchId 内唯一**（INV-1） |
| S3 | 追加路径满足 `logicalPosition == occurredOrder`（因为 `nextLogicalPosition` 与 `nextOccurredOrder` 在"从未修订过"的局里同步递增） |
| S4 | 领域层的 `occurredOrder` 已等于 `logicalPosition`（`decode` 已改），但**没有任何读路径使用它做排序变更**（仍按 `occurredOrder`，而值是逻辑位置 → 行为等价，因为 Step 1 内 `logicalPosition == occurredOrder` 对所有新建数据成立；对历史含修订数据，读仍是旧顺序 → 与今天一致） |

> **S4 的严格表述**：Step 1 只是"开始写正确的值"，`retracted` 与旧顺序语义**完全没变**，所以用户可观察行为与今天 100% 相同。RT-003 的可见修复发生在 Step 3。

**禁止项（违反即打回）**

1. ❌ 不得改 `core/replay/ReplayEngine.kt`、`core/replay/TimelineBuilder.kt`（Step 4 才改注释）。
2. ❌ 不得改 `listActive` / `observeActive` / `listAll` / `listActiveUpTo` 的 `ORDER BY` 或 `WHERE`（Step 3）。
3. ❌ 不得改 `core/export/ExportModels.kt`、`MatchJsonCodec.kt` 的任何字段（v1 契约冻结）。
4. ❌ 不得改 `markRetracted(matchId, id)` 的签名，不得删除 `check(affected == 1)`（RT-002 收口）。
5. ❌ 不得引入 `fallbackToDestructiveMigration()`。
6. ❌ 不得对 `match_events` 执行任何 `DELETE`（§14-② 硬约束）。
7. ❌ 不得给 `logicalPosition` / `revisionSeq` 加 Kotlin 默认值（§15.6-D2）。
8. ❌ 不得保留 `appendWithOrder` / `reviseSupersede` 旧方法（§15.6-D1）。
9. ❌ 不得把 Step 1 单独发版（Phase 3 未合入前 DB 无法回退到 v3，详见 §8 Phase 1 回退说明）。

### 15.5 验收命令与预期

```bash
export JAVA_HOME="/c/Users/HK-info/android-build/jdk"
export ANDROID_HOME="/c/Users/HK-info/android-build/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

# 1) 单元测试（含 T1–T4 与改造后的 T5–T6）
./gradlew :app:testDebugUnitTest --rerun-tasks
#   预期：BUILD SUCCESSFUL；failed=0 / error=0；用例数 ≥ N₀（§15.0-P3 记下的基线，≥208）
#        且 ≥ N₀ + 15（T1 七例 + T2 两例 + T3 一例 + T4 六例）

# 2) lint
./gradlew :app:lintDebug
#   预期：0 error（warning 允许持平当前 54；若新增 Room 相关 warning 需说明）

# 3) 打包（同时生成 schema）
./gradlew :app:assembleDebug
#   预期：BUILD SUCCESSFUL；产物 app/build/outputs/apk/debug/app-debug.apk 存在

# 4) schema 已生成且纳入版本控制
git status --short app/schemas
#   预期：?? app/schemas/.../4.json（必须 git add，不得忽略）
```

**行为等价的判定方式**：Step 1 前后跑同一套既有用例（208+ 条）全绿，且**没有任何既有断言被修改期望值** → 即为"行为与今天完全一致"的证据。这条比任何人工比对都硬。

### 15.6 已定型的 5 个取舍（只给结论，不留可选）

| ID | 取舍点 | 选定 | 理由 |
|---|---|---|---|
| D1 | 旧 DAO 方法是「改名替换」还是「新旧并存」 | **改名替换** | 旧签名无法传 `logicalPosition`，保留它必然有人调用并写出 `logicalPosition = 0` 的脏行——这是本方案最危险的失败模式。宁可让编译器在 4 个文件里报错，强制同步更新 |
| D2 | 实体新字段是否给 Kotlin 默认值 | **不给** | 有默认值等于允许漏传；漏传 `logicalPosition` 会静默产生脏行，且这类 bug 只在 Step 3 切读路径后才暴露。默认值只放在 `EventCodec.encode` 的参数上（那是为了让**未改动的调用点**保持等价） |
| D3 | `revisionSeq` 基数 | **0 基** | 语义变为"被修订过几次"，`0` 与 `DEFAULT 0` 天然一致，主理人验收断言也是 `== 0`（详见 §15.1） |
| D4 | 迁移 SQL 的写法 | **`\n` 字符串拼接** | 原始字符串 `"""..."""` 在未来 SQL 出现 `$` 时会被 Kotlin 当模板插值；拼接版无此风险。代价是可读性略低，用注释行号对齐 |
| D5 | 迁移测试的造库方式 | **T1 用 `MigrationTestHelper` + T2 用「手工 v3 夹具 + Room 打开」** | 两者职责不同：T1 验证「Room 能跑完迁移且 schema 校验通过」（这是 `@ColumnInfo(defaultValue)` 类错误的唯一把关点）；T2 验证「回填值正确」且不依赖 instrumentation。**不是同一件事的两种写法**，故并存 |

### 15.7 Step 1 提交与回退

**提交边界（建议单 commit，因 Phase 1+2 必须同发）**

```
feat(db): RT-003 Step 1 —— logicalPosition/revisionSeq 加列、v4 迁移与双写
  - entity: 2 列 + 1 索引
  - database: version 4 + MIGRATION_3_4
  - codec: encode 两默认参数 / decode 取 logicalPosition
  - dao: appendWithSlot / reviseWithSlot（替换旧方法，保留 RT-002 收口）
  - repository: 3 个调用点
  - build: test/androidTest 增加 schemas assets
  - test: MigrationV3ToV4 / MigrationBackfillManual / DatabaseBackup / EventSlotAssignment
  - schema: 4.json
```

**回退**：`git revert <commit>` 即可（DB 层 v4 列保留不用，读路径从未改过，缺 `4.json` 也不影响运行；若已发版到设备，需按 §9.1 备份恢复 + 回滚 APK）。

**风险与应急（唯一一条）**：若 `MigrationTestHelper` 在纯 JVM 下不可用（`InstrumentationRegistry.getInstrumentation()` 抛异常，或 assets 里读不到 schema），**把 T1 整体下沉到 `androidTest`**（`src/androidTest/.../MigrationV3ToV4Test.kt`，用真机/模拟器跑），JVM 侧由 T2 承担回填断言；此时验收命令第 1 条不变，额外记录 `connectedDebugAndroidTest` 为 NOT RUN（无设备）并在 `TEST_REPORT.md` 说明。**不要**为了让测试变绿去删掉 T1 的断言。

---

## 附录 A：改动文件清单（给工程师的执行 checklist）

> **Step 1（加列 + 迁移 + 双写）的权威清单是 §15.2 / §15.3**（含逐字 SQL、注解字符串与方法签名）。
> 本附录是 **Step 1–4 全线**的汇总视图，冲突时以 §15 为准。

| # | 文件 | 改动 | Phase |
|---|---|---|---|
| 1 | `core/database/entity/MatchEventEntity.kt` | +2 列（含 `@ColumnInfo(defaultValue = "0")`）+1 索引 | 1 |
| 2 | `core/database/RoundTraceDatabase.kt` | `version 3→4`、`MIGRATION_3_4`、加入 `addMigrations` | 1 |
| 3 | `app/build.gradle.kts` | `sourceSets` 把 `schemas/` 加进 test / androidTest assets | 1 |
| 4 | `app/schemas/.../4.json` | 构建生成后提交 | 1 |
| 5 | `core/database/EventCodec.kt` | 写：`encode` +2 默认参数；读：`decode` 的 `base.occurredOrder` 改取 `logicalPosition` | 2 |
| 6 | `core/database/dao/MatchEventDao.kt` | 写：`EventSlot`/`RevisionSeed`/`nextLogicalPosition`/`findRevisionSeed`/`appendWithSlot`/`reviseWithSlot`；读：3 条 `ORDER BY` + 1 条 `WHERE`（**读属 Step 3**） | 2（写）/ 3（读） |
| 7 | `core/data/RoomMatchRepository.kt` | `appendEvent`（2 处）/ `reviseEvent`（1 处）调用点 | 2 |
| 8 | `core/replay/ReplayEngine.kt` | **仅 KDoc**（`upTo` 语义） | 4 |
| 9 | `core/replay/TimelineBuilder.kt` | **仅 KDoc** | 4 |
| 10 | `test/.../database/MigrationV3ToV4Test.kt` | 新建（T1-a…T1-g） | 1 |
| 11 | `test/.../database/MigrationBackfillManualTest.kt` | 新建（T2，手工 v3 夹具） | 1 |
| 12 | `test/.../database/DatabaseBackupTest.kt` | 新建（T3，备份可读） | 1 |
| 13 | `test/.../replay/RevisionOrderSemanticsTest.kt` | 新建（B1，Phase 0 先写红） | 0 |
| 14 | `test/.../data/EventSlotAssignmentTest.kt` | 新建（T4，槽位分配） | 2 |
| 15 | `test/.../database/MatchDaoRobolectricTest.kt`、`test/.../data/RoomMatchRepositoryRobolectricTest.kt`、`androidTest/.../database/MatchDaoTest.kt` | 旧 DAO 方法签名同步改造（仅签名，**不改期望值**） | 2 |
| 16 | `docs/ARCHITECTURE.md`、`docs/decisions.md` | 语义更新 + RT-003 决策记录 | 4 |

**预计净改动**：生产代码 ≈ 120 行（含 SQL 与 KDoc），测试 ≈ 300 行。**领域层、UI 层、导出层零改动。**
