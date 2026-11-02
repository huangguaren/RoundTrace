# RoundTrace（局迹）

阿瓦隆线下对局记录与复盘 App（Android 原生 · Kotlin · Jetpack Compose）。

> **状态**：M0–M5 全部完成（T01–T65）。**356 个 JVM 单测全绿**（52 个测试类），debug APK 可直接安装。
> 功能覆盖：创建对局 → 现场记录（提案/投票/任务牌/发言）→ **历史事件按 eventId 编辑** → 结局揭晓（角色/胜负/刺杀）→ 视角复盘（梅林/派西维尔/忠臣/坏人/上帝）→ JSON/Markdown 导入导出与分享 → 设置（主题/默认人数/隐私）。
> 数据库版本：**v4**（v3 → v4 首次启动前自动备份为 `<db>.bak.v3`，见 `docs/release/v0.4.0.md`）。

---

## 1. 环境要求

| 项 | 要求 |
|---|---|
| JDK | 17（构建用 `jvmToolchain(17)`） |
| Android SDK | `compileSdk 34`、`build-tools 34.0.0` |
| Gradle | 8.7（由 wrapper 固定，AGP 8.5.2 要求 ≥ 8.7；国内走腾讯云镜像，见 `docs/decisions.md` B18） |
| Kotlin | 2.0.21（Compose 编译器随 Kotlin 版本） |
| 最低运行版本 | Android 8.0（minSdk 26） |

`local.properties` 需要指向本机 SDK（该文件被 `.gitignore` 忽略）：

```properties
sdk.dir=C\:\\Users\\HK-info\\android-build\\sdk
```

> 国内网络环境：`gradle/wrapper/gradle-wrapper.properties` 已把 `distributionUrl` 指向
> `mirrors.cloud.tencent.com`（`services.gradle.org` 实测约 16KB/s 会卡死）；
> 依赖仓库在 `settings.gradle.kts` 中配置了阿里云镜像作为 `google()` / `mavenCentral()` 的前置加速，
> 后两者保留为兜底。

## 2. 构建

```bash
# 调试包
./gradlew :app:assembleDebug

# 只编译 Kotlin（最快发现编译错误）
./gradlew :app:compileDebugKotlin

# 静态检查
./gradlew :app:lintDebug
```

Windows 下使用 `gradlew.bat`；Git Bash 下直接 `./gradlew`。

产物路径（**存在才可信**）：`app/build/outputs/apk/debug/app-debug.apk`

## 3. 测试

```bash
# 领域规则单测（纯 JVM，最高优先级）
./gradlew :app:testDebugUnitTest

# 单个测试类
./gradlew :app:testDebugUnitTest --tests "com.roundtrace.app.rules.AvalonPresetMatrixTest"

# 仪器测试（需设备/模拟器）
./gradlew :app:connectedDebugAndroidTest
```

HTML 报告：`app/build/reports/tests/testDebugUnitTest/index.html`

本轮已实现的用例：**356 个 JVM 单测（52 个测试类）全绿**，含领域规则、数据修订、视角隔离、导入导出与本次审计新增的对抗性用例。
其中 `app/src/test/java/com/roundtrace/app/audit/` 是本轮代码审计新增的**独立验收包**（11 个测试类 / 48 条），用来钉住跨对局写入、并发修订、隐私出口、迁移回填等真实故障场景。
**仪器测试（`app/src/androidTest`）已编写 2 个测试文件，但本机无设备，未执行（NOT RUN）。**
用例与文件的对应关系见 `docs/TEST_REPORT.md` 与 `docs/audit/`。

## 4. 依赖版本

| 组件 | 版本 |
|---|---|
| AGP | 8.5.2 |
| Kotlin / Compose 编译器插件 | 2.0.21 |
| KSP | 2.0.21-1.0.25 |
| Compose BOM | 2024.10.01（ui 1.7.4 / material3 1.3.0） |
| Room | 2.6.1 |
| Navigation Compose | 2.8.4 |
| DataStore | 1.1.1 |
| kotlinx-serialization-json | 1.7.3 |
| Lifecycle / Activity / Core KTX | 2.8.7 / 1.9.3 / 1.13.1 |
| JUnit | 4.13.2 |

