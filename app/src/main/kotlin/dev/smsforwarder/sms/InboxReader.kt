package dev.smsforwarder.sms

import android.content.Context
import android.provider.Telephony

data class InboxRow(
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long,
    val subId: Int?,
)

/** Reads the phone's SMS inbox. A null result means "could not read", never "empty". */
interface InboxReader {
    fun readSince(fromDate: Long): List<InboxRow>?

    /** Whether the whole inbox, with no date filter, has at least one row. Null if unreadable. */
    fun hasAnyRow(): Boolean?
}

class ContentInboxReader(context: Context) : InboxReader {
    private val resolver = context.applicationContext.contentResolver

    override fun readSince(fromDate: Long): List<InboxRow>? = try {
        resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            PROJECTION,
            "date >= ?",
            arrayOf(fromDate.toString()),
            "date ASC",
        )?.use { c ->
            val rows = mutableListOf<InboxRow>()
            while (c.moveToNext()) {
                rows += InboxRow(
                    address = c.getString(0).orEmpty(),
                    body = c.getString(1).orEmpty(),
                    date = c.getLong(2),
                    dateSent = c.getLong(3),
                    subId = c.getInt(4).takeIf { it >= 0 },
                )
            }
            rows
        }
    } catch (e: SecurityException) {
        null
    }

    override fun hasAnyRow(): Boolean? = try {
        resolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id"), null, null, "date DESC LIMIT 1")
            ?.use { it.moveToFirst() }
    } catch (e: SecurityException) {
        null
    }

    private companion object {
        val PROJECTION = arrayOf("address", "body", "date", "date_sent", "sub_id")
    }
}
