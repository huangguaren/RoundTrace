# 交接件（handoff）—— 复制即用，不用手敲

> 目的：把「设计侧已定稿」的东西做成**可直接落地**的文件，编码侧只做复制 + 构建，不再来回问。
> 两个文件都不在编译路径上，放 `docs/` 下，随时可弃。

| 文件 | 用途 | 状态 |
|---|---|---|
| `Color.silicon-pink.kt` | 硅粉配色的**完整 `Color.kt`**：48 个色值已换好，注释/结构/常量名一字未动 | ✅ 待应用 |
| `strings_delta.xml` | **只含缺口**的 14 条文案（12 条规则名 + 兜底 + 导出正文） | ✅ 待合并 |

> ⚠️ 原来的 `review_strings.xml`（26 条）**已删除**：主线 15:23 已自行实现
> `perspective_*` / `evidence_level_*` / `evidence_*` / `replay_*` / `reveal_*` / `claim_*` 共 80+ 条，
> 再合并会造出两套同义 key。现在只补真正缺的。

---

## 1. 换配色（3 步，可回滚）

```bash
cd RoundTrace

# ① 备份 + 覆盖（只动这一个文件）
cp app/src/main/java/com/roundtrace/app/core/ui/theme/Color.kt /tmp/Color.kt.bak
cp docs/handoff/Color.silicon-pink.kt app/src/main/java/com/roundtrace/app/core/ui/theme/Color.kt

# ② 构建（增量即可，不必 clean）
./gradlew :app:assembleDebug --no-daemon --console=plain

# ③ 出问题回滚
cp /tmp/Color.kt.bak app/src/main/java/com/roundtrace/app/core/ui/theme/Color.kt
```

**为什么只改一个文件**：`Theme.kt` 只做装配（逐位映射到 `lightColorScheme`/`darkColorScheme`），
`Dimens`/`Shape`/`Type` 与颜色无关，Composable 里没有 `Color(0xFF...)` 字面量（TEST_REPORT §5 的 D5 已证明零命中）。

**覆盖后先看这两屏**（粉色面积最大）：首页（FAB / 筛选 chip / 卡片徽标）、现场记录（主按钮 / 选中座位 / 当前任务点）。
`SeatPicker`、`QuestStepper`、`StatusBadge` 只消费语义 Token，**不用改代码**。

---

## 2. 复盘页两处「机器语言漏到界面」（R6 / R7）

`EvidenceCard.kt` 现在把内部标识直接打在卡片上，线上看起来会像 bug。
`strings_delta.xml` 里的 `rule_a01..a12` 就是为这两处准备的。

### R6 · `ruleId` 原样显示（`EvidenceCard.kt:126`）

现在：`Text(text = finding.ruleId)` → 用户看到 `A05_TEAM_CONTAINS_EVIL`。

建议：加映射函数（放 `core/ui/`，别放领域层——D6 约定领域层不拼文案），样式保持 `labelSmall`：

```kotlin
// core/ui/RuleLabels.kt
@Composable
fun ruleLabel(ruleId: String): String = when (ruleId) {
    AvalonEvidenceAnalyzer.RULE_A01 -> stringResource(R.string.rule_a01)   // 队伍人数
    AvalonEvidenceAnalyzer.RULE_A02 -> stringResource(R.string.rule_a02)   // 投票未通过
    AvalonEvidenceAnalyzer.RULE_A03 -> stringResource(R.string.rule_a03)   // 失败牌达到阈值
    AvalonEvidenceAnalyzer.RULE_A04 -> stringResource(R.string.rule_a04)   // 任务成功不能洗白队员
    AvalonEvidenceAnalyzer.RULE_A05 -> stringResource(R.string.rule_a05)   // 队伍含坏人
    AvalonEvidenceAnalyzer.RULE_A06 -> stringResource(R.string.rule_a06)   // 失败牌数暗示队内有坏人
    AvalonEvidenceAnalyzer.RULE_A07 -> stringResource(R.string.rule_a07)   // 无法定位出失败牌的人
    AvalonEvidenceAnalyzer.RULE_A08 -> stringResource(R.string.rule_a08)   // 声称不等于事实
    AvalonEvidenceAnalyzer.RULE_A09 -> stringResource(R.string.rule_a09)   // 队长顺序
    AvalonEvidenceAnalyzer.RULE_A10 -> stringResource(R.string.rule_a10)   // 连续否决次数
    AvalonEvidenceAnalyzer.RULE_A11 -> stringResource(R.string.rule_a11)   // 夜间已知坏人
    AvalonEvidenceAnalyzer.RULE_A12 -> stringResource(R.string.rule_a12)   // 数据不完整
    else -> stringResource(R.string.rule_unknown)
}
```

> 引用常量而不是字面量：改名后是编译错误，不是静默失配。

### R7 · 证据事件 id 原样显示（`EvidenceCard.kt:139–146`）

现在：`finding.sourceEventIds.joinToString(", ") { it.raw }` → 用户看到一串内部 id。

建议：**先给条数，展开再看 id**。

```kotlin
text = if (finding.sourceEventIds.isEmpty()) {
    stringResource(R.string.evidence_no_direct_source)
} else {
    stringResource(R.string.evidence_source_ids) + " ${finding.sourceEventIds.size} 条"
}
```

理由：`EventId.raw` 由 `IdGenerator` 产生，对人没有意义；真要追溯就放卡片展开区
（DESIGN_SPEC §4.8「点击展开证据与原始 payload」），那才是唯一该看 id 的地方。

---

## 3. 画板与代码的差异（**以代码为准**，设计侧已认账）

| 项 | 代码实现 | 说明 |
|---|---|---|
| 视角说明行 | `perspective_current_line`「当前视角：%1$s，截至第 %2$d 个事件」 | 比画板的「截至第 3 任务」更精确（用 `occurredOrder`），**采纳代码版本** |
| 视角前缀 | `perspective_current_public` / `_observer` / `_god` | 画板只画了 observer 一种 |
| 按规则重建提示 | `perspective_reconstructed_banner`（已实现） | 与 15:19 给的规格一致 |
| 上帝视角横幅 | `perspective_god_banner`（已实现） | 与 DESIGN_SPEC §4.9 一致 |
| 时间位置选择 | `replay_select_quest` / `replay_select_event` / `replay_at_end` | **画板没有这组控件**，代码自建且合理；补画板时按代码形态画 |

---

## 4. 不用做的事（避免重复劳动）

- 不用重跑单测验证颜色 —— 颜色不影响任何断言；
- 不用改 `Theme.kt` / `StatusBadge` / `SeatPicker` / `PerspectiveSwitcher` —— 只消费语义 Token；
- 不用等我重上色画板 —— 等 `Color.kt` 落地 + 设备确认后我一次性改，避免「一半蓝一半粉」；
- 不用为 `perspective_*` / `evidence_level_*` 再造文案 —— 已经够了。