全部版本锁定在 `gradle/libs.versions.toml`，**无 `+`、无 `latest.release`**。
依赖仓库除 `google()` / `mavenCentral()` 外额外配置了阿里云镜像（见 `docs/decisions.md` B1）。

## 5. 架构简述

```
UI：Compose Screens（纯 State 渲染）+ ViewModel(StateFlow + onAction) + Navigation(字符串路由)
              ↓ 只依赖接口
Domain（纯 Kotlin，零 Android 依赖）
  core/model    领域模型：GameConfig / MatchEvent(10 种 sealed) / GameState / RuleIssue
  core/rules    GameRules / GameReducer 接口 + AvalonPreset / AvalonRules / AvalonReducer / 夜间知识
  core/replay   ReplayEngine（过滤 retracted → 按逻辑位置排序 → 逐步 step）/ TimelineBuilder
                VisibleEventPolicy（可见性唯一出口：PUBLIC / 当时 / 上帝）
Data
  core/database Room v4（logicalPosition + revisionSeq 双序）/ core/data 端口与实现 / core/export 导入导出
Platform
  RoundTraceApplication + AppContainer（手动 DI）
```

四条不变式：

1. **事件表是唯一历史**。`GameState`、任务得分、时间线、复盘结论全部由 `ReplayEngine` 从事件重算，不落库为权威数据。
2. **未知即未知**。`null`（未记录）与 `0`（已知为零）语义分离；`UNKNOWN` 枚举显式存在；**任务成败结果明确即计入胜负**，`verified` 只表示数据可复核性、不参与计数判定（`docs/decisions.md` B34）。
3. **组队反对票 ≠ 任务失败牌**。数据模型、UI、导出三个层面都不共用字段。
4. **逻辑顺序 ≠ 写入顺序**。列 `occurredOrder` 是写入序号（审计用），列 `logicalPosition` 才是时间线位置，修订继承原位置（`docs/ARCHITECTURE.md` §2.3.1）。

关于**标准预设角色构成**（PRD §6.1 只给阵营人数、未定义角色构成，按官方规则手册补全，见 `docs/decisions.md` B17）：

| 人数 | 好人角色 | 坏人角色 |
|---|---|---|
| 5 | 梅林 1、忠臣 2 | 刺客 1、爪牙 1 |
| 6 | 梅林 1、忠臣 3 | 刺客 1、爪牙 1 |
| 7 | 梅林 1、派西维尔 1、忠臣 2 | 刺客 1、莫甘娜 1、爪牙 1 |
| 8 | 梅林 1、派西维尔 1、忠臣 3 | 刺客 1、莫甘娜 1、莫德雷德 1 |
| 9 | 梅林 1、派西维尔 1、忠臣 4 | 刺客 1、莫甘娜 1、莫德雷德 1 |
| 10 | 梅林 1、派西维尔 1、忠臣 4 | 刺客 1、莫甘娜 1、莫德雷德 1、奥伯伦 1 |

注意：**5、6 人局没有派西维尔**；**10 人局第 4 个坏人是奥伯伦**（不参与坏人互认，梅林也看不见他）。
角色构成属于「预设」而非「规则」——`AvalonRules` 的判定只读人数矩阵与阵营数，不感知角色清单。

更多见 `docs/ARCHITECTURE.md`、`docs/decisions.md`。

## 6. 目录结构（本轮已落地部分）

