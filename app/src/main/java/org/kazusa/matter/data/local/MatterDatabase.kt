package org.kazusa.matter.data.local

import android.content.Context
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
    version = 2,
    exportSchema = true
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
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
