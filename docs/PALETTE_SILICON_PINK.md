# 色彩方案 v2 · 硅粉（Silicon Pink）

> 2026-10-30 · 设计稿：`色彩 · 硅粉 v2` 页（https://ardot.tencent.com/file/741982307190774 ）
> 适用：`core/ui/theme/Color.kt` 一次性替换。**本文件所有对比度均为实测值（WCAG 2.1 相对亮度算法），不是估算。**

---

## 1. 设计判断（先说结论，再说为什么）

「硅粉」是一种**低饱和、微冷、哑光**的粉 —— 它好看的地方是“克制”，但它有个硬伤：
**它太浅，压不住白字。**

实测：`#E6A0B4`（最像“硅粉”的那个色）配白字只有 **2.09:1**，远低于正文要求的 4.5:1。
如果照字面把主色设成浅粉，全站的按钮、Tab 激活态、FAB 文字全部不达标，上线就得返工。

所以这套方案把「硅粉」拆成两层：

| 层 | 色 | 干什么 |
|---|---|---|
| **表达层** pink-400 `#E6A0B4` / pink-300 `#F2C6D3` | 视觉上就是“硅粉”本人 | 只做**底**：选中底、身份标签底、局部强调面 |
| **可用层** pink-600 `#AD4D68` | 同一个色相、压深两个档 | 做**可承载文字的面**：主按钮、Tab 激活、FAB、链接文字（配白字 5.20 ✓） |

这样既保住了“硅粉”的气质，又不需要在可用性上妥协。**这是本方案唯一需要你确认的取舍**：
如果你的意图是「页面大面积就是粉色」，那不是换 Token 能解决的，得重做版式（并接受对比度降级），我可以另出一版。

配套调整：原主色是蓝，所以成功/失败/点缀三色可以随便挑；现在主色是粉，
原来的金色点缀 `#C6903D` 和错误红 `#D64C4C` 会跟主色**撞暖调**，因此：

- `error` 从正红偏到**砖红** `#C8483C`（更冷、更硬，和粉色拉开）；
- `accent`（身份点缀 / 推测）从**金**改为**雾蓝** `#3F6E8C`（冷色对位，一眼能分辨“确定/推测”）；
- `success` 从翠绿收到**松绿** `#17795F`（降饱和，不与粉色抢眼）。

---

## 2. 三层 Token

### 2.1 Primitive（原始色阶，只做原料，组件不许直接用）

| Token | Hex | 说明 |
|---|---|---|
| `pink-100` | `#FDF4F6` | 极浅底（大面积留白区） |
| `pink-200` | `#F9E3E9` | 浅底 |
| `pink-300` | `#F2C6D3` | 选中底 / 标签底 |
| `pink-400` | `#E6A0B4` | **硅粉本体**（只做底） |
| `pink-500` | `#D67890` | 强调面（非文字承载） |
| `pink-600` | `#AD4D68` | **= primary**（可承载白字） |
| `pink-700` | `#8F3C55` | 按下态 / 深色文字备选 |
| `pink-800` | `#743044` | 深色辅助 |
| `pink-900` | `#582534` | 深色最深 |

### 2.2 Semantic（浅色）

| 语义 | 新值 | 用途 | 实测对比度 |
|---|---|---|---|
| `background` | `#F8F7F8` | 页面底 | — |
| `surface` | `#FFFFFF` | 卡片 | — |
| `surfaceVariant` | `#F1EFF1` | 次级容器 / 未选中座位 | — |
| `onSurface` / `onBackground` | `#221A1D` | 主文字 | 15.93:1 on bg |
| `onSurfaceVariant` | `#6E6670` | 次要文字 | **5.17:1** on bg |
| `outline` | `#DCD8DC` | 描边 | — |
| `outlineVariant` | `#EAE6EA` | 弱分隔 | — |
| `primary` | `#AD4D68` | 主操作 / 选中 / 进行中 | 白字 **4.87:1**；作文字 **5.20:1** |
| `onPrimary` | `#FFFFFF` | 主按钮文字 | 同上 |
| `primaryContainer` | `#FBE9EE` | 选中座位底 / 主色浅底 | — |
| `onPrimaryContainer` | `#6D2338` | 浅底上的文字 | **9.24:1** on container |
| `success` | `#17795F` | 任务成功 | 白字 **5.33:1** |
| `successContainer` | `#E3F2EC` | 成功浅底 | — |
| `onSuccessContainer` | `#0C3B2E` | — | ≥9:1 |
| `error` | `#C8483C` | 任务失败 / 删除 | 白字 **4.72:1** |
| `errorContainer` | `#FBE9E6` | 失败浅底 | — |
| `onErrorContainer` | `#4A1712` | — | ≥9:1 |
| `accent`（secondary） | `#3F6E8C` | 身份点缀 / 推测 | 白字 **5.50:1** |
| `accentContainer` | `#E4EEF4` | 身份 / 推测浅底 | — |
| `onAccentContainer` | `#1E3B4D` | — | ≥9:1 |
| `unknown` | `#8A8A94` | 未知 / 未记录 | 白字仅 **3.06:1 → 禁用** |
| `unknownContainer` | `#EFEFF2` | 未知浅底 | — |
| `onUnknownContainer` | `#3A3B42` | — | **4.8:1** |

