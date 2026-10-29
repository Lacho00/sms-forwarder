package dev.smsforwarder.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsStatus

@Database(entities = [SmsEntity::class, AttemptEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun smsDao(): SmsDao
}

class Converters {
    @TypeConverter fun statusToString(value: SmsStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): SmsStatus = SmsStatus.valueOf(value)
    @TypeConverter fun originToString(value: SmsOrigin): String = value.name
    @TypeConverter fun stringToOrigin(value: String): SmsOrigin = SmsOrigin.valueOf(value)
}
