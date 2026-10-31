# QA Phase 1 独立验收报告（RT-001 / RT-002 / RT-004 / RT-005）

> 验收人：QA（Edward / 严过关）
> 时间：2026-10-31
> 仓库：`C:\Users\HK-info\WorkBuddy\2026-10-30-11-53-57\RoundTrace`
> 受验 HEAD：`9f34e61`
> 工作区状态：仅新增 `app/src/test/java/com/roundtrace/app/audit/`（4 个文件），**未修改任何生产代码、未 commit**

---

## 0. 结论摘要

| 编号 | 声称修复 | 判定 | 一句话依据 |
|---|---|---|---|
| RT-002 | 事件写入按 matchId 归属 | **PASS** | DAO 层 `WHERE id=:id AND matchId=:matchId` + 仓储前置校验 + 撤销未命中即抛并回滚，5 条对抗性测试（含半状态检查）全绿 |
| RT-001 | `revealedAt` 不被状态推进清空 | **PASS** | 状态投影统一走 `updateStatusOnly`；用**可推进时钟**证明 `revealedAt` 在后续 append 后逐字不变，且上帝视角仍开放 |
| RT-004 | 四条写路径统一重算投影 | **PASS** | append / revise / retract / import 四处在同一事务内调用 `recomputeProjectionLocked`；撤销决定胜负的事件后 status 正确回落；导入 FINISHED_GOOD 落库即 FINISHED_GOOD |
| RT-005 | `VisibleEventPolicy` 单一出口 | **PASS（含 1 条 P2 缺口）** | 5 个出口（publicFacts / claimLabels / findings / 默认导出 / 复盘时间线）无一泄漏；但**观察者自己的私有声称不会在 `SeatKnowledge.labels` 体现**（时间线与 A08 结论能看到），属一致性缺口不属泄漏 |

**数字**：全量 `:app:testDebugUnitTest` → **245 用例 / 0 失败**（基线 229 + 本次新增 16）。
**新发现缺陷**：0 个 P0、0 个 P1、2 个 P2、2 个 P3（见 §4）。
**没有修任何生产代码，也没有改工程师的测试期望值。**

---

## 1. A 组：代码级核对（不只看测试名）

### A.1 `MatchEventDao.reviseSupersede` 的执行顺序与回滚语义 — PASS

`app/src/main/java/com/roundtrace/app/core/database/dao/MatchEventDao.kt:111-128`

```kotlin
val affected = markRetracted(matchId, oldEventId)   // :120
check(affected == 1) { ... }                        // :121 → 抛 IllegalStateException
val order = nextOccurredOrder(matchId)              // :124
insert(newEntityProvider(order))                    // :125
```

- 顺序是**先撤销、后插入**：撤销未命中就抛，`@Transaction` 整体回滚，不存在
  「A 局写入替代事件、B 局未撤销」的半状态。
- `markRetracted` 本身带 matchId 限定（`:70-71`），是第二道闸门而不是唯一闸门。
- 实测：`e2e03b_dao_reviseSupersede_rolls_back_when_retraction_misses`
  直接拿 B 局事件 id 走 A 局事务 → 抛异常 → A 局事件行数不变、B 局 `retracted` 仍为 0、B 局行数不变。

### A.2 所有 `updateStatus(` 调用点 — PASS（附 1 条防御性建议）

全库只有 3 处写 `matches.status`（`RoomMatchRepository.kt`）：

| 行 | 方法 | 是否写 `revealedAt` | 评价 |
|---|---|---|---|
| 310 | `revealAllRoles` | 写 `committedAt`（= 揭晓时刻） | 符合语义 |
| 318 | `setStatus` | 透传参数 | **生产代码零调用点**（只有接口 + 实现） |
| 453 | `recomputeProjectionLocked` | **不写**（`updateStatusOnly`） | 正确，RT-001 的核心 |

- 结论：**没有任何一处用 `revealedAt = null` 覆盖已揭晓时间**。
- 防御性建议（P3-1）：`MatchDao.updateRevealedAt`（`:52-56`）与 `MatchRepository.setStatus`
  是**死代码**，但都是「能把 revealedAt 写成 null」的 API。建议删除或加注释说明禁用理由，
  否则下一个调用者很容易踩回来。

### A.3 `recomputeProjectionLocked` 的覆盖面与事务位置 — PASS

`RoomMatchRepository.kt:450-454`，四个调用点：

