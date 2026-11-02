# RoundTrace 下一轮实施方案与 AI Coding 指令

日期：2026-11-02

状态：方案交付，待执行。

需求权威来源：[NEXT_ITERATION_REQUIREMENTS.md](NEXT_ITERATION_REQUIREMENTS.md)。本文定义实施顺序与验证方式，需求编号 NX-01～NX-05 的业务含义以该文件为准。

## 1. 开始条件

1. 阅读适用的 `AGENTS.md`、`docs/decisions.md` 中 B29/B34/B35/B37，以及最新 `docs/TEST_REPORT.md` §13、`docs/audit/final-audit.md`。
2. 记录当前 HEAD、工作区差异及设备清单。当前已有未提交的 P1 修复、队长交互及测试成果；逐项保留，避免覆盖。
3. 原 `IMPLEMENTATION_PLAN_AI_CODING.md` 用于前一轮历史编辑实施，本文负责新批次。历史阶段的“无设备”“待修 P1”以最新报告和现有代码核实。
4. 建立 `docs/audit/next-iteration-progress.md`，记录各需求状态、具体测试名、性能数据与设备证据。首先重跑与即将修改的部分相关的测试，失败时记录原因。

完成标准：能明确区分既有成果、本轮改动和待验证项，并可从当前工作区继续实施。

## 2. 阶段 A：修正推论与数据质量（NX-01）

主要入口：

- `app/src/main/java/com/roundtrace/app/core/knowledge/AvalonEvidenceAnalyzer.kt`
- `core/knowledge/AvalonKnowledgeProvider.kt`、`TeamCountBounds.kt`
- `core/model` 中 `EvidenceFinding`、`KnowledgeSnapshot` 的实际定义
- `app/src/test/java/com/roundtrace/app/knowledge/EvidenceAnalyzerTest.kt`、视角隔离测试

实施：

1. 先增加会暴露现状的用例：8 人第 4 任务 1 失败仍成功，只有成功观察值而牌数未知，以及成员缺失但结果明确。
2. 修正 A04/A12 的触发条件与文案，分别描述结果、牌数、队伍质量。保持 B34 的胜负计数，不以 `verified` 重新设置计数闸门。
3. 对每条产生具体身份/数量结论的规则列出输入前提；用参数化或表驱动案例覆盖畸形数量、座位与冲突记录。
4. 优先在分析入口或小型纯函数中统一判定证据前提。是否可推断具体玩家、是否可给任务数量下界、是否可展示门槛说明分别判断。
5. 保留原始事件，产出可追溯到源事件的质量提示；延续当前视角与逻辑位置过滤。

完成标准：NX-01 案例通过，正常输入输出语义保持正确；公开/个人/上帝视角和历史选点测试通过。记录任何修改既有断言的业务理由。

## 3. 阶段 B：统一事务快照与追加复核（NX-02）

主要入口：`core/data/RoomMatchRepository.kt`、`core/database/dao`，以及现有 Room 仓储、跨对局、原子操作测试。

实施：

1. 为 `getBundle` 的跨表读取、`observeBundle` 的组合发射、首次提案并发补建任务各准备一个受控时序案例。检验实际风险，记录可复现条件。
2. 增加仓储内部一致读取方法，在 Room transaction 中完成元数据、成员、身份和有效事件读取及映射。避免循环调用公共方法造成难以判断的事务边界。
3. 响应式读取可保留四表变化作为失效信号，每次信号触发一致事务读取；或使用经验证的 Room `@Transaction` 关系查询。仅给现有四路 Flow 外包注解不能作为一致性证明。
4. 将追加依赖的状态读取、必要校验、自动补建 `QUEST_STARTED` 和事件写入放入同一事务。审查修订/揭晓/回滚对同一快照方法的使用，保持原子操作行为。
5. 确认规则校验返回 issues 与拒绝写入的现有契约，避免顺手改变现场容错和草稿处理策略。事务期间不进行外部 IO；取消异常继续传播。

完成标准：同局并发首次提案只补建一个相应任务开始事件；中途失败不留下部分写入；监听发射满足跨表不变量；原有原子揭晓、回滚、修订、跨局隔离回归通过。记录一致读取成本供阶段 C 对比。

## 4. 阶段 C：首页订阅与复盘复用（NX-03）

### C1. 首页

入口：`feature/home/HomeViewModel.kt`。

1. 用可计数的 repository fake 验证两位 UI 收集者时的底层订阅数，以及筛选切换、异常与重试行为。
2. 将加载、数据、错误组成单一查询结果流，通过共享状态供界面读取。错误处理放在可被重试重新创建的查询分支内。
3. 移除专门为关闭 loading 而常驻收集同一冷 Flow 的路径；确认无人订阅后的生命周期符合 `WhileSubscribed` 策略。

完成标准：当前筛选只保留一份有效底层查询订阅；查询异常可显示并重试恢复，空列表与加载状态可区分。

### C2. 复盘

入口：`feature/replay/ReplayViewModel.kt`、`core/knowledge/ReplayAnalyzer.kt`、`AvalonEvidenceAnalyzer.kt`。