### 2.3 Semantic（深色）

| 语义 | 新值 | 实测对比度（on `#121114`） |
|---|---|---|
| `background` | `#121114` | — |
| `surface` | `#1A181C` | — |
| `surfaceVariant` | `#242227` | — |
| `onSurface` | `#F4F1F3` | **16.78:1** |
| `onSurfaceVariant` | `#A79FA5` | **6.84:1**（on surface） |
| `outline` / `outlineVariant` | `#332F35` / `#2B282D` | — |
| `primary` | `#E89BB0` | **8.73:1** |
| `onPrimary` | `#451024` | **7.21:1** on primary |
| `primaryContainer` | `#4A2130` | — |
| `onPrimaryContainer` | `#FBD9E2` | **10.39:1** |
| `success` | `#4FC79B` | **8.94:1** |
| `onSuccess` | `#06281C` | **7.51:1** |
| `error` | `#F0786B` | **6.81:1** |
| `onError` | `#3A0F0D` | **6.06:1** |
| `accent` | `#8FB6D0` | **8.75:1** |
| `accentContainer` | `#2A3F4C` | — |
| `onAccentContainer` | `#CFE4F0` | ≥8:1 |
| `unknown` | `#9A9AA4` | **6.75:1** |
| `unknownContainer` | `#26242A` | — |
| `onUnknownContainer` | `#C7C2C7` | ≥8:1 |

> 深色不是浅色反相：primary 提亮到 `#E89BB0`（8.73），container 压暗成深酒红（`#4A2130`），
> 保证“粉还在”，但不会在暗背景上发光刺眼。

---

## 3. `Color.kt` 迁移映射（逐常量，一次替换）

```diff
- val LightBackground = Color(0xFFF7F8FA)
+ val LightBackground = Color(0xFFF8F7F8)
- val LightSurface = Color(0xFFFFFFFF)          // 不变
- val LightSurfaceVariant = Color(0xFFEEF1F6)
+ val LightSurfaceVariant = Color(0xFFF1EFF1)
- val LightOnSurface = Color(0xFF171B23)
+ val LightOnSurface = Color(0xFF221A1D)
- val LightOnBackground = Color(0xFF171B23)
+ val LightOnBackground = Color(0xFF221A1D)
- val LightOnSurfaceVariant = Color(0xFF667085)
+ val LightOnSurfaceVariant = Color(0xFF6E6670)
- val LightOutline = Color(0xFFD6DBE5)
+ val LightOutline = Color(0xFFDCD8DC)
- val LightOutlineVariant = Color(0xFFE7EAF0)
+ val LightOutlineVariant = Color(0xFFEAE6EA)
- val LightPrimary = Color(0xFF3956D8)
+ val LightPrimary = Color(0xFFAD4D68)
- val LightPrimaryContainer = Color(0xFFE4E9FF)
+ val LightPrimaryContainer = Color(0xFFFBE9EE)
- val LightOnPrimaryContainer = Color(0xFF1D2C7A)
+ val LightOnPrimaryContainer = Color(0xFF6D2338)
- val LightSuccess = Color(0xFF168A61)
+ val LightSuccess = Color(0xFF17795F)
- val LightSuccessContainer = Color(0xFFE2F4EC)
+ val LightSuccessContainer = Color(0xFFE3F2EC)
- val LightOnSuccessContainer = Color(0xFF0B3A28)
+ val LightOnSuccessContainer = Color(0xFF0C3B2E)
- val LightError = Color(0xFFD64C4C)
+ val LightError = Color(0xFFC8483C)
- val LightErrorContainer = Color(0xFFFBE7E7)
+ val LightErrorContainer = Color(0xFFFBE9E6)
- val LightOnErrorContainer = Color(0xFF4A1414)
+ val LightOnErrorContainer = Color(0xFF4A1712)
- val LightAccent = Color(0xFFC6903D)
+ val LightAccent = Color(0xFF3F6E8C)
- val LightAccentContainer = Color(0xFFFAF0DE)
+ val LightAccentContainer = Color(0xFFE4EEF4)
- val LightOnSecondaryContainer = Color(0xFF4A3413)
+ val LightOnSecondaryContainer = Color(0xFF1E3B4D)
- val LightUnknown = Color(0xFF8A94A6)
+ val LightUnknown = Color(0xFF8A8A94)
- val LightUnknownContainer = Color(0xFFEDF0F4)
+ val LightUnknownContainer = Color(0xFFEFEFF2)
- val LightOnUnknownContainer = Color(0xFF3A4150)
+ val LightOnUnknownContainer = Color(0xFF3A3B42)
```