| 路径 | 调用点 | 是否在同一 `database.withTransaction` 内 |
|---|---|---|
| append | `:186` | 是（`:158` 开启） |
| revise | `:241` | 是（`:228` 开启） |
| retract | `:265` | 是（`:260` 开启，且仅 `rows == 1` 时） |
| import | `:428` | 是（`:422` 开启） |

- 实现只用 `updateStatusOnly`（`:453`），**不碰 revealedAt**。
- 事件写入 DAO 的调用点穷举（`appendWithOrder` / `reviseSupersede` / `markRetracted` / `insertAll`）
  全部落在 `RoomMatchRepository` 的这四个方法内，**没有旁路**。
- 一个已接受的设计权衡：`appendEvent` 里 `validateEvent` 用的 `state` 在事务外读取
  （`:154`），所以返回的 `issues` 反映的是「写入前」的状态；事务内的 `recompute` 会重新读，
  因此 `status` 不会错。只是 `issues` 有极小的 TOCTOU 窗口（并发写同一对局时）。
  单机单用户场景下不构成缺陷，记为观察项。
- 性能观察（P3-2）：每次 append 都做一次全量 replay（O(n)），一局 50 条事件就是 O(n²)。
  事件量级下无感，但若将来做批量补录需要注意。

### A.4 `VisibleEventPolicy` 四分支与四个入口 — PASS

策略本体 `core/replay/VisibleEventPolicy.kt:43-80`：

| 视角 | 行为 | 实测 |
|---|---|---|
| `PUBLIC` | 仅 `visibility == PUBLIC` | `visible_event_policy_matrix_is_as_documented` |
| `OBSERVER_AT_TIME` | `PUBLIC` + **本人**的 `PRIVATE_OBSERVER` | 同上（本人 true / 他人 false） |
| `GOD_AFTER_REVEAL` | 全部 | 同上 |
| 无归属私有事件 | 对所有观察者不可见，仅上帝可见 | 同上（用 `TeamProposed + PRIVATE_OBSERVER` 造无主事件） |

四个入口**全部**经过它，且有对照组证明不是「全空假绿」：

1. `AvalonKnowledgeProvider.kt:160-187` — `publicOnlyEvents` 一次性喂给
   `buildPublicFacts` / `buildSeatKnowledge`(claimLabels) / `buildUnknowns`；
2. `claimLabels` — 见上（`buildSeatKnowledge(eventsAtTime = publicOnlyEvents)`，`:175`）；
3. `AvalonEvidenceAnalyzer.kt:48-52` — `analyze()` 内部再过滤一次（公开 API 防误用）；
4. `ReplayViewModel.kt:179-186` — 复盘时间线按 `effectiveMode` 过滤。

额外核对（两处导出，属 RT-005 的下游而非四入口）：
`VisibilityFilter.kt:25-43` → 被 `MarkdownExporter.kt:60` 与 `MatchJsonCodec.kt:82` 调用。
UI 侧默认值链也已核对：`MatchDetailUiState.kt:33,35`（`includeSecrets=false` /
`includePrivateNotes=false`）→ `MatchDetailViewModel.kt:77-80` → 服务默认 → `VisibilityFilter`，
**默认脱敏不是只在 service 层成立**。

**一处容易漏的调用点已确认安全**：`AvalonKnowledgeProvider.kt:120-124`（主观笔记 beliefs）
读的是**未按可见性过滤**的 `match.events`，但：
- `PUBLIC` 分支返回 `emptyList()`（`:126`）；
- `OBSERVER_AT_TIME` 分支按 `observerSeat == observer` 判归属（`:129`）；
- `GOD` 分支全量（本就允许）。
因此不构成泄漏。这一处的「不走统一策略」是**有意的**（为了保住 B31 的
`availableFromOrder` 回填语义），建议后续补一句注释，避免下一个维护者误判为漏网。

---

## 2. B 组：新增对抗性测试（16 个，全绿）

位置：`app/src/test/java/com/roundtrace/app/audit/`
（`AuditSupport.kt` 为夹具：可推进时钟 + 事件构造器 + 派生状态工具）

