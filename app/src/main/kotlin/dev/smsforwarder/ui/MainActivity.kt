package dev.smsforwarder.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import dev.smsforwarder.work.WorkScheduler

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                Text("SMS Forwarder")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WorkScheduler.sweepNow(applicationContext)
    }
}
