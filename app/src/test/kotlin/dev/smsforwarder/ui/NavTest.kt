package dev.smsforwarder.ui

import dev.smsforwarder.health.HealthIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavTest {
    @Test fun stackForTabPinsUnderMain() {
        assertEquals("main", stackForTab(Routes.MAIN))
        assertEquals("main|destinations", stackForTab(Routes.DESTINATIONS))
        assertEquals("main|rules", stackForTab(Routes.RULES))
        assertEquals("main|settings", stackForTab(Routes.SETTINGS))
    }

    @Test fun destinationRoutesRoundTrip() {
        assertEquals(DestinationTarget.New, Routes.destinationTarget(Routes.destination(null)))
        assertEquals(DestinationTarget.Edit(7), Routes.destinationTarget(Routes.destination(7)))
        assertNull(Routes.destinationTarget("detail/3"))
        assertNull(Routes.destinationTarget("destination/abc"))
    }

    @Test fun issueTargets() {
        assertEquals(Routes.DESTINATIONS, issueTarget(HealthIssue.TOKEN_REJECTED))
        assertEquals(Routes.DESTINATIONS, issueTarget(HealthIssue.NOT_CONFIGURED))
        // Until the Rules tab exists (task 8) this still opens Settings.
        assertEquals(Routes.SETTINGS, issueTarget(HealthIssue.NO_RULES))
        assertNull(issueTarget(HealthIssue.SMS_PERMISSION))
    }
}
