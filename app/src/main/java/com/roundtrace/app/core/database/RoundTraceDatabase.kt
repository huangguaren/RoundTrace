package com.roundtrace.app.core.database

import android.content.Context
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
         * @param name 数据库名；测试可传 `null` 走内存库
         */
        fun build(context: Context, name: String? = DATABASE_NAME): RoundTraceDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                RoundTraceDatabase::class.java,
                name ?: DATABASE_NAME
            )
                // 显式声明 WAL；不调用 fallbackToDestructiveMigration
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

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
