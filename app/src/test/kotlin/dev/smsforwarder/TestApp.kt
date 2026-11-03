package dev.smsforwarder

/** Robolectric application: no background legacy import, so tests control when (and whether) it runs. */
class TestApp : SmsForwarderApp() {
    override fun startLegacyImport() = Unit
}
