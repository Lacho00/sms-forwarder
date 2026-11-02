package dev.smsforwarder.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.TemplateKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

/**
 * Builds a version 1 database from the committed schema 1.json, then opens it with the real
 * database class and [MIGRATION_1_2]. Room validates the migrated tables against the entities on open.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private fun schema(version: Int): JSONObject {
        val rel = "schemas/dev.smsforwarder.data.AppDatabase/$version.json"
        val file = listOf(File(rel), File("app/$rel")).first { it.exists() }
        return JSONObject(file.readText()).getJSONObject("database")
    }

    private fun createSchema(file: File, version: Int) {
        val schema = schema(version)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) {
                    db.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = version
        }
    }

    private fun createV1(file: File) {
        createSchema(file, 1)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for ((i, key) in listOf("key-a", "key-b").withIndex()) {
                db.execSQL(
                    "INSERT INTO sms (localKey, sender, senderNormalized, body, receivedAt, phoneDate, subId, " +
                        "origin, status, attempts, lastHttpCode, lastError, firstSeenAt, sentAt, stuckNotified) " +
                        "VALUES ('$key', 'SENDER', 'SENDER', 'Code 123$i', ${1_800_000_000_000L + i}, NULL, 1, " +
                        "'RECEIVER', 'QUEUED', 0, NULL, NULL, ${1_800_000_000_000L + i}, NULL, 0)",
                )
            }
        }
    }

    private fun insertSms(db: SQLiteDatabase, id: Int, status: String, attempts: Int, code: Int?, error: String?, sentAt: Long?) {
        db.execSQL(
            "INSERT INTO sms (id, localKey, sender, senderNormalized, body, receivedAt, phoneDate, subId, " +
                "origin, status, attempts, lastHttpCode, lastError, firstSeenAt, sentAt, stuckNotified, deletedAt) " +
                "VALUES ($id, 'k$id', 'S', 'S', 'b$id', ${1_800_000_000_000L + id}, NULL, 1, 'RECEIVER', " +
                "'$status', $attempts, ${code ?: "NULL"}, ${error?.let { "'$it'" } ?: "NULL"}, " +
                "${1_800_000_000_000L + id}, ${sentAt ?: "NULL"}, 0, NULL)",
        )
    }

    private fun insertAttempt(db: SQLiteDatabase, smsId: Int, at: Long, code: Int?, error: String?) {
        db.execSQL(
            "INSERT INTO attempt (smsId, at, httpCode, error) VALUES ($smsId, $at, ${code ?: "NULL"}, " +
                "${error?.let { "'$it'" } ?: "NULL"})",
        )
    }

    private fun createV2WithThreeSms(file: File) {
        createSchema(file, 2)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            insertSms(db, 1, "SENT", 2, 200, null, 1_800_000_005_000L)
            insertSms(db, 2, "QUEUED", 0, null, null, null)
            insertSms(db, 3, "ERROR", 1, 401, "HTTP 401", null)
            insertAttempt(db, 1, 10, 500, "HTTP 500")
            insertAttempt(db, 1, 20, 200, null)
            insertAttempt(db, 3, 30, 401, "HTTP 401")
        }
    }

    private fun open(file: File): AppDatabase =
        Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, file.absolutePath)
            .addMigrations(MIGRATION_1_2, migration2to3("Main"))
            .allowMainThreadQueries()
            .build()

    private fun newFile() = File(RuntimeEnvironment.getApplication().cacheDir, "migration-${UUID.randomUUID()}.db")

    @Test fun migrates2to3KeepingEverySms() = runBlocking {
        val file = newFile()
        createV2WithThreeSms(file)
        val db = open(file)
        try {
            val primary = db.destinationDao().get(PRIMARY_DESTINATION_ID)!!
            assertEquals("Main", primary.name)
            assertEquals(TemplateKind.STANDARD_JSON, primary.template)
            assertEquals(HttpMethod.POST, primary.method)
            assertEquals(SuccessPolicy.ANY_2XX, primary.successPolicy)
            assertEquals(true, primary.enabled)
            assertEquals(true, primary.bodyIsJson)
            assertEquals("[]", primary.publicHeaders)
            assertEquals(
                "{\"sender\":\"{sender}\",\"body\":\"{body}\",\"received_at\":{received_at}}",
                primary.bodyTemplate,
            )

            val d1 = db.deliveryDao().forSms(1).single()
            val d2 = db.deliveryDao().forSms(2).single()
            val d3 = db.deliveryDao().forSms(3).single()
            for (d in listOf(d1, d2, d3)) {
                assertEquals("Main", d.destinationName)
                assertEquals(PRIMARY_DESTINATION_ID, d.destinationId)
            }
            assertEquals(DeliveryStatus.SENT, d1.status)
            assertEquals(2, d1.attempts)
            assertEquals(200, d1.lastHttpCode)
            assertNull(d1.lastError)
            assertEquals(1_800_000_005_000L, d1.sentAt)
            assertEquals(DeliveryStatus.QUEUED, d2.status)
            assertEquals(0, d2.attempts)
            assertNull(d2.lastHttpCode)
            assertNull(d2.sentAt)
            assertEquals(DeliveryStatus.ERROR, d3.status)
            assertEquals(1, d3.attempts)
            assertEquals(401, d3.lastHttpCode)
            assertEquals("HTTP 401", d3.lastError)

            val a1 = db.smsDao().observeAttempts(1).first()
            assertEquals(2, a1.size)
            assertEquals(setOf(d1.id), a1.map { it.deliveryId }.toSet())
            assertEquals(setOf(d3.id), db.smsDao().observeAttempts(3).first().map { it.deliveryId }.toSet())
            assertEquals(0, db.smsDao().observeAttempts(2).first().size)
        } finally {
            db.close()
        }
    }

    @Test fun migrationIsValidatedByRoom() = runBlocking {
        val file = newFile()
        createV2WithThreeSms(file)
        val db = open(file)
        try {
            // Opening the database makes Room compare every migrated table with its entity.
            db.openHelper.writableDatabase
            assertEquals(3, db.openHelper.writableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test fun migrates1to3() = runBlocking {
        val file = newFile()
        createV1(file)
        val db = open(file)
        try {
            val a = db.smsDao().findByLocalKey("key-a")!!
            assertNull(a.deletedAt)
            val delivery = db.deliveryDao().forSms(a.id).single()
            assertEquals(DeliveryStatus.QUEUED, delivery.status)
            assertEquals("Main", db.destinationDao().get(PRIMARY_DESTINATION_ID)!!.name)
        } finally {
            db.close()
        }
    }

    @Test fun emptyDatabaseStillGetsPrimary() = runBlocking {
        val file = newFile()
        createSchema(file, 2)
        val db = open(file)
        try {
            assertEquals("Main", db.destinationDao().get(PRIMARY_DESTINATION_ID)!!.name)
            assertEquals(0, db.deliveryDao().smsIdsWithStatus(DeliveryStatus.QUEUED).size)
            assertEquals(0, db.deliveryDao().forSms(1).size)
        } finally {
            db.close()
        }
    }

    @Test fun migrating1To2KeepsRowsAndLeavesDeletedAtNull() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "migration-${UUID.randomUUID()}.db")
        createV1(file)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .addMigrations(MIGRATION_1_2, migration2to3("Main"))
            .allowMainThreadQueries()
            .build()
        try {
            val a = db.smsDao().findByLocalKey("key-a")!!
            val b = db.smsDao().findByLocalKey("key-b")!!
            assertEquals("Code 1230", a.body)
            assertEquals("Code 1231", b.body)
            assertNull(a.deletedAt)
            assertNull(b.deletedAt)
        } finally {
            db.close()
        }
    }
}
