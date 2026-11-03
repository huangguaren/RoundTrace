# 复盘性能与快照调用证据（NX-03-PERF）

**日期**：2026-11-03
**分支/基线**：`fix/nx03-retry-perf`，基线 `9d94040`
**对应需求**：`RoundTrace_Next_Requirements_2026-11-03.md` §4（RT-NX03-PERF）
**对应实施**：`RoundTrace_Next_Implementation_2026-11-03.md` §3（Phase C2/C3）

> **诚实声明**
> 1. 本文只报告**同环境、同提交、同夹具**下的测量值，**不承诺任何百分比提升**。
> 2. 基线 `9d94040` 的 `ReplayViewModel` **已经**在使用
>    `probe = snapshot(PUBLIC)` → 复用 → `analyzer.analyze(snapshot, visibleHistory)` 这条路径。
>    因此本轮**没有**「优化前 / 优化后」的耗时对照可报告：优化前的旧版本在本工作区不可复现，
>    相应数据标记为 **NOT MEASURED**。
> 3. 本文能提供的硬结论只有两类：**快照调用次数**（结构性、与机器无关）和
>    **同一提交内两条代码路径的对照**（`probe+analyze` vs `analyzeAt`）。
> 4. 耗时为 JVM 单测进程内的 `System.nanoTime()` 采样，**不是**设备端帧时间，
>    也不包含 Compose 重组、数据库 IO。

## 1. 测量环境

| 项 | 值 |
|---|---|
| JDK | OpenJDK 17.0.13 (Microsoft build 17.0.13+11-LTS) |
| 构建/运行模式 | Gradle `:app:testDebugUnitTest`，Debug 变体，JVM 单测（非 Robolectric） |
| 编译器执行策略 | `-Pkotlin.compiler.execution.strategy=in-process` |
| 操作系统 | Windows（MINGW64_NT-10.0-26200，x86_64），16 逻辑核 |
| 计时方法 | `System.nanoTime()` 包住单次调用；每个 stage 先预热 5 次，再采样 40 次 |
| 统计量 | 采样排序后按索引取 median（p50）与 p95；同时记录 min / max |
| 夹具 | 纯 JVM 构造的 `ReconstructedMatch`（不读数据库、不读设备） |
| 测试类 | `app/src/test/java/com/roundtrace/app/feature/ReplayAnalyzePerformanceTest.kt` |

样本规模两档：

| 夹具 | 构造 | 事件数（可见历史） |
|---|---|---|
| `small` | 8 人局，2 个完整任务 | 10 |
| `long` | 8 人局完整 5 任务 + 40 条公开发言 | 66 |

每个 stage 的重复次数：**40 次**（要求 ≥30），另有 5 次预热不计入统计。

## 2. 测量结果（原始数据，两次独立运行）

单位：纳秒（ns）。两次运行相隔数分钟、同一提交、同一夹具，用于展示机器噪声幅度。

### 2.1 运行 A

| 夹具 | stage | samples | median | p95 | min | max | snapshot 调用/次 |
|---|---|---|---|---|---|---|---|
| small | `snapshot_public` | 40 | 921,100 | 1,753,100 | 475,900 | 1,969,300 | 0（调用方建） |
| small | `analyze_public` | 40 | 140,400 | 239,900 | 86,700 | 297,600 | 0 |
| small | `probe+analyze` | 40 | 674,300 | 1,089,400 | 387,900 | 1,202,100 | 1 |
| small | `analyzeAt(自建快照)` | — | NOT MEASURED（运行 A 未含此 stage） | | | | 1 |
| long | `snapshot_public` | 40 | 2,067,200 | 3,778,800 | 951,200 | 6,571,400 | 0 |
| long | `analyze_public` | 40 | 315,500 | 845,800 | 135,600 | 1,133,800 | 0 |
| long | `probe+analyze` | 40 | 1,839,500 | 4,266,300 | 1,102,600 | 9,134,100 | 1 |
| long | `analyzeAt(自建快照)` | — | NOT MEASURED（运行 A 未含此 stage） | | | | 1 |

### 2.2 运行 B（含 `analyzeAt` 同环境对照）

| 夹具 | stage | samples | median | p95 | min | max | snapshot 调用/次 |
|---|---|---|---|---|---|---|---|
| small | `snapshot_public` | 40 | 453,900 | 814,800 | 309,200 | 1,003,800 | 0（调用方建） |
| small | `analyze_public` | 40 | 73,800 | 120,200 | 62,900 | 208,000 | 0 |
| small | `probe+analyze` | 40 | 330,800 | 734,000 | 268,900 | 2,412,900 | 1 |
| small | `analyzeAt(自建快照)` | 40 | 292,600 | 497,800 | 237,000 | 547,600 | 1（内部） |
| long | `snapshot_public` | 40 | 767,200 | 1,715,800 | 449,900 | 5,196,300 | 0 |
| long | `analyze_public` | 40 | 134,600 | 326,100 | 126,000 | 603,200 | 0 |
| long | `probe+analyze` | 40 | 576,200 | 804,700 | 444,000 | 890,800 | 1 |
| long | `analyzeAt(自建快照)` | 40 | 590,300 | 767,200 | 438,900 | 963,200 | 1（内部） |

