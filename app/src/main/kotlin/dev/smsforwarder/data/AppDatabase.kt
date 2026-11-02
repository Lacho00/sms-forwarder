package dev.smsforwarder.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.smsforwarder.core.BodyMode
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.TemplateKind

@Database(
    entities = [
        SmsEntity::class,
        AttemptEntity::class,
        DestinationEntity::class,
        RuleEntity::class,
        RuleDestinationEntity::class,
        DeliveryEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun smsDao(): SmsDao
    abstract fun destinationDao(): DestinationDao
    abstract fun ruleDao(): RuleDao
    abstract fun deliveryDao(): DeliveryDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sms ADD COLUMN deletedAt INTEGER")
    }
}

const val PRIMARY_DESTINATION_ID = 1L

private const val STANDARD_JSON_BODY = "{\"sender\":\"{sender}\",\"body\":\"{body}\",\"received_at\":{received_at}}"

/**
 * Moves every SMS to a delivery toward one primary destination (id [PRIMARY_DESTINATION_ID]), which
 * always exists afterwards, and ties each attempt to the delivery of its SMS. The primary destination
 * keeps the old single endpoint: its URL and token stay in the secret store.
 */
fun migration2to3(primaryName: String): Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `destination` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `template` TEXT NOT NULL, " +
                "`method` TEXT NOT NULL, `bodyTemplate` TEXT, `bodyIsJson` INTEGER NOT NULL, " +
                "`publicHeaders` TEXT NOT NULL, `successPolicy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`deletedAt` INTEGER)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `rule` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `senderMode` TEXT NOT NULL, " +
                "`senderPattern` TEXT NOT NULL, `bodyMode` TEXT, `bodyPattern` TEXT, `sim` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `rule_destination` (`ruleId` INTEGER NOT NULL, " +
                "`destinationId` INTEGER NOT NULL, PRIMARY KEY(`ruleId`, `destinationId`), " +
                "FOREIGN KEY(`ruleId`) REFERENCES `rule`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`destinationId`) REFERENCES `destination`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_rule_destination_destinationId` " +
                "ON `rule_destination` (`destinationId`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `delivery` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`smsId` INTEGER NOT NULL, `destinationId` INTEGER, `destinationName` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `lastHttpCode` INTEGER, `lastError` TEXT, " +
                "`sentAt` INTEGER, " +
                "FOREIGN KEY(`smsId`) REFERENCES `sms`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`destinationId`) REFERENCES `destination`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_delivery_smsId_destinationId` " +
                "ON `delivery` (`smsId`, `destinationId`)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_delivery_status` ON `delivery` (`status`)")

        db.execSQL(
            "INSERT INTO destination (id, name, enabled, template, method, bodyTemplate, bodyIsJson, " +
                "publicHeaders, successPolicy, createdAt, deletedAt) " +
                "VALUES (?, ?, 1, 'STANDARD_JSON', 'POST', ?, 1, '[]', 'ANY_2XX', ?, NULL)",
            arrayOf<Any>(PRIMARY_DESTINATION_ID, primaryName, STANDARD_JSON_BODY, System.currentTimeMillis()),
        )
        db.execSQL(
            "INSERT INTO delivery (smsId, destinationId, destinationName, status, attempts, lastHttpCode, " +
                "lastError, sentAt) " +
                "SELECT id, ?, ?, status, attempts, lastHttpCode, lastError, sentAt FROM sms ORDER BY id",
            arrayOf<Any>(PRIMARY_DESTINATION_ID, primaryName),
        )

        // SQLite cannot add a foreign key with ALTER TABLE, so attempt is rebuilt.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `attempt_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`smsId` INTEGER NOT NULL, `at` INTEGER NOT NULL, `httpCode` INTEGER, `error` TEXT, " +
                "`deliveryId` INTEGER, " +
                "FOREIGN KEY(`smsId`) REFERENCES `sms`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`deliveryId`) REFERENCES `delivery`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "INSERT INTO attempt_new (id, smsId, at, httpCode, error, deliveryId) " +
                "SELECT a.id, a.smsId, a.at, a.httpCode, a.error, " +
                "(SELECT d.id FROM delivery d WHERE d.smsId = a.smsId LIMIT 1) FROM attempt a",
        )
        db.execSQL("DROP TABLE attempt")
        db.execSQL("ALTER TABLE attempt_new RENAME TO attempt")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_attempt_smsId` ON `attempt` (`smsId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_attempt_deliveryId` ON `attempt` (`deliveryId`)")
    }
}

class Converters {
    @TypeConverter fun statusToString(value: SmsStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): SmsStatus = SmsStatus.valueOf(value)
    @TypeConverter fun originToString(value: SmsOrigin): String = value.name
    @TypeConverter fun stringToOrigin(value: String): SmsOrigin = SmsOrigin.valueOf(value)
    @TypeConverter fun templateToString(value: TemplateKind): String = value.name
    @TypeConverter fun stringToTemplate(value: String): TemplateKind = TemplateKind.valueOf(value)
    @TypeConverter fun methodToString(value: HttpMethod): String = value.name
    @TypeConverter fun stringToMethod(value: String): HttpMethod = HttpMethod.valueOf(value)
    @TypeConverter fun policyToString(value: SuccessPolicy): String = value.name
    @TypeConverter fun stringToPolicy(value: String): SuccessPolicy = SuccessPolicy.valueOf(value)
    @TypeConverter fun deliveryStatusToString(value: DeliveryStatus): String = value.name
    @TypeConverter fun stringToDeliveryStatus(value: String): DeliveryStatus = DeliveryStatus.valueOf(value)
    @TypeConverter fun senderModeToString(value: SenderMode): String = value.name
    @TypeConverter fun stringToSenderMode(value: String): SenderMode = SenderMode.valueOf(value)
    @TypeConverter fun bodyModeToString(value: BodyMode?): String? = value?.name
    @TypeConverter fun stringToBodyMode(value: String?): BodyMode? = value?.let { BodyMode.valueOf(it) }
    @TypeConverter fun simToString(value: SimMatch): String = value.name
    @TypeConverter fun stringToSim(value: String): SimMatch = SimMatch.valueOf(value)
}
