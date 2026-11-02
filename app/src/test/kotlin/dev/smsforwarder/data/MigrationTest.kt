package dev.smsforwarder.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
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
    private fun schema1(): JSONObject {
        val rel = "schemas/dev.smsforwarder.data.AppDatabase/1.json"
        val file = listOf(File(rel), File("app/$rel")).first { it.exists() }
        return JSONObject(file.readText()).getJSONObject("database")
    }

    private fun createV1(file: File) {
        val schema = schema1()
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
            for ((i, key) in listOf("key-a", "key-b").withIndex()) {
                db.execSQL(
                    "INSERT INTO sms (localKey, sender, senderNormalized, body, receivedAt, phoneDate, subId, " +
                        "origin, status, attempts, lastHttpCode, lastError, firstSeenAt, sentAt, stuckNotified) " +
                        "VALUES ('$key', 'SENDER', 'SENDER', 'Code 123$i', ${1_800_000_000_000L + i}, NULL, 1, " +
                        "'RECEIVER', 'QUEUED', 0, NULL, NULL, ${1_800_000_000_000L + i}, NULL, 0)",
                )
            }
            db.version = 1
        }
    }

    @Test fun migrating1To2KeepsRowsAndLeavesDeletedAtNull() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "migration-${UUID.randomUUID()}.db")
        createV1(file)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .addMigrations(MIGRATION_1_2)
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
