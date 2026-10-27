package org.kazusa.matter.data.local

import android.content.Context
import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * 本地数据库（Room）。
 *
 * 目前承载：浏览历史 [BrowsingHistoryEntity]、搜索记录 [SearchHistoryEntity]。
 * 后续若需要「草稿 / 离线时间线」等本地表，继续往 [entities] 里加即可。
 */
@Database(
    entities = [BrowsingHistoryEntity::class, SearchHistoryEntity::class],
    version = 3,
    exportSchema = true,
    // v3 只是给 browsing_history 补一个 (uid, create_at) 索引 —— 属于 Room 能自动推导的变更
    // （加/删索引、加可空列、改名等），交给它按 2.json 生成 DDL，
    // 省得手写 SQL 与 @Entity(indices=…) 的定义漂移导致打开数据库时 schema 校验失败。
    autoMigrations = [AutoMigration(from = 2, to = 3)]
)
abstract class MatterDatabase : RoomDatabase() {

    abstract fun browsingHistoryDao(): BrowsingHistoryDao

    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        private const val DB_NAME = "matter.db"

        /** v2：新增搜索记录表 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `search_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `keyword` TEXT NOT NULL,
                        `uid` TEXT,
                        `create_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var instance: MatterDatabase? = null

        fun getInstance(context: Context): MatterDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MatterDatabase::class.java,
                    DB_NAME
                )
                    // v2 新增搜索记录表是手写迁移（线上已生效，保持原样不动）；
                    // v3 的索引变更由 autoMigrations 自动生成，不在这里注册。
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
