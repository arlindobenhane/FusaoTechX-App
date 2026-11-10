package com.fusaotech.studio

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * FusãoTechX Mz • Lê as notificações em tempo real para as mostrar no PC
 * e poder dispensá-las. Pede a permissão de "Acesso às notificações".
 */
class NotifService : NotificationListenerService() {
    companion object { @Volatile var inst: NotifService? = null }

    override fun onListenerConnected() { inst = this }
    override fun onListenerDisconnected() { if (inst === this) inst = null }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    fun lista(): List<StatusBarNotification> {
        return try { activeNotifications?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    fun dispensar(key: String) {
        try { cancelNotification(key) } catch (_: Exception) {}
    }

    fun dispensarTodas() {
        try { cancelAllNotifications() } catch (_: Exception) {}
    }
}
