# RoundTrace 实现决策记录（decisions.md）

> 本文件记录**工程实现与上游文档（PRD / TDD / ARCHITECTURE / DESIGN_SPEC）之间的差异、补充与裁决理由**。
> 覆盖 `docs/ARCHITECTURE.md` §9.4 要求的 16 条，并追加本轮（M0+M1）实际遇到的决定。
> 记录规范：**决策 → 理由 → 代价 / 影响**。

---

## A. ARCHITECTURE §9.4 要求的 16 条

### 1. 手动 DI 而非 Hilt

**决策**：使用 `RoundTraceApplication.container: AppContainer`（`lazy` 单例）+ `di/ViewModelFactories.kt`（M2 添加），不引入 Hilt。

**理由**
1. 首次搭建环境时本机无 JDK / SDK / Gradle，Hilt 会把「AGP / Kotlin / KSP / Compose / Room」五元版本矩阵扩成六元，首次构建失败概率显著上升。
2. Hilt 2.52 对 Kotlin 2.0.21 需要 KSP2 或 KAPT；KAPT 在 Kotlin 2.0 下有告警，KSP2 又与 Room 2.6.1（建议 KSP1）冲突——正是最容易踩坑的链路。
3. 本项目只有 1 个 Android 组件入口（单 Activity）+ 8 个 ViewModel，手写 DI 的维护成本低于版本风险成本。
4. TDD §1.1 原文允许：「如果首次安装环境不兼容，可以改为清晰的手动 DI；必须记录选择理由」。

**代价**：新增 ViewModel 必须手工在 `di/ViewModelFactories.kt` 登记；没有编译期注入图校验。

### 2. Compose 编译器改用插件

**决策**：`app/build.gradle.kts` 使用 `alias(libs.plugins.kotlin.compose)`（`org.jetbrains.kotlin.plugin.compose`，版本 = Kotlin 2.0.21），**不写** `composeOptions { kotlinCompilerExtensionVersion }`。

**理由**：Kotlin 2.0 起 Compose 编译器随 Kotlin 版本发布，`composeOptions` 已废弃，写错会直接编译失败（ARCHITECTURE §0.1 / T02⑥）。

### 3. Gradle 8.9 wrapper 与 AGP 8.5.2 配对

