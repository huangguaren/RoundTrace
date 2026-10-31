# RoundTrace 审计基线

> 建立时间：2026-10-31 15:20（Asia/Shanghai）
> 执行人：主理人（Phase 0）

## 环境

| 项 | 实测值 |
|---|---|
| OS | Windows 11（Git Bash） |
| JAVA_HOME | `C:\Users\HK-info\android-build\jdk`（OpenJDK 17.0.13 Microsoft） |
| ANDROID_HOME | `C:\Users\HK-info\android-build\sdk`（platform-34 / build-tools 34.0.0） |
| Gradle | 8.7（wrapper，腾讯镜像） |
| HEAD | `a6da7c4f01a99687019492dd1b0a8179e32adc4a`（= 审计报告基线，未回退） |
| 分支 | `main` |
| 工作区 | 有 2 个未提交改动（已有 P0 半成品，见下）+ `docs/audit/` 未跟踪 |

## 基线命令与结果

```bash
export JAVA_HOME="/c/Users/HK-info/android-build/jdk"
export ANDROID_HOME="/c/Users/HK-info/android-build/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :app:testDebugUnitTest --rerun-tasks   # BUILD SUCCESSFUL in 1m 31s
./gradlew :app:lintDebug                          # BUILD SUCCESSFUL in 1m 33s
./gradlew :app:assembleDebug                      # BUILD SUCCESSFUL
```

| 项 | 结果 |
|---|---|
| 单元测试 | **208 用例 / 0 失败 / 0 错误**（`--rerun-tasks` 强制全量） |
| lint | **0 error / 54 warning** |
| APK | 可构建（`app/build/outputs/apk/debug/app-debug.apk`） |
| instrumented test | **NOT RUN**（无设备/模拟器） |
| 依赖树 | 待 Phase 4 记录（`docs/audit/dependencies.md`） |

## 基线期发现并修复的问题（Phase 0 顺手修复，非审计条目）

**LINT-001（新引入）**：`app/src/main/res/values/themes.xml` 在基础 `values/` 中使用了
`android:windowSplashScreenBackground` / `windowSplashScreenAnimatedIcon`（API 31 属性），
`minSdk=26` 触发 lint `NewApi` **Error**，`lintDebug` 直接失败（v0.3.0 引入，2026-10-30 16:53
的 lint 通过记录之后）。

- 修复：splash 属性迁到 `values-v31/themes.xml`，并新增 `values-night-v31/themes.xml`
  （深色 splash 背景取 `DarkSurface #1A181C`，避免深色模式闪白）；基础主题只保留 API 26 可用属性。
- 验证：`lintDebug` 0 error / 54 warning。

## 已有未提交改动（前序会话遗留，已核实、继续保留）

| 文件 | 内容 | 对应审计项 |
|---|---|---|
| `core/database/dao/MatchDao.kt` | 新增 `updateStatusOnly(id,status,now)`、`updateRevealedAt(id,revealedAt,now)` | RT-001 |
| `core/data/RoomMatchRepository.kt` | `appendEvent` 状态变更改调 `updateStatusOnly`（不再传 `revealedAt=null`）；`reviseEvent`/`retractEvent` 增加 `targetEntity.matchId == matchId.raw` 前置校验 | RT-001 / RT-002 |

> 这两处只有实现、**尚无回归测试**，且 RT-002 的 DAO UPDATE 仍未带 `matchId` 条件（存在检查-更新竞态窗口），
> RT-004 状态投影、RT-005 视角过滤、RT-003 修订顺序均未处理。Phase 1 继续收口。

## Phase 1 任务分派

| 任务 | 负责 | 状态 |
|---|---|---|
| P0-A：RT-001 回归测试 + RT-002 DAO 级归属收口 | software-engineer | 进行中 |
| P0-B：RT-004 状态投影统一重算（append/revise/retract/import） | software-engineer | 进行中 |
| P0-C：RT-005 视角可见性统一策略（PUBLIC 不泄漏 PRIVATE） | software-engineer | 进行中 |
| P0-D：RT-003 修订逻辑顺序 —— 迁移方案 RFC（先设计后动 Schema） | software-architect | 进行中 |
| P0 独立验收 | software-qa-engineer | 待工程师提交后开始 |
