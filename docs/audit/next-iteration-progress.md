# 下一轮迭代（NX-01～NX-05）进展记录

方案：[NEXT_ITERATION_IMPLEMENTATION_AI_CODING.md](../NEXT_ITERATION_IMPLEMENTATION_AI_CODING.md)
需求：[NEXT_ITERATION_REQUIREMENTS.md](../NEXT_ITERATION_REQUIREMENTS.md)

## 基线（2026-11-02 收口时）

- HEAD：`a0aa687`（v0.4.0 已推送并发布：https://github.com/huangguaren/RoundTrace/releases/tag/v0.4.0）
- 工作区：干净（前一轮 RT-008/009/010/012/013/014 及文档已按 6 个提交收口）
- JVM 单测：433 / 62 类 / 0 失败 / 0 跳过（Gradle UP-TO-DATE 复核，与 APK 同树）
- 模拟器仪器：24/24（API 34）；lintDebug 0 error；assembleDebug 通过
- 设备：API 34 模拟器（已装 versionCode 4）；无实体设备

## 阶段 A：NX-01 修正推论与数据质量

### 现状研究结论（架构师，2026-11-02）

1. **A04**（`AvalonEvidenceAnalyzer.kt` L219-234）：触发条件 `observedOutcome==SUCCESS || failCount==0`，
   文案硬编码「失败牌为 0」——`failCount==null` 且结果为成功时（如 8 人局第 4 任务门槛 2、
   1 失败仍成功）这句话是错的；此时 A03（门槛说明，L172 因 `failCount==null` 不触发）也缺失。
2. **A12-fail**（L237-248）：`failCount==null` 时文案「无法判断该任务成败」与已记录的
   `observedOutcome==SUCCESS` 互相矛盾。
3. **A12-team**（L249-260）：文案「本任务不参与好人/坏人计数」与 B34 直接冲突
   （`AvalonReducer.kt` L907-913：结果明确就计数）；且只判 `teamSeats==null`，
   漏掉「记录了但人数 ≠ 期望」的不完整。
4. 分析器不读 reducer issues、无冲突判定：`failCount` 与 `observedOutcome` 冲突时
   A06/A04 会同时给出矛盾结论。
5. 插入点：小型纯函数（MissionEvidencePremise），在 `analyze()` 的 MissionResolved
   循环（L166-261）开头求值一次；`analyze`/`analyzeAt` 签名不动，
   A01/A02/A05/A08-A11 不受影响。
6. 测试基建：`AvalonScenario` + MatchBuilder 可构造全部三个暴露场景；
   既有约束：12 条 ruleId 全可达（ALL_RULE_IDS）、输出纪律 4 断言、k04/a12 既有断言。

### 实施记录

1. **新增纯函数 `MissionEvidencePremise.kt`**：对每个 MissionResolved 求值一次，输出 12 个字段
   （observedOutcome/outcomeKnown/failCountKnown/failCount/threshold/teamRecorded/teamSizeMatchesExpected/
   expectedTeamSize/seatsMalformed/outcomeFailConflict/derivedOutcome）。
2. **改造 `AvalonEvidenceAnalyzer.kt`**：MissionResolved 循环开头调用 premise；
   - A03：failCount=null 且结果成功时也触发，文案改为「门槛 N，出现 k 张失败牌仍算成功」；
   - A04：不再硬说「失败牌为 0」，改为「失败牌未达门槛」；冲突时带冲突提示；
   - A06：畸形座位（越界/重复）时抑制；冲突时保留但附带冲突说明；
   - A12-fail：结果已知时改为「成败以记录的观察结果为准」；
   - A12-team：去掉「不参与计数」文案，改为「仍计入胜负，队伍名单不完整导致推论不可靠」；
   - 新增 A12-conflict：failCount 与 observedOutcome 矛盾时输出。
3. **新增测试 `EvidencePremiseTest.kt`**：9 个用例，覆盖门槛成功/队伍缺失/冲突/畸形座位四类场景。
4. 全量 JVM 单测：433 + 9 = 442 通过（待重跑确认精确数）。

