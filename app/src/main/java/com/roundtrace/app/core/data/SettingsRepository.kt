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

    /**
     * 「投影顺序模型」版本标记（RT-003 Step 3 的一次性刷新用）。
     *
     * 用途：`matches.status` 是写入时用**当时顺序**算好并冻结的列。读路径切到逻辑位置后，
     * 同一局重放可能得出不同结论 —— 老对局只要不再被写，首页卡（读列）就会永远显示旧结论，
     * 而记录页/复盘页（读 replay）显示新结论，两者长期矛盾。
     *
     * 语义：值 < [CURRENT_PROJECTION_ORDER_MODEL_VERSION] 时表示「这批数据是用旧顺序模型算的」。
     */
    val projectionOrderModelVersion: Flow<Int>

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setDefaultPlayerCount(count: Int)

    suspend fun setIncludePrivateByDefault(include: Boolean)

    /** 写入投影顺序模型版本；**只在刷新成功后**调用，失败不写以便下次重试。 */
    suspend fun setProjectionOrderModelVersion(version: Int)

    companion object {
        /** 默认人数（标准 8 人局是最常见的开局配置）。 */
        const val DEFAULT_PLAYER_COUNT: Int = 8

        /** 默认不导出私有信息（PRD：导出默认脱敏）。 */
        const val DEFAULT_INCLUDE_PRIVATE: Boolean = false

        /**
         * 当前投影顺序模型版本。
         *
         * `0` = 从未刷新过；`1` = 已按「逻辑位置」顺序重算过所有对局的状态（RT-003 Step 3）。
         * 以后若再改变顺序语义，这个数字要 +1 并把刷新逻辑重新挂上。
         */
        const val CURRENT_PROJECTION_ORDER_MODEL_VERSION: Int = 1
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
