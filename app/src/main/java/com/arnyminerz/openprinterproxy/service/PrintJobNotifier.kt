package com.arnyminerz.openprinterproxy.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.arnyminerz.openprinterproxy.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shows a notification for each print job while it's being prepared and sent, and its result afterwards.
 * Must be used on the main thread.
 */
class PrintJobNotifier(private val context: Context) {
    companion object {
        private const val CHANNEL_ID = "print_jobs"

        /** Android drops notification updates posted faster than a few per second. */
        private const val MIN_UPDATE_INTERVAL_MS = 500L

        /** How long the "printed" notification stays before dismissing itself. */
        private const val COMPLETED_TIMEOUT_MS = 10_000L

        /** Extra of [cancelAction] broadcasts: the id of the notification whose job to cancel. */
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        fun cancelAction(context: Context) = "${context.packageName}.CANCEL_PRINT_JOB"

        private val nextNotificationId = AtomicInteger(1)

        /** A notification id for a new job, unique across the local print service and network printing. */
        fun newNotificationId(): Int = nextNotificationId.getAndIncrement()
    }

    private val manager = NotificationManagerCompat.from(context)

    // Notification id -> uptime of its last progress update
    private val lastUpdate = mutableMapOf<Int, Long>()

    init {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Print jobs")
                .setDescription("Progress of documents being printed")
                .build()
        )
    }

    /**
     * Shows or updates the progress of a job. Updates closer than [MIN_UPDATE_INTERVAL_MS] to the previous one are
     * skipped, unless [force] is set.
     * @param progress From 0 to 1. The first half is preparing the pages, the second sending them to the printer.
     */
    fun showProgress(
        notificationId: Int,
        jobName: String,
        printerName: String,
        progress: Float,
        force: Boolean = false,
    ) {
        val now = SystemClock.uptimeMillis()
        val last = lastUpdate[notificationId]
        if (!force && last != null && now - last < MIN_UPDATE_INTERVAL_MS) return
        lastUpdate[notificationId] = now

        val stage = if (progress < 0.5f) "Preparing pages" else "Sending to printer"
        notify(
            notificationId,
            builder(jobName)
                .setContentTitle("Printing $jobName")
                .setContentText("$printerName · $stage")
                .setProgress(100, (progress * 100).toInt(), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, "Cancel", cancelIntent(notificationId))
                .build()
        )
    }

    fun showCompleted(notificationId: Int, jobName: String, printerName: String) {
        lastUpdate.remove(notificationId)
        notify(
            notificationId,
            builder(jobName)
                .setContentTitle("Printed $jobName")
                .setContentText(printerName)
                .setAutoCancel(true)
                .setTimeoutAfter(COMPLETED_TIMEOUT_MS)
                .build()
        )
    }

    fun showFailed(notificationId: Int, jobName: String, printerName: String, message: String) {
        lastUpdate.remove(notificationId)
        notify(
            notificationId,
            builder(jobName)
                .setContentTitle("Couldn't print $jobName")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$printerName: $message"))
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .build()
        )
    }

    fun dismiss(notificationId: Int) {
        lastUpdate.remove(notificationId)
        manager.cancel(notificationId)
    }

    private fun builder(jobName: String) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_print)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setTicker(jobName)

    private fun cancelIntent(notificationId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            notificationId,
            Intent(cancelAction(context))
                .setPackage(context.packageName)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun notify(notificationId: Int, notification: Notification) {
        // Without the permission (API 33+) notifications are silently dropped
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        manager.notify(notificationId, notification)
    }
}
