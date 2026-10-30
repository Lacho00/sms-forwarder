package dev.smsforwarder.ui

import androidx.room.Room
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.ConnectionTestResult
import dev.smsforwarder.core.interpretConnectionTest
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.Credentials
import dev.smsforwarder.data.SecretStore
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.data.SmsRepository
import dev.smsforwarder.ui.onboarding.OnboardingStep
import dev.smsforwarder.ui.onboarding.OnboardingTexts
import dev.smsforwarder.ui.onboarding.OnboardingViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class OnboardingViewModelTest {
    private class FakeSecrets : SecretStore {
        var saved: Credentials? = null
        override suspend fun get(): Credentials? = saved
        override suspend fun set(c: Credentials) {
            saved = c
        }
    }

    private val now = 1_800_000_000_000L
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private val secrets = FakeSecrets()
    private val tested = mutableListOf<Credentials>()
    private var httpCode: Int? = 400
    private lateinit var vm: OnboardingViewModel

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val sms = SmsRepository(db, Clock { now })
        val file = File(context.cacheDir, "onboarding-${UUID.randomUUID()}.preferences_pb")
        settings = SettingsRepository(context, secrets, sms, scheduler = {}, storeFile = file)
        vm = OnboardingViewModel(settings, Clock { now }) { credentials ->
            tested += credentials
            interpretConnectionTest(httpCode)
        }
    }

    @After fun tearDown() {
        db.close()
    }

    private suspend fun reachSenders() {
        vm.confirmCutover(true)
        vm.next()
        vm.onPermissionResult(smsGranted = true, restrictedHint = false)
        vm.next() // permissions -> battery
        vm.next() // battery -> miui
        vm.next() // miui -> senders
    }

    @Test fun onboardingRejectsHttpUrl() = runBlocking {
        vm.setUrl("http://example.com/ingest")
        vm.setToken("test-token")
        vm.testConnection()

        assertNotNull(vm.state.value.urlError)
        assertNull(vm.state.value.testResult)
        assertTrue(tested.isEmpty())
        assertFalse(vm.state.value.copy(step = OnboardingStep.CONNECTION).canContinue)
    }

    @Test fun onboardingRequiresAtLeastOneSender() = runBlocking {
        reachSenders()
        assertEquals(OnboardingStep.SENDERS, vm.state.value.step)
        assertFalse(vm.state.value.canContinue)
        vm.next()
        assertEquals(OnboardingStep.SENDERS, vm.state.value.step)

        vm.addSender("OPERADOR")
        assertTrue(vm.state.value.canContinue)
        vm.next()
        assertEquals(OnboardingStep.CONNECTION, vm.state.value.step)
    }

    @Test fun onboarding400MeansOkAndShowsCutoverReminder() = runBlocking {
        assertEquals(OnboardingStep.CUTOVER, vm.state.value.step)
        assertTrue(OnboardingTexts.CUTOVER.contains("desinstala o desactiva la app anterior"))
        vm.next()
        assertEquals("Needs confirmation", OnboardingStep.CUTOVER, vm.state.value.step)

        reachSenders()
        vm.addSender("OPERADOR")
        vm.next()
        assertEquals(OnboardingStep.CONNECTION, vm.state.value.step)

        vm.setUrl("https://example.com/ingest")
        vm.setToken("test-token")
        assertFalse(vm.state.value.canContinue)
        vm.testConnection()
        assertEquals(ConnectionTestResult.OK, vm.state.value.testResult)
        assertTrue(vm.state.value.canContinue)

        vm.next()
        assertEquals(OnboardingStep.REMINDER, vm.state.value.step)
        assertEquals("test-token", secrets.saved?.token)
        vm.next()
        assertEquals(OnboardingStep.RECOVERY, vm.state.value.step)
    }

    @Test fun onboardingFinishSetsFloorCursorAndDone() = runBlocking {
        assertFalse(settings.onboardingDone.first())
        vm.finish()
        assertEquals(now, settings.sweepFloor())
        assertEquals(now, settings.sweepCursor())
        assertTrue(settings.onboardingDone.first())
    }
}