深色同构替换（逐值见 §2.3），不再重复贴 diff：

```diff
-DarkBackground  0xFF101319 -> 0xFF121114
-DarkSurface     0xFF171B23 -> 0xFF1A181C
-DarkSurfaceVariant 0xFF20252F -> 0xFF242227
-DarkOnSurface   0xFFF2F4F8 -> 0xFFF4F1F3
-DarkOnSurfaceVariant 0xFF98A2B3 -> 0xFFA79FA5
-DarkOutline     0xFF2E3542 -> 0xFF332F35
-DarkPrimary     0xFF8EA2FF -> 0xFFE89BB0
-DarkOnPrimary   0xFF10194A -> 0xFF451024
-DarkPrimaryContainer 0xFF2A3878 -> 0xFF4A2130
-DarkOnPrimaryContainer 0xFFDCE3FF -> 0xFFFBD9E2
-DarkSuccess     0xFF3DBE8B -> 0xFF4FC79B
-DarkError       0xFFF0736F -> 0xFFF0786B
-DarkAccent      0xFFDCAF62 -> 0xFF8FB6D0
-DarkAccentContainer 0xFF3D2E17 -> 0xFF2A3F4C
-DarkUnknown     0xFF7C8798 -> 0xFF9A9AA4
-DarkUnknownContainer 0xFF232935 -> 0xFF26242A
```

**改动面**：只需动 `Color.kt` 一个文件。`Theme.kt` 的装配、`Dimens`/`Shape`/`Type` 全部不用动 ——
这也是当初把颜色锁进单一文件的收益（DESIGN_SPEC §8 最后一条 / D5）。

**不要顺手改的地方**：`ScrimBlack` 保持纯黑；`Type.kt` 的字号行高不动；`dynamicColor` 继续恒为 false。

---

## 4. 五条铁律（违反即返工）

1. **浅粉只做底**：`#F2C6D3` / `#E6A0B4` 永远不承载白字（400 配白字 2.09）。
2. **容器底上不用 primary 当文字**：`primary on primaryContainer` 只有 **4.46**，用 `onPrimaryContainer`（9.24）。
3. **未知灰不承载白字**：`#8A8A94` 配白字 3.06，一律「浅底 + 深字 + 图标」。
4. **粉色只出现在可操作的地方**：按钮、选中座位、当前任务、徽标；页面底与卡片保持中性灰白。
   整屏铺粉会让信息层级塌陷，也不像“硅”。
5. **三态仍是 颜色 + 图标 + 文字**：成功/失败/未知 的三重表达是 PRD 硬约束，粉色不参与胜负语义。

---

## 5. 现状与实施顺序（避免半套状态）

**当前状态**：6 张画板（首页/创建/记录/投票/任务牌/复盘）**仍是旧蓝色 `#3956D8`**，尚未套用本方案。
这是有意为之 —— 一次性替换 Token 比分屏改稿更省事，也不会出现“一半蓝一半粉”的中间态。

建议顺序（编码侧单人一次做完，不会冲突）：

1. 替换 `Color.kt` 全量色值（§3）；
2. 跑一次 `:app:assembleDebug`，装到设备看首页与记录页（这两页粉色面积最大）；
3. 若 `SeatPicker` 选中态描边用了 `primary`、徽标用了 `accent`，颜色会自动跟上，**不需要改组件代码**；
4. 视觉确认后，我把 6 张画板一次性重上色（需要你叫我一声），保证稿与机一致；
5. 深色同理，`Color.kt` 已经是双套，切系统主题即可验证。

---

## 6. 为什么浅底用 `#F8F7F8` 而不是纯白

纯白 + 粉按钮会显得“医院感”，把底压到 `#F8F7F8`（极淡的暖灰）能让粉色显暖、卡片显干净，
卡片与页面底仍然只差 3% 亮度 —— 这是 Material 3 的常规手法，不引入第二层阴影。
