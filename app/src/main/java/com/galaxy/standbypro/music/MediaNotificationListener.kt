package com.galaxy.standbypro.music

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * 다른 음악 앱(Spotify, YouTube Music, Samsung Music, Apple Music 등)의 MediaSession에
 * 접근하기 위한 공식 경로. 사용자가 '알림 접근'을 허용해야 MediaSessionManager.getActiveSessions()를 쓸 수 있다.
 * 알림 내용 자체는 읽거나 저장하지 않는다.
 */
class MediaNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    companion object {
        fun component(context: Context) = ComponentName(context, MediaNotificationListener::class.java)

        fun isGranted(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