**决策**：`gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 固定 `gradle-8.9-bin.zip`；`gradle-wrapper.jar` 由 `gradle wrapper --gradle-version 8.9` 生成后提交（**不手写 jar**）。

**理由**：AGP 8.5.2 要求 Gradle ≥ 8.7；8.9 是与该 AGP 同期验证过的稳定组合。

**说明**：本机实际安装的是 Gradle 8.7（用于生成 wrapper 与本地验证），wrapper 目标版本仍按文档固定 8.9。

### 4. `MatchStatus` 不含 `INCOMPLETE`

**决策**：`MatchStatus` 只有 `DRAFT / IN_PROGRESS / AWAITING_ASSASSINATION / FINISHED_GOOD / FINISHED_EVIL / ABANDONED`；数据完整性用独立的 `IntegrityStatus { OK, NEEDS_REVIEW, INCOMPLETE }` + `issues` 表达。

**理由**：生命周期状态与数据质量是正交维度。混在一起会让 reducer 的穷举分支爆炸，且「未核实」不应阻止继续录入。

### 5. 引入 `WinReason.NO_ASSASSIN_PRESENT`

**决策**：自定义变体去掉刺客时，三次任务成功直接 `FINISHED_GOOD / NO_ASSASSIN_PRESENT`。

**理由**：否则会静默进入 `AWAITING_ASSASSINATION` 且没有任何出口（卡死）。由 `AvalonReducerTest`（`AvalonAssassinationTest`）钉住。

### 6. 上帝视角判据是数据条件而非状态条件

**决策**：`canOpenGodView = (match.revealedAt != null) && status ∈ {AWAITING_ASSASSINATION, FINISHED_GOOD, FINISHED_EVIL}`。

**理由**：PRD 要求「结局揭晓后」，DESIGN_SPEC §7 又只约束「入口不早于刺杀阶段」。用「是否已揭晓」这个数据条件可以同时满足两份文档。

**本轮状态**：判据由 M4（T51/T57）实现，M1 只提供 `ReconstructedMatch.revealedAt` 字段。

### 7. 五连否决的累计与重置边界

**决策**
- `R` 计入的是**否决决议**次数，不是提案次数；
- `QUEST_STARTED` 时 `R = 0`；
- 提案 **APPROVED** 时 `R = 0`（此后本任务不可能再有否决）；
- `MISSION_RESOLVED` 后 `R = 0`；
- `R >= 5` 时**立即**终局 `FINISHED_EVIL / FIVE_CONSECUTIVE_REJECTS`，`phase = ENDED`，第 6 次提案一律被拒。

**理由**：TDD §4.1 与 PRD §6.1 都要求「同一任务内；任务完成后重置」；TASKS.md T20③ 明确要求「任务结算后归零」，故在 `MISSION_RESOLVED` 也显式归零（与 §6.4 的「下一次 QUEST_STARTED 重置」等价且更保守，不影响任何可观测结果）。

### 8. 未核实任务不计入胜负计数

**决策**：`QuestState.verified == false` 的任务不计入 `goodSuccessCount` / `evilFailureCount`。

**理由**：PRD FR-03「不完整录入不得宣称合法任务已执行」+ TDD §3.3。`verified` 由 reducer 计算，**不采信事件里存的 `verified` 字段**（事件是历史，规则要能重算）。

### 9. 修订采用「新事件 + 旧事件 retracted」双写

**决策**：`EventRevisionPolicy` 判定通过后，写入新事件（`supersedesId = 旧 id`）并在同一事务把旧事件 `retracted = 1`。**读路径只按 `retracted` 过滤**，`supersedesId` 仅作审计链。

**理由**：单条件过滤无歧义、一条 SQL 覆盖、易测（T27/D03）。

### 10. 队长顺延公式与「不阻断只提示」

**决策**：`nextLeader = (currentLeader % playerCount) + 1`；用户手工指定不同座位时记 `LEADER_NOT_EXPECTED`（INFO），**不阻断**。

**理由**：补录场景 PRD 明确允许修订；规则不能覆盖现场事实。

**实现边界**：`LEADER_NOT_EXPECTED` 目前只在第 1 个任务（有 `initialLeaderSeat` 参照）时产生，后续任务没有「规则期望队长」的独立事实源，故不臆造断言。

### 11. 私有事件在 Repository 不过滤、由导出/视角层过滤

**决策**：`PRIVATE_NOTE_ADDED` 事件在 Repository 层**照常返回**；对外脱敏由 `ImportExportService.VisibilityFilter`（T60）负责，展示边界由 `KnowledgeProvider`（T51）负责。

**理由**：本机是单人使用（记录者 = 所有者）。若在 SQL 层过滤，记录者自己也看不到自己写的笔记（TDD §3.1-6 的要求由上述两层满足，而不是靠 WHERE）。

### 12. `QUEST_STARTED` 自动补建

**决策**：用户首次提交某任务提案时，若该任务无 `QUEST_STARTED`，仓储在同一事务里先补一条（`leaderSeat` = 用户选的队长）。

**理由**：现场不能强迫用户多点一次「开始任务」，但时间线需要任务节点。

**本轮状态**：由 M2/T35 实现；M1 的 reducer 对「没有 QUEST_STARTED 就提案」会给 `QUEST_OUT_OF_ORDER` 而不是静默接受。

### 13. Navigation 用字符串路由

**决策**：`Routes` 集中定义字符串常量与带参构造函数，不用 type-safe serialization 路由。

**理由**：少引入一条 kotlinx-serialization 与 Navigation 的耦合，降低首次构建风险。代价是参数类型靠约定（本项目只有 `matchId` / `eventId` 两个参数）。

### 14. `analysis_reports` 在 P0 不使用

**决策**：表结构与 DAO 保留（M2 建表），P0 复盘全部实时计算。

**理由**：实时计算的结果永远是事实源；缓存只会在 P1 需要性能时启用，且不得成为事实源。

### 15. 标准 5–10 人预设角色清单

**决策**（PRD §6.1 只给阵营数，未给角色清单，此处补齐并被单测钉住）
- 5–10 人：固定 1 梅林 + 1 刺客 + 1 派西维尔；
- 8/9/10 人：再加 1 莫甘娜 + 1 莫德雷德；
- 其余按阵营配额补齐：好人用 `LOYAL_SERVANT`，坏人用 `MINION`。

合计校验：5p(3好/2坏)、6p(4/2)、7p(4/3)、8p(5/3)、9p(6/3)、10p(6/4) 全部与人数相等。
`AvalonPresetMatrixTest` 逐人数断言了「总数 == 人数」与「坏人配额 == 矩阵值」。

### 16. 版本目录的最终实际版本

见下表（`gradle/libs.versions.toml`，全部为精确版本，无 `+` / `latest.release`）：

| 组件 | 版本 | 备注 |
|---|---|---|
| AGP | 8.5.2 | 需 Gradle ≥ 8.7 |
| Kotlin | 2.0.21 | Compose 编译器随 Kotlin |
| KSP | 2.0.21-1.0.25 | KSP1（未开启 `ksp.useKSP2`） |
| Compose BOM | 2024.10.01 | ui 1.7.4 / material3 1.3.0 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26 | |
| Java toolchain | 17 | `jvmToolchain(17)` + `compileOptions` |
| Room | 2.6.1 | `ksp` 处理器 |
| Navigation Compose | 2.8.4 | 字符串路由 |
| DataStore | 1.1.1 | Preferences |
| kotlinx-serialization-json | 1.7.3 | 仅 payload/导出 |
| Lifecycle / Activity / Core KTX | 2.8.7 / 1.9.3 / 1.13.1 | |
| JUnit / coroutines-test | 4.13.2 / 1.9.0 | 单测 |
| androidx.test ext-junit / runner / espresso | 1.2.1 / 1.6.2 / 3.6.1 | 仪器测试 |

---

## B. 本轮（M0 + M1）新增的裁决

### B1. 额外加入阿里云 Maven 镜像（环境适配，非设计变更）

**决策**：`settings.gradle.kts` 的 `dependencyResolutionManagement` 在 `google()` / `mavenCentral()` 之外增加
`https://maven.aliyun.com/repository/central` 与 `.../google`（顺序在 `mavenCentral` 之前，`mavenCentral` 保留兜底）；
`pluginManagement` 同理增加 `gradle-plugin` / `public` 镜像。

**理由**：本机构建环境需要镜像加速，否则依赖下载极易超时。
**影响**：与 TASKS.md T01① 「仅声明 google()/mavenCentral()」字面不符，按主理人指令执行。功能与产物完全一致。

### B2. Tab 的「记录 / 复盘」两个页面在本轮只提供**真实空态**，不提供占位页

**决策**：`Routes.RECORDS` / `Routes.REVIEW` 渲染 `EmptyState`：
- 记录：「还没有进行中的对局 / 创建一局后即可在现场记录提案、投票与任务结果 / 开始一局」
- 复盘：「暂无可用分析 / 需要至少一条已确认的任务记录 / 返回记录」（DESIGN_SPEC §5 逐字）

**理由**：TASKS.md 明确禁止占位页；但底部导航必须 3 个 Tab 且点击后不能空白。空态是 DESIGN_SPEC §5 强制要求的真实 UI 状态（「所有页面必须有：加载态、空态、错误态」），按钮绑定真实导航行为。
**影响**：空态组件与文案在 T41/T57 接入真实数据后继续复用，不产生返工。

### B3. 两个空的 Tab 页面写在 `RoundTraceNavHost.kt` 内（private composable）

**决策**：不新增 `feature/records/...`、`feature/review/...` 目录，直接把空态渲染写在 NavHost 的 `composable` 块里。

**理由**：T09 的文件清单只有 3 个文件；本轮这两个页面没有 ViewModel 与状态，单独建文件会造成后续 T41/T57 重复创建同名文件。等 M2/M4 有真实实现时再落地正式文件。

### B4. `GameConfig` / `RoleSlotSpec` 不做 `require` 断言

**决策**：删掉「角色数量不得为负」的 `require`，让负数量能进入 `validateSetup` 并被报成 `ROLE_SLOT_NEGATIVE`。

**理由**：领域层对**脏数据**一律「记录 + 报 issue」，不抛异常（与 T20⑤「reducer 不抛异常」一致）。若在构造期抛异常，从数据库/JSON 读到历史脏数据会直接崩溃。
**例外**：`GameOutcome` 的 `require(winner ∈ {GOOD, EVIL})` 保留（T15④ 明确要求），因为该对象只在规则内部构造。