| # | 测试类 · 方法 | 证明什么 |
|---|---|---|
| 1 | `CrossMatchWriteIsolationTest.e2e02_retract_cannot_cross_match_boundary` | 跨局撤销返回错误；A 表不变、B 的 `retracted` 仍 0、B 读路径仍可见 |
| 2 | `…e2e03_revise_cannot_cross_match_boundary` | 跨局修订返回 `INVALID_SUPERSEDE_TARGET`；A 不得出现替代事件、B 不得被撤销 |
| 3 | `…e2e03b_dao_reviseSupersede_rolls_back_when_retraction_misses` | DAO 层「先撤销后插入」的回滚：A/B 两张表逐行不变（半状态不存在） |
| 4 | `…e2e02b_dao_markRetracted_is_match_scoped` | `markRetracted` 真的是 matchId 作用域（错配 0 行、正确 1 行） |
| 5 | `…same_match_revise_and_retract_still_work` | 反向保险：同局修订/撤销正常，审计链保留 |
| 6 | `StatusProjectionTest.e2e01_revealedAt_survives_later_event_appends` | 揭晓后把时钟推后 1 小时再追加刺杀：`revealedAt` 逐字不变、status=FINISHED_EVIL、`canOpenGodView=true`、上帝视角确实看到 4 号=莫德雷德 |
| 7 | `…e2e05_retracting_decisive_event_rolls_status_back_to_derived` | 撤销第三次失败任务 → `matches.status` == 重放派生值（IN_PROGRESS），不是残留的 FINISHED_EVIL |
| 8 | `…e2e06_import_recomputes_status_from_events` | 库里先放 1 场对局 → 导出 FINISHED_GOOD JSON → `importFromJson` → 落库 status 即 FINISHED_GOOD、revealedAt 保留、对局数变 2、**已有对局的 status/revealedAt 逐字不变** |
| 9 | `…boundary_retract_every_event_keeps_projection_consistent` | 撤销全部事件后投影仍与重放一致（实测值见 §3） |
| 10 | `PerspectiveLeakExitsTest.e2e07_exit1to3_public_perspective_never_sees_private_claim` | 出口 1/2/3：公开视角的 publicFacts、CLAIMED_MERLIN 标签、A08 结论都不含私有声称；**用公开声称作对照组** |
| 11 | `…e2e07_exit3_observer_perspective_does_see_own_private_claim` | 观察者本人能通过 A08 引用看到自己的私有声称（核对工程师的说法） |
| 12 | `…e2e07_god_perspective_sees_everything` | 上帝视角看到全部（含私有声称 + 4 号=MORDRED） |
| 13 | `…e2e07_exit4_default_export_drops_private_claim` | 出口 4：JSON **和** Markdown 默认导出都不含私有声称（两个 marker：事件 id + 正文），勾选后才出现；公开声称必须在默认导出里 |
| 14 | `…e2e07_exit5_replay_timeline_hides_private_claim_in_public_mode` | 出口 5：走真实 `ReplayViewModel` 派生链 —— 公开视角时间线无私有声称，观察者视角有 |
| 15 | `…b31_private_note_respects_available_from_order` | B31 回归：回填笔记按其 `availableFromOrder` 可见性生效，且不进 publicFacts |
| 16 | `…visible_event_policy_matrix_is_as_documented` | 策略四分支 + 无主事件安全侧默认 |

### 运行记录

```
:app:testDebugUnitTest --no-daemon --rerun-tasks   → BUILD SUCCESSFUL，229 tests / 0 failed（基线复现）
:app:testDebugUnitTest --tests "com.roundtrace.app.audit.*"  → 16 tests / 0 failed
:app:testDebugUnitTest --no-daemon（最终全量）      → BUILD SUCCESSFUL，245 tests / 0 failed / 0 skipped
                                                      29 个测试类（基线 26 类 + 审计 3 类）
```

> 最终一次全量回归是在补齐 `e2e06` 的「不覆盖已有对局」断言之后跑的，结果仍为 245/0。

### 关于「观察者可见自己的私有声称」这个绕行是否成立

工程师的说法是「断言落在 A08 分析引用上」。实测（测试 #11 + 打印）：

```
[AUDIT] 观察者(4) 视角：publicFacts 含私有声称=false，seat4.labels=[SELF]
```

- **时间线出口成立**：`ReplayViewModel` + `VisibleEventPolicy` 让 4 号在自己的视角看到这条事件（测试 #14 断言通过）；
- **结论出口成立**：A08 会为这条私有声称生成「4 号『梅林』只是声称」（测试 #11 通过）；
- **座位标签出口不成立**：`seat4.labels` 只有 `SELF`，没有 `CLAIMED_MERLIN`。

