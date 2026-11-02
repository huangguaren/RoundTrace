package com.roundtrace.app.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.roundtrace.app.core.database.dao.AnalysisReportDao
import com.roundtrace.app.core.database.dao.MatchDao
import com.roundtrace.app.core.database.dao.MatchDraftDao
import com.roundtrace.app.core.database.dao.MatchEventDao
import com.roundtrace.app.core.database.dao.ParticipantDao
import com.roundtrace.app.core.database.dao.RoleAssignmentDao
import com.roundtrace.app.core.database.entity.AnalysisReportEntity
import com.roundtrace.app.core.database.entity.MatchDraftEntity
import com.roundtrace.app.core.database.entity.MatchEntity
import com.roundtrace.app.core.database.entity.MatchEventEntity
import com.roundtrace.app.core.database.entity.ParticipantEntity
import com.roundtrace.app.core.database.entity.RoleAssignmentEntity
import java.io.File

/** 日志 tag（数据库相关的启动期自检与备份都走它）。 */
private const val DB_TAG = "RoundTraceDb"

/**
 * INV-1 自检命中记录（同一对局同一位置存在多条 active 事件）。
 *
 * 只是「观测结果」，不是可修复建议：本项目禁止 DELETE、也不允许自动改历史。
 */
internal data class DuplicateSlot(
    val matchId: String,
    val logicalPosition: Int,
    val count: Int
)

/**
 * INV-1 自检（RT-003 / migration-plan §14-②）：
 * **同一 `matchId` 下，每个 `logicalPosition` 至多一条 `retracted = 0` 的事件**。
 *
 * 为什么要拆成独立函数：启动期只能「记录」不能「修复」，
 * 所以必须有办法在**不启动 App** 的前提下断言这个查询是对的（见 `Inv1SelfCheckTest`）。
 *
 * 实现要点：
 * - `LIMIT 20`：异常库可能有大量重复，日志只需要样本，不需要刷屏；
 * - 只读 `retracted = 0`：被撤销的历史版本本来就会共享同一位置，那是**正常**的。
 */
internal fun findDuplicateActivePositions(db: SupportSQLiteDatabase): List<DuplicateSlot> {
    val result = mutableListOf<DuplicateSlot>()
    db.query(
        "SELECT matchId, logicalPosition, COUNT(*) AS c " +
            "  FROM match_events WHERE retracted = 0 " +
            " GROUP BY matchId, logicalPosition HAVING c > 1 LIMIT 20"
    ).use { cursor ->
        while (cursor.moveToNext()) {
            result += DuplicateSlot(
                matchId = cursor.getString(0),
                logicalPosition = cursor.getInt(1),
                count = cursor.getInt(2)
            )
        }
    }
    return result
}

/**
 * 启动期执行 INV-1 自检：命中就 `Log.w`，**不修复、不抛异常、不阻断启动**。
 *
 * 分层理由：数据库可能已经被前端版本写坏，此时「拒绝启动」比「带着脏数据继续跑」更糟——
 * 用户会彻底打不开 App。所以这里只留证据，把处置权交给后续版本与备份恢复。
 */
internal fun warnOnDuplicateActivePositions(db: SupportSQLiteDatabase) {
    runCatching {
        val duplicates = findDuplicateActivePositions(db)
        if (duplicates.isNotEmpty()) {
            Log.w(
                DB_TAG,
                "INV-1 违反：${duplicates.size} 个位置存在多条 active 事件（只记录，不修复；" +
                    "可用数据库备份恢复）"
            )
            duplicates.forEach { slot ->
                Log.w(
                    DB_TAG,
                    "  matchId=${slot.matchId} logicalPosition=${slot.logicalPosition} " +
                        "activeCount=${slot.count}"
                )
            }
        }
    }.onFailure { throwable ->
        Log.w(DB_TAG, "INV-1 自检本身失败（已忽略，不阻断启动）", throwable)
    }
}

/**
 * 跨局 `supersedesId` 命中记录（F2 追加诊断）。
 *
 * 一行 = 一条「B 局的事件却说自己在修订 A 局的事件」的脏链。
 */
internal data class CrossMatchSupersede(
    val matchId: String,
    val eventId: String,
    val supersedesId: String
)