```
RoundTrace/
├── app/src/main/java/com/roundtrace/app/
│   ├── RoundTraceApplication.kt        # 应用入口（只构造 AppContainer）
│   ├── MainActivity.kt                 # 单 Activity + 主题 + NavHost
│   ├── di/AppContainer.kt              # 手动 DI（appScope / Clock / IdGenerator / 投影刷新）
│   ├── navigation/                     # Routes + RoundTraceNavHost（3 Tab）
│   ├── core/util/IdGenerator.kt        # 可注入 ID 生成器与固定时钟
│   ├── core/ui/theme/                  # Color / Type / Shape / Dimens / Theme
│   ├── core/ui/component/              # RoundTraceScaffold / EmptyState / StatusBadge / VoteRecorder …
│   ├── core/model/                     # 领域模型（纯 Kotlin）
│   ├── core/rules/                     # 规则接口 + avalon 实现（纯 Kotlin）
│   ├── core/replay/                    # ReplayEngine / TimelineBuilder / VisibleEventPolicy（纯 Kotlin）
│   ├── core/data/                      # 仓储端口与实现 / EventRevisionPolicy / 设置
│   ├── core/database/                  # Room v4 / Dao / Entity / EventCodec / 迁移
│   ├── core/knowledge/                 # 角色知识与证据分析（视角隔离）
│   ├── core/export/                    # JSON / Markdown 导出 + 分享
│   └── feature/                        # home / create / record / claim / reveal / replay / detail / settings
├── app/src/test/java/com/roundtrace/app/
│   ├── util/                           # MatchBuilder / RoleFixtures / FakeMatchRepository
│   ├── rules/                          # R01–R14 单测
│   ├── knowledge/                      # K01–K10 视角与知识边界
│   ├── export/ data/ feature/          # 导入导出、数据层、记录流程
│   └── audit/                          # 本轮审计新增的独立验收包（11 类 / 48 例）
├── app/src/androidTest/                # 仪器测试（2 个文件，需设备；本机 NOT RUN）
├── app/src/test/resources/fixtures/    # TDD §11 真实案例夹具
├── docs/                               # 架构、任务、设计规范、决策、测试报告
│   ├── audit/                          # baseline / progress / migration-plan / qa-phase1 / qa-phase-d / final-audit
│   └── release/                        # 版本发布说明（v0.4.0.md）
├── gradle/libs.versions.toml           # 唯一版本目录
└── gradlew / gradlew.bat / gradle/wrapper/
```

## 7. 已知限制

1. **仪器测试（`connectedDebugAndroidTest`）未执行**：测试文件已编写（`androidTest/MatchDaoTest.kt`、`androidTest/MigrationV3ToV4Test.kt`），但本机无设备/模拟器。其中 `MigrationV3ToV4Test` 是 `@ColumnInfo(defaultValue)` 一致性的**唯一严格把关点**，有设备时请优先跑它。
2. **真机 UI 手测未做**：1.3× 字体、TalkBack、文件选择器、系统分享面板、杀进程恢复——无设备。UI 仅有编译期与状态层保证。
3. **v4 不可直接回退旧 APK**：Room identityHash 变化后，旧版 APK 打不开数据库。回退只能恢复 `.bak.v3` 备份，且会丢失备份之后的记录。
4. `analysis_reports` 表暂不使用（预留缓存表），删除需显式迁移，故保留。
5. 手动 DI 无编译期注入图校验；新增 ViewModel 需在 `di/ViewModelFactories.kt` 手工登记。
6. 依赖版本仍是 2024 年的稳定线（AGP 8.5.2 / Kotlin 2.0.21 / Room 2.6.1）。审计判定「升级必须独立 PR、递进兼容矩阵」，本轮未做——见 `docs/audit/final-audit.md` RT-依赖段。

## 8. 相关文档

| 文件 | 内容 |
|---|---|
| `docs/ARCHITECTURE.md` | 分层、领域模型、Room 表、事件顺序模型、接口签名、reducer 规则表、视角契约 |
| `docs/TASKS.md` | 65 个任务与逐条验收标准 |
| `docs/DESIGN_SPEC.md` | 颜色/字号/间距/组件规格与可访问性验收 |
| `docs/decisions.md` | 与上游文档的差异与裁决理由（B1–B37） |
| `docs/TEST_REPORT.md` | 测试执行记录与结论 |
| `docs/audit/` | 本轮代码审计的完整轨迹：`baseline`（基线）、`progress`（阶段进度）、`migration-plan`（RT-003 方案）、`qa-phase1`/`qa-phase-d`（独立验收）、`final-audit`（RT-001~031 终态） |
| `docs/release/v0.4.0.md` | v0.4.0 发布说明：数据升级、行为变化、回退与备份恢复 |
| `docs/IMPLEMENTATION_PLAN_AI_CODING.md` | 本轮 A→E 五阶段执行方案与 V01–V12 验证矩阵 |
| `01_产品需求文档_PRD.md` / `02_技术实施文档_TDD.md` | 上游需求与技术实施文档 |
