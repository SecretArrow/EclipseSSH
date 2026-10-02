package dev.eclipse.ssh.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.eclipse.ssh.R

/**
 * Creates the app's notification channels.
 *
 * These used to be created only in [EclipseSessionService.onCreate], which made every other
 * notification depend on the service having run at least once: posting to a channel that does not
 * exist yet is silently dropped on API 26+, so anything that needed to warn the user *because* the
 * service could not start had nowhere to go. Channel creation is idempotent and cheap, so it now
 * happens at application startup and callers can rely on the channels existing.
 *
 * No SDK_INT guard: channels landed in API 26 and minSdk is 28.
 */
internal object NotificationChannels {
    const val SESSIONS = "active_sessions"
    const val TRANSFERS = "transfer_progress"
    const val ALERTS = "alerts"
    const val LINUX = "linux_userspace"

    /**
     * The channel id a previous build used for transfer results, before [TRANSFERS] was the only one.
     *
     * Kept as a constant rather than inlined into the deletion because it is a *historical* id: it
     * belongs to no code that posts anymore, so the only thing it can be named for is removal, and
     * naming it is what keeps a future reader from "fixing" the orphaned channel by pointing new
     * notifications back at it.
     */
    private const val LEGACY_TRANSFERS = "transfer_results"

    /** Ongoing foreground-service notification. */
    const val ID_SERVICE = 4101

    /**
     * "Background sessions paused" — the Android 15 foreground-service time limit was hit.
     *
     * Per service, not shared: the session service and the Linux userspace service run on the
     * one `dataSync` budget, so when it runs out they both time out, and a single shared id
     * made the second alert silently overwrite the first - the user learned about one of the
     * two services that had just stopped protecting their work.
     */
    const val ID_TIMEOUT_SESSIONS = 4102

    /** "Ubuntu runs only while the app is open" — the userspace service hit the same limit. */
    const val ID_TIMEOUT_LINUX = 4106

    /** "Reconnect your sessions" — the platform refused a background service start. */
    const val ID_RESTORE = 4103

    /** The local Ubuntu environment's ongoing "Running" notification. */
    const val ID_LINUX = 4104

    /** "Ubuntu runs only while the app is open" — the platform refused the promotion to foreground. */
    const val ID_LINUX_PROMOTION = 4105

    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        // Wrapped because this runs during Application.onCreate, where an unexpected failure would
        // take the whole process down before any UI exists to report it.
        runCatching {
            manager.createNotificationChannels(
                listOf(
                    NotificationChannel(
                        SESSIONS,
                        context.getString(R.string.channel_sessions),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = context.getString(R.string.channel_sessions_desc) },
                    NotificationChannel(
                        TRANSFERS,
                        context.getString(R.string.channel_transfers),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = context.getString(R.string.channel_transfers_desc) },
                    NotificationChannel(
                        ALERTS,
                        context.getString(R.string.channel_alerts),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { description = context.getString(R.string.channel_alerts_desc) },
                    NotificationChannel(
                        LINUX,
                        context.getString(R.string.channel_linux),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = context.getString(R.string.channel_linux_desc) },
                ),
            )
            // An earlier build registered a second transfer channel, "transfer_results", beside the
            // one above; everything both were for now posts to [TRANSFERS]. Creating the channels is
            // not enough to be rid of it — a channel is the user's, and the system keeps it until it
            // is deleted, so it would sit in the app's notification settings forever with nothing
            // ever posting to it, which is exactly the "which one do I mute?" question the merge was
            // meant to answer. Deleting also drops any mute the user had applied to the old id, but
            // it was a channel they could only ever have seen under this app's name for the same
            // subject as the survivor, so the preference it stood for is preserved by [TRANSFERS]
            // rather than lost.
            //
            // No API guard: [deleteNotificationChannel] is API 26 and minSdk is 28. Its own
            // runCatching, so a failure to clean up an obsolete channel cannot stop the four
            // channels above from having been created.
            runCatching { manager.deleteNotificationChannel(LEGACY_TRANSFERS) }
        }
    }
}

/**
 * Posts a one-off alert on [NotificationChannels.ALERTS].
 *
 * Shared by the session service and its workers, which both need to tell the user about something
 * the platform stopped the app from doing quietly in the background — a notification is the only
 * channel either of them has when no UI is on screen. [contentIntent] is what a tap does.
 */
internal fun postAlert(
    context: Context,
    id: Int,
    title: String,
    body: String,
    contentIntent: PendingIntent?,
) {
    // Android 13+ silently discards notifications from apps without POST_NOTIFICATIONS, so there is
    // nothing to gain by building one; the check is also what keeps lint's MissingPermission quiet.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    // Both alert bodies explain a platform restriction and are longer than one collapsed line.
    val notification = NotificationCompat.Builder(context, NotificationChannels.ALERTS)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .build()
    runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
}
