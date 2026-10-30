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
    version = 3,
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
