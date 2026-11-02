package dev.smsforwarder.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val name = "migration-test"

    @Test fun migrating1To2KeepsRowsAndLeavesDeletedAtNull() {
        val assets = org.robolectric.RuntimeEnvironment.getApplication().assets
        val listing = runCatching { assets.list("")?.toList() }.toString() +
            runCatching { assets.list("dev.smsforwarder.data.AppDatabase")?.toList() }.toString() +
            InstrumentationRegistry.getInstrumentation().context.javaClass
        try { helper.createDatabase(name, 1).close() } catch (e: Exception) { throw AssertionError(listing + " / " + e, e) }
        helper.createDatabase(name, 1).use { db ->
            for ((i, key) in listOf("key-a", "key-b").withIndex()) {
                db.execSQL(
                    "INSERT INTO sms (localKey, sender, senderNormalized, body, receivedAt, phoneDate, subId, " +
                        "origin, status, attempts, lastHttpCode, lastError, firstSeenAt, sentAt, stuckNotified) " +
                        "VALUES ('$key', 'SENDER', 'SENDER', 'Code 123$i', ${1_800_000_000_000L + i}, NULL, 1, " +
                        "'RECEIVER', 'QUEUED', 0, NULL, NULL, ${1_800_000_000_000L + i}, NULL, 0)",
                )
            }
        }

        helper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT localKey, body, status, deletedAt FROM sms ORDER BY id").use { c ->
                assertEquals(2, c.count)
                assertTrue(c.moveToFirst())
                assertEquals("key-a", c.getString(0))
                assertEquals("Code 1230", c.getString(1))
                assertEquals("QUEUED", c.getString(2))
                assertTrue(c.isNull(3))
                assertTrue(c.moveToNext())
                assertEquals("key-b", c.getString(0))
                assertTrue(c.isNull(3))
            }
        }
    }
}
