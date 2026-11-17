package com.fusaotech.studio

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * FusãoTechX Mz • Lê as notificações em tempo real para as mostrar no PC
 * e poder dispensá-las. Pede a permissão de "Acesso às notificações".
 */
class NotifService : NotificationListenerService() {
    companion object {
        @Volatile var inst: NotifService? = null
        // "Relógio" das notificações: sobe a cada nova/removida; o PC espera por mudanças em /events.
        @Volatile var seq: Long = 0
        private val trava = Object()
        fun esperar(desde: Long, timeoutMs: Long) { synchronized(trava) { if (seq <= desde) try { trava.wait(timeoutMs) } catch (_: Exception) {} } }
        private fun bump() { synchronized(trava) { seq++; trava.notifyAll() } }
    }

    override fun onListenerConnected() { inst = this; bump() }
    override fun onListenerDisconnected() { if (inst === this) inst = null }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { bump() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { bump() }

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
