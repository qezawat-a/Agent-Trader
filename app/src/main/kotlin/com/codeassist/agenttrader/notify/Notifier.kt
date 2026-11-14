package com.codeassist.agenttrader.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Status-bar notifications for autonomous trade events. One channel
 * ("trade_events"); nothing fires when the user has notifications off or
 * denied POST_NOTIFICATIONS (Android 13+ asks at first launch and from the
 * Control screen toggle). Tapping the notification opens the app.
 */
object Notifier {
    const val CHANNEL_ID = "trade_events"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "Trade events", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = "Positions, SL/TP moves, engine actions and periodic reports"
        mgr.createNotificationChannel(ch)
    }

    /** Whether this app may post notifications right now. */
    fun allowed(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < 33) NotificationManagerCompat.from(context).areNotificationsEnabled()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Posts [text]; silently does nothing when not allowed, blank or the OS rejects it. */
    fun post(context: Context, title: String, text: String) {
        if (text.isBlank() || !allowed(context)) return
        try {
            ensureChannel(context)
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val intent = PendingIntent.getActivity(
                context, 0,
                open?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            NotificationManagerCompat.from(context).notify(
                text.hashCode(),
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(context.applicationInfo.icon)
                    .setContentTitle(title)
                    .setContentText(text.take(180))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(intent)
                    .build(),
            )
        } catch (_: Exception) {
        }
    }
}

/**
 * True for lines worth waking the user for: opens, failed opens, live errors,
 * fills/partials, closes, SL moves and periodic reports. WS balance pushes,
 * connection noise and plain command answers stay silent.
 */
private val NOTIFIABLE_PREFIXES = listOf(
    "live open",   // filled, failed and rejected orders
    "live error",
    "open",        // paper opens ("OPEN …", "Opened …")
    "partial",
    "close",       // paper + live closes and close failures
    "sl moved",
    "breakeven",
    "periodic report",
    "ws order",
    "ws tpsl",
    "ws position",
)

internal fun notifiable(line: String): Boolean {
    val l = line.trim().lowercase()
    return l.isNotEmpty() && NOTIFIABLE_PREFIXES.any { l.startsWith(it) }
}

/** Short notification title matched to the event kind. */
internal fun notificationTitle(line: String): String = when {
    line.startsWith("Periodic report") -> "Report"
    line.startsWith("WS order") -> "Order update"
    line.startsWith("WS tpsl") -> "TP/SL update"
    line.startsWith("WS position") -> "Position update"
    else -> "Agent Trader"
}
