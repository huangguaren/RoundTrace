package com.roundtrace.app.feature.settings

import com.roundtrace.app.core.data.ThemeMode
import com.roundtrace.app.core.rules.avalon.AvalonPreset

/**
 * 设置页 UiState（TASKS.md T63①）。
 *
 * 设置项严格按 PRD：
 * 1. 主题模式（跟随系统 / 浅色 / 深色）；
 * 2. 隐私默认遮挡（导出时默认是否含私有信息）；
 * 3. 默认规则预设 —— 用「默认人数」表达，因为标准预设与人数一一对应
 *    （`avalon.standard.Np`），不再额外引入一个会与人数冲突的字段；
 * 4. 关于（版本、规则版本、隐私声明）。
 *
 * **不含 API Key 配置**：P0 没有任何 AI/网络调用（PRD 明确 MVP 不联网）。
 */
data class SettingsUiState(
    val isLoading: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val defaultPlayerCount: Int = 8,
    val includePrivateByDefault: Boolean = false,
    val errorMessage: String? = null,
    val importMessage: String? = null
) {

    /** 默认预设标识（与 ruleset 的命名保持一致）。 */
    val defaultPresetId: String
        get() = AvalonPreset.presetId(defaultPlayerCount)

    /** 可选人数（来自 ruleset，不在 UI 侧硬编码 5..10）。 */
    val supportedPlayerCounts: List<Int>
        get() = AvalonPreset.supportedPlayerCounts
}

/** 设置页动作。 */
sealed interface SettingsAction {

    data class SetThemeMode(val mode: ThemeMode) : SettingsAction

    data class SetDefaultPlayerCount(val playerCount: Int) : SettingsAction

    data class SetIncludePrivateByDefault(val enabled: Boolean) : SettingsAction

    /** 导入一段 JSON 文本（由 UI 从 ACTION_OPEN_DOCUMENT 读入后传入）。 */
    data class ImportJson(val text: String) : SettingsAction

    /** 清除一次性导入反馈。 */
    data object ClearImportMessage : SettingsAction
}
