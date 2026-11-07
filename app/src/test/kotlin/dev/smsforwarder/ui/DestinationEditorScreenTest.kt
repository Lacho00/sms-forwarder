package dev.smsforwarder.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.smsforwarder.ui.destinations.STORED_LOCK_TAG
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import dev.smsforwarder.R
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.DestinationError
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.HeaderDraft
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.TestRepos
import dev.smsforwarder.net.DeliveryClient
import dev.smsforwarder.net.DestinationTester
import dev.smsforwarder.ui.destinations.DestinationEditorScreen
import dev.smsforwarder.ui.destinations.DestinationEditorState
import dev.smsforwarder.ui.destinations.DestinationEditorViewModel
import dev.smsforwarder.ui.destinations.HeaderRowUi
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DestinationEditorScreenTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val clock = Clock { 1_800_000_000_000L }
    private lateinit var db: AppDatabase
    private lateinit var repos: TestRepos

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        repos = TestRepos(db, clock)
    }

    @After fun tearDown() {
        db.close()
    }

    private fun vm(id: Long?) = DestinationEditorViewModel(
        id, repos.destinations,
        DestinationTester(DeliveryClient(OkHttpClient()), repos.secrets, "test", "device-x", clock),
        {}, { "Name" }, CoroutineScope(Dispatchers.Unconfined),
    )

    private fun show(vm: DestinationEditorViewModel, override: DestinationEditorState? = null) {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                val s by vm.state.collectAsState()
                DestinationEditorScreen(state = override ?: s, vm = vm, onBack = {})
            }
        }
    }

    @Test fun storedSecretsNotShown() {
        val id = runBlocking {
            repos.destinations.create(
                DestinationPresets.custom("Alpha").copy(
                    url = "https://h.example/secret-path",
                    headers = listOf(HeaderDraft("X-Key", "hush-value", secret = true), HeaderDraft("X-Plain", "plain-value", secret = false)),
                    body = "{}",
                ),
            )
        }
        val vm = vm(id)
        val end = System.currentTimeMillis() + 5_000
        while (!vm.state.value.loaded && System.currentTimeMillis() < end) Thread.sleep(10)
        show(vm)
        compose.waitForIdle()

        compose.onAllNodes(hasText("hush-value", substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasText("secret-path", substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("plain-value").assertExists()
        // One hint for the URL and one for the secret header.
        compose.onAllNodesWithText(app.getString(R.string.destination_secret_kept)).assertCountEquals(2)
    }

    @Test fun deleteDialogShowsCount() {
        val state = DestinationEditorState(loaded = true, isNew = false, deletePending = 3)
        show(vm(null), state)
        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.destination_delete_confirm, 3, 3)).assertExists()
    }

    @Test fun errorsAreShownNextToTheirFields() {
        val state = DestinationEditorState(
            loaded = true,
            errors = setOf(DestinationError.NAME_EMPTY, DestinationError.URL_NOT_HTTPS, DestinationError.BODY_WITH_GET),
        )
        show(vm(null), state)
        compose.onNodeWithText(app.getString(R.string.destination_error_name_empty)).assertExists()
        compose.onNodeWithText(app.getString(R.string.destination_error_url_not_https)).assertExists()
        compose.onNodeWithText(app.getString(R.string.destination_error_body_with_get)).assertExists()
    }

    @Test fun templateSelectorOnlyWhenCreating() {
        show(vm(null), DestinationEditorState(loaded = true, isNew = true))
        compose.onNodeWithText(app.getString(R.string.template_standard_json)).assertExists()
    }

    @Test fun ntfyTemplateCanBeChosenAndSaved() {
        val vm = vm(null)
        show(vm)
        compose.onNode(hasClickAction() and hasText(app.getString(R.string.template_standard_json))).performClick()
        compose.onNode(hasClickAction() and hasText(app.getString(R.string.template_ntfy))).performClick()
        compose.waitForIdle()
        vm.setPreset(vm.state.value.preset.copy(url = "https://ntfy.sh/topic"))
        vm.applyPreset()
        vm.save()
        val end = System.currentTimeMillis() + 5_000
        while (!vm.state.value.done && System.currentTimeMillis() < end) Thread.sleep(10)
        val saved = runBlocking { repos.destinations.observeLive().first() }
        assertEquals(listOf(TemplateKind.NTFY), saved.map { it.template })
    }

    @Test fun templateSelectorAbsentWhenEditing() {
        show(vm(null), DestinationEditorState(loaded = true, isNew = false, template = TemplateKind.CUSTOM))
        compose.onAllNodesWithText(app.getString(R.string.template_custom)).assertCountEquals(0)
    }

    @Test fun savingAnEmptyFormShowsErrorsAndDoesNotFinish() {
        val vm = vm(null)
        show(vm)
        compose.onNodeWithText(app.getString(R.string.destination_save)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(app.getString(R.string.destination_error_url_missing)).assertExists()
        assertTrue(!vm.state.value.done)
    }

    @Test fun headerRemoveButtonsNameTheirHeader() {
        val state = DestinationEditorState(
            loaded = true,
            headers = listOf(HeaderRowUi("X-Key", "", false, false), HeaderRowUi("", "", false, false)),
        )
        show(vm(null), state)
        compose.onNodeWithContentDescription(app.getString(R.string.destination_header_remove, "X-Key")).assertExists()
        val fallback = app.getString(R.string.destination_header_fallback_name, 2)
        compose.onNodeWithContentDescription(app.getString(R.string.destination_header_remove, fallback)).assertExists()
    }

    @Test fun templateControlSelectsTemplate() {
        val vm = vm(null)
        show(vm)
        // The field shows the current template; opening it lists the four.
        compose.onNode(hasClickAction() and hasText(app.getString(R.string.template_standard_json))).performClick()
        compose.onNode(hasClickAction() and hasText(app.getString(R.string.template_telegram))).performClick()
        compose.waitForIdle()
        assertEquals(TemplateKind.TELEGRAM, vm.state.value.template)
    }

    @Test fun headerRowHasNameValueSecretAndRemove() {
        val vm = vm(null)
        vm.setHeaders(listOf(HeaderRowUi("X-Key", "val-1", secret = false, stored = false)))
        show(vm)
        compose.onNodeWithText("X-Key").performScrollTo().assertExists()
        compose.onNodeWithText("val-1").assertExists()
        compose.onNodeWithText(app.getString(R.string.destination_header_secret)).assertExists()
        compose.onNodeWithContentDescription(app.getString(R.string.destination_header_remove, "X-Key")).performClick()
        compose.waitForIdle()
        assertTrue(vm.state.value.headers.isEmpty())
    }

    @Test fun addHeaderButtonAddsARow() {
        val vm = vm(null)
        show(vm)
        compose.onNode(hasClickAction() and hasText(app.getString(R.string.destination_header_add))).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, vm.state.value.headers.size)
    }

    @Test fun longHeaderNameKeepsRemoveButtonOnScreen() {
        val vm = vm(null)
        vm.setHeaders(listOf(HeaderRowUi("X-".padEnd(80, 'A'), "B".repeat(80), secret = false, stored = false)))
        show(vm)
        val remove = compose.onNodeWithContentDescription(
            app.getString(R.string.destination_header_remove, "X-".padEnd(80, 'A')),
        ).performScrollTo()
        val root = compose.onRoot().getBoundsInRoot()
        val b = remove.getBoundsInRoot()
        assertTrue(b.left >= 0.dp && b.right <= root.right)
    }

    @Test fun storedSecretHelperHasLockIcon() {
        val state = DestinationEditorState(loaded = true, urlStored = true)
        show(vm(null), state)
        compose.onNodeWithText(app.getString(R.string.destination_secret_kept)).performScrollTo().assertExists()
        compose.onNodeWithTag(STORED_LOCK_TAG, useUnmergedTree = true).assertExists()
    }

    @Test fun saveAndTestArePinnedAtBottom() {
        val state = DestinationEditorState(
            loaded = true,
            headers = List(8) { HeaderRowUi("X-$it", "v", secret = false, stored = false) },
        )
        show(vm(null), state)
        // Never scrolled to: the bar sits outside the scrolling form.
        compose.onNode(hasClickAction() and hasText(app.getString(R.string.destination_save))).assertIsDisplayed()
        compose.onNode(hasClickAction() and hasText(app.getString(R.string.destination_test))).assertIsDisplayed()
    }

    @Test fun deleteButtonOnlyOnExistingDestinations() {
        show(vm(null), DestinationEditorState(loaded = true, isNew = false))
        compose.onNode(hasClickAction() and hasText(app.getString(R.string.destination_delete))).performScrollTo().assertExists()
    }

    @Test fun newDestinationHasNoDeleteButton() {
        show(vm(null), DestinationEditorState(loaded = true, isNew = true))
        compose.onAllNodesWithText(app.getString(R.string.destination_delete)).assertCountEquals(0)
    }
}
