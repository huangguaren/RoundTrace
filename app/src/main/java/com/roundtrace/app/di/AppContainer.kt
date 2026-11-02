package com.roundtrace.app.di

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import com.roundtrace.app.core.data.DataStoreSettingsRepository
import com.roundtrace.app.core.data.MatchRepository
import com.roundtrace.app.core.data.ProjectionRefreshCoordinator
import com.roundtrace.app.core.data.RoomMatchRepository
import com.roundtrace.app.core.data.SettingsRepository
import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.knowledge.AvalonEvidenceAnalyzer
import com.roundtrace.app.core.export.DefaultImportExportService
import com.roundtrace.app.core.export.ImportExportService
import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.knowledge.KnowledgeProvider
import com.roundtrace.app.core.knowledge.ReplayAnalyzer
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.model.EventFactory
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.GameReducer
import com.roundtrace.app.core.rules.GameRulesRegistry
import android.util.Log
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.core.util.IdGenerator
import com.roundtrace.app.core.util.UuidIdGenerator
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 手动依赖注入容器（ARCHITECTURE §1.3 / TASKS.md T37）。
 *
 * 为什么不用 Hilt：见 `docs/decisions.md` §1。
 *
 * 本类为 `lazy` 单例（由 `RoundTraceApplication` 持有），保证**进程内唯一**
 * Database / Repository 实例。全部依赖都是 `lazy`，首次访问才创建。
 *
 * @param appContext Application Context（只用于需要 Context 的基础设施）
 */
private const val PROJECTION_TAG = "RoundTraceDb"

class AppContainer(appContext: Context) {

    private val applicationContext: Context = appContext.applicationContext

    /** 进程级协程作用域：不随任何页面销毁而取消。 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 统一时钟：`recordedAt` / `updatedAt` / `createdAt` 全部取自于此。 */
    val clock: Clock = Clock.systemUTC()

    /** 唯一 ID 生成器：MatchId / EventId / 实体主键。 */
    val idGenerator: IdGenerator = UuidIdGenerator()

    /** 事件工厂：负责填 `eventId` / `recordedAt`，`occurredOrder` 恒为 0（由仓储盖章）。 */
    val eventFactory: EventFactory by lazy { EventFactory(idGenerator, clock) }

    /** 唯一数据库实例（T37③）。 */
    val database: RoundTraceDatabase by lazy { RoundTraceDatabase.build(applicationContext) }

    /** 事件编解码：`MatchEvent` ↔ `payloadJson` 的唯一出口。 */
    val eventCodec: EventCodec by lazy { EventCodec() }

    /** 规则注册表：按 `rulesetId + version` 解析规则实现。 */
    val gameRulesRegistry: GameRulesRegistry by lazy { DefaultGameRulesRegistry() }

    /** 规则实现（需要 `validateTeamDraft` / `decideProposal` 等扩展方法时使用）。 */
    val avalonRules: AvalonRules by lazy { AvalonRules() }

    /** 状态推进器（与注册表共用同一个无状态实例）。 */
    val reducer: GameReducer by lazy { (gameRulesRegistry as DefaultGameRulesRegistry).avalonReducer }

    /** 对局仓储（端口实现）。 */
    val matchRepository: MatchRepository by lazy {
        RoomMatchRepository(
            database = database,
            gameRulesRegistry = gameRulesRegistry,
            reducer = reducer,
            codec = eventCodec,
            idGenerator = idGenerator,
            clock = clock
        )
    }

    /** 设置仓储（DataStore）。 */
    val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository(applicationContext)
    }

    /**
     * RT-003 Step 3 的一次性投影刷新（migration-plan §17.1-S3-5）。
     *
     * 读路径切到逻辑位置后，同一局重放可能得出与「写入时冻结的 `matches.status`」不同的结论。
     * 老对局只要不再被写，首页卡（读列）就会永远显示旧结论，而复盘页（读 replay）显示新结论。
     *
     * 三条硬约束：
     * 1. **在应用级 scope 后台执行**，绝不阻塞启动、绝不在主线程做 IO；
     * 2. **全部成功后才写版本标记**；任何失败都不写 → 下次启动自动重试（不阻断、不静默放弃）；
     * 3. 刷新本身不改 `updatedAt`（见 `MatchDao.updateStatusKeepTimestamp`），
     *    否则所有老对局会突然跳到首页顶部。
     */
    init {
        appScope.launch {
            runCatching {
                val required = SettingsRepository.CURRENT_PROJECTION_ORDER_MODEL_VERSION
                val version = settingsRepository.projectionOrderModelVersion.first()
                val alreadyMarked = !ProjectionRefreshCoordinator.shouldRefresh(version, required)
                if (alreadyMarked) {
                    return@runCatching
                }
                val result = matchRepository.recomputeAllProjections()
                // 判断本身抽成纯函数（F5）：这样「失败却写标记」能被单测直接钉住
                if (!ProjectionRefreshCoordinator.shouldWriteProjectionMarker(result, alreadyMarked)) {
                    // **不写标记** → 下次启动重试。宁可重复扫描，不可静默停在旧结论。
                    Log.w(
                        PROJECTION_TAG,
                        "投影刷新未完成（成功 ${result.processed} / 失败 ${result.failed.size}），" +
                            "不写版本标记，下次启动重试。失败=${result.failed.map { it.raw }}"
                    )
                    return@runCatching
                }
                settingsRepository.setProjectionOrderModelVersion(required)
                Log.i(
                    PROJECTION_TAG,
                    "投影顺序模型升级完成：重算 ${result.processed} 局（旧版本 $version → $required）"
                )
            }.onFailure { throwable ->
                Log.w(PROJECTION_TAG, "一次性投影刷新失败（不阻断启动，下次重试）", throwable)
            }
        }
    }

    /** 视角引擎（M4/T51）：纯函数，无 IO。 */
    val knowledgeProvider: KnowledgeProvider by lazy { AvalonKnowledgeProvider(reducer) }

    /** 证据分析器（M4/T52）：纯函数，无 IO。 */
    val analyzer: ReplayAnalyzer by lazy { AvalonEvidenceAnalyzer(avalonRules) }

    /** 导入导出编排（M5/T60）：编解码 + 脱敏 + 事务，Android 侧文件由 `ShareLauncher` 负责。 */
    val importExport: ImportExportService by lazy {
        DefaultImportExportService(
            repository = matchRepository,
            rules = avalonRules,
            canRestoreAvatarUri = { raw ->
                // 导入文件只能复用本机已经持有读取授权的图片，不能自行授予外来 URI 权限。
                runCatching {
                    val uri = Uri.parse(raw)
                    uri.scheme == ContentResolver.SCHEME_CONTENT &&
                        applicationContext.contentResolver.persistedUriPermissions.any {
                            it.isReadPermission && it.uri == uri
                        }
                }.getOrDefault(false)
            }
        )
    }
}
