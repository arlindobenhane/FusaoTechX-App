package com.fusaotech.studio

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * FusãoTechX Mz • Armazenamento dedicado de mensagens WhatsApp/WhatsApp Business.
 *
 * Funcionalidades:
 *  - Extração via MessagingStyle nativo (API 24+): remetente por mensagem, timestamps precisos.
 *  - Fallback para título/texto quando MessagingStyle não está disponível.
 *  - Deduplicação por (chave de conversa + timestamp de mensagem).
 *  - Persistência em JSON (wa_msgs.json) — sobrevive a reinicializações.
 *  - Resposta direta via RemoteInput sem abrir o WhatsApp.
 *  - Máximo de MAX_TOTAL mensagens no histórico geral; MAX_POR_CONVERSA por conversa.
 */
object WhatsAppStore {

    val PACOTES = setOf("com.whatsapp", "com.whatsapp.w4b")

    private const val MAX_TOTAL = 1000
    private const val MAX_POR_CONVERSA = 200
    private const val FICHEIRO = "wa_msgs.json"

    // chave-da-conversa → lista de mensagens ordenada por timestamp
    private val conversas = ConcurrentHashMap<String, ArrayDeque<WhatsAppMessage>>()

    // chave-da-conversa → última StatusBarNotification (para poder responder)
    private val sbnAtivas = ConcurrentHashMap<String, StatusBarNotification>()

    // timestamps já processados por conversa (evita duplicados quando WA reposta a notificação)
    private val tsVistos = ConcurrentHashMap<String, MutableSet<Long>>()

    @Volatile private var carregado = false
    private val travoFicheiro = Any()

    // ──────────────────────────────────────────────────────────────
    //  Entrada principal — chamada pelo NotifService
    // ──────────────────────────────────────────────────────────────

    fun processar(ctx: Context, sbn: StatusBarNotification): Boolean {
        if (sbn.packageName !in PACOTES) return false
        val n = sbn.notification ?: return false
        // Ignora o resumo do grupo de notificações Android ("5 mensagens de 3 conversas")
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false

        carregar(ctx)
        sbnAtivas[sbn.key] = sbn
        return extrairMensagens(ctx, sbn, n)
    }

    fun notificacaoRemovida(chave: String) {
        sbnAtivas.remove(chave)
        tsVistos.remove(chave)
    }

    // ──────────────────────────────────────────────────────────────
    //  Extração de mensagens
    // ──────────────────────────────────────────────────────────────

    private fun extrairMensagens(ctx: Context, sbn: StatusBarNotification, n: Notification): Boolean {
        val extras = n.extras ?: return false
        val vistos = tsVistos.getOrPut(sbn.key) { mutableSetOf() }
        var novas = false

        // ── Caminho 1: MessagingStyle (API 24+) ──
        // A API do sistema não expõe extractMessagingStyleFromNotification (só existe no AndroidX),
        // por isso lemos directamente os extras que o MessagingStyle grava na notificação.
        val msgsBrutas = try { extras.getParcelableArray(Notification.EXTRA_MESSAGES) } catch (_: Exception) { null }
        val msgs = msgsBrutas?.mapNotNull { it as? android.os.Bundle }.orEmpty()
        if (msgs.isNotEmpty()) {
            val tituloConversa = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: sbn.packageName.substringAfterLast('.')
            val isGrupo = if (android.os.Build.VERSION.SDK_INT >= 28)
                extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
            else extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null

            for (m in msgs) {
                val ts = m.getLong("time")
                if (!vistos.add(ts)) continue               // já processada
                val texto = m.getCharSequence("text")?.toString()?.trim() ?: continue
                if (texto.isEmpty()) continue
                val pessoa = if (android.os.Build.VERSION.SDK_INT >= 28)
                    (try { m.getParcelable<android.app.Person>("sender_person") } catch (_: Exception) { null })?.name?.toString()?.trim()
                else null
                val remetente = pessoa
                    ?: m.getCharSequence("sender")?.toString()?.trim()
                    ?: tituloConversa

                adicionar(ctx, WhatsAppMessage(
                    id = ts,
                    chave = sbn.key,
                    conversa = tituloConversa,
                    remetente = remetente ?: tituloConversa,
                    texto = texto,
                    isGrupo = isGrupo,
                    pacote = sbn.packageName
                ))
                novas = true
            }
            return novas
        }

        // ── Caminho 2: Fallback título/texto simples ──
        val ts = sbn.postTime
        if (!vistos.add(ts)) return false
        val titulo = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return false
        var texto = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        if (texto.isEmpty()) texto = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        if (texto.isEmpty()) return false
        // Ignora contadores como "3 mensagens não lidas"
        if (titulo.isEmpty() && texto.matches(Regex("^\\d+ .*mensagens.*", RegexOption.IGNORE_CASE))) return false

        adicionar(ctx, WhatsAppMessage(
            id = ts,
            chave = sbn.key,
            conversa = titulo,
            remetente = titulo,
            texto = texto,
            isGrupo = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            pacote = sbn.packageName
        ))
        return true
    }

    // ──────────────────────────────────────────────────────────────
    //  Armazenamento interno
    // ──────────────────────────────────────────────────────────────