### B5. `RecordPhase` / `IntegrityStatus` 放在 `Enums.kt`

**决策**：ARCHITECTURE §8.4 把这两个枚举列在 `GameState.kt`，但 §2.1 把它们列在 `Enums.kt`；按 §2.1 + TASKS.md T11（「Enums.kt 实现 §2.1 全部枚举」）执行。

**理由**：全部枚举集中一处，避免「同一个枚举在两张表里出现」的歧义。

### B6. 新增 `core/util/IdGenerator.kt`（未在原文件清单中）

**决策**：新增 `IdGenerator` / `UuidIdGenerator` / `SequentialIdGenerator` / `fixedClock`。

**理由**：T08 要求 `AppContainer` 持有 `Clock` 与 `IdGenerator`，T14 要求 `EventFactory` 用它们填充 `eventId` / `recordedAt`。原文档未指定这两个类型的落地文件，最小合理落点是 `core/util`。纯 Kotlin（`java.time` + `java.util.UUID`），无 Android 依赖。

### B7. 新增 `core/model/Knowledge.kt`（只含 `NightKnowledge`）

**决策**：M1 只需要 `NightKnowledge`（T19⑤「输出 NightKnowledge 为纯数据」），但 T50（M4）要求 `Knowledge.kt` 承载完整视角模型。
**做法**：现在创建 `Knowledge.kt` 并只放 `NightKnowledge`，M4 在同一文件追加其余类型，避免两处定义 `NightKnowledge`。

### B8. 新增 `AvalonRules.validateTeamDraft`

**决策**：在 `AvalonRules` 上增加 `validateTeamDraft(teamSeats, playerCount, questNo)`，把队伍校验降级为 WARNING。

**理由**：R13 要求「草稿态可保存、确认态不合法」，T46 要求「允许只选部分成员后暂存」。若不提供该入口，测试只能用「草稿没有校验路径」这种空断言冒充。该方法是 `AvalonRules` 的**扩展**（不加入 `GameRules` 接口，接口保持 T16 的逐字签名）。

### B9. 新增 `AvalonRules.decideProposal`

**决策**：把 §6.3 的可判定性算法作为 `AvalonRules` 的公开方法暴露（内部实现仍在 `AvalonStateMachine`，单一事实源）。

**理由**：测试需要直接覆盖三分支，而 reducer 走完整事件流才能到达该分支会让测试变得又长又脆。

### B10. 新增测试文件 `test/util/AvalonFixtureTest.kt`

**决策**：新增一个测试，用 `Json.parseToJsonElement` 解析 fixture 并逐字段断言。

**理由**：T23③ 的验收标准要求「fixture JSON 可被 kotlinx-serialization 解析且字段与 TDD §11 一致」，该标准必须被自动化钉住，否则无法证明。刻意不使用 `@Serializable` DTO：fixture 是外部输入契约，用 `JsonElement` 能精确断言「字段存在且类型正确、缺失字段是 null 而不是 0」。

### B11. `TimelineBuilder` 产出中文文案

**决策**：`TimelineEntry.title` / `detail` / `unrecordedFields` 是中文展示字符串，直接由 `TimelineBuilder` 生成。

**理由**：ARCHITECTURE §8.6 把 `TimelineEntry` 定义为「时间线 **UI 中间模型**」，其职责就是给记录页与复盘页共用渲染。D6 的约束对象是 `RuleIssue` / `EvidenceFinding`（携带 code/参数），不含 `TimelineEntry`。若把每条时间线也做成 code+args，会让 UI 层退化成一个巨型 `when`。
**边界**：`unrecordedFields` 目前是中文字段名；若后续需要 i18n，再改成 key + `stringResource` 映射（改一处即可）。

### B12. `ReduceEvent` 的 `verified` 字段「存而不用」

**决策**：`MissionResolved.verified` / `TeamProposalResolved.verified` 字段保留（持久化契约需要），但 reducer **重新计算** `verified`，不采信事件里的值。

**理由**：事件是历史快照，规则要能在修订后重算。若采信旧值，T27 的「不保留旧结论」就无法实现。

### B13. `MATCH_ABANDONED` 不产生 `GameOutcome`

**决策**：弃局只把 `status = ABANDONED`、`phase = ENDED`、`integrity = INCOMPLETE`，`outcome` 保持 `null`。

**理由**：`GameOutcome.winner` 只允许 GOOD/EVIL（T15④）。弃局没有胜方，用 `WinReason.ABANDONED` 硬造一个 winner 会污染统计。UI 层直接读 `status == ABANDONED` 展示「已弃局」。

### B14. `Material 3` 缺少的语义位用派生色补齐

**决策**：`onError` / `onSecondary` / `onErrorContainer` / `outlineVariant` 等 DESIGN_SPEC 未给出的槽位，在 `Color.kt` 中用与主色一致的深/浅派生值补齐，并注释标注「派生值」。

**理由**：`lightColorScheme()` / `darkColorScheme()` 需要完整 ColorScheme；缺项会用 Material 默认的紫色基线，破坏设计契约。所有派生值仍集中在 `Color.kt`（D5 唯一允许字面量的文件）。

### B15. `dynamicColor` 参数保留但恒为 false

**决策**：`RoundTraceTheme(darkTheme, dynamicColor = false, content)` 保留 `dynamicColor` 参数但**不使用**（显式表达禁止）。

**理由**：T07③ 要求「`dynamicColor` 默认 false 且禁止动态取色」；同时 `androidx.compose.material3.dynamicDarkColorScheme` 需要 API 31 且会破坏 success/unknown 等自定义语义位。保留参数是为了让「禁止」这件事在 API 上可见，而不是靠注释。

### B16. `RoundTraceScaffold` 增加 `showBottomBar` 参数

**决策**：工作流页面（创建/记录/揭晓/详情）可以关掉底部栏。

**理由**：DESIGN_SPEC §5 要求「底部主操作固定」，工作流页面的底部主操作会与 Tab 栏冲突。参数默认 `true`，不影响主页面。

---

## C. 主理人裁决（优先级最高，覆盖本文档 A 部分与 ARCHITECTURE / TASKS 的对应条目）

