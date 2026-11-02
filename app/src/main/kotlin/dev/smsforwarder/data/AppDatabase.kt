package dev.smsforwarder.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsStatus

@Database(entities = [SmsEntity::class, AttemptEntity::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun smsDao(): SmsDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sms ADD COLUMN deletedAt INTEGER")
    }
}

class Converters {
    @TypeConverter fun statusToString(value: SmsStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): SmsStatus = SmsStatus.valueOf(value)
    @TypeConverter fun originToString(value: SmsOrigin): String = value.name
    @TypeConverter fun stringToOrigin(value: String): SmsOrigin = SmsOrigin.valueOf(value)
}
