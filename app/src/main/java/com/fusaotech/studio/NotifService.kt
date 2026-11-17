package com.fusaotech.studio

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * FusãoTechX Mz • Lê as notificações em tempo real.
 *  - Mostra e dispensa notificações no PC.
 *  - GUARDA as mensagens das apps (WhatsApp, Messenger, Telegram, SMS…) para o PC as poder LER
 *    sem precisar do ecrã ao vivo. As mensagens vêm das notificações, por isso não é preciso
 *    abrir a app no telefone. A leitura acumula a partir do momento em que isto é instalado.
 */
class NotifService : NotificationListenerService() {
    companion object {
        @Volatile var inst: NotifService? = null
        @Volatile var seq: Long = 0
        private val trava = Object()
        fun esperar(desde: Long, timeoutMs: Long) { synchronized(trava) { if (seq <= desde) try { trava.wait(timeoutMs) } catch (_: Exception) {} } }
        private fun bump() { synchronized(trava) { seq++; trava.notifyAll() } }

        private const val MAX = 800
        private val msgs = java.util.ArrayDeque<JSONObject>()
        private var carregado = false

        // Apps de mensagens mais comuns (mostradas primeiro).
        val APPS_MSG = mapOf(
            "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram", "com.facebook.orca" to "Messenger",
            "com.instagram.android" to "Instagram", "com.facebook.katana" to "Facebook",
            "com.google.android.apps.messaging" to "Mensagens", "com.android.mms" to "SMS",
            "com.imo.android.imoim" to "imo", "com.viber.voip" to "Viber", "com.snapchat.android" to "Snapchat",
            "com.linkedin.android" to "LinkedIn", "com.twitter.android" to "X", "com.discord" to "Discord"
        )

        private fun ficheiro(c: Context) = File(c.filesDir, "appmsgs.json")

        private fun carregar(c: Context) {
            if (carregado) return; carregado = true
            try {
                val arr = JSONArray(ficheiro(c).readText())
                for (i in 0 until arr.length()) msgs.add(arr.getJSONObject(i))
            } catch (_: Exception) {}
        }

        private fun guardar(c: Context) {
            try {
                val arr = JSONArray(); for (o in msgs) arr.put(o)
                ficheiro(c).writeText(arr.toString())
            } catch (_: Exception) {}
        }

        /** Mensagens guardadas (todas, ou de uma app). Mais recentes primeiro. */
        fun mensagens(c: Context, pkg: String?): JSONArray {
            carregar(c)
            val arr = JSONArray()
            synchronized(msgs) {
                val it = msgs.descendingIterator()
                while (it.hasNext()) { val o = it.next(); if (pkg == null || o.optString("pkg") == pkg) arr.put(o) }
            }
            return arr
        }

        /** Resumo por app: pacote, nome, nº de mensagens e a última. */
        fun apps(c: Context): JSONArray {
            carregar(c)
            val cont = HashMap<String, IntArray>(); val nomes = HashMap<String, String>(); val ult = HashMap<String, Long>()
            synchronized(msgs) {
                for (o in msgs) {
                    val p = o.optString("pkg"); cont.getOrPut(p) { IntArray(1) }[0]++
                    nomes[p] = o.optString("app"); ult[p] = maxOf(ult[p] ?: 0, o.optLong("data"))
                }
            }
            val arr = JSONArray()
            for ((p, n) in cont.entries.sortedByDescending { ult[it.key] ?: 0 }) {
                arr.put(JSONObject().put("pkg", p).put("app", nomes[p] ?: p).put("n", n[0]).put("ultima", ult[p] ?: 0)
                    .put("msg", APPS_MSG.containsKey(p)))
            }
            return arr
        }

        private fun HashMap<String, IntArray>.getOrPut(k: String, d: () -> IntArray): IntArray { return this[k] ?: d().also { this[k] = it } }
    }

    override fun onListenerConnected() { inst = this; carregar(this); bump() }
    override fun onListenerDisconnected() { if (inst === this) inst = null }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // WhatsApp/WhatsApp Business: processamento dedicado com MessagingStyle
        if (sbn != null && sbn.packageName in WhatsAppStore.PACOTES) {
            WhatsAppStore.processar(this, sbn)
        }
        guardarMsg(sbn)
        bump()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && sbn.packageName in WhatsAppStore.PACOTES) {
            WhatsAppStore.notificacaoRemovida(sbn.key)
        }
        bump()
    }

    private fun guardarMsg(sbn: StatusBarNotification?) {
        try {
            sbn ?: return
            val pkg = sbn.packageName ?: return
            if (pkg == packageName) return
            val ex = sbn.notification.extras ?: return
            val titulo = ex.getCharSequence("android.title")?.toString()?.trim() ?: ""
            var texto = ex.getCharSequence("android.text")?.toString()?.trim() ?: ""
            if (texto.isEmpty()) texto = ex.getCharSequence("android.bigText")?.toString()?.trim() ?: ""
            if (texto.isEmpty() && titulo.isEmpty()) return
            // Ignora notificações de sistema/progresso sem interesse de leitura.
            if (sbn.isOngoing && !NotifService.APPS_MSG.containsKey(pkg)) return
            if (texto.matches(Regex("^\\d+ (mensagens|novas mensagens|messages).*$", RegexOption.IGNORE_CASE)) && titulo.isEmpty()) return
            val o = JSONObject()
                .put("pkg", pkg).put("app", appLabel(pkg)).put("de", titulo).put("texto", texto)
                .put("data", if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis())
            synchronized(msgs) {
                // Evita duplicados imediatos (a mesma notificação atualiza-se várias vezes).
                val ultimo = msgs.peekLast()
                if (ultimo != null && ultimo.optString("pkg") == pkg && ultimo.optString("de") == titulo && ultimo.optString("texto") == texto) return
                msgs.add(o); while (msgs.size > MAX) msgs.pollFirst()
            }
            guardar(this)
        } catch (_: Exception) {}
    }

    private fun appLabel(pkg: String): String {
        NotifService.APPS_MSG[pkg]?.let { return it }
        return try { val pm = packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg.substringAfterLast('.') }
    }

    fun lista(): List<StatusBarNotification> = try { activeNotifications?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
    fun dispensar(key: String) { try { cancelNotification(key) } catch (_: Exception) {} }
    fun dispensarTodas() { try { cancelAllNotifications() } catch (_: Exception) {} }
}
