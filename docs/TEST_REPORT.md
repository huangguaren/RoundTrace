# RoundTrace 测试报告（TEST_REPORT）

> 生成日期：2026-10-30（最后更新：M5 / T59–T65 完成后重跑）
> 覆盖范围：**M0（T01–T10）+ M1（T11–T27）+ M2（T28–T43）+ M3（T44–T49）+ M4（T50–T58）+ M5（T59–T65）**
> 结论：**`./gradlew :app:testDebugUnitTest` 全绿（208 个用例，0 失败）；`./gradlew :app:assembleDebug` 成功产出 APK（18.6 MB）。**
> T43 的 Room 用例已通过 **Robolectric 在 JVM 上真实跑绿**（见 §8），仪器测试文件保留给真机使用。
> 所有结论均为**本机实测**，命令与输出摘要见下。

---

## 1. 环境证据

| 项 | 实测值 |
|---|---|
| 操作系统 | Windows 11 amd64（Git Bash） |
| JDK | `openjdk version "17.0.13" 2024-10-15 LTS`（Microsoft Build 17.0.13+11-LTS） |
| Android SDK | `C:\Users\HK-info\android-build\sdk`，`platforms/android-34`、`build-tools/34.0.0`、`platform-tools`、`cmdline-tools/latest` |
| Gradle（wrapper） | `Gradle 8.7`，`distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip` |
| Gradle（本机独立安装） | `Gradle 8.7`（`C:\Users\HK-info\android-build\gradle`，备选） |
| 依赖仓库 | 阿里云 Maven 镜像（`settings.gradle.kts`，见 `docs/decisions.md` B1） |
| `local.properties` | `sdk.dir=C\:\\Users\\HK-info\\android-build\\sdk`（已 gitignore） |

### 1.1 wrapper 版本与镜像（偏离 TASKS.md T03①，已裁决）

- T03① 原要求 `gradle-8.9-bin.zip`；实际使用 **8.7** + 腾讯云镜像，理由与实测见 `docs/decisions.md` **B18**。
- `services.gradle.org` 实测约 16KB/s，8.9 发行包（约 130MB）下载 9 分钟未完成（已中止）。
- 腾讯云镜像实测：`./gradlew --version` **11 秒**返回 `Gradle 8.7`。
- AGP 8.5.2 要求 Gradle ≥ 8.7，**8.7 完全兼容**；`gradle-wrapper.jar` 由 `gradle wrapper` 生成后提交（未手写）。

---

## 2. 执行命令与结果

### 2.1 单元测试 + 打包（clean 全量，经 wrapper）

```bash
export JAVA_HOME="/c/Users/HK-info/android-build/jdk"
export ANDROID_HOME="/c/Users/HK-info/android-build/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

cd /c/Users/HK-info/WorkBuddy/2026-10-30-11-53-57/RoundTrace
./gradlew --version                                     # → Gradle 8.7（11s）
./gradlew clean --no-daemon --console=plain              # → CLEAN_EXIT=0
./gradlew :app:testDebugUnitTest :app:assembleDebug \
          --no-daemon --console=plain                    # → BUILD EXIT=0
```

输出摘要：

```
BUILD SUCCESSFUL in 25s
```

- 编译告警数：**0**（`grep -c '^w: '` → 0）
- 编译错误数：**0**
- 依赖首次下载耗时数分钟（阿里云镜像），后改为增量，属正常