观察（只描述事实，不换算成提升百分比）：

- 同一 stage 两次运行的 median 相差可达 **2 倍**（如 `small-snapshot` 921µs → 454µs），
  说明单机 JVM 单测环境的噪声大于本轮任何代码改动带来的差异。**因此耗时不能作为验收门槛。**
- 耗时随事件数增长：`long` 的 `analyze` median 约为 `small` 的 1.8–1.9 倍（同一运行内对照）。
- `analyze` 本身只占「一次复盘重算」的一小部分；**主要成本在快照构建**（`snapshot_public`）。
  这是本轮不做「跨事件全局分析缓存」的直接理由：缓存快照会立刻与 `eventRevision` 脱钩（B29）。

## 3. 快照调用次数（硬结论，与机器无关）

由 `ReplaySnapshotReuseTest` 用计数装饰器 `KnowledgeProvider`/`ReplayAnalyzer` 直接断言：

| 场景 | 快照次数 | 断言位置 |
|---|---|---|
| PUBLIC 视角首次有效输入 | **1**（公开探测即最终快照） | `PUBLIC 视角每次输入只创建一次快照` |
| 重复选择同一视角 | **0** 新增（`MutableStateFlow` 同值不发射） | `快速重复选择同一视角不产生无意义重算` |
| 切到个人视角 | **2**（公开探测 1 + 目标视角 1；其中 PUBLIC 计数 +1、OBSERVER 计数 +1） | `个人视角最多创建两次快照（公开探测 + 目标视角）` |
| 切到上帝视角（已揭晓） | **≤2** | `已揭晓时上帝视角最多创建两次快照` |
| 未揭晓请求上帝视角 | **0 次上帝快照**（被拒绝，模式不变） | `未揭晓时拒绝上帝视角且不构建上帝快照` |
| 切换时刻 | 必须 **>0**（重新计算，不复用） | `切换时刻必须重新计算且内容刷新` |
| 修订事件 / 撤销事件 | 必须 **>0** 且内容刷新 | `修订事件后重新计算并刷新内容` / `撤销事件后重新计算并刷新内容` |
| `analyze()` 内部 | **0**（快照由调用方建一次） | `ReplayAnalyzePerformanceTest` 计数断言 |

同环境结构对照：`probe + analyze` 每次输入 **1 次**快照；若页面改用
`probe + analyzeAt(...)`，则每次输入 **2 次**快照（`analyzeAt` 内部自建）。
本轮的 `ReplayViewModel` 走的是前者，因此**结构上**少建一次快照；
但因为它已存在于基线 `9d94040`，本轮**不声称**这是本轮带来的性能提升。

## 4. 首页查询订阅次数（NX-03-RETRY 的量化项）

由 `HomeViewModelTest` 的计数假仓储直接断言（`stateIn(WhileSubscribed(5s))` 语义）：

| 场景 | `observeSummaries` 调用 | 底层订阅次数 |
|---|---|---|
| 单个收集者首次收集 | 1 | 1 |
| 两个收集者同时收集 | 1（共享，不双订阅） | 1 |
| 停掉其中一个收集者 | 1 | 1 |
| 两个都停 + 超过 5s 停止超时 | 1 | 1 次取消（`summaryCancellations = 1`） |
| 停止超时后重新收集 | 2 | 2 |
| 首次失败 → `retry()` | 2 | 2（第 1 次抛异常结束订阅，重试新建订阅） |
| 连续失败两次 → 第三次成功 | 3 | 3 |

结论：**每次失败只结束一次订阅**，`retry()` 真正新建订阅并能恢复数据；
多收集者不会造成双订阅。

## 5. 未完成 / 未测量项（诚实标记）

| 项 | 状态 | 原因 |
|---|---|---|
| 「优化前 vs 优化后」耗时对照 | **NOT MEASURED** | 基线 `9d94040` 已包含 probe 复用路径，旧版本无法在本工作区复现 |
| 设备端（真机/模拟器）帧时间 | **NOT MEASURED** | 本轮 NX-05 设备验收已取消，且机器无可用设备 |
| 内存占用 / 分配次数 | **NOT MEASURED** | JVM 单测无稳定堆采样手段，未做 |
| 数据库 IO 在总耗时中的占比 | **NOT MEASURED** | 夹具不经过 Room，刻意隔离纯计算成本 |
| 提升百分比承诺 | **不提供** | 同一 stage 两次运行差异达 2 倍，任何百分比都不可信 |

## 6. 复现命令

```bash
export JAVA_HOME="/c/Users/HK-info/android-build/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew.bat :app:testDebugUnitTest --console=plain \
  '-Pkotlin.compiler.execution.strategy=in-process' \
  --tests "com.roundtrace.app.feature.ReplayAnalyzePerformanceTest"

# 原始数据在测试报告里，前缀 [PERF]：
grep -o "\[PERF\][^<]*" \
  app/build/test-results/testDebugUnitTest/TEST-com.roundtrace.app.feature.ReplayAnalyzePerformanceTest.xml
```
