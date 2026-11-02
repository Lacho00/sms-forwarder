package dev.smsforwarder.sms

import android.content.Context
import android.telephony.SubscriptionManager

object SimSlots {
    /** 1-based SIM slot of [subId], or null when unknown or unreadable. Never throws. */
    fun slotOf(context: Context, subId: Int?): Int? {
        if (subId == null || subId < 0) return null
        return try {
            val index = SubscriptionManager.getSlotIndex(subId)
            if (index >= 0) index + 1 else null
        } catch (e: Exception) {
            null
        }
    }
}
