package com.roundtrace.app.core.data

import kotlinx.coroutines.flow.Flow

/**
 * 设置仓储端口（ARCHITECTURE §3.1 / TASKS.md T34③）。
 *
 * 三项设置均有**默认值**且 `Flow` 可持续观察；写入一律 `suspend`。
 * **不把整场对局写进 DataStore**（TDD §8 底线）：这里只有偏好。
 */
interface SettingsRepository {

    /** 主题模式：跟随系统 / 浅色 / 深色。 */
    val themeMode: Flow<ThemeMode>

    /** 创建对局时的默认人数（5..10）。 */
    val defaultPlayerCount: Flow<Int>

    /** 导出/分享时默认是否包含私有信息。 */
    val includePrivateByDefault: Flow<Boolean>

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setDefaultPlayerCount(count: Int)

    suspend fun setIncludePrivateByDefault(include: Boolean)

    companion object {
        /** 默认人数（标准 8 人局是最常见的开局配置）。 */
        const val DEFAULT_PLAYER_COUNT: Int = 8

        /** 默认不导出私有信息（PRD：导出默认脱敏）。 */
        const val DEFAULT_INCLUDE_PRIVATE: Boolean = false
    }
}

/** 主题模式。 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        fun fromPersisted(raw: String?): ThemeMode =
            entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}