/**
 * 跨局修订链自检（F2 追加）：找出 `supersedesId` 指向**另一局**事件的记录。
 *
 * ## 为什么需要它（修 CTE 不够）
 *
 * `MIGRATION_3_4` 加上 `AND child.matchId = chain.matchId` 只保证「升级时不再算错位置」，
 * **它不修复数据**：脏链本身还在库里。而 RT-002 修复前，`reviseEvent` 只按 `eventId` 查、
 * `sameMatch = true` 是**硬编码断言而非校验**，所以旧库里真的可能已经留下跨局链。
 * 这条自检负责把存量情况暴露出来，供人工判断（是否用备份恢复）。
 *
 * ## 三条约束
 *
 * 1. **只读**：不 DELETE、不改 `supersedesId`——自动改历史数据比不修更危险；
 * 2. **可测**：查询与日志分开（[findCrossMatchSupersedes] 返回数据，[warnOnCrossMatchSupersedes]
 *    只打日志），否则只能靠读日志断言；
 * 3. **不阻断**：与 INV-1 自检同一条原则——库脏也要能启动，否则用户彻底打不开 App。
 *
 * `LIMIT 20`：异常库可能有大量命中，日志只需要样本。
 */
internal fun findCrossMatchSupersedes(db: SupportSQLiteDatabase): List<CrossMatchSupersede> {
    val result = mutableListOf<CrossMatchSupersede>()
    db.query(
        "SELECT child.matchId, child.id, child.supersedesId " +
            "  FROM match_events AS child " +
            "  JOIN match_events AS parent ON parent.id = child.supersedesId " +
            " WHERE child.matchId <> parent.matchId " +
            " LIMIT 20"
    ).use { cursor ->
        while (cursor.moveToNext()) {
            result += CrossMatchSupersede(
                matchId = cursor.getString(0),
                eventId = cursor.getString(1),
                supersedesId = cursor.getString(2)
            )
        }
    }
    return result
}

/** 启动期执行跨局链自检：命中就 `Log.w`，**不修复、不抛异常、不阻断启动**。 */
internal fun warnOnCrossMatchSupersedes(db: SupportSQLiteDatabase) {
    runCatching {
        val rows = findCrossMatchSupersedes(db)
        if (rows.isNotEmpty()) {
            Log.w(
                DB_TAG,
                "跨局修订链：${rows.size} 条 supersedesId 指向其它对局的事件（只记录，不修复；" +
                    "可能来自 RT-002 修复前的跨局修订，可用数据库备份恢复）"
            )
            rows.forEach { row ->
                Log.w(
                    DB_TAG,
                    "  matchId=${row.matchId} eventId=${row.eventId} " +
                        "supersedesId=${row.supersedesId}"
                )
            }
        }
    }.onFailure { throwable ->
        Log.w(DB_TAG, "跨局链自检本身失败（已忽略，不阻断启动）", throwable)
    }
}

/**
 * 迁移前备份的结果（F3）。
 *
 * 为什么要有返回值：原来只复制文件然后无条件 `Log.i("迁移前备份完成")`，
 * 而 `wal_checkpoint(TRUNCATE)` 在**别的连接还持着读锁**时会返回 `busy=1` 且**不做合并**，
 * 于是复制到的是一个「缺最近事务」的备份，却报「完成」——这种备份比没有备份更危险。
 * 现在把判断结果变成返回值，日志与返回值同源，测试也能直接断言。
 */
internal sealed interface BackupOutcome {

    /** 不需要备份：库不存在，或版本不在 v1..v3。 */
    data object NotNeeded : BackupOutcome

    /** 备份完成，且 WAL 已完全合并（可放心当回退点）。 */
    data object Complete : BackupOutcome

    /** 备份文件已生成，但 WAL 未能完全合并 —— **可能缺最近事务**，不得报「完成」。 */
    data class Incomplete(val reason: String) : BackupOutcome
}

/** `PRAGMA wal_checkpoint(TRUNCATE)` 的三元组。`busy != 0` 表示未能完全合并。 */
internal data class WalCheckpointResult(val busy: Int, val log: Int, val checkpointed: Int) {

