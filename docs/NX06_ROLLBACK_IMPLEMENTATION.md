# NX-06 回滚逻辑重构 — 实施文档

## 1. 涉及文件

| 文件 | 改动 |
|---|---|
| `feature/record/RecordScreen.kt` | 去掉回滚确认对话框；路线图点击改成 setEditQuest |
| `feature/record/RecordViewModel.kt` | 加 editQuest 状态；修改提交时检测影响并提示 |
| `feature/record/RecordUiState.kt` | 加 editQuest 字段、ImpactWarning 数据类 |
| `core/data/RoomMatchRepository.kt` | 已有 retractAfterOrder，复用 |

## 2. 实施步骤

### Step 1：加 editQuest 状态
- RecordUiState 加 `editQuest: Int?` 字段（null = 正常记录模式）
- RecordViewModel 加 `setEditQuest(n: Int)` 方法
- 路线图点击回调从 `RollbackToQuest(n)` 改成 `SetEditQuest(n)`

### Step 2：去掉回滚确认对话框
- RecordScreen 里 `showRollbackConfirm` 相关代码删掉
- 点路线图任务号 → 直接调 `SetEditQuest(n)`

### Step 3：修改提交时检测影响
- 当 `editQuest != null` 且用户提交了修改（reviseEvent）：
  - 如果修改的是任务结果（MissionResolved）→ 提示"会重置后续所有任务记录"
  - 如果修改的是提案（TeamProposed）→ 提示"会重置该任务的投票和结果"
  - 如果修改的是投票（TeamVoteRecorded）→ 不提示，直接改
- 用户确认后，才调 retractAfterOrder 删除受影响的后续事件

### Step 4：UI 提示
- 顶部显示横幅："正在编辑：第 N 任务（修改后会影响后续记录）"
- 底部"完成编辑"按钮 → 退出编辑模式，回到最新位置

## 3. 风险

- 编辑模式下如果用户连续修改多条记录，影响范围可能累积——简化处理：每次修改都重新计算影响范围，以最大范围为准
- 现有 reviseEvent 已经是事务化的，不会半提交
