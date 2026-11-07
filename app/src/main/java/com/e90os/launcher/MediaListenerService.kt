package com.e90os.launcher

import android.service.notification.NotificationListenerService

/**
 * Empty listener. Android only lets an app read other apps' media sessions
 * (track title, play state, transport controls) when this service is enabled
 * by the user in Settings > Notification access.
 */
class MediaListenerService : NotificationListenerService()