    /** 只有 `busy == 0` 才算「WAL 已合并」。无法解析时用 [UNKNOWN]（busy = -1）走保守分支。 */
    val isComplete: Boolean get() = busy == 0

    companion object {
        /** 拿不到结果（PRAGMA 失败 / 无返回行）：当作「未完成」，绝不当作成功。 */
        val UNKNOWN: WalCheckpointResult = WalCheckpointResult(busy = -1, log = -1, checkpointed = -1)
    }
}

/**
 * 迁移前自动备份（migration-plan §14-① 第 5 条）。
 *
 * ## 为什么必须在 `Room.databaseBuilder(...)` **之前**调用
 *
 * `RoomDatabase.Callback.onOpen` 是在**迁移跑完之后**才触发的，
 * 那时库已经是 v4 了，再备份等于备份了一个已经被改过的库——无法作为回退点。
 * 因此备份只能放在「打开连接」这一动作之前，靠直接读文件判断版本。
 *
 * ## 三个必须的处理
 *
 * 1. **WAL**：本库启用了 `WRITE_AHEAD_LOGGING`，最新事务可能还在 `-wal` 文件里。
 *    不先 `PRAGMA wal_checkpoint(TRUNCATE)` 就复制，会得到「缺最近事务」的备份。
 *    checkpoint 的 `busy` 必须参与判断（F3）：busy 时照常复制（有总比没有好），
 *    但结果降级为 [BackupOutcome.Incomplete]，日志不得写「完成」。
 * 2. **只备份 v1..v3**：v4 已经是目标版本，不需要备份（也就不会每次启动都复制一遍）。
 * 3. **绝不抛出**：备份失败只 `Log.w`。备份是为了降低风险，不能自己变成新的启动失败源。
 *
 * @param checkpoint WAL 合并动作，可注入（测试用它模拟 busy）
 */
internal fun backupBeforeMigration(
    context: Context,
    name: String,
    checkpoint: (SQLiteDatabase) -> WalCheckpointResult = ::runWalCheckpoint
): BackupOutcome = try {
    val dbFile = context.applicationContext.getDatabasePath(name)
    if (!dbFile.exists()) {
        BackupOutcome.NotNeeded
    } else {
        val (version, wal) = readUserVersionWithCheckpoint(dbFile, checkpoint)
        if (version !in OLDEST_BACKED_UP_VERSION..NEWEST_BACKED_UP_VERSION) {
            BackupOutcome.NotNeeded
        } else {
            val target = File(dbFile.parentFile, "$name.bak.v$version")
            dbFile.copyTo(target, overwrite = true)
            deleteOlderBackups(dbFile.parentFile, name, keepVersion = version)

            if (wal.isComplete) {
                Log.i(DB_TAG, "迁移前备份完成：v$version → ${target.name}")
                BackupOutcome.Complete
            } else {
                Log.w(
                    DB_TAG,
                    "迁移前备份可能缺最近事务（wal_checkpoint busy=${wal.busy}）：" +
                        "v$version → ${target.name}"
                )
                BackupOutcome.Incomplete("wal_checkpoint busy=${wal.busy}")
            }
        }
    }
} catch (throwable: Throwable) {
    Log.w(DB_TAG, "迁移前备份失败（已忽略，不阻断启动）", throwable)
    BackupOutcome.Incomplete("exception: ${throwable.javaClass.simpleName}")
}

/** 需要备份的版本区间：v1..v3（v4 是当前版本）。 */
private const val OLDEST_BACKED_UP_VERSION = 1
private const val NEWEST_BACKED_UP_VERSION = 3

/**
 * 先 checkpoint 再读 `user_version`。
 *
 * 打开方式用 `OPEN_READWRITE`：`wal_checkpoint` 需要写权限（要把 `-wal` 合并回主文件）。
 *
 * busy 时**重试一次**：busy 通常来自一个瞬时的读者，重试能救回大部分情况；
 * 第二次仍 busy 就把结果原样返回，由调用方决定日志级别。
 */
