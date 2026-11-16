package io.github.sumankayal247.gbaemulator.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        GameEntity::class,
        RomSourceEntity::class,
        SavedFolderEntity::class,
        CheatEntity::class,
        CoreOptionOverrideEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class GbaLibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        const val DATABASE_NAME = "gba_emulator_library.db"

        // Explicit non-destructive migrations list (ADR-0005). Never use fallbackToDestructiveMigration.
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun create(context: Context): GbaLibraryDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                GbaLibraryDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
