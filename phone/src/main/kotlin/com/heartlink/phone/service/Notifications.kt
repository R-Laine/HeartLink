package com.heartlink.phone.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.heartlink.phone.MainActivity
import com.heartlink.phone.R
import com.heartlink.shared.Message

object Notifications {
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_HEARTBEATS = "heartbeats"
    const val CHANNEL_MOMENTS = "moments"
    /** Anything arriving during quiet hours: shown, but no sound or vibration. */
    const val CHANNEL_QUIET = "quiet"

    const val ID_STATUS = 1
    private const val ID_HEARTBEAT = 2
    private const val ID_MOMENT = 3

    fun createChannels(ctx: Context) {
        val manager = ctx.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_STATUS, "Partner status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Always-on notification that keeps HeartLink connected"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Messages from your partner"
                },
                NotificationChannel(CHANNEL_HEARTBEATS, "Heartbeats", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When your partner sends you their heartbeat"
                    // We vibrate the heartbeat rhythm ourselves.
                    enableVibration(false)
                    setSound(null, null)
                },
                NotificationChannel(CHANNEL_MOMENTS, "Wake and sleep", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When your partner wakes up or falls asleep"
                },
                NotificationChannel(CHANNEL_QUIET, "During quiet hours", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Messages and heartbeats that arrive during your quiet hours"
                    enableVibration(false)
                    setSound(null, null)
                },
            ),
        )
    }

    fun openAppIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun showMessage(ctx: Context, message: Message, quiet: Boolean = false) {
        val notification = NotificationCompat.Builder(ctx, if (quiet) CHANNEL_QUIET else CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(message.fromName ?: "Your partner")
            .setContentText(message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openAppIntent(ctx))
            .setAutoCancel(true)
            .setWhen(message.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis())
            .build()
        notify(ctx, message.id.hashCode(), notification)
    }

    /** [localOnly] when the watch is vibrating it, so the watch doesn't also get a copy. */
    /** [sentAt] shows when it was sent, for one that arrived too late to play. */
    fun showHeartbeat(ctx: Context, fromName: String?, bpm: Int, localOnly: Boolean, quiet: Boolean = false, sentAt: Long? = null) {
        val notification = NotificationCompat.Builder(ctx, if (quiet) CHANNEL_QUIET else CHANNEL_HEARTBEATS)
            .setSmallIcon(R.drawable.ic_heart)
            .apply { if (sentAt != null) setWhen(sentAt).setShowWhen(true) }
            .setContentTitle("${fromName ?: "Your partner"} sent you their heartbeat")
            .setContentText("$bpm bpm 💓")
            .setContentIntent(openAppIntent(ctx))
            .setAutoCancel(true)
            .setLocalOnly(localOnly)
            .build()
        notify(ctx, ID_HEARTBEAT, notification)
    }

    /** Quiet hours are over: the heartbeats that arrived silently are played now. */
    fun showReplay(ctx: Context, fromName: String?, count: Int, bpm: Int, localOnly: Boolean) {
        val who = fromName ?: "Your partner"
        val title = if (count == 1) {
            "$who sent you their heartbeat during quiet hours"
        } else {
            "$who sent you $count heartbeats during quiet hours"
        }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_HEARTBEATS)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(title)
            .setContentText("Playing the latest: $bpm bpm 💓")
            .setContentIntent(openAppIntent(ctx))
            .setAutoCancel(true)
            .setLocalOnly(localOnly)
            .build()
        notify(ctx, ID_HEARTBEAT, notification)
    }

    fun showMoment(ctx: Context, text: String, quiet: Boolean) {
        val notification = NotificationCompat.Builder(ctx, if (quiet) CHANNEL_QUIET else CHANNEL_MOMENTS)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(text)
            .setContentIntent(openAppIntent(ctx))
            .setAutoCancel(true)
            .build()
        notify(ctx, ID_MOMENT, notification)
    }

    @Suppress("MissingPermission") // Checked by areNotificationsEnabled(); silently skipped otherwise.
    private fun notify(ctx: Context, id: Int, notification: android.app.Notification) {
        val manager = NotificationManagerCompat.from(ctx)
        if (manager.areNotificationsEnabled()) manager.notify(id, notification)
    }
}