private fun readUserVersionWithCheckpoint(
    dbFile: File,
    checkpoint: (SQLiteDatabase) -> WalCheckpointResult
): Pair<Int, WalCheckpointResult> {
    val sqlite = SQLiteDatabase.openDatabase(
        dbFile.path,
        null,
        SQLiteDatabase.OPEN_READWRITE
    )
    return sqlite.use { db ->
        var wal = runCatching { checkpoint(db) }.getOrDefault(WalCheckpointResult.UNKNOWN)
        if (!wal.isComplete) {
            wal = runCatching { checkpoint(db) }.getOrDefault(wal)
        }
        val version = db.rawQuery("PRAGMA user_version", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        version to wal
    }
}

/** 真正的 `PRAGMA wal_checkpoint(TRUNCATE)`：返回 (busy, log, checkpointed)。 */
private fun runWalCheckpoint(db: SQLiteDatabase): WalCheckpointResult =
    db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
        if (!cursor.moveToFirst()) {
            WalCheckpointResult.UNKNOWN
        } else {
            WalCheckpointResult(
                busy = cursor.getInt(0),
                log = if (cursor.columnCount > 1) cursor.getInt(1) else -1,
                checkpointed = if (cursor.columnCount > 2) cursor.getInt(2) else -1
            )
        }
    }

/** 只保留最新一份备份，避免历史备份把用户存储撑爆。 */
private fun deleteOlderBackups(dir: File?, name: String, keepVersion: Int) {
    if (dir == null) return
    val prefix = "$name.bak.v"
    dir.listFiles()
        ?.filter { it.name.startsWith(prefix) && it.name != "$prefix$keepVersion" }
        ?.forEach { stale -> runCatching { stale.delete() } }
}

/**
 * 应用数据库（ARCHITECTURE §4.8 / TASKS.md T30）。
 *
 * 硬约束：
 * 1. `version = 1` + `exportSchema = true`：schema 必须导出到 `app/schemas/` 并提交仓库
 *    （迁移测试依赖它）；
 * 2. **禁止** `fallbackToDestructiveMigration()`：正式数据不允许静默丢弃（TDD §8）；
 * 3. 显式声明 `WRITE_AHEAD_LOGGING`（默认值，但显式写出来避免依赖默认行为变化）；
 * 4. 进程内唯一实例由 `AppContainer` 持有，本类不做全局单例（便于测试替换）。
 */