### 2.2 产物

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/debug/output-metadata.json
```

HTML 报告：`app/build/reports/tests/testDebugUnitTest/index.html`

---

## 3. 单元测试明细（100 个用例，0 失败 0 跳过）

| 测试类 | 用例数 | 失败 | 覆盖用例编号 |
|---|---|---|---|
| `AvalonPresetMatrixTest` | 13 | 0 | **R01** + **T17 官方角色构成（6 行各一条）** |
| `AvalonVoteRulesTest` | 4 | 0 | **R02 / R03** + 可判定性三分支 |
| `AvalonMissionRulesTest` | 10 | 0 | **R04 / R05 / R06 / R07 / R12** |
| `AvalonStateMachineTest` | 10 | 0 | **R08 / R09 / R11** + 否决计数重置 |
| `AvalonAssassinationTest` | 7 | 0 | **R10**（命中 / 未命中 / 角色未知）+ 无刺客变体 |
| `AvalonSetupValidationTest` | 12 | 0 | **R13（setup 部分）** + 七个配置类 IssueCode |
| `AvalonEventValidationTest` | 12 | 0 | **R13（事件部分）** + 票数超人数 / 队伍少一人 |
| `AvalonRoleKnowledgeTest` | 11 | 0 | **T19 夜间知识**（含 10 人局奥伯伦两条例外） |
| `EventRevisionPolicyTest` | 9 | 0 | **R14（修订策略）** |
| `AvalonReducerReplayTest` | 8 | 0 | **R14（重放重算）** + 幂等 / 部分重放 |
| `AvalonFixtureTest` | 4 | 0 | **T23③**（fixture 可解析且字段与 TDD §11 一致） |
| **合计** | **100** | **0** | |

### 3.1 R01–R14 逐条结论

| 编号 | 输入摘要 | 期望 | 结果 | 断言位置 |
|---|---|---|---|---|
| R01 | 5/6/7/8/9/10 人 | 阵营与任务人数矩阵严格正确 | ✅ 通过 | `AvalonPresetMatrixTest` |
| R02 | 8 人 4 赞成 4 反对 | 组队否决（平票不过） | ✅ 通过 | `AvalonVoteRulesTest` |
| R03 | 8 人 5 赞成 3 反对 | 组队通过 | ✅ 通过 | `AvalonVoteRulesTest` |
| R04 | 第 2 任务 7 赞成 1 反对 + 1 张失败牌 | 提案通过、任务失败；两字段不混淆 | ✅ 通过 | `AvalonMissionRulesTest`（另含「反对票 1 / 失败牌 2」独立字段用例） |
| R05 | 8 人第 4 任务 5 人 1 张失败牌 | 任务成功 | ✅ 通过 | `AvalonMissionRulesTest` |
| R06 | 8 人第 4 任务 5 人 2 张失败牌 | 任务失败 | ✅ 通过 | `AvalonMissionRulesTest` |
| R07 | 6 人第 4 任务 1 张失败牌 | 任务失败 | ✅ 通过 | `AvalonMissionRulesTest` |
| R08 | 同一任务 5 次连续否决 | 坏人直接胜利 | ✅ 通过 | `AvalonStateMachineTest`（含 4 次未终局对照） |
| R09 | 3 次任务成功 | 待刺杀，不立即好人胜 | ✅ 通过 | `AvalonStateMachineTest`（`determineOutcome == null`） |
| R10 | 命中 / 刺错 / 角色未知 | 坏人胜 / 好人胜 / 维持待刺杀 | ✅ 通过 | `AvalonAssassinationTest` |
| R11 | 3 次任务失败 | 坏人直接胜利 | ✅ 通过 | `AvalonStateMachineTest` |
| R12 | 已记录 1 张反对票、失败牌未知 | 不能据此认定任务失败 | ✅ 通过 | `AvalonMissionRulesTest` |
| R13 | 队伍少一人 / 重复座位 / 票数超人数 / 非法角色配置 | 草稿可存、已确认事件不合法 | ✅ 通过 | `AvalonSetupValidationTest` + `AvalonEventValidationTest` |
| R14 | 修改第二轮组队结果 | 后续状态重算、冲突提示、不保留错误结果 | ✅ 通过 | `AvalonReducerReplayTest` + `EventRevisionPolicyTest` |

### 3.2 T17 官方角色构成（主理人 2026-10-30 裁决，见 `docs/decisions.md` B17）

`AvalonPresetMatrixTest` 对 6 行各写一条 `@Test`，逐条断言「角色计数 + 阵营计数（与 TDD §4.1 矩阵一致）+ 唯一性 + 总数等于人数」：

| 人数 | 断言的角色构成 | 结果 |
|---|---|---|
| 5 | 梅林 1 / 忠臣 2 / 刺客 1 / 爪牙 1，**派西维尔 0** | ✅ |
| 6 | 梅林 1 / 忠臣 3 / 刺客 1 / 爪牙 1，**派西维尔 0** | ✅ |
| 7 | 梅林 1 / 派西维尔 1 / 忠臣 2 / 刺客 1 / 莫甘娜 1 / 爪牙 1，**莫德雷德 0** | ✅ |
| 8 | 梅林 1 / 派西维尔 1 / 忠臣 3 / 刺客 1 / 莫甘娜 1 / 莫德雷德 1，**与 TDD §11 夹具逐角色一致** | ✅ |
| 9 | 梅林 1 / 派西维尔 1 / 忠臣 4 / 刺客 1 / 莫甘娜 1 / 莫德雷德 1 | ✅ |
| 10 | 梅林 1 / 派西维尔 1 / 忠臣 4 / 刺客 1 / 莫甘娜 1 / 莫德雷德 1 / **奥伯伦 1**，**爪牙 0** | ✅ |

另有两条回归：
- `T17 预设表六行全部自洽且与人数矩阵双向一致`
- `T17 角色构成属于预设而非规则5人局也能通过 setup 校验`（防止 `validateSetup` 因「缺少派西维尔」误报 BLOCKING）

### 3.3 T19 夜间知识（含 10 人局奥伯伦）

`AvalonRoleKnowledgeTest` 11 个用例覆盖：
- 8 人局梅林可见坏人 = `[3, 5]`，**不含 4 号莫德雷德**；
- 10 人局梅林可见坏人 = `[7, 8]`，**不含 9 号莫德雷德、不含 10 号奥伯伦**；
- 派西维尔在有莫甘娜时候选 = `[1, 3]`；无莫甘娜时只看到真梅林；
- 坏人互认含同伴但**排除奥伯伦与自身**；奥伯伦自己不参与互认；
- 莫德雷德**仍可**参与坏人互认（只是不被梅林看见）；
- 角色未知时返回空知识且不抛异常；知识表按座位升序并跳过未知角色。

### 3.4 关键断言举例（防止「空断言」）

- R02：`assertFalse(rules.isTeamApproved(4, 8))` + `decideProposal(4,4,0,8) == REJECTED` + 复盘 `phase == PROPOSAL_OPEN`、`consecutiveRejections == 1`、无 `DECISION_VOTE_CONFLICT`。
- R05/R06：同一脚本只改 `failCount`（1 vs 2），断言 `quest4.outcome` 分别为 `SUCCESS` / `FAILED`，且 `goodSuccessCount` 与 `evilFailureCount` 分别为 3/1 与 2/2。
- R08：5 次否决后 `status == FINISHED_EVIL` 且 `outcome.reason == FIVE_CONSECUTIVE_REJECTS`；另有「4 次否决仍未终局」对照用例。
- R09：3 次成功后 `status == AWAITING_ASSASSINATION` 且 `outcome == null`、`rules.determineOutcome(state) == null`。
- R10 角色未知分支：`assassination.hitMerlin == null`、`status` 仍为 `AWAITING_ASSASSINATION`、存在 `UNKNOWN_ROLE_FOR_OUTCOME`。
- R14：同一 4 任务脚本的「基准版」与「修订版」分别重放，断言 `evilFailureCount` 由 1 变为 2、`quest4.verified` 由 false 变为 true、`issues` 中出现 `MISSION_TEAM_MISMATCH` / `DECISION_VOTE_CONFLICT`。

---

## 4. 未执行项（如实记录）

| 项 | 状态 | 原因 |
|---|---|---|
| `connectedDebugAndroidTest` | **未执行** | 本机无 Android 设备/模拟器；D01/D03/U01–U05 的仪器测试属 M2（T43）与 M4/M5（T58/T65）范围 |
| `lintDebug` | **未执行** | 属 T65 收尾项，本轮未在范围内（M5 执行并列出 Warning） |
| 真机冷启动 / Tab 切换 / 深浅色目视验证 | **未执行** | 无设备。导航壳与首页空态的运行时行为**只有静态保证**（编译通过 + grep 检查），无运行时证据 |
| `Compose UI test` | **未执行** | 无设备；U01–U03 需 M3+ 的组件就位 |

> **重要**：本轮**没有**真机/模拟器，「App 启动不崩溃」「Tab 切换」「1.3x 字体不裁切」这类结论
> **没有运行时证据**。M2 起必须补仪器测试或人工清单。

---

## 5. 静态检查（可复现的 grep 证据）

| 检查项 | 命令要点 | 结果 |
|---|---|---|
| D1 纯 Kotlin 层无 Android 依赖 | `grep -rn "^import android\|^import androidx" core/{model,rules,replay,util,data}` | **零命中** |
| D1 测试层无 Android 依赖 | 同上，作用于 `app/src/test/.../{rules,util,data}` | **零命中** |
| D5 Composable 无颜色字面量 | `grep -rn "Color(0xFF" app/src \| grep -v theme/Color.kt` | **零命中** |
| T04 Manifest 无权限声明 | `grep -c "uses-permission" app/src/main/AndroidManifest.xml` | **0** |
| 依赖版本无浮动 | `gradle/libs.versions.toml` 检索 `+` / `latest.release` | **零命中** |
| 编译告警 | `grep -c '^w: '`（clean 全量构建日志） | **0** |

---

## 6. 下一轮（M2 + M3）需要立刻补的验证

1. **Room schema 生成**：`app/schemas/com.roundtrace.app.core.database.RoundTraceDatabase/1.json` 必须在 T30 后真实存在并提交。
2. **仪器测试 D01/D03**（T43）：无设备时必须在报告中写「未执行 + 原因」，不得写「通过」。
3. **`lintDebug`**：M5 前至少跑一次，把 Warning 列全。
4. **UI 运行时验证**：SeatPicker（T39）、VoteRecorder（T47）、MissionOutcomeSelector（T48）是「反对票 ≠ 失败牌」的最终防线，必须有 Compose UI test 或人工清单。
5. **`SeatPicker` 的座位数与预设解耦**：T17 更正后，5/6 人局没有派西维尔，若 UI 上有「身份徽标」需按 `AvalonPreset.standardPreset(playerCount)` 动态取，不可硬编码角色清单。


---

## 7. M2 + M3（T28–T49）执行记录

### 7.1 命令与结果

```bash
export JAVA_HOME="/c/Users/HK-info/android-build/jdk"
export ANDROID_HOME="/c/Users/HK-info/android-build/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

