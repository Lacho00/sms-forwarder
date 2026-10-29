package dev.smsforwarder.diag

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File

/** Debug-only screen: receiver captures next to the inbox rows, coloured by how they pair up. */
class DiagActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DiagScreen() } }
    }
}

private val permissions = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)

@Composable
private fun DiagScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var granted by remember { mutableStateOf(hasPermissions(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermissions(context)
        refresh++
    }

    val captures = remember(refresh) { DiagStore(File(context.filesDir, DiagStore.FILE_NAME)).all() }
    val inbox = remember(refresh, granted) { if (granted) runCatching { readInbox(context) }.getOrElse { emptyList() } else emptyList() }

    LazyColumn(Modifier.padding(16.dp)) {
        item {
            Column {
                if (!granted) Button(onClick = { launcher.launch(permissions) }) { Text("Conceder permisos SMS") }
                Button(onClick = { refresh++ }) { Text("Actualizar") }
                Text("Capturas del receptor (${captures.size})", style = MaterialTheme.typography.titleMedium)
            }
        }
        items(captures) { c ->
            Text(
                "${c.sender} · pdu=${c.pduTimestamp} · hash=${c.bodyHash} · len=${c.bodyLength} · partes=${c.parts} · sub=${c.subId}",
                Modifier.padding(vertical = 4.dp),
            )
        }
        item { Text("Bandeja (${inbox.size})", style = MaterialTheme.typography.titleMedium) }
        items(inbox) { row ->
            val best = captures.map { pairKind(it, row) }.minOrNull() ?: PairKind.NONE
            val color = when (best) {
                PairKind.EXACT -> Color(0xFFB7E4C7)
                PairKind.FALLBACK -> Color(0xFFFFE08A)
                PairKind.NONE -> Color.Transparent
            }
            Text(
                "${row.address} · date=${row.date} · date_sent=${row.dateSent} · sub=${row.subId} · hash=${row.bodyHash} · len=${row.bodyLength}",
                Modifier.fillMaxWidth().background(color).padding(vertical = 4.dp),
            )
        }
    }
}

private fun hasPermissions(context: Context): Boolean = permissions.all {
    context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun readInbox(context: Context): List<DiagInboxRow> {
    val projection = arrayOf("address", "date", "date_sent", "sub_id", "body")
    val rows = mutableListOf<DiagInboxRow>()
    context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, projection, null, null, "date DESC LIMIT ${DiagStore.MAX_ENTRIES}")?.use { c ->
        while (c.moveToNext() && rows.size < DiagStore.MAX_ENTRIES) {
            val body = c.getString(4).orEmpty()
            rows += DiagInboxRow(
                address = c.getString(0).orEmpty(),
                date = c.getLong(1),
                dateSent = c.getLong(2),
                subId = c.getInt(3),
                bodyHash = shortHash(body),
                bodyLength = body.length,
            )
        }
    }
    return rows
}
