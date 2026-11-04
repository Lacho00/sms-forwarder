package dev.smsforwarder.data

import androidx.room.Room
import dev.smsforwarder.core.BodyMode
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.ConfigCodec
import dev.smsforwarder.core.ConfigError
import dev.smsforwarder.core.ConfigFile
import dev.smsforwarder.core.ConfigParse
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.RuleDraft
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import dev.smsforwarder.diag.EngineLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ConfigTransferTest {
    private val clock = Clock { 1_800_000_000_000L }
    private lateinit var db: AppDatabase
    private lateinit var repos: TestRepos
    private lateinit var transfer: ConfigTransfer

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repos = TestRepos(db, clock)
        transfer = ConfigTransfer(db, repos.destinations, repos.rules, repos.secrets)
    }

    @After fun tearDown() {
        db.close()
    }

    private fun rule(name: String, vararg ids: Long, pattern: String = "AAA", mode: SenderMode = SenderMode.EXACT) =
        RuleDraft(name, true, mode, pattern, null, null, SimMatch.ANY, ids.toSet())

    private suspend fun seed(): Pair<Long, Long> {
        val d = repos.destinations.create(DestinationPresets.standardJson("Main", "https://h.example/in", "TOKEN"))
        val r = repos.rules.create(rule("Rule", d))
        return d to r
    }

    private suspend fun counts(): List<Int> = listOf(
        db.destinationDao().observeLive().first().size,
        db.ruleDao().observeAllWithDestinations().first().size,
        db.ruleDao().observeAllWithDestinations().first().sumOf { it.destinations.size },
        repos.secrets.map.size,
    )

    private fun ok(text: String): ConfigFile = (ConfigCodec.decode(text) as ConfigParse.Ok).file

    @Test fun exportWithoutSecrets() = runBlocking {
        seed()
        val text = transfer.export(includeSecrets = false, appVersion = "2.0.0")
        assertFalse(text.contains("https://h.example"))
        assertFalse(text.contains("TOKEN"))
        val f = ok(text)
        val d = f.destinations.single()
        assertNull(d.draft.url)
        val h = d.draft.headers.single()
        assertEquals("Authorization", h.name)
        assertTrue(h.secret)
        assertNull(h.value)
        assertEquals(listOf(d.key), f.rules.single().destinationKeys)
        assertFalse(f.includesSecrets)
    }

    @Test fun exportWithSecrets() = runBlocking {
        val (d, _) = seed()
        val gone = repos.destinations.create(DestinationPresets.standardJson("Gone", "https://h.example/gone", "T2"))
        repos.rules.create(rule("Other", d, gone, pattern = "BBB"))
        repos.destinations.delete(gone)
        val f = ok(transfer.export(includeSecrets = true, appVersion = "2.0.0"))
        val dest = f.destinations.single()
        assertEquals("https://h.example/in", dest.draft.url)
        assertEquals("Bearer TOKEN", dest.draft.headers.single().value)
        assertEquals(2, f.rules.size)
        assertTrue(f.rules.all { it.destinationKeys == listOf(dest.key) })
        assertTrue(f.includesSecrets)
    }

    @Test fun importAddsNeverReplaces() = runBlocking {
        val (orig, _) = seed()
        val file = ok(transfer.export(includeSecrets = true, appVersion = "2.0.0"))
        val before = repos.secrets.map[orig]
        val result = transfer.apply(file)
        assertEquals(1, result.destinations)
        assertEquals(1, result.rules)
        assertEquals(0, result.withoutCredentials)
        val live = db.destinationDao().observeLive().first()
        assertEquals(listOf("Main", "Main (2)"), live.map { it.name })
        assertEquals(before, repos.secrets.map[orig])
        val newId = live[1].id
        assertEquals("https://h.example/in", repos.secrets.map[newId]!!.url)
        val imported = db.ruleDao().observeAllWithDestinations().first().first { it.rule.name == "Rule (2)" }
        assertEquals(listOf(newId), imported.destinations.map { it.id })
    }

    @Test fun invalidFileChangesNothing() = runBlocking {
        seed()
        val before = counts()
        val text = transfer.export(true, "2.0.0").replace("\"AAA\"", "\"(\"").replace("\"EXACT\"", "\"REGEX\"")
        val p = transfer.preview(text)
        assertTrue(p is ImportPreview.Invalid)
        assertEquals(ConfigError.INVALID_RULE, (p as ImportPreview.Invalid).error)
        assertEquals(before, counts())
    }

    @Test fun importWithoutSecretsIsUnconfigured() = runBlocking {
        seed()
        val file = ok(transfer.export(false, "2.0.0"))
        val result = transfer.apply(file)
        assertEquals(1, result.withoutCredentials)
        val newId = db.destinationDao().observeLive().first().last().id
        assertNull(repos.destinations.secrets(newId))
        assertTrue(newId in repos.destinations.unconfiguredInUse())
    }

    @Test fun secretFailureRollsBackEverything() = runBlocking {
        seed()
        val file = ok(transfer.export(true, "2.0.0"))
        val two = file.copy(destinations = file.destinations + file.destinations.single().let {
            it.copy(key = "d99", draft = it.draft.copy(name = "Second"))
        })
        val before = counts()
        repos.secrets.setCalls = 0
        repos.secrets.failOnNthSet = 2
        try {
            transfer.apply(two)
            fail("expected failure")
        } catch (e: java.security.GeneralSecurityException) {
            // expected
        }
        assertEquals(before, counts())
    }

    @Test fun exportFailsOnTransientKeystore() = runBlocking {
        seed()
        repos.secrets.getFailure = TransientSecretException(RuntimeException("x"))
        try {
            transfer.export(false, "2.0.0")
            fail("expected failure")
        } catch (e: TransientSecretException) {
            // expected
        }
        Unit
    }

    @Test fun applyRenamesAgainstCurrentNames() = runBlocking {
        seed()
        val file = ok(transfer.export(true, "2.0.0"))
        val named = file.copy(destinations = file.destinations.map { it.copy(draft = it.draft.copy(name = "A")) })
        val preview = transfer.preview(ConfigCodec.encode(named, "2.0.0"))
        assertTrue(preview is ImportPreview.Ready)
        repos.destinations.create(DestinationPresets.standardJson("A", "https://h.example/a", "T"))
        transfer.apply((preview as ImportPreview.Ready).file)
        assertTrue(db.destinationDao().observeLive().first().any { it.name == "A (2)" })
    }

    private suspend fun twoWithUrls(): ConfigFile {
        seed()
        val file = ok(transfer.export(true, "2.0.0"))
        return file.copy(
            destinations = file.destinations + file.destinations.single().let {
                it.copy(key = "d99", draft = it.draft.copy(name = "Second"))
            },
        )
    }

    @Test fun removeFailureDuringCleanupKeepsOriginalAndSuppresses() = runBlocking {
        val two = twoWithUrls()
        repos.secrets.setCalls = 0
        repos.secrets.failOnNthSet = 2
        repos.secrets.removeFailsAfterSet = true
        try {
            transfer.apply(two)
            fail("expected failure")
        } catch (e: java.security.GeneralSecurityException) {
            assertEquals("set failed", e.message)
            assertTrue(e.suppressed.any { it.message == "remove failed" })
        }
    }

    @Test fun cancelAfterCommitKeepsSecrets() = runBlocking {
        seed()
        val file = ok(transfer.export(true, "2.0.0"))
        val log = EngineLog(java.io.File.createTempFile("engine", ".log"))
        val cancelling = ConfigTransfer(
            db, repos.destinations, repos.rules, repos.secrets,
            engineLog = log, clock = Clock { throw kotlinx.coroutines.CancellationException("after commit") },
        )
        try {
            cancelling.apply(file)
            fail("expected cancellation")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
        val live = db.destinationDao().observeLive().first()
        assertEquals(2, live.size)
        assertEquals("https://h.example/in", repos.secrets.map[live.last().id]!!.url)
    }

    @Test fun ruleInsertFailureLeavesNothing() = runBlocking {
        seed()
        val file = ok(transfer.export(true, "2.0.0"))
        val bad = file.copy(
            rules = file.rules.map { it.copy(draft = it.draft.copy(senderMode = SenderMode.REGEX, senderPattern = "(")) },
        )
        val before = counts()
        try {
            transfer.apply(bad)
            fail("expected failure")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertEquals(before, counts())
    }

    @Test fun reusedIdAfterRollbackHasNoSecrets() = runBlocking {
        repos.secrets.map[1L] = DestinationSecrets("https://stale.example", listOf("X-Key" to "stale"))
        val file = ok(
            ConfigCodec.encode(
                ConfigFile(
                    listOf(dev.smsforwarder.core.ConfigDestination("d1", DestinationPresets.ntfy("N", "https://h.example/t").copy(url = null))),
                    emptyList(),
                    false,
                ),
                "2.0.0",
            ),
        )
        transfer.apply(file)
        val id = db.destinationDao().observeLive().first().single().id
        assertNull(repos.secrets.map[id])
    }

    @Test fun inactiveRuleWithUnconfiguredDestinationIsNotInUse() = runBlocking {
        val d = repos.destinations.create(DestinationPresets.ntfy("N", "https://h.example/t"))
        val r = repos.rules.create(rule("Off", d))
        repos.rules.setEnabled(r, false)
        repos.secrets.map.remove(d)
        assertTrue(repos.destinations.unconfiguredInUse().isEmpty())
    }
}
