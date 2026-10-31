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
 *    不先 `PRAGMA wal_checkpoint(TRUNCATE)` 就复制，会得到「缺最近事务」的备份——
 *    这种备份比没有备份更危险，因为它看起来是好的。
 * 2. **只备份 v1..v3**：v4 已经是目标版本，不需要备份（也就不会每次启动都复制一遍）。
 * 3. **绝不抛出**：备份失败只 `Log.w`。备份是为了降低风险，不能自己变成新的启动失败源。
 */
internal fun backupBeforeMigration(context: Context, name: String?) {
    if (name == null) return
    runCatching {
        val dbFile = context.applicationContext.getDatabasePath(name)
        if (!dbFile.exists()) return

        val version = readUserVersionWithCheckpoint(dbFile)
        if (version !in OLDEST_BACKED_UP_VERSION..NEWEST_BACKED_UP_VERSION) return

        val target = File(dbFile.parentFile, "$name.bak.v$version")
        dbFile.copyTo(target, overwrite = true)
        deleteOlderBackups(dbFile.parentFile, name, keepVersion = version)

        Log.i(DB_TAG, "迁移前备份完成：v$version → ${target.name}")
    }.onFailure { throwable ->
        Log.w(DB_TAG, "迁移前备份失败（已忽略，不阻断启动）", throwable)
    }
}

/** 需要备份的版本区间：v1..v3（v4 是当前版本）。 */
private const val OLDEST_BACKED_UP_VERSION = 1
private const val NEWEST_BACKED_UP_VERSION = 3

/**
 * 先 checkpoint 再读 `user_version`。
 *
 * 打开方式用 `OPEN_READWRITE`：`wal_checkpoint` 需要写权限（要把 `-wal` 合并回主文件）。
 */
private fun readUserVersionWithCheckpoint(dbFile: File): Int {
    val sqlite = SQLiteDatabase.openDatabase(
        dbFile.path,
        null,
        SQLiteDatabase.OPEN_READWRITE
    )
    return sqlite.use { db ->
        // 忽略 checkpoint 的返回值：即使因其它连接占用而未能完全合并，
        // 后面复制到的也至少是「某个一致状态」，不会比不 checkpoint 更差。
        runCatching {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }
        db.rawQuery("PRAGMA user_version", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
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
                db.execSQL(
                    "WITH RECURSIVE chain(id, rootOrder) AS (\n" +
                        "    SELECT id, occurredOrder FROM `match_events` WHERE supersedesId IS NULL\n" +
                        "    UNION ALL\n" +
                        "    SELECT child.id, chain.rootOrder\n" +
                        "      FROM `match_events` AS child\n" +
                        "      JOIN chain ON child.supersedesId = chain.id\n" +
                        ")\n" +
                        "UPDATE `match_events`\n" +
                        "   SET logicalPosition = COALESCE(\n" +
                        "         (SELECT rootOrder FROM chain WHERE chain.id = `match_events`.id),\n" +
                        "         occurredOrder)"
                )

                // ③ 回填 revisionSeq：链深度 - 1（根 = 0）
                db.execSQL(
                    "WITH RECURSIVE chain(id, depth) AS (\n" +
                        "    SELECT id, 0 FROM `match_events` WHERE supersedesId IS NULL\n" +
                        "    UNION ALL\n" +
                        "    SELECT child.id, chain.depth + 1\n" +
                        "      FROM `match_events` AS child\n" +
                        "      JOIN chain ON child.supersedesId = chain.id\n" +
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
         * @param name 数据库名；`null` 表示**不启用迁移前备份**（实际库名仍回落到
         *        [DATABASE_NAME]）。内存库请用 [buildInMemory]。
         */
        fun build(context: Context, name: String? = DATABASE_NAME): RoundTraceDatabase {
            // ① 迁移前备份：**必须在 databaseBuilder 之前**
            //    （Callback.onOpen 在迁移之后才触发，那时备份已经晚了）
            backupBeforeMigration(context, name)

            return Room.databaseBuilder(
                context.applicationContext,
                RoundTraceDatabase::class.java,
                name ?: DATABASE_NAME
            )
                // 显式声明 WAL；不调用 fallbackToDestructiveMigration
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
