package dev.smsforwarder.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertCountEquals
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
        compose.onNodeWithText(app.getString(R.string.template_telegram)).assertExists()
    }

    @Test fun ntfyTemplateCanBeChosenAndSaved() {
        val vm = vm(null)
        show(vm)
        compose.onNodeWithText(app.getString(R.string.template_ntfy)).performScrollTo().assertExists().performClick()
        compose.waitForIdle()
        vm.setPreset(vm.state.value.preset.copy(url = "https://ntfy.sh/topic"))
        vm.applyPreset()
        vm.save()
        val end = System.currentTimeMillis() + 5_000
        while (!vm.state.value.done && System.currentTimeMillis() < end) Thread.sleep(10)
        val saved = runBlocking { repos.destinations.observeLive().first() }
        assertEquals(listOf(TemplateKind.NTFY), saved.map { it.template })
    }

    @Test fun savingAnEmptyFormShowsErrorsAndDoesNotFinish() {
        val vm = vm(null)
        show(vm)
        compose.onNodeWithText(app.getString(R.string.destination_save)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(app.getString(R.string.destination_error_name_empty)).assertExists()
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
}