@Database(
    entities = [
        MatchEntity::class,
        ParticipantEntity::class,
        RoleAssignmentEntity::class,
        MatchEventEntity::class,
        MatchDraftEntity::class,
        AnalysisReportEntity::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(RoundTraceConverters::class)
abstract class RoundTraceDatabase : RoomDatabase() {

    abstract fun matchDao(): MatchDao

    abstract fun participantDao(): ParticipantDao

    abstract fun roleAssignmentDao(): RoleAssignmentDao

    abstract fun matchEventDao(): MatchEventDao

    abstract fun matchDraftDao(): MatchDraftDao

    abstract fun analysisReportDao(): AnalysisReportDao

    companion object {

        /** 数据库文件名。 */
        const val DATABASE_NAME: String = "roundtrace.db"

        /** v1→v2：participants 表加座位自由布局坐标列。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE participants ADD COLUMN layoutX REAL NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE participants ADD COLUMN layoutY REAL NOT NULL DEFAULT -1")
            }
        }

        /** v2→v3：participants 表加头像 URI 列。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE participants ADD COLUMN avatarUri TEXT")
            }
        }

        /**
         * v3→v4（RT-003 Step 1）：拆分「逻辑顺序」与「写入序号」。
         *
         * 四条语句的顺序不能变：先加列 → 再回填 `logicalPosition` → 再回填 `revisionSeq`
         * → 最后建索引。两条递归 CTE 都以 `supersedesId IS NULL` 为链根，
         * 因此孤儿链（`supersedesId` 指向不存在的行）会落到 `COALESCE(..., occurredOrder)` /
         * `COALESCE(..., 0)` 的兜底分支，**不会抛异常**。
         *
         * 写法说明：统一用 `\n` 字符串拼接而不是 Kotlin 原始字符串，
         * 避免未来 SQL 里出现 `$` 时被当成模板插值（migration-plan §15.6-D4）。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ① 加列（NOT NULL 必须带 DEFAULT；与 @ColumnInfo(defaultValue = "0") 逐字一致）
                db.execSQL(
                    "ALTER TABLE `match_events` ADD COLUMN `logicalPosition` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `match_events` ADD COLUMN `revisionSeq` INTEGER NOT NULL DEFAULT 0"
                )

                // ② 回填 logicalPosition：沿 supersedesId 链取链根的 occurredOrder
                //
                // `AND child.matchId = chain.matchId` **不能省**（F2）：
                // 递归只按 supersedesId 连线，一旦 v3 库里存在**跨对局**的 supersedesId
                // （RT-002 修复前 `reviseEvent` 只按 eventId 查，跨局修订能写进库），
                // 本局事件就会继承另一局的链根位置，与同局另一条 active 事件撞在同一
                // logicalPosition 上 —— INV-1 被破坏。加上 matchId 约束后，跨局链在
                // 本局查不到祖先，自然落到 COALESCE 的 occurredOrder 兜底。
                db.execSQL(
                    "WITH RECURSIVE chain(id, matchId, rootOrder) AS (\n" +
                        "    SELECT id, matchId, occurredOrder FROM `match_events` WHERE supersedesId IS NULL\n" +
                        "    UNION ALL\n" +
                        "    SELECT child.id, child.matchId, chain.rootOrder\n" +
                        "      FROM `match_events` AS child\n" +
                        "      JOIN chain ON child.supersedesId = chain.id\n" +
                        "                AND child.matchId = chain.matchId\n" +
                        ")\n" +
                        "UPDATE `match_events`\n" +
                        "   SET logicalPosition = COALESCE(\n" +
                        "         (SELECT rootOrder FROM chain WHERE chain.id = `match_events`.id),\n" +
                        "         occurredOrder)"
                )

                // ③ 回填 revisionSeq：链深度 - 1（根 = 0）；同样按 matchId 分段
                db.execSQL(
                    "WITH RECURSIVE chain(id, matchId, depth) AS (\n" +
                        "    SELECT id, matchId, 0 FROM `match_events` WHERE supersedesId IS NULL\n" +
                        "    UNION ALL\n" +
                        "    SELECT child.id, child.matchId, chain.depth + 1\n" +
                        "      FROM `match_events` AS child\n" +
                        "      JOIN chain ON child.supersedesId = chain.id\n" +
                        "                AND child.matchId = chain.matchId\n" +
                        ")\n" +
                        "UPDATE `match_events`\n" +
                        "   SET revisionSeq = COALESCE(\n" +
                        "         (SELECT depth FROM chain WHERE chain.id = `match_events`.id), 0)"
                )

                // ④ 读路径索引（IF NOT EXISTS 保证可重复执行）
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_match_events_matchId_logicalPosition_revisionSeq` " +
                        "ON `match_events` (`matchId`, `logicalPosition`, `revisionSeq`)"
                )
            }
        }

        /**
         * 构造数据库实例。
         *
         * @param context 任意 Context（内部会取 applicationContext）
         * @param name 数据库名，默认 [DATABASE_NAME]。
         *        **库名固定为 [DATABASE_NAME]**——需要内存库请用 [buildInMemory]。
         *
         * 签名刻意不接受 `null`：曾经的 `name: String?` 让「传 null 想开内存库」变成
         * 「打开真实库 + 跳过迁移前备份」，是个静默的坏默认。现在由类型系统消除。
         */
        fun build(context: Context, name: String = DATABASE_NAME): RoundTraceDatabase {
            // ① 迁移前备份：**必须在 databaseBuilder 之前**
            //    （Callback.onOpen 在迁移之后才触发，那时备份已经晚了）
            backupBeforeMigration(context, name)

            return Room.databaseBuilder(
                context.applicationContext,
                RoundTraceDatabase::class.java,
                name
            )
                // 显式声明 WAL；不调用 fallbackToDestructiveMigration
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                // ② INV-1 自检：只记录，不修复、不阻断
                .addCallback(
                    object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            warnOnDuplicateActivePositions(db)
                            // F2 追加：修 CTE 只保证「升级不再算错」，存量脏链靠这条自检暴露
                            warnOnCrossMatchSupersedes(db)
                        }
                    }
                )
                .build()
        }

        /** 内存库（仪器测试用）。 */
        fun buildInMemory(context: Context): RoundTraceDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                RoundTraceDatabase::class.java
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
