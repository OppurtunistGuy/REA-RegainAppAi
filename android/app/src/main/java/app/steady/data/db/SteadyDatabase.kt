package app.steady.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlanEntity::class,
        SessionEntity::class,
        InterceptEventEntity::class,
        CardEntity::class,
        UnlockGrantEntity::class,
        UnlockRequestEntity::class,
        TaskEntity::class,
        EnergyCheckinEntity::class,
    ],
    version = SteadyDatabase.VERSION,
    exportSchema = true,
)
abstract class SteadyDatabase : RoomDatabase() {
    abstract fun planDao(): PlanDao
    abstract fun sessionDao(): SessionDao
    abstract fun cardDao(): CardDao
    abstract fun unlockDao(): UnlockDao
    abstract fun dayDao(): DayDao

    companion object {
        const val NAME = "steady.db"
        const val VERSION = 2

        /** SQL of [MIGRATION_1_2]; must match the index `createSql` in schemas/…/2.json (checked by a JVM test). */
        const val CREATE_INTERCEPT_SURFACE_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_intercept_events_atMs_surfaceId` ON `intercept_events` (`atMs`, `surfaceId`)"

        /** v1 → v2: index for per-surface intercept counts (Insights). No data changes. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_INTERCEPT_SURFACE_INDEX)
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        /** No destructive fallback: a missing migration must fail loudly in testing, never wipe user data. */
        fun build(context: Context): SteadyDatabase =
            Room.databaseBuilder(context.applicationContext, SteadyDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
