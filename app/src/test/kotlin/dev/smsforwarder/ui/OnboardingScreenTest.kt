package dev.smsforwarder.ui

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import dev.smsforwarder.R
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.ConnectionTestResult
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.data.SmsRepository
import dev.smsforwarder.data.TestRepos
import dev.smsforwarder.sms.InboxReader
import dev.smsforwarder.sms.InboxRow
import dev.smsforwarder.ui.onboarding.OnboardingScreen
import dev.smsforwarder.ui.onboarding.OnboardingStep
import dev.smsforwarder.ui.onboarding.OnboardingViewModel
import dev.smsforwarder.ui.recovery.RecoveryViewModel
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class OnboardingScreenTest {
    @get:Rule val compose = createComposeRule()

    private class EmptyReader : InboxReader {
        override fun readSince(fromDate: Long): List<InboxRow>? = emptyList()
        override fun hasAnyRow(): Boolean? = true
    }

    private val now = 1_800_000_000_000L
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var vm: OnboardingViewModel
    private lateinit var recoveryVm: RecoveryViewModel
    private var connectionResult = ConnectionTestResult.OK
    private var finished = 0

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        val clock = Clock { now }
        val sms = SmsRepository(db, clock)
        val repos = TestRepos(db)
        val file = File(app.cacheDir, "onb-screen-${UUID.randomUUID()}.preferences_pb")
        val settings = SettingsRepository(app, repos.destinations, repos.rules, sms, scheduler = {}, storeFile = file)
        vm = OnboardingViewModel(settings, clock) { connectionResult }
        recoveryVm = RecoveryViewModel(EmptyReader(), sms, repos.rules, app, clock) { }
    }

    @After fun tearDown() {
        db.close()
    }

    private fun show() {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) { OnboardingScreen(vm, recoveryVm) { finished++ } }
        }
    }

    private fun str(id: Int, vararg args: Any) = app.getString(id, *args)

    private fun reach(step: OnboardingStep) = runBlocking {
        vm.confirmCutover(true)
        while (vm.state.value.step != step) {
            when (vm.state.value.step) {
                OnboardingStep.PERMISSIONS -> vm.onPermissionResult(smsGranted = true, restrictedHint = false)
                OnboardingStep.SENDERS -> vm.addSender("TEST")
                OnboardingStep.CONNECTION -> {
                    vm.setUrl("https://h.example")
                    vm.setToken("TEST")
                    vm.testConnection()
                }
                else -> Unit
            }
            vm.next()
        }
    }

    private fun clickable(text: String) = hasClickAction() and hasText(text)

    @Test fun showsStepProgress() {
        reach(OnboardingStep.BATTERY)
        show()
        compose.onNodeWithText(str(R.string.onboarding_progress, 3, 8)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.onboarding_step_battery)).assertIsDisplayed()
    }

    @Test fun permissionStatusIsAPill() {
        reach(OnboardingStep.PERMISSIONS)
        show()
        compose.onNodeWithText(str(R.string.onboarding_permissions_pending)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.onboarding_permissions_granted)).assertDoesNotExist()
    }

    @Test fun grantedPermissionStatusIsAPill() {
        shadowOf(app).grantPermissions(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        reach(OnboardingStep.PERMISSIONS)
        show()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.onboarding_permissions_granted)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.onboarding_permissions_pending)).assertDoesNotExist()
        compose.onNode(clickable(str(R.string.onboarding_grant_permissions))).assertIsDisplayed()
        compose.onNode(clickable(str(R.string.onboarding_check_again))).assertIsDisplayed()
    }

    private fun connectionWith(result: ConnectionTestResult) {
        connectionResult = result
        reach(OnboardingStep.CONNECTION)
        show()
        // load() runs on first composition; set the fields after it.
        compose.waitForIdle()
        vm.setUrl("https://h.example")
        vm.setToken("TEST")
        compose.waitForIdle()
        compose.onNode(clickable(str(R.string.action_test_connection))).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test fun connectionOkIsAPill() {
        connectionWith(ConnectionTestResult.OK)
        compose.onNodeWithText(str(R.string.onboarding_conn_ok)).performScrollTo().assertIsDisplayed()
    }

    @Test fun connectionErrorIsAPill() {
        connectionWith(ConnectionTestResult.BAD_TOKEN)
        compose.onNodeWithText(str(R.string.onboarding_conn_bad_token)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.onboarding_conn_ok)).assertDoesNotExist()
    }

    @Test fun continueIsPinnedAndAdvances() {
        reach(OnboardingStep.BATTERY)
        show()
        val node = compose.onNode(clickable(str(R.string.onboarding_continue)))
        node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val d = compose.density.density
        assertTrue("full width", bounds.width >= root.width - 40 * d)
        assertTrue("pinned to the bottom", bounds.bottom > root.bottom - 80 * d)
        node.performClick()
        compose.waitForIdle()
        assertEquals(OnboardingStep.MIUI, vm.state.value.step)
    }

    @Test fun continueIsDisabledWhenTheStepCannotContinue() {
        reach(OnboardingStep.PERMISSIONS)
        show()
        compose.onNode(clickable(str(R.string.onboarding_continue))).assertIsNotEnabled()
    }

    @Test fun noBackArrow() {
        reach(OnboardingStep.BATTERY)
        show()
        compose.onNodeWithContentDescription(str(R.string.back)).assertDoesNotExist()
    }

    @Test fun batteryActionIsATonalFullWidthButton() {
        reach(OnboardingStep.BATTERY)
        show()
        val bounds = compose.onNode(clickable(str(R.string.onboarding_battery_button))).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= root.width - 40 * compose.density.density)
    }

    @Test fun recoveryActionUsesTheBottomBarAndFinishes() {
        reach(OnboardingStep.RECOVERY)
        show()
        compose.waitForIdle()
        val label = str(R.string.onboarding_finish_without)
        val node = compose.onNode(clickable(label))
        node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val d = compose.density.density
        // BottomActionBar: 16 dp side padding, pinned to the bottom edge.
        assertEquals(root.width - 32 * d, bounds.width, 1f)
        assertTrue(bounds.bottom > root.bottom - 80 * d)
        node.performClick()
        compose.waitUntil(5_000) { finished == 1 }
        assertEquals(1, finished)
    }

    @Test fun sendersAddButtonStaysUsable() {
        reach(OnboardingStep.SENDERS)
        show()
        compose.onNodeWithTag("field_sender").performTextInput("TEST2")
        compose.onNode(clickable(str(R.string.action_add))).performClick()
        // addSender saves and re-reads on Room's own executor, which waitForIdle does not wait for.
        // Waiting on the state also keeps tearDown from closing the database mid-query.
        compose.waitUntil(5_000) { "TEST2" in vm.state.value.senders }
        compose.onNodeWithText("TEST2").assertIsDisplayed()
    }
}
