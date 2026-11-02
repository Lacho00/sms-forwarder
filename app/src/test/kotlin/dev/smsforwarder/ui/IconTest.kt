package dev.smsforwarder.ui

import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class IconTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun launcherIconIsDeclared() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertNotEquals(0, info.icon)
        assertNotEquals(0, info.roundIcon)
    }
}
