package dev.smsforwarder.ui

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import dev.smsforwarder.ui.main.formatDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class DateFormatTest {
    @get:Rule val compose = createComposeRule()

    private val millis = 1_800_000_000_000L
    private lateinit var oldZone: TimeZone
    private lateinit var oldLocale: Locale

    @Before fun pin() {
        oldZone = TimeZone.getDefault()
        oldLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        // The system language is English; only the app configuration says Spanish.
        Locale.setDefault(Locale.US)
    }

    @After fun restore() {
        TimeZone.setDefault(oldZone)
        Locale.setDefault(oldLocale)
    }

    private fun formatted(tag: String): String {
        var out = ""
        val config = Configuration().apply { setLocale(Locale.forLanguageTag(tag)) }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides config) {
                out = formatDateTime(millis)
            }
        }
        compose.waitForIdle()
        return out
    }

    private fun expected(tag: String): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.forLanguageTag(tag))
            .format(Date(millis))

    @Test fun datesFollowTheAppLanguageNotTheSystemOne() {
        val es = formatted("es")
        assertEquals(expected("es"), es)
        assertNotEquals(expected("en"), es)
    }
}