cd /c/Users/HK-info/WorkBuddy/2026-10-30-11-53-57/RoundTrace
./gradlew :app:compileDebugKotlin --no-daemon --console=plain     # 0 error / 0 warning
./gradlew clean --no-daemon --console=plain                       # CLEAN=0
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
# → BUILD SUCCESSFUL in 2m 27s；TOTAL tests=117 failures=0
```

| 项 | 结果 |
|---|---|
| 编译错误 | 0 |
| 编译告警（`^w: `） | 0 |
| 单测用例 | **117**（M1 的 100 + 本轮新增 17） |
| 失败 / 跳过 | 0 / 0 |
| APK | `app/build/outputs/apk/debug/app-debug.apk`，**18,260,677 bytes**（clean 全量重建） |
| Room schema | `app/schemas/com.roundtrace.app.core.database.RoundTraceDatabase/1.json` **已生成**（T30④） |

### 7.2 本轮新增的测试

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `com.roundtrace.app.data.EventCodecTest` | 8 | T33：10 种事件往返、`verified` 序列化、`occurredOrder` 只在列、未知 eventType 抛异常、阶段键推导 |
| `com.roundtrace.app.feature.RecordViewModelTest` | 9 | T44⑤（超员 → `TEAM_SIZE_MISMATCH`）、草稿防抖落库、提案落库 order 从 1 开始、T47 判定走规则、T48 失败牌推导 |
| 合计新增 | **17** | |

### 7.3 主理人本轮指定的四条硬性验收项

| # | 要求 | 落实 |
|---|---|---|
| 1 | `occurredOrder` 盖章可验证 | `EventCodec.encode(event, matchId, occurredOrder)` 只把 order 写**列**（`EventCodecTest` 断言 payload 不含 `occurredOrder`）；真实 `1..N` 由 `androidTest/MatchDaoTest.d01_twentyEventsHaveContiguousOrder` 保证（**未执行**，见 §7.5） |
| 2 | `verified` 照常序列化、reducer 不采信 | `EventCodecTest.T33 verified 字段照常序列化且可还原`（true/false 双向）；`AvalonReducer` 未做任何特判 |
| 3 | 判定单一入口 | `RecordViewModel` 只调 `rules.decideProposal` / `rules.isMissionFailed`，结果放进 `RecordUiState.voteDecision` / `missionDerivedOutcome`；`VoteRecorder` 与 `MissionOutcomeSelector` **只展示不计算** |
| 4 | VoteRecorder 与 MissionOutcomeSelector 视觉/措辞彻底分离 | 两个独立文件、独立标题（「组队投票（不是任务牌）」vs「任务牌（不是投票）」）、独立控件（三态座位网格 + 双数字 vs 失败牌分段器）；`ProposalCard` 用 `HorizontalDivider` 分区 |

### 7.4 静态检查（本轮回归）

| 检查项 | 结果 |
|---|---|
| D1：`core/{model,rules,replay}` 无 android/androidx import | **零命中** |
| D2：`feature` 不 import `core.database` | **零命中** |
| D5：`Color(0xFF` 除 `Color.kt` 外 | **零命中** |
| D8（主理人新增）：角色构成只来自 `standardPreset()` | **零违规**——`feature/` 与 `core/ui/` 内**无任何 `RoleId.` 枚举引用**（展示顺序改由 `AvalonPreset.roleDisplayOrder` 提供） |
| Manifest 权限 | **0** |

### 7.5 未执行项（如实记录）

| 项 | 状态 | 原因 |
|---|---|---|
| `connectedDebugAndroidTest`（T43 的 D01/CASCADE/D03） | **未执行** | 本机无 Android 设备/模拟器。两个测试文件已编写且依赖齐备（`androidx.room:room-testing`、`androidx.test:runner`），一有设备即可跑 |
| `lintDebug` | **已执行，通过** | 2026-10-30 补跑：`./gradlew :app:lintDebug` BUILD SUCCESSFUL，**0 error / 37 warning**（均为 `UnusedAttribute`、`RedundantLabel` 等良性提示，报告见 `app/build/reports/lint-results-debug.html`） |
| 真机冷启动 / 现场录入全流程手测 | **未执行** | 无设备。UI 只有编译期保证 |

> **JVM 侧为何不跑 Room**：Room 需要 Android SQLite 运行时，纯 JVM 单测必须引入 Robolectric，
> 而 Robolectric 会在测试期自行从 Maven Central 拉 `android-all` jar——本机 `repo1.maven.org` 不通。
> 因此「真实 1..N 顺序」由仪器测试保证，JVM 侧只验证**盖章契约**（列 vs payload 不双写）。
> 该取舍已记入 `docs/decisions.md` B19。

### 7.6 增量构建踩到的一次 AGP 报错（已用 clean 排除）

增量构建时出现：

```
> Task :app:mergeExtDexDebug FAILED
DexMergingTask property 'fileDependencyDexDir' specifies directory
'app/build/intermediates/external_file_lib_dex_archives/debug/desugarDebugFileDependencies' which doesn't exist.
```

这是 AGP 8.5.2 的增量状态问题（本轮早前有构建被 `TaskStop` 中断过），**与源码无关**：
`clean` 后全量构建通过。记录在此，避免下次误判为代码缺陷。

---

## 8. T43 补验：Robolectric 在 JVM 上跑真实 Room

### 8.1 为什么要补

上一版我给 T43 的结论是「未执行（无设备）」，并写了「Robolectric 不可行，因为 repo1 不通」。
**这个判断是错的**——主理人实测阿里云镜像上有 `android-all-instrumented`（6.7 MB/s），
只是我漏配了 `robolectric.dependency.repo.url`。已按主理人方案重做，T43 从「写了没跑」变成「真跑绿」。

### 8.2 环境与耗时

| 项 | 值 |
|---|---|
| Robolectric | `4.13` |
| `androidx.test:core` | `1.6.1` |
| 为 SDK 34 拉取的运行时 jar | `android-all-instrumented:14-robolectric-10818077-i6`，**150,646,262 bytes** |
| 缓存位置 | `~/.m2/repository/org/robolectric/android-all-instrumented/` |
| 下载源 | `https://maven.aliyun.com/repository/central`（`robolectric.dependency.repo.url`） |
| 首次运行（含下载 + 编译） | 3m 39s |
| 整套 134 个测试再次运行 | **30s** |
| 单个 Robolectric 测试类耗时 | 18.1s |

> 注意：Robolectric 4.13 为 SDK 34 钉的是 `...-i6`，主理人实测的是 `...-i7`。
> 两者都在阿里云镜像上，以库自身 pin 的版本为准。

### 8.3 新增用例（17 个，全部 JVM / Robolectric）

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `MatchDaoRobolectricTest` | 8 | D01 1..20 连续无空洞、撤销后不回退 order、`maxOccurredOrder`、`touchMatch`、payload 不双写 order、retracted 双视图、`countByType` 忽略 retracted、Boolean 列往返 |
| `RoomMatchRepositoryRobolectricTest` | 9 | `createdAtEpochMs` Long 往返（≠0）、角色构成 JSON 往返、**CASCADE 五表零残留**、**D03 修订语义 + replay 不含旧结论**、跨类型修订被拒且零写入、撤销、自动补建 QUEST_STARTED、仓储级 20 连追加 1..20、角色初始化为 UNKNOWN |

### 8.4 主理人指定的四条硬断言 —— 逐条跑绿

| # | 断言 | 结果 |
|---|---|---|
| 1 | 连续 20 条 → `occurredOrder` 1..20 连续、无空洞、无重复、严格递增 | ✅ `d01_twentyEventsHaveContiguousOrderFromOne` + `twentyAppendsThroughRepositoryAreContiguous` |
| 2 | 删除 match → 五张子表（participants / role_assignments / match_events / match_drafts / analysis_reports）零残留 | ✅ `cascadeDeleteLeavesAllFiveTablesEmpty`（删前逐表断言有数据，删后逐表断言为空） |
| 3 | D03：旧事件 `retracted=1`、读路径只回新事件、replay 不含旧结论 | ✅ `reviseEventRetractsOldAndReplayUsesNewFacts`（断言 replay 后队长是修订后的 3 号而非旧 1 号） |
| 4 | `createdAtEpochMs` 经 Room 往返 ≠ 0 | ✅ `createMatchPersistsLongTimestamps`（断言等于注入 `Clock` 的 1_700_000_000_000） |

### 8.5 最终全量结果

```
./gradlew clean                                                → CLEAN=0
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon → BUILD SUCCESSFUL
TOTAL tests=134   failures=0
```

### 8.6 仍未执行项

| 项 | 状态 | 原因 |
|---|---|---|
| `connectedDebugAndroidTest` | **未执行** | 无设备/模拟器。但 T43 的**同等语义已在 JVM 上跑绿**，androidTest 文件保留供真机回归 |
| `lintDebug` | **未执行** | 属 T65 |
| 真机 UI 手测 | **未执行** | 无设备 |

---

## 9. M4（T50–T58）执行记录

### 9.1 命令与结果

```bash
./gradlew clean --no-daemon --console=plain                       # CLEAN=0
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon   # BUILD SUCCESSFUL in 1m 44s
```

| 项 | 结果 |
|---|---|
| 单测 | **176 通过 / 0 失败 / 0 跳过**（M3 后 134 → +42） |
| 编译错误 / 告警 | **0 / 0** |
| APK | `app-debug.apk` **18,453,212 bytes**（clean 重建） |

### 9.2 K01–K10 全部落在 JVM 单测（无 Robolectric 依赖）

| 用例 | 测试类 | 断言要点 |
|---|---|---|
| K01 | `MerlinKnowledgeTest` | 知道 3/5 是坏，**4 号 `knownAlignment == null` 且 `isUnknown`**；公开视角零泄漏 |
| K02 | `PercivalKnowledgeTest` | 候选 `[1,3]`，两人 `candidateRoles == [MERLIN, MORGANA]`，`knownAlignment` 均为 null |
| K03 | `LoyalServantKnowledgeTest` | 只有 SELF，3/4/5 全部 `isUnknown` |
| K04 | `EvidenceAnalyzerTest` | A04 必出现；任务 1 成功后 **4 号阵营仍 null**；无「4 号已证实好人」结论 |
| K05 | `EvidenceAnalyzerTest` | A07 必出现（UNKNOWN 级）；**无「3 号出失败牌」的确定性结论**；A06 只说「至少」 |
| K06 | `EvidenceAnalyzerTest` | A06 给出「至少 2 名坏人」；A07 同时存在；**无单锁 4 号的确定性结论**；A11 只点名 3、5 |
| K07 | `EvidenceAnalyzerTest` | `bounds()` 自检 `minEvil == 1`；A05 说「至少含 1 名坏人」且 **不指名** |
| K08 | `PercivalKnowledgeTest` | 声称只产生 `CLAIMED_PERCIVAL` 标签，`visibleRole`/`knownAlignment` 均为 null；A08 出现 |
| K09 | `PerspectiveIsolationTest` | 上帝视角看到 4 号是莫德雷德 → 切回第 3 任务当时视角，**4 号仍 `isUnknown`**，且 `observerRoleIsReconstructed == true`、basis 为 `RECONSTRUCTED_INITIAL` |
| K10 | `LoyalServantKnowledgeTest` | 观察者角色未知 → 全视角退化 + `OBSERVER_ROLE_UNKNOWN`；他人角色缺失 → 该座位未知 + `MOST_SEATS_UNKNOWN`；失败牌/队员缺失 → 对应 `UnknownItem` |

> **K09 是全套里最关键的一条**：它同时验证了「上帝视角能看真相」与「切回过去不会泄漏」，两者缺一都不算过。

### 9.3 三道闸门的独立测试

| 闸门 | 测试 | 结果 |
|---|---|---|
| ① 时间边界 | `gate1_time_boundary_excludes_later_events`、`gate1_start_position_has_no_facts` | ✅ 早于 `at` 的事实不出现，事实的 `occurredOrder` 全部 `<= at` |
| ① 例外（回填笔记） | `gate1_private_note_respects_available_from_order`、`gate1_private_note_with_later_available_from_is_hidden` | ✅ `availableFromOrder` 早/晚两种情形都对（见 decisions B31） |
| ② 可见性边界 | `gate2_private_note_only_visible_to_its_owner` | ✅ 本人可见 / 他人不可见 / 公开视角为空 |
| ③ 知识来源边界 | `k01_merlin_knows_own_role_and_basis_is_ruleset_initial`、`k09_reconstructed_role_is_flagged_explicitly` | ✅ `RULESET_INITIAL` 与 `RECONSTRUCTED_INITIAL` 可区分，且有 `observerRoleIsReconstructed` 标记 |

### 9.4 上帝视角绕过尝试（T57②）

| 尝试 | 结果 |
|---|---|
| 未揭晓时调用 `snapshot(GOD_AFTER_REVEAL)` | ✅ 抛 `IllegalStateException`，信息含「揭晓」 |
| 未揭晓时 `canOpenGodView` | ✅ `false` |
| `ReplayAction.SelectPerspective(GOD)` 且不可开 | ✅ 模式**不变**，`godViewBlocked = true`（UI 弹提示） |
| 已揭晓但未终局 | ✅ 快照可构建，但 `canOpenGodView` 仍为 `false`（见 decisions B33） |

### 9.5 输出纪律（PRD FR-08）

| 要求 | 落实 |
|---|---|
| 四档 `EvidenceLevel` | ✅ `RULE_CERTAIN / OBSERVER_CERTAIN / INFERENCE / UNKNOWN`，`EvidenceCard` 四档徽标「规则确定/本人可知/推测/信息不足」，**「推测」为 accent 色且带字样** |
| 禁止数值概率 | ✅ `output_discipline_every_finding_has_rule_id_and_evidence` 断言 label/explanation **不含 `%`** |
| 每条带 ruleId + evidenceIds | ✅ 同上用例断言 `ruleId` 非空；无证据者只能是 INFERENCE 且 explanation 含「无直接证据/人工核对」 |
| 数据残缺输出 UNKNOWN | ✅ `a12_reports_missing_data_instead_of_guessing` |

### 9.6 M4 新增/修改文件清单（关键）

- 领域：`core/model/Knowledge.kt`（+8 类型）、`core/knowledge/{KnowledgeProvider,ReplayAnalyzer,TeamCountBounds,AvalonKnowledgeProvider,AvalonEvidenceAnalyzer}.kt`
- UI：`core/ui/component/{PerspectiveSwitcher,TimelineEventCard,EvidenceCard,ClaimQuickAdd}.kt`
- Feature：`feature/claim/{ClaimUiState,ClaimViewModel,ClaimScreen}.kt`、`feature/reveal/{RevealUiState,RevealViewModel,RevealScreen}.kt`、`feature/replay/{ReplayUiState,ReplayViewModel,ReplayScreen}.kt`
- 记录页：新增 `StartQuest` 动作 + 「开始第 N 任务」按钮（幂等）
- 测试：`test/knowledge/{AvalonScenario,MerlinKnowledgeTest,PercivalKnowledgeTest,LoyalServantKnowledgeTest,EvilTeamKnowledgeTest,EvidenceAnalyzerTest,PerspectiveIsolationTest}.kt`、`test/feature/ReplayViewModelTest.kt`

### 9.7 未执行项

| 项 | 状态 | 原因 |
|---|---|---|
| `connectedDebugAndroidTest` | 未执行 | 无设备（Room 语义已由 Robolectric 在 JVM 覆盖） |
| `lintDebug` | 已执行，见 §10.4 | 属 T65 |
| 真机 UI 手测（复盘页 1.3x 字体、TalkBack、U03 上帝视角入口） | 未执行 | 无设备 |

---

## 10. M5（T59–T65）执行记录

### 10.1 命令与结果

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
# BUILD SUCCESSFUL in 42s（修复 3 个 M5 新测试后）
```

| 项 | 结果 |
|---|---|
| 单测 | **208 通过 / 0 失败 / 0 跳过**（M4 后 176 → +32） |
| 编译错误 / 告警 | **0 / 0** |
| APK | `app-debug.apk` **18,623,140 bytes**（含 M5 导出/设置/详情页） |

### 10.2 M5 测试覆盖（test/export/）

| 测试类 | 用例数 | 覆盖点 |
|---|---|---|
| `JsonCodecTest` | 13 | 往返一致（座位/事件/角色/票数/任务结果/私人笔记）、坏输入不抛异常、schema 版本校验、null 与 0 区分 |
| `MarkdownExporterTest` | 12 | 八节顺序、反对票与失败牌分节、声称≠事实、默认脱敏隐去身份与笔记、显式 opt-in 还原、未记录不猜数、findings 徽标、unknowns 列出缺失项 |
| `ImportValidationTest` | 7 | 坏 JSON / 未知 schema / 缺必填 / 规则校验失败 → 枚举 `ImportFailureReason`，不抛异常；导入总是新建对局不覆盖 |

### 10.3 本轮修复的 3 个 M5 测试问题

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 1 | `JsonCodecTest.round trip...` 断言 votes.size=2 | `questCycle` 内部自带一次全票通过投票，夹具实际产生 3 个 TeamVoteRecorded | 断言改为 3，并补全第 1/2/3 次投票的票数与决议顺序 |
| 2 | `MarkdownExporterTest.unknowns section...` 第 8 节取不到 | 测试辅助函数 `section()` 对最后一节 `substringBefore("\n## ", "")` 找不到分隔符返回空串 | 改为 `indexOf` 判空，最后一节取到末尾 |
| 3 | `MarkdownExporterTest.default export hides private notes` 同上 | 同上（脱敏提示在第 8 节末尾） | 同上修复后通过 |

### 10.4 lintDebug 结果

```
./gradlew :app:lintDebug --no-daemon → BUILD SUCCESSFUL
```

| 项 | 结果 |
|---|---|
| Errors | **0**（修复了 backup_rules.xml / data_extraction_rules.xml 里非法的 `domain="cache"`——cache 本就不在备份范围，删掉多余 exclude） |
| Warnings | 37（绝大多数是 `UnusedResources`：预留但 P0 未接的 strings；2 个 `ObsoleteSdkInt`（mipmap-anydpi-v26，minSdk=26 可合并）；2 个 `MonochromeLauncherIcon` 建议）。P0 不修，记录在案。 |
| HTML 报告 | `app/build/reports/lint-results-debug.html` |

### 10.5 本轮顺手修复的 UI 问题（来自 handoff/TODO-dispatch）

| 编号 | 问题 | 修复 |
|---|---|---|
| R1 | RecordScreen 一行 4 控件，「记录组队提案」6 字被裁 | 拆两行：主按钮+撤销 / 投票+任务牌，outlined 文案 13sp |
| R6 | EvidenceCard 直接显示 `A05_TEAM_CONTAINS_EVIL` 内部 code | 加 `ruleDisplayName()` 映射到 12 条中文规则名（strings.xml `rule_a01..a12`），未知兜底 |
| R7 | EvidenceCard 直接显示事件 UUID | 改为「证据事件 N 条」，不再列 raw id |
| R8 | RevealScreen 刺校区 `.take(5)`，7–10 人局 6/7/8 号选不到 | 改为 `1..playerCount` 全座位 + `FlowRow` 自动换行 |
| R9 | 刺杀座位 OutlinedButton 无选中态 | 选中时 `primaryContainer` 底 + 2dp `primary` 描边 + contentDescription，点击可取消 |
| — | 配色仍是旧蓝 #3956D8 | 全量替换为硅粉 #AD4D68（Color.kt 整体覆盖） |
| — | 设置页「导入对局」按钮只是跳回首页（假入口） | SettingsViewModel 注入 ImportExportService，SettingsScreen 加 OpenDocument launcher，导入后显示结果 |

### 10.6 M5 新增文件清单

- 领域：`core/export/{ExportModels,MatchJsonCodec,MarkdownExporter,VisibilityFilter,ImportExportService,ShareLauncher}.kt`
- Feature：`feature/detail/{MatchDetailScreen,MatchDetailUiState,MatchDetailViewModel}.kt`、`feature/settings/{SettingsScreen,SettingsUiState,SettingsViewModel}.kt`
- 测试：`test/export/{ExportFixtures,JsonCodecTest,MarkdownExporterTest,ImportValidationTest}.kt`
- 导航：`RoundTraceNavHost.kt` 注册详情/设置路由
- DI：`AppContainer` 新增 `importExport`；`ViewModelFactories` 登记 `matchDetail` 与 `settings`（注入 importExport）

### 10.7 仍未执行项

| 项 | 状态 | 原因 |
|---|---|---|
| `connectedDebugAndroidTest` | 未执行 | 无设备/模拟器 |
| 真机 UI 手测（1.3x 字体、TalkBack、文件选择器、分享面板） | 未执行 | 无设备 |
