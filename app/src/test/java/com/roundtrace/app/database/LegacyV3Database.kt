package com.roundtrace.app.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.roundtrace.app.core.database.RoundTraceDatabase
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 测试专用：从 `schemas/3.json` 反向生成一个**真实的 v3 库文件**。
 *
 * ## 为什么不用 MigrationTestHelper 造 v3
 *
 * T1（[MigrationV3ToV4Test]）走 `MigrationTestHelper`，它依赖 instrumentation 与 assets，
 * 在纯 JVM（Robolectric）下未必可用。T2/T3 需要一条**不依赖 instrumentation** 的造库路径，
 * 否则一旦 T1 跑不起来，回填正确性与备份可用性就完全没人守。
 *
 * ## 为什么从 3.json 取 DDL 而不是手写 CREATE TABLE
 *
 * `3.json` 是 Room 自己导出的、**它就是 v3 的权威 DDL**。手抄一份 DDL 会随 schema 演进
 * 悄悄漂移；直接读 json 里的 `createSql` 则永远与真实 v3 一致。
 *
 * v3 的 `identity_hash` 也在同一份 json 里（`database.identityHash`），
 * 写进 `room_master_table` 后 Room 打开时才会认为「这是自己建的 v3 库」并触发迁移。
 */
internal object LegacyV3Database {

    /** v3 的 schema 文件名（相对 app 模块目录）。 */
    private const val SCHEMA_RELATIVE_PATH =
        "schemas/com.roundtrace.app.core.database.RoundTraceDatabase/3.json"

    /** 用文件系统读 schema，避免依赖 assets 上下文。 */
    private fun schemaFile(): File {
        val candidates = listOf(
            File(SCHEMA_RELATIVE_PATH),
            File("app/$SCHEMA_RELATIVE_PATH")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "找不到 v3 schema。user.dir=${System.getProperty("user.dir")}，" +
                    "尝试过=$candidates"
            )
    }

    /** 把 3.json 里的 DDL 应用到 [db]（建表 + 建索引 + room_master_table + user_version=3）。 */
    fun applyV3Schema(db: SupportSQLiteDatabase) {
        val root = Json.parseToJsonElement(schemaFile().readText()).jsonObject
        val database = root.getValue("database").jsonObject

        database.getValue("entities").jsonArray.forEach { element ->
            val entity = element.jsonObject
            val tableName = entity.getValue("tableName").jsonPrimitive.content
            db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", tableName))
            (entity["indices"] as? kotlinx.serialization.json.JsonArray)?.forEach { indexElement ->
                val index = indexElement.jsonObject
                db.execSQL(index.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", tableName))
            }
        }

        val identityHash = database.getValue("identityHash").jsonPrimitive.content
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)", arrayOf(identityHash))
        db.execSQL("PRAGMA user_version = 3")
    }

    /**
     * 在 [context] 的 databases 目录下创建一个 v3 库文件，并用 [seed] 写入数据。
     *
     * @return 已关闭的库文件（Room 打开时会自动跑迁移）
     */
    fun create(
        context: Context,
        name: String,
        seed: (SupportSQLiteDatabase) -> Unit = {}
    ): File {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        if (file.exists() && !file.delete()) {
            error("无法清理旧测试库：${file.absolutePath}")
        }

        val helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(file.absolutePath)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) = applyV3Schema(db)

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldV: Int, newV: Int) = Unit
                    }
                )
                .build()
        )
        val db = helper.writableDatabase
        try {
            // callback 的 onCreate 只在新建时触发；这里显式再跑一次，保证结构确定
            if (!tableExists(db, "match_events")) {
                applyV3Schema(db)
            }
            seed(db)
        } finally {
            helper.close()
        }

        // 用只读方式打开一次，确认 user_version 真的落盘为 3
        val verify = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(file.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldV: Int, newV: Int) = Unit
                })
                .build()
        )
        verify.readableDatabase.query("PRAGMA user_version").use { cursor ->
            cursor.moveToFirst()
            val version = cursor.getInt(0)
            check(version == 3) { "v3 夹具的 user_version 应为 3，实际=$version" }
        }
        verify.close()
        return file
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
            it.moveToFirst()
        }

    /** 插入一条 v3 结构的 `match_events` 行（无 logicalPosition/revisionSeq 列）。 */
    fun insertV3Event(
        db: SupportSQLiteDatabase,
        id: String,
        matchId: String,
        occurredOrder: Int,
        supersedesId: String? = null,
        retracted: Boolean = false
    ) {
        db.execSQL(
            "INSERT INTO match_events " +
                "(id, matchId, occurredOrder, phaseKey, eventType, payloadVersion, payloadJson, " +
                "visibility, recordedAt, supersedesId, retracted) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                id, matchId, occurredOrder, "QUEST_1", "QUEST_STARTED", 1,
                "{\"questNo\":1,\"leaderSeat\":1}", "PUBLIC", 1_700_000_000_000L,
                supersedesId, if (retracted) 1 else 0
            )
        )
    }

    /** 插入一条 v3 结构的 `matches` 行（迁移校验要求父表存在）。 */
    fun insertV3Match(db: SupportSQLiteDatabase, id: String, title: String = "迁移测试对局") {
        db.execSQL(
            "INSERT INTO matches (id, gameType, rulesetId, rulesetVersion, presetId, playerCount, " +
                "configJson, status, title, mySeat, initialLeaderSeat, createdAt, updatedAt, " +
                "revealedAt, schemaVersion) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                id, "AVALON", "avalon.standard", 1, "avalon.standard.8p", 8, "{}",
                "DRAFT", title, 1, 1, 0L, 0L, null, 1
            )
        )
    }

    /** 打开一个**不跑迁移**的只读句柄（用于断言「迁移前」的状态或备份文件）。 */
    fun openRaw(context: Context, file: File, version: Int): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(file.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldV: Int, newV: Int) = Unit
                })
                .build()
        )

    /** 以 Room 打开（会触发迁移），供 T2/T3 复用。 */
    fun openWithRoom(context: Context, name: String): RoundTraceDatabase =
        RoundTraceDatabase.build(context, name)

    /** 便于断言：把 `PRAGMA user_version` 读成 Int。 */
    fun userVersion(db: SupportSQLiteDatabase): Int =
        db.query("PRAGMA user_version").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    /** 便于断言：`SELECT COUNT(*)`。 */
    fun count(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    /** 便于断言：`sqlite_master` 里是否存在某索引。 */
    fun hasIndex(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND name=?", arrayOf(name)).use {
            it.moveToFirst()
        }

    /** 便于断言：读一行的 (logicalPosition, revisionSeq)。 */
    fun slotOf(db: SupportSQLiteDatabase, id: String): Pair<Int, Int> =
        db.query(
            "SELECT logicalPosition, revisionSeq FROM match_events WHERE id = ?",
            arrayOf(id)
        ).use { cursor ->
            check(cursor.moveToFirst()) { "找不到事件：$id" }
            cursor.getInt(0) to cursor.getInt(1)
        }

    /** 便于断言：某表的所有行数。 */
    fun rowsOf(db: SupportSQLiteDatabase, table: String): Int = count(db, "SELECT COUNT(*) FROM $table")

    /** 便于断言：json 字段解析用（保留给需要深层断言的用例）。 */
    fun jsonObjectOf(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
}
