package com.example.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        WorkspaceEntity::class,
        ThreadEntity::class,
        MessageEntity::class,
        ToolCallLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TorveDatabase : RoomDatabase() {
    abstract fun torveDao(): TorveDao

    companion object {
        @Volatile
        private var INSTANCE: TorveDatabase? = null

        fun getInstance(context: Context): TorveDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TorveDatabase::class.java,
                    "torve_harness.db"
                ).fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
