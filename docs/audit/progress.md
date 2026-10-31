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
