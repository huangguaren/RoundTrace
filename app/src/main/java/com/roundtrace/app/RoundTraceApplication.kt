package com.roundtrace.app

import android.app.Application
import com.roundtrace.app.di.AppContainer

/**
 * 应用入口。
 *
 * 职责边界（TASKS.md T08①）：`onCreate` **只**构造 `AppContainer`，不含任何业务逻辑。
 * 容器本身是 `lazy` 单例，首次访问时才真正创建。
 */
class RoundTraceApplication : Application() {

    /** 进程级手动 DI 容器。 */
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        // 触发容器初始化；后续里程碑在此处预热数据库等基础设施。
        container
    }
}
