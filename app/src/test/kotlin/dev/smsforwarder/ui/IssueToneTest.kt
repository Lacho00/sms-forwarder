package dev.smsforwarder.ui

import dev.smsforwarder.health.HealthIssue
import dev.smsforwarder.ui.main.bannerTone
import dev.smsforwarder.ui.main.issueTone
import dev.smsforwarder.ui.theme.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IssueToneTest {
    private val blocking = setOf(
        HealthIssue.SMS_PERMISSION, HealthIssue.TOKEN_REJECTED, HealthIssue.NOT_CONFIGURED,
        HealthIssue.NO_RULES, HealthIssue.MIUI_SMS_BLOCKED, HealthIssue.MIGRATION_FAILED,
    )

    @Test fun everyIssueHasItsTone() {
        HealthIssue.entries.forEach {
            assertEquals(it.name, if (it in blocking) StatusTone.ERROR else StatusTone.PENDING, issueTone(it))
        }
        assertEquals(11, HealthIssue.entries.size)
    }

    @Test fun bannerToneIsErrorIfAnyBlocks() {
        assertEquals(StatusTone.ERROR, bannerTone(setOf(HealthIssue.AUTOSTART_OFF, HealthIssue.TOKEN_REJECTED)))
        assertEquals(StatusTone.PENDING, bannerTone(setOf(HealthIssue.BATTERY_OPTIMIZED)))
        assertNull(bannerTone(emptySet()))
    }
}