原因：`AvalonKnowledgeProvider.kt:175` 把 `publicOnlyEvents` 同时传给了 `claimLabels`，
而 `OBSERVER_AT_TIME` 分支没有为「本人可见的私有声称」补一次标签。

**判定：不是泄漏（安全方向是对的），但三个出口对同一事实给出不一致的呈现。**
定为 **P2-1**（见 §4），不算 RT-005 失败。

---

## 3. B.7 边界：撤销全部事件之后

实测（测试 #9 打印，未做猜测性断言）：

```
[AUDIT] 撤销全部事件后：matches.status=DRAFT，replay 派生=DRAFT
```

- 投影与派生**一致**（这是硬要求，已断言）；
- 但产品语义上会出现：**一场「确实打过」的对局回退成 DRAFT**，
  `MatchStatusFilter.IN_PROGRESS` 包含 DRAFT → 首页会把它显示成「进行中/草稿」。
  这与「草稿 = 还没开始」的语义冲突（用户看到的是「我明明记过」）。

**判定：符合 RT-004 的技术要求（投影=派生），但暴露一个产品语义缺口。**
定为 **P2-2**，建议：要么在 `matches` 增加 `everStarted` 标记，要么 DRAFT 只在
`eventCount == 0 && 从未有事件` 时使用（需要一个「曾有过事件」的持久位）。
不要求本阶段修，但要有决策记录。

---

## 4. 缺陷清单

| 级别 | 编号 | 位置 | 现象 | 影响 | 建议修法 |
|---|---|---|---|---|---|
| P2 | P2-1 | `core/knowledge/AvalonKnowledgeProvider.kt:160-177` | 观察者本人的 `PRIVATE_OBSERVER` 声称不会产生 `CLAIMED_*` 标签（`seat4.labels=[SELF]`），但同一条事件在时间线与 A08 结论里可见 | 同一事实三种呈现不一致；「我的当时视角」里座位徽标少了「声称梅林」 | 给 `buildSeatKnowledge` 传两份事件：`publicOnlyEvents` 用于公开标签，另加 `visibleToObserverEvents`（`VisibleEventPolicy.forPerspective(mode=OBSERVER_AT_TIME, observerSeat)`）用于本人标签；注意别把本人私有声称算进 `publicFacts` |
| P2 | P2-2 | `core/rules/avalon/AvalonReducer.kt` 初始 status + `MatchStatus.DRAFT` 语义 | 事件被全部撤销后 status 回到 `DRAFT`，首页当作「进行中草稿」展示 | 用户会看到「记过的对局变成空白草稿」，且无法从状态区分「真草稿」与「被撤空的局」 | 引入 `matches.everStarted`（或 `eventRevisionCount > 0`）参与首页投影文案；本阶段可先在 `docs/decisions.md` 记一条决策 |
| P3 | P3-1 | `core/data/MatchRepository.kt:61`、`core/database/dao/MatchDao.kt:52-56` | `setStatus`（生产零调用）与 `updateRevealedAt` 是死代码，且都能把 `revealedAt` 写成 null | 未来调用者可能重新引入 RT-001 | 删除，或在 KDoc 里显式写「禁止用于事件推进路径」 |
| P3 | P3-2 | `core/export/MatchJsonCodec.kt:75-90` | 导出只含 `bundle.events`（已过滤 `retracted`），因此 `supersedesId` 常指向不存在的事件、撤销历史在导入后丢失 | 「修订链」不可跨设备复原；审计追溯能力下降 | 若需要保真：在 `ExportBundle` 增加 `retractedEvents`（或 `includeAuditTrail` 选项），并在导入时保留 `retracted=true` 行 |

> 无 P0 / P1。RT-002 早期那个「跨对局写入」的 P0 已被 `ee20e3d` 真正修掉（不是只加测试）。

---

## 5. C 组：兼容性检查

