package dev.smsforwarder

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import dev.smsforwarder.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SmokeTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    // MainActivity schedules work on creation, so WorkManager must exist before the rule launches it.
    private val workManager = object : ExternalResource() {
        override fun before() {
            WorkManagerTestInitHelper.initializeTestWorkManager(
                RuntimeEnvironment.getApplication(),
                Configuration.Builder().build(),
            )
        }
    }

    @get:Rule val chain: RuleChain = RuleChain.outerRule(workManager).around(compose)

    @Test fun showsTitle() {
        compose.onNodeWithText("SMS Forwarder").assertExists()
    }
}
