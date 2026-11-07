package com.e90os.launcher

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Android only lets an app read other apps' media sessions (track title, play state,
 * transport controls) when this listener is enabled in Settings > Notification access.
 * It also picks up the navigator's guidance notification for the route card.
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        runCatching { activeNotifications?.forEach { capture(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = capture(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (RouteState.info?.pkg == sbn.packageName) RouteState.info = null
    }

    private fun capture(sbn: StatusBarNotification) {
        if (sbn.packageName !in RouteState.navPackages) return
        val e = sbn.notification?.extras ?: return
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = listOfNotNull(
            e.getCharSequence(Notification.EXTRA_TEXT),
            e.getCharSequence(Notification.EXTRA_SUB_TEXT),
            e.getCharSequence(Notification.EXTRA_BIG_TEXT),
            e.getCharSequence(Notification.EXTRA_INFO_TEXT),
        ).joinToString("  ")
        if (title.isBlank() && text.isBlank()) return
        RouteState.info = RouteInfo(sbn.packageName, title, text, System.currentTimeMillis())
    }
}
