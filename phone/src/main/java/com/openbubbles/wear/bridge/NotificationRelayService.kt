package com.openbubbles.wear.bridge

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationRelayService : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        NotificationCenter.attachService(this)
    }

    override fun onDestroy() {
        NotificationCenter.detachService(this)
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationCenter.attachService(this)
        NotificationCenter.rescan(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationCenter.detachService(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        NotificationCenter.onNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        NotificationCenter.onNotificationRemoved(sbn)
    }
}