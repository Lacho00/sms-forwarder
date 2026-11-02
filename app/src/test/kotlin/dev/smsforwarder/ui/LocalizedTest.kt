package dev.smsforwarder.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import dev.smsforwarder.R
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.core.Language
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.notify.Notifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import android.app.Notification
import android.app.NotificationManager
import org.robolectric.Shadows.shadowOf
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class LocalizedTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val workManager = object : ExternalResource() {
        override fun before() {
            WorkManagerTestInitHelper.initializeTestWorkManager(
                RuntimeEnvironment.getApplication(),
                Configuration.Builder().build(),
            )
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(workManager).around(compose)

    @Test fun wrapChangesTextPerLanguage() {
        val en = Localized.wrap(context, "en").getString(R.string.about_title)
        val es = Localized.wrap(context, "es").getString(R.string.about_title)
        assertNotEquals(en, es)
    }

    @Test fun wrapWithNullReturnsSameContext() {
        assertSame(context, Localized.wrap(context, null))
    }

    @Test fun tagRoundTripsAndClears() {
        assertEquals(null, Localized.savedTag(context))
        Localized.saveTag(context, "es")
        assertEquals("es", Localized.savedTag(context))
        Localized.saveTag(context, null)
        assertEquals(null, Localized.savedTag(context))
    }

    @Test fun setLanguageMirrorsTagSynchronously() = runBlocking {
        val settings = (context as SmsForwarderApp).graph.settings
        settings.setLanguage(Language.ES)
        assertEquals("es", Localized.savedTag(context))
        settings.setLanguage(Language.SYSTEM)
        assertEquals(null, Localized.savedTag(context))
    }

    // Review Focus 5: changing language with the app open keeps the navigation state.
    @Test fun recreateAfterLanguageChangeKeepsNavigationStack() {
        val graph = (context as SmsForwarderApp).graph
        runBlocking { graph.settings.setOnboardingDone() }
        // The test runs in English until the language is switched below.
        compose.onNodeWithText(context.getString(R.string.tab_settings)).performClick()
        compose.onNodeWithTag("field_url").assertExists()

        runBlocking { graph.settings.setLanguage(Language.ES) }
        compose.activityRule.scenario.recreate()

        compose.onNodeWithTag("field_url").assertExists()
        compose.onNodeWithText(Localized.wrap(context, "es").getString(R.string.tab_home)).assertExists()
        compose.activity.let { activity ->
            assertEquals(
                Localized.wrap(context, "es").getString(R.string.about_title),
                activity.getString(R.string.about_title),
            )
        }
    }

    @Test fun stuckNotificationUsesChosenLanguage() {
        Localized.saveTag(context, "es")
        val item = SmsEntity(
            id = 7,
            localKey = "key-7",
            sender = "OPERADOR",
            senderNormalized = "OPERADOR",
            body = "cuerpo de prueba",
            receivedAt = 1_800_000_000_000L,
            phoneDate = null,
            subId = null,
            origin = SmsOrigin.SWEEP,
            status = SmsStatus.QUEUED,
            firstSeenAt = 1_800_000_000_000L,
        )
        Notifier(context, ZoneOffset.UTC).stuck(item)
        val manager = context.getSystemService(NotificationManager::class.java)
        val n = shadowOf(manager).getNotification(Notifier.STUCK_TAG, 7)
        val expected = Localized.wrap(context, "es").getString(R.string.notif_stuck_title)
        assertEquals(expected, n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNotEquals(context.getString(R.string.notif_stuck_title), expected)
    }
}
