package com.example.smartexpensetracker.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.smartexpensetracker.data.AppDatabase
import com.example.smartexpensetracker.data.AppPreferences
import com.example.smartexpensetracker.ui.MainActivity
import java.util.Calendar
import java.util.concurrent.TimeUnit

class UncategorizedReminderWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val dao = AppDatabase.getInstance(appContext).transactionDao()
        val uncategorizedList = dao.getUncategorizedTransactionsSnapshot()

        if (uncategorizedList.isNotEmpty()) {
            val totalAmount = uncategorizedList.sumOf { it.amount }
            sendReminderNotification(uncategorizedList.size, totalAmount)
        }

        return Result.success()
    }

    private fun sendReminderNotification(count: Int, totalAmount: Double) {
        val channelId = "uncategorized_daily_reminder"
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Daily Uncategorized Expense Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminds you daily to categorize ambiguous transactions"
            }
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_UNCATEGORIZED_TAB", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            appContext,
            1001,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val formattedTotal = String.format("₹%,.2f", totalAmount)
        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$count Transaction(s) Need Your Category")
            .setContentText("AI couldn't auto-categorize $count spend(s) totaling $formattedTotal. Tap to assign manually.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "AI couldn't automatically determine the category for $count transaction(s) totaling $formattedTotal today. Tap here to quickly select their categories."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(2002, notification)
    }

    companion object {
        private const val WORK_NAME = "DailyUncategorizedTransactionReminder"

        fun scheduleDailyReminder(context: Context) {
            val (targetHour, targetMinute) = AppPreferences.getReminderTime(context)
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, targetHour)
                set(Calendar.MINUTE, targetMinute)
                set(Calendar.SECOND, 0)
                if (before(now)) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val initialDelayMillis = target.timeInMillis - now.timeInMillis

            val workRequest = PeriodicWorkRequestBuilder<UncategorizedReminderWorker>(
                24, TimeUnit.HOURS
            )
                .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }

        fun triggerImmediateCheckNow(context: Context) {
            val immediateRequest = OneTimeWorkRequestBuilder<UncategorizedReminderWorker>().build()
            WorkManager.getInstance(context).enqueue(immediateRequest)
        }
    }
}
