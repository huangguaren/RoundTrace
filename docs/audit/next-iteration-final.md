# NX-01～NX-05 最终实施报告

日期：2026-11-02
基线：a0aa687（v0.4.0）

## 状态总览

| 阶段 | 需求 | 状态 | 说明 |
|---|---|---|---|
| Phase A | NX-01 推论准确性 | DONE | A04/A12 文案修正，冲突/畸形检测，9 个新测试 |
| Phase B | NX-02 事务一致性 | DONE | appendEvent 全事务化，readBundleLocked 关闭撕裂读 |
| Phase C1 | NX-03 首页订阅优化 | DONE | HomeViewModel 去掉第二订阅 |
| Phase C2 | NX-03 复盘快照复用 | DONE | PUBLIC 视角复用 probe，不重复计算 |
| Phase C3 | NX-03 性能对比文档 | PARTIAL | 已做优化，未写 before/after 测量文档 |
| Phase D1 | NX-04 GitHub Actions | DONE | .github/workflows/android.yml 已写，远端未验证 |
| Phase D2 | NX-04 技术债清理 | NOT STARTED | 低优先级，留后续 |
| Phase E | NX-05 设备验收 | PARTIAL | 模拟器已装可手测，真机无设备 |

## 修改文件清单

| 文件 | 改动 |
|---|---|
| core/knowledge/MissionEvidencePremise.kt | 新增：任务证据前提纯函数 |
| core/knowledge/AvalonEvidenceAnalyzer.kt | 改造：按 premise 驱动 A03/A04/A06/A12 |
| test/knowledge/EvidencePremiseTest.kt | 新增：9 个场景测试 |
| core/data/RoomMatchRepository.kt | appendEvent 全事务化，新增 readBundleLocked |
| feature/home/HomeViewModel.kt | 去掉单独 loading collect |
| feature/replay/ReplayViewModel.kt | PUBLIC 视角复用 probe |
| feature/record/RecordUiState.kt | 加 editingQuest/impactWarning |
| feature/record/RecordViewModel.kt | 加编辑模式状态机+影响提示 |
| feature/record/RecordScreen.kt | 加编辑横幅+影响对话框 |
| feature/create/CreateMatchScreen.kt | 座位拖拽互换用格子坐标精确匹配 |
| core/ui/component/VoteRecorder.kt | 加 aspectRatio(1f) 修复头像过大 |
| .github/workflows/android.yml | 新增 CI 工作流 |

## 测试证据

- JVM 单测：全量通过（433+9 新测试）
- assembleDebug：通过
- APK：已装到 emulator-5554
- 仪器测试：NOT RUN（本轮未新增仪器测试）
- Lint：未跑（本轮未改动 lint 配置）

## 剩余问题

1. C3 性能对比文档未写（优化已做，缺测量数据）
2. D2 技术债清理未做
3. E 真机验收未做（无实体设备）
4. CI 远端未验证（未推送）
