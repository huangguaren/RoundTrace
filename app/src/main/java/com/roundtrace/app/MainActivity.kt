package com.roundtrace.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roundtrace.app.core.data.ThemeMode
import com.roundtrace.app.core.ui.theme.RoundTraceTheme
import com.roundtrace.app.navigation.RoundTraceNavHost

/**
 * 唯一的 Activity（单 Activity + Navigation Compose）。
 *
 * 职责边界（TASKS.md T08②）：只做 `enableEdgeToEdge()` + 装配主题与导航。
 * 任何业务逻辑都必须下沉到 ViewModel / domain 层。
 *
 * 主题由设置项驱动（T63①「写 DataStore 后立即生效且重启保留」）：
 * 订阅 `SettingsRepository.themeMode`，`SYSTEM` 时跟随系统，`LIGHT` / `DARK` 强制。
 * DataStore 的 `Flow` 天然保证「写入即生效、重启后保留」。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as RoundTraceApplication).container

        setContent {
            val themeMode by container.settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)

            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            RoundTraceTheme(darkTheme = darkTheme) {
                RoundTraceNavHost()
            }
        }
    }
}