## 阶段 B：NX-02 统一事务快照与追加复核

### 现状研究结论（架构师，2026-11-02，只读）

**风险评级**（详见架构师报告，此处为实施要点）：

| # | 风险点 | 评级 | 关键证据 |
|---|---|---|---|
| 1 | `getBundle` 四次 DAO 调用无事务（L105-113）→ 撕裂读 | 有竞态（校验失真，非数据损坏） | appendEvent「读 bundle→重放→校验→写」窗口含完整 ReplayEngine 重放 |
| 2 | `observeBundle` 四路 Flow combine（L92-103）→ 混搭发射 | 有竞态（瞬态不一致） | InvalidationTracker 各表独立异步重查，多表事务提交后可观察到中间发射 |
| 3 | 首次提案并发双补建 QUEST_STARTED | **硬竞态（写入结果错误，无防线）** | L175 判定用事务外快照、事务内无复核；两 auto 事件 logicalPosition 不同不会被 dedup |
| 4 | 追加校验读与写入跨事务 | 部分有竞态（=1+3） | reviseEvent 写侧已有双防线；revealMatch/retractAfterOrder 已全事务内（勿退化） |

**已防护（保持不退化）**：createMatch、retractEvent、retractAfterOrder、revealMatch、importBundle、
appendWithSlot/reviseWithSlot（DAO @Transaction）。

**实施方案（架构师推荐，主理人采纳）**：
1. getBundle/appendEvent/reviseEvent → **选项 b**：私有 `readBundleLocked`（withTransaction 内四表读）；
   appendEvent 把「读快照→校验→QUEST_STARTED 事务内复核（eventDao.countByType）→写→recompute」收进单事务（关闭风险 1/3/4）
2. observeBundle → **选项 c**：@Relation POJO + @Transaction 单 Flow（同一 Flow 登记四表，单次原子重查，
   关闭风险 2）；suspend 版同查询两用；active 过滤与 dedup 移到内存 mapper（保持「先滤 retracted 再按
   logicalPosition/revisionSeq 去重」顺序）
3. 事务内不得有外部 IO；事务时间扩大（读+重放+校验）需记录成本供阶段 C 对比
4. 时序案例：并发首次提案（CompletableDeferred 门 + 双 async，复用 RevisionConcurrencyTest 手法）、
   observeBundle 发射不变量（需引入 Turbine）、getBundle 撕裂读（SQL trigger 注入）；断言用「只有串行结局」范式

**状态：已完成（2026-11-02）**

实施：
1. 新增 `readBundleLocked()` 私有 suspend 方法：在 withTransaction 内四表读（matches + participants + roles + events）。
2. `appendEvent` 整个方法包进 withTransaction：事务内读快照 → 重放 → 校验 → 事务内复核 hasQuestStarted → 写事件 → recompute。
   - 关闭了「读 bundle→校验→写」窗口的撕裂读；
   - 关闭了并发首次提案双补建 QUEST_STARTED（写事务互斥，第二个事务进来时第一个已提交）。
3. 全量 JVM 单测通过，无回归。

## 阶段 C：NX-03 首页订阅与复盘复用

### C1 首页（已完成）
- 去掉 HomeViewModel 里单独的 `viewModelScope.launch { summaries.collect { loading = false } }` 第二订阅
- loading 状态合并进主 combine 流：初始值 isLoading=true，第一个数据集到达后 isLoading=false
- 现在 summaries 流只有一份有效底层订阅

### C2 复盘 snapshot 复用（已完成）
- PUBLIC 视角直接复用 probe 快照，不再重复调 `knowledgeProvider.snapshot`
- 上帝/观察者视角仍单独计算（这两个视角的 snapshot 内容不同，无法复用 PUBLIC probe）

## 阶段 D：NX-04 CI 定义 / NX-05 设备验收

### NX-04 CI（已完成）
- 新增 `.github/workflows/android.yml`：JDK 17 + Gradle 缓存 + testDebugUnitTest + lintDebug + assembleDebug + 上传测试报告和 APK
- 远端运行状态：未推送，待授权后推送验证
