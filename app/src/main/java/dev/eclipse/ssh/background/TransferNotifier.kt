package dev.eclipse.ssh.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eclipse.ssh.MainActivity
import dev.eclipse.ssh.R
import dev.eclipse.ssh.data.model.TransferItem
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransferNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        // Only the terminal channel is built here. The transfer channel lives in
        // [NotificationChannels], because there used to be two of them: this class registered
        // "transfer_results" for the completion notices while [NotificationChannels] registered
        // "transfer_progress" for the same subject, so a user who muted one in the system settings
        // still got the other, and the app's own transfer switch lived in a screen that could not
        // show both. One id, one place to mute it.
        runCatching {
            // A channel of its own, not the transfers one: "your command finished" is a different
            // kind of event from a transfer's result, and a user who mutes transfers should not lose
            // terminal notifications with them (or vice versa).
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_TERMINAL,
                    "Terminal commands",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    fun notifyComplete(item: TransferItem) {
        notify(item, "Transfer complete", "${item.direction.label} finished: ${item.name}")
    }

    fun notifyFailed(item: TransferItem) {
        notify(item, "Transfer failed", "${item.direction.label} failed: ${item.name}")
    }

    /**
     * "Notify when done": the long command the user armed in a terminal's overflow menu has gone
     * quiet. One notification per arming - the caller disarms the detector when it fires - and the
     * tap intent is the app's own main activity, the same trampoline every notification here uses.
     */
    fun notifyTerminalDone(sessionKey: String, hostName: String) {
        if (!canPostNotifications()) return
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_TERMINAL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Terminal: $hostName")
            .setContentText("Command finished")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        // Keyed by session so a second arming replaces the first rather than stacking.
        runCatching { notificationManager.notify(("terminal-done:$sessionKey").hashCode() and Int.MAX_VALUE, notification) }
    }

    private fun notify(item: TransferItem, title: String, text: String) {
        // Android 13+ drops notifications from apps without POST_NOTIFICATIONS. Checking
        // first keeps lint's MissingPermission happy and avoids building a notification
        // that the system would silently discard.
        if (!canPostNotifications()) return
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.TRANSFERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        runCatching { notificationManager.notify(item.id.hashCode() and Int.MAX_VALUE, notification) }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        /**
         * Terminal notifications only — see the [init] block for why the transfer channel is not
         * declared here. The former `CHANNEL_TRANSFERS = "transfer_results"` id is gone with it; the
         * surviving one is [NotificationChannels.TRANSFERS].
         */
        const val CHANNEL_TERMINAL = "terminal_events"
    }
}
