package com.roundtrace.app.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 顶层委托：`preferencesDataStore` 是进程级单例，必须放在文件顶层（TASKS.md T36①）。 */
private val Context.roundTraceSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "roundtrace_settings"
)

/**
 * DataStore 实现的 [SettingsRepository]（ARCHITECTURE §8.8 / TASKS.md T36）。
 *
 * 只用 Preferences（非 Proto），减少 KSP / 序列化链路风险。
 * **不把整场对局写入 DataStore**（TDD §8 底线）：这里只有用户偏好。
 */
class DataStoreSettingsRepository(
    private val context: Context
) : SettingsRepository {

    private val dataStore: DataStore<Preferences>
        get() = context.roundTraceSettingsDataStore

    override val themeMode: Flow<ThemeMode> = dataStore.data.map { preferences ->
        ThemeMode.fromPersisted(preferences[KEY_THEME_MODE])
    }

    override val defaultPlayerCount: Flow<Int> = dataStore.data.map { preferences ->
        preferences[KEY_DEFAULT_PLAYER_COUNT] ?: SettingsRepository.DEFAULT_PLAYER_COUNT
    }

    override val includePrivateByDefault: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_INCLUDE_PRIVATE] ?: SettingsRepository.DEFAULT_INCLUDE_PRIVATE
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    override suspend fun setDefaultPlayerCount(count: Int) {
        dataStore.edit { it[KEY_DEFAULT_PLAYER_COUNT] = count }
    }

    override suspend fun setIncludePrivateByDefault(include: Boolean) {
        dataStore.edit { it[KEY_INCLUDE_PRIVATE] = include }
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DEFAULT_PLAYER_COUNT = intPreferencesKey("default_player_count")
        val KEY_INCLUDE_PRIVATE = booleanPreferencesKey("include_private_by_default")
    }
}