| 项 | 结果 | 证据 |
|---|---|---|
| Room schema 版本仍为 3 | **PASS** | `RoundTraceDatabase.kt:42`（`version = 3`） |
| `app/schemas/` 无 4.json | **PASS** | 目录只有 `1.json` / `2.json` / `3.json` |
| 本阶段无新增迁移 | **PASS** | 同上；本次只新增测试文件 |
| JSON v1 导入导出往返 | **PASS** | `JsonCodecTest`（13）+ `ImportValidationTest`（7）+ `MarkdownExporterTest`（11）全绿；我另加 `e2e06` 走真实 Room 落库 |
| 未知 schema 可读提示 | **PASS** | `JsonCodecTest."unknown schema version is rejected"`、`ImportValidationTest."unknown schema version is rejected"` |
| `docs/decisions.md` B33/B34 未改动 | **PASS** | `git status` 显示工作区仅多出 `app/src/test/java/com/roundtrace/app/audit/`；B33 在 `decisions.md:507`、B34 在 `:517` |
| B34「结果明确即计数」未被改回旧规则 | **PASS** | `AvalonReducer.kt:907-918` 已无 `if (verified)` 包裹；`grep "if (verified)"` 零命中；`AvalonStateMachineTest."R11 未核实任务仍按既定结果计数并标记待复核"` 在场且通过 |

---

## 6. 我实际运行了什么 / 没运行什么

**实际运行（本机执行，非引用他人结论）**

```
export JAVA_HOME=/c/Users/HK-info/android-build/jdk
export ANDROID_HOME=/c/Users/HK-info/android-build/sdk
gradle :app:testDebugUnitTest --no-daemon --rerun-tasks      → BUILD SUCCESSFUL，229/0（基线复现）
gradle :app:testDebugUnitTest --tests "com.roundtrace.app.audit.*"  → 16/0
gradle :app:testDebugUnitTest --no-daemon                    → BUILD SUCCESSFUL，245/0
```

- 结果数字取自 `app/build/test-results/testDebugUnitTest/*.xml` 的 `tests/failures/errors/skipped` 求和，
  不是 Gradle 控制台估计值。
- 全部审计测试是 **JVM**：Robolectric（`@Config(sdk=[34])`）跑真实 Room + SQLite，
  纯 Kotlin 部分无 Android 依赖。

**没有运行（如实声明）**

- **instrumented test（`app/src/androidTest`）→ NOT RUN**：无设备/模拟器。
  因此真机上的事务并发、进程被杀后的恢复、`ShareLauncher` 实机分享**未验证**。
- **UI 层（Compose）未做像素级验证**：本审计只测到 ViewModel/领域层，
  没有 Compose 测试，也没有人工点按。P2-1 的影响面（座位徽标）是从代码+数据快照推出来的，
  没有在界面上肉眼确认。
- **性能未测**：P3-2 的 O(n²) 是读代码得出的，没有 profiling 数据。
- 我没有验证 `a6da7c4` 之前的历史提交，只验当前 HEAD `9f34e61`。

---

## 7. 剩余风险

1. **跨进程/进程被杀路径未覆盖**（无设备）。`revealedAt` 与 `status` 的持久化正确性靠
   Robolectric 的 in-memory + 文件级 SQLite 语义近似，不等于真机。
2. **并发写同一对局**：`appendEvent` 的 `validateEvent` 读在事务外（§A.3 的 TOCTOU）。
   单用户单线程 UI 下安全，但「导入同时点记录」这类场景没有测试。
3. **P2-1 的修复会有回归风险**：一旦给 `claimLabels` 补「本人可见的私有声称」，
   很容易顺手把 `publicFacts` 也放宽。建议修复时同时加一条「private claim 永不进 publicFacts」的测试。
4. **P2-2 属于产品决策**，不是技术缺陷；如果主理人认为「撤空即回到草稿」可接受，
   应写入 `decisions.md` 而不是留白。
5. **导出保真度（P3-2）**：如果未来要支持跨设备接力，撤销/修订链的丢失会变成真问题。

---

## 附：本次新增文件

```
app/src/test/java/com/roundtrace/app/audit/AuditSupport.kt              （夹具：MutableClock / AuditEvents / derivedStatus / bundleOf）
app/src/test/java/com/roundtrace/app/audit/CrossMatchWriteIsolationTest.kt  （5 用例，RT-002）
app/src/test/java/com/roundtrace/app/audit/StatusProjectionTest.kt          （4 用例，RT-001 / RT-004）
app/src/test/java/com/roundtrace/app/audit/PerspectiveLeakExitsTest.kt      （7 用例，RT-005 / B31）
docs/audit/qa-phase1.md                                                      （本文件）
```

生产代码、工程师既有测试的断言值、`docs/decisions.md` **均未改动**；未执行 `git commit`。
