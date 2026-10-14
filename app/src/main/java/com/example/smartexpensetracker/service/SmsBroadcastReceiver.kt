package com.example.smartexpensetracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.smartexpensetracker.data.TransactionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.firstOrNull()?.originatingAddress ?: "SMS"
        val fullBody = messages.joinToString(separator = "") { it.messageBody ?: "" }
        if (fullBody.isBlank()) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TransactionRepository.processIncomingAlert(
                    context = context.applicationContext,
                    rawText = "$sender: $fullBody",
                    sourcePackage = "SMS ($sender)"
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