    private fun adicionar(ctx: Context, msg: WhatsAppMessage) {
        val fila = conversas.getOrPut(msg.chave) { ArrayDeque() }
        synchronized(fila) {
            fila.addLast(msg)
            while (fila.size > MAX_POR_CONVERSA) fila.removeFirst()
        }
        // Limitar total global
        val total = conversas.values.sumOf { it.size }
        if (total > MAX_TOTAL) {
            // Remove a mensagem mais antiga de todas as conversas
            val maisAntiga = conversas.entries
                .filter { it.value.isNotEmpty() }
                .minByOrNull { it.value.first().id }
            maisAntiga?.let { e -> synchronized(e.value) { if (e.value.isNotEmpty()) e.value.removeFirst() } }
        }
        guardar(ctx)
    }

    // ──────────────────────────────────────────────────────────────
    //  API pública de leitura
    // ──────────────────────────────────────────────────────────────

    /** Resumo de todas as conversas, ordenado pela mensagem mais recente. */
    fun resumoConversas(): JSONArray {
        val arr = JSONArray()
        conversas.entries
            .filter { it.value.isNotEmpty() }
            .sortedByDescending { e -> e.value.lastOrNull()?.id ?: 0L }
            .forEach { (chave, fila) ->
                val ultima = fila.lastOrNull() ?: return@forEach
                arr.put(JSONObject()
                    .put("chave", chave)
                    .put("conversa", ultima.conversa)
                    .put("isGrupo", ultima.isGrupo)
                    .put("pacote", ultima.pacote)
                    .put("n", fila.size)
                    .put("ultima_msg", ultima.texto)
                    .put("ultima_ts", ultima.id)
                    .put("pode_responder", sbnAtivas.containsKey(chave) && temAcaoResposta(chave))
                )
            }
        return arr
    }

    /** Mensagens de uma conversa específica (mais antigas primeiro). */
    fun mensagensConversa(chave: String, limite: Int = 100): JSONArray {
        val arr = JSONArray()
        val fila = conversas[chave] ?: return arr
        synchronized(fila) {
            fila.takeLast(limite.coerceIn(1, MAX_POR_CONVERSA)).forEach { m ->
                arr.put(mensagemParaJson(m))
            }
        }
        return arr
    }

    /** Todas as mensagens de todos os pacotes WhatsApp (mais recentes primeiro, limite). */
    fun todasMensagens(limite: Int = 200): JSONArray {
        val todas = mutableListOf<WhatsAppMessage>()
        conversas.values.forEach { fila -> synchronized(fila) { todas.addAll(fila) } }
        todas.sortByDescending { it.id }
        val arr = JSONArray()
        todas.take(limite.coerceIn(1, MAX_TOTAL)).forEach { arr.put(mensagemParaJson(it)) }
        return arr
    }

    private fun mensagemParaJson(m: WhatsAppMessage) = JSONObject()
        .put("id", m.id)
        .put("chave", m.chave)
        .put("conversa", m.conversa)
        .put("remetente", m.remetente)
        .put("texto", m.texto)
        .put("isGrupo", m.isGrupo)
        .put("pacote", m.pacote)

    // ──────────────────────────────────────────────────────────────
    //  Resposta via RemoteInput
    // ──────────────────────────────────────────────────────────────

    /** Responde a uma conversa usando a ação "Responder" da notificação ativa.
     *  Retorna "ok", "sem_notif", "sem_acao" ou "falhou". */
    fun responder(ctx: Context, chave: String, resposta: String): String {
        val sbn = sbnAtivas[chave] ?: return "sem_notif"
        val acao = sbn.notification?.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }
            ?: return "sem_acao"
        return try {
            val intent = Intent()
            val bundle = Bundle()
            acao.remoteInputs.forEach { bundle.putCharSequence(it.resultKey, resposta) }
            android.app.RemoteInput.addResultsToIntent(acao.remoteInputs, intent, bundle)
            acao.actionIntent.send(ctx, 0, intent)
            "ok"
        } catch (e: PendingIntent.CanceledException) {
            "falhou"
        } catch (_: Exception) {
            "falhou"
        }
    }

    private fun temAcaoResposta(chave: String): Boolean {
        return sbnAtivas[chave]?.notification?.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true
    }

    // ──────────────────────────────────────────────────────────────
    //  Persistência
    // ──────────────────────────────────────────────────────────────

    private fun carregar(ctx: Context) {
        if (carregado) return
        synchronized(travoFicheiro) {
            if (carregado) return
            try {
                val f = ficheiro(ctx)
                if (!f.exists()) return
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val msg = WhatsAppMessage(
                        id = o.getLong("id"),
                        chave = o.getString("chave"),
                        conversa = o.optString("conversa", "?"),
                        remetente = o.optString("remetente", "?"),
                        texto = o.getString("texto"),
                        isGrupo = o.optBoolean("isGrupo", false),
                        pacote = o.optString("pacote", "com.whatsapp")
                    )
                    val fila = conversas.getOrPut(msg.chave) { ArrayDeque() }
                    fila.addLast(msg)
                }
            } catch (_: Exception) {}
            carregado = true
        }
    }

    private fun guardar(ctx: Context) {
        synchronized(travoFicheiro) {
            try {
                val arr = JSONArray()
                conversas.values.forEach { fila ->
                    synchronized(fila) { fila.forEach { m -> arr.put(mensagemParaJson(m)) } }
                }
                ficheiro(ctx).writeText(arr.toString())
            } catch (_: Exception) {}
        }
    }

    private fun ficheiro(ctx: Context) = File(ctx.filesDir, FICHEIRO)

    // Extensão interna para ConcurrentHashMap sem getOrDefault (minSdk 26 tem, mas kotlin-stdlib é mais claro)
    private fun <K, V> ConcurrentHashMap<K, V>.getOrPut(key: K, default: () -> V): V =
        this[key] ?: default().also { this[key] = it }
}