### B17. 标准预设角色构成按官方规则手册更正 —— **作废 ARCHITECTURE §9.3-4 与 TASKS.md T17 的旧描述**

**裁决来源**：主理人 2026-10-30 指令，依据《The Resistance: Avalon》官方规则手册。

**问题**：PRD §6.1 只给了阵营人数，**未定义角色构成**；ARCHITECTURE §9.3-4 与 TASKS.md T17 原写的
「5–10 人固定 1 梅林 + 1 刺客 + 1 派西维尔，8 人以上再加 1 莫甘娜 + 1 莫德雷德」是**臆测**，
其中「5、6 人局含派西维尔」与官方规则不符。

**采纳的官方构成**（`AvalonPreset.STANDARD_PRESETS` 常量表，逐格一致）：

| 人数 | 好人 | 坏人 | 好人角色 | 坏人角色 |
|---|---|---|---|---|
| 5 | 3 | 2 | 梅林×1、忠臣×2 | 刺客×1、爪牙×1 |
| 6 | 4 | 2 | 梅林×1、忠臣×3 | 刺客×1、爪牙×1 |
| 7 | 4 | 3 | 梅林×1、派西维尔×1、忠臣×2 | 刺客×1、莫甘娜×1、爪牙×1 |
| 8 | 5 | 3 | 梅林×1、派西维尔×1、忠臣×3 | 刺客×1、莫甘娜×1、莫德雷德×1 |
| 9 | 6 | 3 | 梅林×1、派西维尔×1、忠臣×4 | 刺客×1、莫甘娜×1、莫德雷德×1 |
| 10 | 6 | 4 | 梅林×1、派西维尔×1、忠臣×4 | 刺客×1、莫甘娜×1、莫德雷德×1、**奥伯伦×1** |

三条关键结论：
1. **5、6 人局没有派西维尔**（派西维尔从 7 人局才开始出现）；
2. **8 人局与 TDD §11 测试夹具完全一致**（1 梅林 / 2 忠臣 / 3 莫甘娜 / 4 莫德雷德 / 5 刺客 / 6 忠臣 / 7 派西维尔 / 8 忠臣）——该基准已由单测逐角色比对；
3. **10 人局第 4 个坏人是奥伯伦**（官方推荐），不是爪牙。奥伯伦的两条例外已实现并有测试：
   - `AvalonRoleKnowledge` 中梅林的 `visibleEvilSeats` **排除奥伯伦**（同时排除莫德雷德）；
   - 坏人互认的 `evilTeammates` **排除奥伯伦**，且奥伯伦自己不参与互认。

**实现方式（按裁决要求）**：角色构成用 `AvalonPreset.STANDARD_PRESETS` **常量表**表达，
`standardPreset()` 只做「取表 + 自洽断言」，**没有任何 `if (playerCount >= 8)` 之类的分支**。
「是否加莫甘娜/莫德雷德、10 人用不用奥伯伦」属于**预设**而非**规则**：
`AvalonRules` 的判定逻辑（任务人数、失败牌门槛、半数判定、终局）完全读矩阵与阵营数，不感知角色构成。

**测试钉住**（T17 / T24）：
- `AvalonPresetMatrixTest` 对**6 行各写一条 `@Test`**，逐条断言「角色计数 + 阵营计数（与 TDD §4.1 矩阵一致）+ 唯一性 + 总数等于人数」；
- 另加「5/6 人局 PERCIVAL 必须为 0」「8 人局与 TDD §11 夹具逐角色一致」「10 人局 OBERON==1 且 MINION==0」等针对性断言；
- 新增 `AvalonRoleKnowledgeTest`（11 个用例）覆盖奥伯伦的夜间知识两条例外。
- 新增回归：`T17 角色构成属于预设而非规则5人局也能通过 setup 校验` —— 防止 `validateSetup` 因「缺少派西维尔」误报 BLOCKING。

**遗留（需架构师处理）**：`docs/ARCHITECTURE.md` §9.3-4 与 `docs/TASKS.md` T17 表格里仍是旧描述，
本文件为权威更正；建议架构师在下一轮同步这两处，避免 M2+ 实现者再次按旧表编码。

### B18. Gradle wrapper 改为 Gradle 8.7 + 腾讯云镜像

