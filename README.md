# RoundTrace（局迹）

阿瓦隆线下对局记录与复盘 App（Android 原生 · Kotlin · Jetpack Compose）。

> **状态**：M0–M5 全部完成（T01–T65）。208 个 JVM 单测全绿，debug APK 可直接安装。
> 功能覆盖：创建对局 → 现场记录（提案/投票/任务牌/发言）→ 结局揭晓（角色/胜负/刺杀）→ 视角复盘（梅林/派西维尔/忠臣/坏人/上帝）→ JSON/Markdown 导入导出与分享 → 设置（主题/默认人数/隐私）。

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

本轮已实现的规则用例：**M1–M5 共 208 个**（领域规则 + 数据修订 + 知识边界 + 导入导出，全绿）。
用例与文件的对应关系见 `docs/TEST_REPORT.md` 与 `docs/TASKS.md`。

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
  core/replay   ReplayEngine（过滤 retracted → 按 occurredOrder 排序 → 逐步 step）/ TimelineBuilder
Data
  core/database Room（M2）/ core/data 端口与实现 / core/export 导入导出（M5）
Platform
  RoundTraceApplication + AppContainer（手动 DI）
```

三条不变式：

1. **事件表是唯一历史**。`GameState`、任务得分、时间线、复盘结论全部由 `ReplayEngine` 从事件重算，不落库为权威数据。
2. **未知即未知**。`null`（未记录）与 `0`（已知为零）语义分离；`UNKNOWN` 枚举显式存在；未核实任务不参与胜负计数。
3. **组队反对票 ≠ 任务失败牌**。数据模型、UI、导出三个层面都不共用字段。

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
│   ├── di/AppContainer.kt              # 手动 DI（appScope / Clock / IdGenerator）
│   ├── navigation/                     # Routes + RoundTraceNavHost（3 Tab）
│   ├── core/util/IdGenerator.kt        # 可注入 ID 生成器与固定时钟
│   ├── core/ui/theme/                  # Color / Type / Shape / Dimens / Theme
│   ├── core/ui/component/              # RoundTraceScaffold / EmptyState / StatusBadge
│   ├── core/model/                     # 领域模型（纯 Kotlin）
│   ├── core/rules/                     # 规则接口 + avalon 实现（纯 Kotlin）
│   ├── core/replay/                    # ReplayEngine / TimelineBuilder（纯 Kotlin）
│   ├── core/data/EventRevisionPolicy.kt # 修订策略（纯 Kotlin）
│   └── feature/home/                   # HomeUiState + HomeScreen（无状态）
├── app/src/test/java/com/roundtrace/app/
│   ├── util/                           # MatchBuilder / RoleFixtures / AvalonFixtureTest
│   ├── rules/                          # R01–R14 单测
│   └── data/EventRevisionPolicyTest.kt
├── app/src/test/resources/fixtures/    # TDD §11 真实案例夹具
├── docs/                               # 架构、任务、设计规范、决策、测试报告
├── gradle/libs.versions.toml           # 唯一版本目录
└── gradlew / gradlew.bat / gradle/wrapper/
```

## 7. 已知限制

1. 仪器测试（`connectedDebugAndroidTest`）尚未编写，需在真机/模拟器上回归。
2. `analysis_reports` 表在 P0 不使用（P1 缓存预留）。
3. 真机 UI 手测（1.3x 字体、TalkBack、文件选择器、系统分享面板）尚未做——无设备。
4. 手动 DI 无编译期注入图校验；新增 ViewModel 需在 `di/ViewModelFactories.kt` 手工登记。

## 8. 相关文档

| 文件 | 内容 |
|---|---|
| `docs/ARCHITECTURE.md` | 分层、领域模型、Room 表、接口签名、reducer 规则表、视角契约 |
| `docs/TASKS.md` | 65 个任务与逐条验收标准 |
| `docs/DESIGN_SPEC.md` | 颜色/字号/间距/组件规格与可访问性验收 |
| `docs/decisions.md` | 与上游文档的差异与裁决理由 |
| `docs/TEST_REPORT.md` | 测试执行记录与结论 |
| `01_产品需求文档_PRD.md` / `02_技术实施文档_TDD.md` | 上游需求与技术实施文档 |