1. 固定同一批对局数据、选点和视角，用计数包装器记录 snapshot 调用次数，记录现状。
2. 公开探测快照在有效视角为 PUBLIC 时直接作为最终快照；其他有效视角只建立对应的必要快照。
3. 将最终快照用于分析。抽取或复用按同一逻辑位置与视角裁剪的历史入口，保证 `analyze` 接收的历史与快照一致；继续保留分析器自身的可见性防御。
4. 复用限于同一输入计算内；事件、身份、揭晓时间、观察座位、选点或视角变化时重建。若涉及 B29 的解释，记录“同次计算复用”的适用范围。
5. 如将计算迁移到后台调度，使用取消或版本判据避免旧计算覆盖新选择；用测试验证连续快速切换的最终状态。

完成标准：普通公开复盘每次有效输入只需一次 snapshot，个人/上帝视角最多公开探测加目标视角两次；分析入口不额外重建快照。可解释的例外记录原因。原有视角、修订、撤销、时间线与上帝视角闸门测试通过。

### C3. 性能证据

用固定随机种子或静态夹具，至少覆盖小局与较长历史。每个变更点记录输入规模、设备/JVM、预热方式、采样次数、耗时中位数和调用计数。耗时用于同环境对照；CI 以语义和调用计数断言为主，避免毫秒阈值造成不稳定失败。不要预先承诺未经测量的提速比例。

## 5. 阶段 D：CI 定义与设备验收（NX-04、NX-05）

1. 编写 `.github/workflows/android.yml` 或职责清楚的等价文件。基础作业执行 wrapper 的 `testDebugUnitTest`、`lintDebug`、`assembleDebug`，上传单测、lint 报告与 APK。
2. 项目 JDK 为 17；从项目配置读取 Gradle、SDK 和依赖版本。CI 使用已验证的镜像或官方来源，下载校验与 Action 引用在实施时核实。
3. 将模拟器仪器测试放入独立作业或 `workflow_dispatch`；首先覆盖已有 API 34 路径，再按条件补充较低 API 验证。配置并发组，避免同一分支无用的重复构建。
4. 本地先执行相同任务；没有远端执行权限/运行记录时，将 CI 状态标为“定义已交付，远端未验证”。发布到 GitHub 后的首个运行另附链接与结果。
5. 按需求文档 NX-05 表逐条记录设备验收，分清模拟器与实体设备。无法复现的 WAL busy、缺少实体设备等写明缺失条件，继续其他独立项。

完成标准：CI 文件可审查、本地对应任务通过；设备验收逐项有结果或具体缺失条件。远端工作流和真机未执行项不得记为通过。

## 6. 集成验证与交付

各阶段先运行针对性测试；集成后执行：

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug `
  --no-daemon --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
./gradlew.bat :app:connectedDebugAndroidTest --no-daemon --console=plain `
  '-Pkotlin.compiler.execution.strategy=in-process'
```

本机 SDK/JDK 路径参见 `TEST_REPORT.md` §13；wrapper 不可用时记录原因后使用已验证的同版本独立 Gradle。依赖已缓存时可用离线模式；CI 首次下载依赖时使用联网构建。

交付记录包括：

- NX-01～NX-05 各项的实现、自动化、设备验证状态与缺失条件。
- 实际 HEAD 与工作区差异；保留了哪些前一轮成果。
- 测试类/用例、失败与跳过数，lint 错误/警告数；失败根因与处理。
- 同负载性能对照，底层订阅数与 snapshot 调用数。
- APK 实际路径、版本和大小；CI 定义及远端运行状态。

同步 `docs/TEST_REPORT.md`、`docs/audit/final-audit.md`、适用的发布说明及进展记录。本文不指定新版本号；发布时按实际发布计划确定。CI 文件与代码先交付本地可审查差异，提交、推送和对外发布按用户授权执行。

## 7. 可复制的 AI Coding 指令

```text
请在当前 RoundTrace 工作区执行 docs/NEXT_ITERATION_IMPLEMENTATION_AI_CODING.md。
业务需求以 docs/NEXT_ITERATION_REQUIREMENTS.md 的 NX-01～NX-05 为准。

先核实 HEAD、git diff、适用 AGENTS.md、最新验证记录及设备条件，保留已有
RT-008～RT-014、队长/成员交互和所有未提交成果。按 A 推论语义与证据质量、
B 事务快照、C 首页/复盘效率、D CI 与设备验收的顺序实施。

优先补会暴露当前缺陷的测试，再修实现。A04 按任务门槛解释成功；A12 分别说明
牌数与成员缺失影响。遵守 B34：结果明确即计数，verified 表示可复核性。
畸形记录依照具体规则前提抑制不可靠推论，保留现场事实与冲突提示。
事务读取及追加复核需要受控并发证据；复盘只在同次计算内复用快照，修订和视角
切换后重算，继续保持时间、可见性与身份揭晓闸门。

每阶段验证针对性用例，再集成运行单测、lint、APK 构建及可用设备仪器测试。
性能按同负载调用次数和测量数据报告。CI 定义完成与远端通过分别记录；真机、
WAL busy 等缺条件的验证如实列出，同时继续独立工作。

本轮实施边界为 NX-01～NX-05。需求文档的后续批次表用于排期，不自动扩成
SDK 升级、R8、其他游戏或历史数据修复任务。完成后更新测试报告、审计终态、
适用发布说明与进展文件，交付本地代码差异、测试证据、性能对照和 APK。
未经另外授权不执行提交、推送或对外发布。
```
