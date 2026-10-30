# TODO-DISPATCH（更新于 2026-10-30 16:40，M5 收尾完成）

> 状态：**M0–M5 全部完成，208 单测全绿，lintDebug 0 errors，APK 已出。**
> 上一版（15:30）列出的 A1/A2/A3/A5/B1/B5 全部修复；C1/C2/C3 全部完成。

---

## A. 设计侧遗留小 bug（**全部已修**）

- [x] **A1** RecordScreen 一行 4 控件（提案/投票/任务牌/撤销）每按钮仅 ~37dp 文案区 → **已拆两行**：行 1 主按钮+撤销图标，行 2 投票+任务牌（weight 平分，文案 13sp）。
- [x] **A2** EvidenceCard:126 `finding.ruleId` 直出（如 `A05_TEAM_CONTAINS_EVIL`）→ **已加 `ruleDisplayName()` 映射**，按 Axx 前缀查 strings.xml `rule_a01..rule_a12`（12 条中文规则名已合并进 strings.xml），未知兜底 `rule_unknown`。
- [x] **A3** 配色仍用旧蓝 #3956D8 → **已整体替换为硅粉 #AD4D68**（`Color.kt` 覆盖为 `Color.silicon-pink.kt`，含 48 个色值）。
- [x] **A5** RevealScreen:132/143 `.take(5)` 硬截 5 座位，7–10 人局 6/7/8 号选不到 → **已改为 `1..playerCount` 全座位 + `FlowRow` 自动换行**；刺客/刺杀目标两组各加小标题行。

## B. 上一手测发现（**全部已修**）

- [x] **B1** EvidenceCard:139-146 直接列 `sourceEventIds.raw`（UUID）→ **改为「证据事件 N 条」**（`evidence_source_count` 文案），不再列 UUID。
- [ ] **B2** Ardot 导出 PNG 缺失 → 本环境无 Ardot 工具，P0 不阻塞（非 App 功能）。
- [ ] **B3** Ardot 组件库补 roundRect → 同上，非 App 功能。
- [ ] **B4** 组件变体表落表 → 同上。
- [x] **B5** 刺杀座位 OutlinedButton 无选中态 → **已加选中态**：选中时 `primaryContainer` 底 + 2dp `primary` 描边 + contentDescription（复用 `seat_picker_desc_selected/unselected`），点击已选可取消。

## C. 主线待办（**全部完成**）

- [x] **C1** M5 导出/导入 UI 接 `ImportExportService` → 详情页（MatchDetailScreen）已接 CreateDocument/OpenDocument launcher + 分享确认弹窗；设置页「导入对局」按钮已接 OpenDocument launcher（原为假入口跳首页，已补真实导入 + 结果反馈）。
- [x] **C2** Markdown 摘要导出（T61）→ `MarkdownExporter` 已实现 8 节结构，反对票与失败牌严格分节，默认脱敏。
- [x] **C3** `lintDebug` 首跑 → **0 errors**（修了 backup_rules/data_extraction_rules 的非法 `domain="cache"`），37 warnings 记录在 TEST_REPORT §10.4。

## M5 编码侧补充修复（本轮）

- [x] 设置页 ViewModel 注入 `ImportExportService`，新增 `ImportJson` action + `importMessage` 状态。
- [x] 修复 3 个 M5 新测试失败：
  - JsonCodecTest 投票事件数断言（questCycle 自带一次投票，实际 3 个而非 2 个）；
  - MarkdownExporterTest `section()` 辅助函数对最后一节截取 bug（`substringBefore` 找不到分隔符返回空串）。

## 仍未做（无设备/非 P0）

- `connectedDebugAndroidTest` 仪器测试
- 真机 UI 手测（1.3x 字体、TalkBack、文件选择器、系统分享面板）
- Ardot 组件库导出与变体表