**决策**：`gradle/wrapper/gradle-wrapper.properties`

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip
```

**理由**：`services.gradle.org` 在本机实测约 **16KB/s**，首次下载 Gradle 8.9（约 130MB）会长时间卡死
（实测 9 分钟未完成，已中止）。主理人要求改用腾讯云镜像；AGP 8.5.2 要求 Gradle ≥ 8.7，**8.7 完全兼容**。
实测镜像下载 + 启动：`./gradlew --version` **11 秒**返回 `Gradle 8.7`。

**与 TASKS.md T03① 的差异**：T03① 原写 `gradle-8.9-bin.zip`，此处按主理人指令改为 `8.7`。
这是**环境适配**，不改变任何构建产物语义；`gradle-wrapper.jar` 仍由 `gradle wrapper` 生成后提交（未手写）。

**`local.properties`**：按主理人给的形式写入

```properties
sdk.dir=C\:\\Users\\HK-info\\android-build\\sdk
```

该文件已被 `.gitignore` 忽略，不进入仓库。
---

## D. M2 + M3（T28–T49）实现裁决

### B19.（**已被 B28 推翻**）JVM 侧不引入 Robolectric，`occurredOrder` 的真实 1..N 交给仪器测试

> ⚠️ 本条结论有误，已由主理人 2026-10-30 实测推翻并改由 **B28** 执行。
> 错误点：我判断「本机 `repo1.maven.org` 不通 ⟹ Robolectric 不可行」，
> 漏掉了 Robolectric 支持 `robolectric.dependency.repo.url` 自定义下载源——
> 指向阿里云镜像后 150MB 的 `android-all-instrumented` 十几秒就拉完了。
> 保留原文仅为审计，**不要按本条执行**。

<details>
<summary>原始（错误）决策记录</summary>

**决策**：不引入 Robolectric。JVM 单测只验证「盖章契约」——
`EventCodec.encode(event, matchId, occurredOrder)` 把 order 写进**列**、payload 里**不含** order；
真实的 `1..N` 连续递增由 `androidTest/MatchDaoTest` 与 `RoomMatchRepositoryTest` 保证。

**理由**：Room 依赖 Android SQLite 运行时，纯 JVM 跑内存库必须用 Robolectric，
而 Robolectric 会在**测试运行期**自行到 Maven Central 拉 `android-all-instrumented`（约 100MB+）。
本机 `repo1.maven.org` 实测完全不通，加进来会让整个单测套件无法运行——比「少一条 JVM 断言」严重得多。

**代价**：`occurredOrder` 的端到端验证只能靠有设备时执行 `connectedDebugAndroidTest`。
本轮无设备，故该项**未验证**，已在 `TEST_REPORT.md` §7.5 如实标注。

</details>

### B20. `match_events` 没有 `questNo` 列，相关 SQL 调整

**决策**：
1. `questNo` 放进 `payloadJson`（表结构按 ARCHITECTURE §4.5，未加列）；
2. **删除** `MatchEventDao.markQuestRetracted`（ARCHITECTURE §5.4 里它引用了不存在的 `questNo` 列，
   Room 编译期会直接报 SQL 校验失败）；
3. TS「自动补建 `QUEST_STARTED`」的判断改为**在仓储里用已加载的事件列表判断**，
   不用 SQL `LIKE` 匹配 JSON（SQLite 的字符串拼接是 `||` 而不是 `+`，写 `LIKE` 拼接极易出错）。

**理由**：宁可少一个「清空某任务」的便捷方法，也不能让 DAO 带着一条编译不过的 SQL。

### B21. `ImportBundle` 暂定义在 `core/data`

**决策**：`ImportBundle(config, participants, roleAssignments, events, revealedAt)` 放在
`core/data/MatchBundle.kt`，等 M5（T59）的 `ExportBundle` 落地后再对齐字段。

**理由**：T35⑥ 要求实现 `importBundle`，而 T59 才定义导出模型。先定义一个**只含导入必需字段**的最小类型，
避免为了「对齐未来格式」而提前臆造 M5 的 DTO 树。M5 只需在 `MatchJsonCodec` 里做一次映射。

### B22. `AppContainer` 暂不暴露 `knowledgeProvider` / `analyzer` / `importExport`

**决策**：T37① 列了这几个依赖，但 `KnowledgeProvider`（T51）、`ReplayAnalyzer`（T52）、
`ImportExportService`（T60）的接口在 M4/M5 才存在，本轮**不预留空实现**。

**理由**：留空实现会制造「看起来注入了、实际调不通」的假依赖，比缺一个 `val` 更危险。

### B23. 规则判定只走 `AvalonRules`，UI 与 ViewModel 都不重算

**决策**：
- `RecordViewModel` 调用 `rules.decideProposal` / `rules.isMissionFailed`，把结果放进
  `RecordUiState.voteDecision` / `missionDerivedOutcome`；
- `VoteRecorder` / `MissionOutcomeSelector` 只接收上述判定结果做展示，**不复算**；
- 界面上的「半数 4」由 `playerCount / 2` 展示（纯算术），判定结论仍来自规则方法。

**理由**：主理人本轮硬性要求「投票/判定单一入口」。如果组件自己算，`isTeamApproved` 与 UI 就会各有一份实现，
AC-02 的防线会从一处变成两处，早晚漂移。

### B24. `VoteRecorder` 与 `MissionOutcomeSelector` 的物理隔离清单

**决策**（本轮定稿，后续不得合并）：

| 维度 | `VoteRecorder` | `MissionOutcomeSelector` |
|---|---|---|
| 标题 | 「组队投票（不是任务牌）」 | 「任务牌（不是投票）」 |
| 控件 | 座位三态网格 + 赞成/反对双步进器 | 失败牌数分段器 0/1/2/3/未知 |
| 词汇 | 同意 / 反对 / 未知 | 失败牌 / 成功 / 失败 |
| 数据字段 | `votesBySeat` / `approveCount` / `rejectCount` | `failCount` / `observedOutcome` |
| 所在 Sheet | `VoteSheet` | `MissionSheet` |

`ProposalCard` 内部再用 `HorizontalDivider` 把两块分区展示。
**UI 上不存在任何能把「反对票」写成「失败牌」的控件**。

### B25. 新增 `core/ui/IssueText.kt`

**决策**：新增该文件，把 29 个 `IssueCode` 全部映射为 `stringResource`。

**理由**：D6 要求领域层不拼 UI 文案，而 T40② 要求「非法时展示具体 `RuleIssue` 文案」。
没有这层映射，UI 只能显示 `TEAM_SIZE_MISMATCH` 这种枚举名。

### B26. 撤销只做「标记 retracted + Snackbar 提示」，不做「反悔重放」

**决策**：`Undo` 把最近一条事件标记 `retracted`，Snackbar 提示「已撤销上一步」；
用户如需恢复，重新录入即可（重放会自动按新事实重算）。

**理由**：真正的「反悔重放」需要保存一条撤销事件的反向事件，会污染审计链且与「revision 双写」语义重叠。
T49② 要求的是「提供 Snackbar 反悔窗口」，这里以「可立即重新录入」满足，属已知的简化，M4 可增强。

### B27. 角色的「展示顺序」收进 ruleset（主理人 M2 新增验收项）

**决策**：`AvalonPreset.roleDisplayOrder` 是角色在 UI 上出现顺序的**唯一来源**；
`CreateMatchViewModel` 用它对角色槽排序，feature 层**不再出现任何 `RoleId.` 枚举引用**。

**理由**：主理人新增验收项「凡涉及角色构成的地方一律调 `standardPreset()`，禁止 `if (playerCount == 5) hasPercival`」。
除了「构成」之外还有「顺序」——如果 UI 自己维护一份角色清单，预设一变（例如 10 人局改用奥伯伦）顺序就会与规则脱节。
把顺序也放进 ruleset，feature 层就彻底不需要认识角色枚举了。

**审计命令（可复现）**：

```bash
grep -rn "RoleId\." app/src/main/java/com/roundtrace/app/feature app/src/main/java/com/roundtrace/app/core/ui
# → 零命中
```

**唯一保留的 `playerCount` 分支**：`AvalonRules.failThreshold` 的 `if (playerCount >= 7 && questNo == 4) 2 else 1`。
这是 TDD §4.1 逐字要求的**失败牌门槛规则**（不是角色构成），必须留在规则层。

### B28. 引入 Robolectric，在 JVM 上跑真实 Room（推翻 B19）

**决策**：
1. `libs.versions.toml` 增加 `robolectric = "4.13"`、`androidx-test-core = "1.6.1"`；
2. `app/build.gradle.kts` 的 `testOptions.unitTests` 打开 `isIncludeAndroidResources = true`，
   并给每个 `Test` 任务设置：
   ```kotlin
   it.systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/central")
   it.systemProperty("robolectric.dependency.repo.id", "aliyun")
   ```
3. 把 T43 的核心用例**复制到 `src/test`**（`MatchDaoRobolectricTest` 8 个 + `RoomMatchRepositoryRobolectricTest` 9 个），
   用 `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34])`；
4. `src/androidTest` 的原文件**保留**（将来接真机仍需要），JVM 这份是并行保障。

**实测证据**：
- Robolectric 4.13 为 SDK 34 拉的是 `org.robolectric:android-all-instrumented:14-robolectric-10818077-i6`
  （**150,646,262 bytes**），落在 `~/.m2/repository/...`，首次下载约 3 分钟（含编译），
  之后整套 134 个测试 **30 秒**跑完；
- 首次运行该测试类耗时 18.1s（Robolectric 启动 + SQLite 影子实现初始化），后续复用缓存。

**主理人指定的四条硬断言全部跑绿**：
| 断言 | 用例 |
|---|---|
| D01：20 条事件 `occurredOrder` 为 1..20 连续、无空洞、无重复 | `d01_twentyEventsHaveContiguousOrderFromOne`、`twentyAppendsThroughRepositoryAreContiguous` |
| CASCADE：五张子表零残留 | `cascadeDeleteLeavesAllFiveTablesEmpty`（含 `analysis_reports`） |
| D03：`supersedesId` + 旧事件 `retracted=1`，读路径只回新事件，replay 不含旧结论 | `reviseEventRetractsOldAndReplayUsesNewFacts` |
| 类型转换：`createdAtEpochMs` 往返后 ≠ 0 | `createMatchPersistsLongTimestamps`（断言等于注入 Clock 的毫秒值） |

**教训（写给未来的自己）**：判断「某个库不可用」之前，先查它有没有**可配置的下载源**。
我把「默认源不通」直接等价成「库不可行」，漏掉了 Robolectric 的 `dependency.repo.url` 这个开关，
导致本可以在 JVM 上完成的验证被推迟了一轮。

---

## E. M4（T50–T58）实现裁决

### B29. P0 不做分析缓存（`analysis_reports` 继续空置）

**决策**：`ReplayViewModel` 每次切换位置/视角都**全量重算** `snapshot + findings + timeline`，
不写也不读 `analysis_reports`。

**理由**（主理人已确认）：
1. P0 数据量极小（一局几十条事件），重算成本可忽略；
2. 缓存失效机制依赖 `eventRevision`，而「修订/撤销」会让 revision 语义变得脆弱——
   一旦缓存与真实事件版本对不齐，用户看到的就是**脏结论**；
3. 视角是「反剧透」的核心，宁可每次重算，也不要冒「用旧缓存泄漏真相」的风险。

**触发重新评估的条件**：单场事件数进入数百条量级，或复盘页出现可感知卡顿。

### B30. `KnowledgeSnapshot` 增加规则矩阵快照字段

**决策**：`KnowledgeSnapshot` 增加 `playerCount` / `goodCount` / `evilCount` 三个字段（带默认值）。

**理由**：`ReplayAnalyzer.analyze(snapshot, history)` 的签名由 ARCHITECTURE §3 固定，**没有 config 参数**；
而「队伍里至少几个坏人」的 bounds 推导必须知道该人数下的好人/坏人总数。
放在快照里比让分析器持有可变状态安全，也不破坏接口签名（默认值保证向后兼容）。

### B31. 「当时视角」的笔记可见性由 `availableFromOrder` 决定，不受事件 `occurredOrder` 限制

**决策**：`PrivateNoteAdded` 的可见性只看 `at.includes(availableFromOrder)`，
**不看**事件自身的 `occurredOrder`。

**理由**：TDD §5.2-4 要求「`availableFrom` 晚于所选时间点的笔记不显示」，
反过来就意味着**允许回填**（事后补一条可见时间更早的笔记）。
这是时间闸门的**唯一例外**，且不构成泄漏：`availableFromOrder` 就是这条笔记的权威可见时间点。
其他所有事件仍严格受 `occurredOrder <= at` 约束。

### B32. 坏人观察者不得产出 `KNOWN_GOOD_SELF`

**决策**：`KNOWN_GOOD_SELF` 只对**好人**观察者产出；坏人观察者的自身阵营由
`observerRole.alignment` 直接填入 `seatKnowledge`。

**理由**：`KNOWN_GOOD_SELF` 的字面语义是「确定自己是好人」。我最初对好人/坏人都产出这条，
导致刺客被自己的视角标成 GOOD——这是 M4 开发中被 K01/K09 同族测试抓到的真实 bug，
由 `EvilTeamKnowledgeTest.assassin_knows_morgana_and_mordred_but_not_himself` 钉住。

### B33. 「能构建上帝快照」与「允许进入上帝视角」是两件事

**决策**：
- `KnowledgeProvider.snapshot(GOD_AFTER_REVEAL)` 只要求 `match.revealedAt != null`，否则抛 `IllegalStateException`；
- `canOpenGodView`（UI 入口开关）额外要求 `status ∈ {AWAITING_ASSASSINATION, FINISHED_GOOD, FINISHED_EVIL}`。

**理由**：ARCHITECTURE §9.3-7 给的是「入口条件」，而「快照能否构建」是纯数据能力。
把两者混成一个判断，会让「已揭晓但未终局」的对局无法做 K09 这类回归测试。
UI 只能通过 `canOpenGodView` 进入（`PerspectiveSwitcher` 禁用态 + `ReplayViewModel` 二次拦截）。

### B34. 任务「结果明确即计数」，与 `verified` 解耦（2026-10-31 主理人裁决，废止 T20④）

**背景**：原规则 T20④ 要求「未核实任务不计数」——即队伍与已通过提案不一致、或队伍不完整时，
即使失败牌数明确也不计入 `goodSuccessCount` / `evilFailureCount`。实机使用中暴露出问题：
现场记不全队伍是常态（录入层本来就允许队员不完整），一旦不完整，整局可能**永远无法判出胜负**，
时间线看着完整、结局却卡在「进行中」。

**决策**：
- `AvalonReducer` 不再以 `verified` 作为计数闸门；只要 `outcome != UNKNOWN`（失败牌数已知，
  或用户记录了观察结果）就累加计数；
- `verified` 降级为**可复核性标记**：继续驱动 `integrity = NEEDS_REVIEW`、`IssueHints` 提示
  与导入校验，但不再影响胜负推进；
- `outcome == UNKNOWN`（既无失败牌数、也无观察结果）仍然不计数。

**理由**：阿瓦隆里「任务成功/失败」本身就是公开信息，失败牌张数才是秘密。计数只需要结果明确，
不需要账目完美；把账目质量与事实判定绑在一起，会让「记录工具」因为记不全而失去作用。

**影响面**（已同步修改）：
- `AvalonReducer`：移除 `if (verified)` 包裹；
- 6 个测试按新规则重写断言：`AvalonStateMachineTest R11`、`AvalonMissionRulesTest R12`、
  `AvalonEventValidationTest R13`、`AvalonReducerReplayTest R14`（3 个）；
- `ARCHITECTURE.md`：§4 模型注释、§6.1 规则表、异常表第 10 行、§不变量第 8 条、设计原则表。

### B35. 历史记录可编辑的只有 3 类事件；改票**不得**顺手改决议（2026-11-02 主理人裁决）

**背景**：RT-003 C-2 要给「点时间线修改历史记录」定可编辑范围。实施方案第 1 节原文是
「编辑**组队提案、组队投票和任务结果**」，容易被顺手扩成 4 类（把决议也算进去）。

**决策**：可编辑白名单只放 `TEAM_PROPOSED` / `TEAM_VOTE_RECORDED` / `MISSION_RESOLVED`
（唯一判据在 `feature/record/RecordUiState.kt` 的 `EDITABLE_EVENT_TYPES`）。`TEAM_PROPOSAL_RESOLVED`
只读，不给编辑入口。

**理由**：决议是**现场既成事实**，§C.8 要求「与已有现场通过／否决记录冲突时提示复核，
**不静默改写现场事实**」。若决议也可编辑，「改票数导致决议矛盾」与「直接改决议」两条路径会互相打架，
事后无法判断哪个才是现场真实发生的。

**因此的硬约束**：编辑 `TEAM_VOTE_RECORDED` 后**不改同轮** `TEAM_PROPOSAL_RESOLVED`。
新票数与既有决议矛盾时，让 reducer 的 `DECISION_VOTE_CONFLICT` 浮出来、UI 提示复核，原决议保持不动。
记录错了要改决议，走「撤销最近一条 + 重录」这条显式路径。
`RecordEditSessionTest.editingVoteDoesNotTouchProposalDecision` 钉住这条。

### B36. v4 为预发布版本，`MIGRATION_3_4` 允许就地修正（不升 v5）

**背景**：审计 P1-1 发现 `MIGRATION_3_4` 的递归 CTE 未按 `matchId` 分组——
`mB` 的事件若 `supersedesId` 指向 `mA` 的事件，会继承 `mA` 的链根位置，与 `mB` 自有事件撞位，
破坏 INV-1（同一逻辑位置至多一条 active）。

**为什么可以就地改迁移脚本**：v4 **从未对外发布**。远端 `origin/main` 仍停在 v0.3.0
（`a6da7c4`，versionCode 3），没有任何用户数据库停在 v4，因此不存在「已按旧脚本迁移过的库」。
所有真实用户都是**v3 直接升级到修正后的 v4**，迁移脚本只有一次生效机会——改在这一刻成本最低。

**决策**：
- 两条 CTE 的 JOIN 加 `AND child.matchId = chain.matchId`；跨局链按孤儿处理，回落为自身 `occurredOrder`；
- **不升 v5**：`4.json` 未变（纯数据回填逻辑，不是 schema 变更），无需新增迁移；
- 配套加一条**只读**启动诊断 `findCrossMatchSupersedes`：命中只 `Log.w` 打印
  `matchId + eventId + supersedesId`，**绝不自动修数据**（自动改写历史事实比不修更危险）。

**理由链**：RT-002 修复前 `reviseEvent` 只按 `eventId` 查、`sameMatch = true` 是硬编码断言而非校验，
那段窗口里跨局链**能写进库**；所以这条不是纯理论。启动自检负责把存量情况暴露出来供人工判断。

### B37. 历史事件可编辑范围限定 3 类（2026-11-02）

**决策**：只有 `TEAM_PROPOSED`（组队提案）、`TEAM_VOTE_RECORDED`（组队投票）、
`MISSION_RESOLVED`（任务结果）三类事件提供编辑入口，判据是**单一白名单**
`RecordUiState.EDITABLE_EVENT_TYPES`（时间线点击、`openEdit()` 校验、`commit()` 都读它，
不会出现「能点开、保存时报类型不支持」的分叉）。

**`TEAM_PROPOSAL_RESOLVED`（通过/否决决议）只读**，理由：
- 它是**现场既成事实**。若它也可编辑，「改票数导致决议矛盾」与「直接改决议」两条路径会互相打架，
  事后无法判断哪个才是现场真实发生的；
- 因此编辑投票后**不改同轮决议**：矛盾由 reducer 的 `DECISION_VOTE_CONFLICT` 浮出、UI 提示复核，
  原决议事件保持不动；
- 记录错了要改决议，走「撤销最近一条 + 重录」这条**显式**路径，不混进编辑流程。

`QUEST_STARTED`（轮次锚点）、`PLAYER_CLAIMED`（语义事实）、`PRIVATE_NOTE_ADDED`（私人内容）同样只读。

### B38. 「计划 ≠ 真相 / UNKNOWN 不自动补齐」契约索引（2026-11-05 补登记）

> 本条**只做契约索引，不新增裁决、不改代码**。目的：补上
> `docs/audit/seat-identity-and-reveal-closeout.md` §9(3) 指出的检索缺口 ——
> 此前 decisions.md 没有这三条的专门条款，导致基线文档引用 B 编号时无处可查。
> 权威出处仍是 `ARCHITECTURE.md §2.2`、`§9.3-3`、`GameConfig.kt`、`RoleAssignment.kt`
> 及源码类注释；本条仅把它们与实现位置钉在一起，便于检索与回归时核对。

**三条硬契约**

1. **开局计划 ≠ 真实身份**。`GameConfig.roleSlots` 是**创建时的配置**，不是身份事实。
   揭晓页逐座位填的是「结局后核实的真实角色」，`UNKNOWN` 是合法的「仍未知」。
   计划与真实不一致时**允许按真实记录**，绝不反向把真相改写回计划。
2. **UNKNOWN 不自动补齐、不占配额**。缺失行保持 `UNKNOWN`，`RevealRoleQuotaPolicy` 恒不为其计数，
   也不因「差一个角色」推断补齐；角色不完整时揭晓保存成功，高级结论区显示「信息不足」（T56⑤）。
3. **超额不硬禁用，只警告 + 独立二次确认**。
   `planMismatchWarning` 明确提示差异，`ConfirmPlanMismatch` 只放行**本次**写入；
   任一行变化后确认立即作废，必须重新确认；
   全程**不修改 `roleSlots`、不篡改已录入的 `roleAssignments`**，
   写入由 `revealMatch()` 一次原子提交，失败保留输入（B18/ID-18 护栏不变）。

**实现位置（回归核对清单）**

| 契约 | 代码位置 |
|---|---|
| 配额只取本局 `roleSlots`、按 `roleId` 合计、排除当前座位 | `feature/reveal/RevealRoleQuotaPolicy.kt` |
| 差异精确化（`实际>计划` 已超额即警告；`实际<计划` 仅全录完才报） | 同上 `planMismatch()` |
| 二次确认闸门（未确认则拦下并给 `errorMessage`） | `RevealViewModel.reveal()`、`RevealUiState.requiresPlanMismatchConfirmation` |
| 下拉不硬禁用、可选项来自 ruleset 展示顺序 | `RevealScreen.RevealRow`（B27） |
| 原子写入 | `MatchRepository.revealMatch()`；既有 `AtomicWriteViewModelTest` 全量回归 |

**UX-ID-P2 Phase D（2026-11-05，`d950241`）只调整了上述 UI 的可见性，未改任何契约**：
计划差异警告卡从列表底部移到顶部首屏可见，并补渲染此前被吞掉的 `errorMessage`
（未确认时点揭晓原为静默失败）。`UNKNOWN`、二次确认、原子写入三条保持不变，
`RevealQuotaViewModelTest` 与 `RevealRoleQuotaPolicyTest` 全量通过。

---

## F. 待裁决（**未生效**，不得按此改代码）

> 本节与 A～E 的性质不同：这里记录的是**已经发现、但需要主理人明确裁决**的语义分歧。
> 在裁决落地之前，代码按「当前行为」执行，并已有回归测试钉住该行为。
> 任何实现都**不得**以「优化撤销条数」为由自行改动依赖判定 —— 那等于偷偷改游戏事实。

### P-1. 只改队长（`teamSeats` 不变）是否应撤销投票/决议/任务结果

**现状（已实现并有回归测试）**：`EditImpactPlanner.proposalFindings` 把提案视为
「谁提的 + 提了谁」的复合事实，`leaderSeat` 或 `teamSeats` 任一变化都算内容变化，
于是**同提案的 `TEAM_VOTE_RECORDED`、`TEAM_PROPOSAL_RESOLVED` 全部 RETRACT**；
若该提案正是把队伍送上任务的那一次，`MISSION_RESOLVED` 也一并撤销。

**分歧点**：任务结果的前提是「谁上了这支队伍」，队长换人不改变这一点。
严格按依赖语义，只改队长时任务结果**不该**被撤销；现有实现偏保守。

**方案 A —— 保留当前严格撤销（默认）**
- 做法：不改代码。
- 理由：PRD §2.2 把「队长或成员」写在同一行；队长变更意味着「这次提案的现场是否还成立」存疑，
  整队信息一起复核最安全；且撤销**不静默** —— 预览对话框会逐条列出「将撤销 N 条」，用户是知情确认的。
- 代价：改一次队长要重录该提案的投票与决议（低频动作，但一次撤销 3 条）。
- 风险：用户为省事而放弃勘误，错误数据被保留。

**方案 B —— 只保留队员相关下游事实**
- 做法：`proposalFindings` 中，任务结果只在 `teamSeats` 变化时才 RETRACT；
  `leaderSeat` 单独变化时，投票/决议改为 `REVIEW_ONLY`（提示「队长已改，请核对当时是谁主持」），
  并把「队长合法性」作为人工复核原因。
- 前提：**必须先确认 `leaderSeat` 在规则层只是展示字段**。实际并非如此：
  `AvalonRules` 会用「上一轮队长」推导 `expectedLeader` 并对 `QUEST_STARTED.leaderSeat` 做一致性校验
  （`AvalonRules.kt` 的 `expectedLeader` 分支），`AvalonReducer` 还会把它投影到
  `currentLeaderSeat` 与 `quest.leaderSeat`。改队长会让后续轮次出现「应到队长与实际不符」的规则提示，
  因此「只影响展示」这个前提当前**不成立**。
  （注：`FIVE_CONSECUTIVE_REJECTS` 只看 `consecutiveRejections` 计数，与队长无关 —— 这点已核实，不构成阻碍。）
- 收益：撤销条数从 3 降到 0–1，勘误成本显著下降。
- 风险：**把真实发生的投票/决议留在时间线上，但它们的主持人已经换了**，
  等于让审计链自相矛盾；且后续轮次的队长序列校验会持续报不一致，需要用户逐轮解释。

**建议**：维持方案 A，直到能明确回答「`leaderSeat` 变更后，历史投票/决议的语义是否仍然成立」。
若产品认为「队长只是记录谁主持、与规则无关」，那是**规则层**的修改（`AvalonReducer` 不再用
`QUEST_STARTED.leaderSeat` 推导轮次队长），必须先改规则再谈编辑策略 —— 不能只在编辑层放宽。

**裁决所需的输入**（缺一不可）：
1. 线下牌局中「队长记错了」与「队伍记错了」的发生频率；
2. 产品是否接受「改队长 → 重录投票/决议」的操作成本；
3. 规则层是否愿意把队长降级为纯展示字段（影响 `FIVE_CONSECUTIVE_REJECTS` 与下一轮队长推导）。
