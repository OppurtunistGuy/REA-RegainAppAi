package app.steady.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
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

        fun build(context: Context): SteadyDatabase =
            Room.databaseBuilder(context.applicationContext, SteadyDatabase::class.java, NAME).build()
    }
}
