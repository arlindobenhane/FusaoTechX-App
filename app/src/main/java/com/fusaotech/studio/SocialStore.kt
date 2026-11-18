package com.fusaotech.studio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * FusãoTechX Mz • Mensagens de redes sociais.
 * Alimentado por NotifService (notificações) e SocialCapture (acessibilidade).
 * Deduplicação por hash de conteúdo para evitar duplicados entre as duas fontes.
 */
object SocialStore {

    val PACOTES = setOf(
        "com.facebook.orca",
        "com.instagram.android",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.snapchat.android",
        "com.discord",
        "com.twitter.android",
        "com.viber.voip",
        "com.facebook.katana",
        // Expandido
        "com.whatsapp.w4b",
        "com.skype.raider",
        "com.microsoft.teams",
        "com.tencent.mm",
        "com.linkedin.android",
        "com.imo.android.imoim",
        "com.imo.android.imoimbeta",
        "com.wire",
        "org.thoughtcrime.securesms",
        "com.kakao.talk",
        "jp.naver.line.android",
        "com.zing.zalo",
        "com.truecaller",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.huawei.mms"
    )

    val NOMES = mapOf(
        "com.facebook.orca" to "Messenger",
        "com.instagram.android" to "Instagram",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok",
        "com.snapchat.android" to "Snapchat",
        "com.discord" to "Discord",
        "com.twitter.android" to "X (Twitter)",
        "com.viber.voip" to "Viber",
        "com.facebook.katana" to "Facebook",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.skype.raider" to "Skype",
        "com.microsoft.teams" to "Teams",
        "com.tencent.mm" to "WeChat",
        "com.linkedin.android" to "LinkedIn",
        "com.imo.android.imoim" to "imo",
        "com.imo.android.imoimbeta" to "imo",
        "com.wire" to "Wire",
        "org.thoughtcrime.securesms" to "Signal",
        "com.kakao.talk" to "KakaoTalk",
        "jp.naver.line.android" to "Line",
        "com.zing.zalo" to "Zalo",
        "com.truecaller" to "Truecaller",
        "com.google.android.apps.messaging" to "SMS",
        "com.samsung.android.messaging" to "SMS",
        "com.huawei.mms" to "SMS"
    )

    data class Mensagem(
        val id: Long,
        val chave: String,
        val conversa: String,
        val remetente: String,
        val texto: String,
        val pacote: String,
        val appNome: String,
        val meuTurno: Boolean = false,
        val tipo: String = "texto",
        val fonte: String = "notif"
    )

    private const val MAX_POR_CONVERSA = 200
    private const val MAX_TOTAL = 1500
    private const val FICHEIRO = "social_msgs.json"

    private val conversas = ConcurrentHashMap<String, ArrayDeque<Mensagem>>()
    private val hashVistos = ConcurrentHashMap<String, Long>()

    @Volatile private var carregado = false
    private val travoFicheiro = Any()

    fun chave(pkg: String, conversa: String): String =
        "${pkg}_${conversa.trim().lowercase().replace(Regex("[^a-z0-9]"), "_").take(50)}"

    fun adicionar(ctx: Context, msg: Mensagem) {
        carregar(ctx)
        val hash = "${msg.pacote}|${msg.conversa.take(60)}|${msg.texto.take(80)}|${msg.meuTurno}"
        val agora = System.currentTimeMillis()
        val ultimo = hashVistos[hash]
        if (ultimo != null && agora - ultimo < 90_000L) return
        hashVistos[hash] = agora
        if (hashVistos.size > 2000) hashVistos.entries.removeIf { agora - it.value > 600_000L }

        val fila = conversas.getOrPut(msg.chave) { ArrayDeque() }
        synchronized(fila) {
            fila.addLast(msg)
            while (fila.size > MAX_POR_CONVERSA) fila.removeFirst()
        }
        val total = conversas.values.sumOf { it.size }
        if (total > MAX_TOTAL) {
            conversas.entries.filter { it.value.isNotEmpty() }
                .minByOrNull { it.value.first().id }
                ?.let { e -> synchronized(e.value) { if (e.value.isNotEmpty()) e.value.removeFirst() } }
        }
        guardar(ctx)
    }

    fun resumoConversas(pkg: String? = null): JSONArray {
        val arr = JSONArray()
        conversas.entries
            .filter { it.value.isNotEmpty() && (pkg == null || it.value.last().pacote == pkg) }
            .sortedByDescending { it.value.lastOrNull()?.id ?: 0L }
            .forEach { (chave, fila) ->
                val ultima = fila.lastOrNull() ?: return@forEach
                arr.put(JSONObject()
                    .put("chave", chave)
                    .put("conversa", ultima.conversa)
                    .put("pacote", ultima.pacote)
                    .put("app", ultima.appNome)
                    .put("n", fila.size)
                    .put("ultima_msg", ultima.texto)
                    .put("ultima_ts", ultima.id)
                    .put("ultima_meu", ultima.meuTurno)
                )
            }
        return arr
    }

    fun mensagensConversa(chave: String, limite: Int = 100): JSONArray {
        val arr = JSONArray()
        val fila = conversas[chave] ?: return arr
        synchronized(fila) {
            fila.takeLast(limite.coerceIn(1, MAX_POR_CONVERSA)).forEach { m ->
                arr.put(JSONObject()
                    .put("id", m.id)
                    .put("chave", m.chave)
                    .put("conversa", m.conversa)
                    .put("remetente", m.remetente)
                    .put("texto", m.texto)
                    .put("pacote", m.pacote)
                    .put("app", m.appNome)
                    .put("meuTurno", m.meuTurno)
                    .put("tipo", m.tipo)
                    .put("fonte", m.fonte)
                )
            }
        }
        return arr
    }

    fun exportar(chave: String?, fmt: String): ByteArray {
        val msgs = mutableListOf<Mensagem>()
        if (chave != null) {
            conversas[chave]?.let { f -> synchronized(f) { msgs.addAll(f) } }
        } else {
            conversas.values.forEach { f -> synchronized(f) { msgs.addAll(f) } }
            msgs.sortBy { it.id }
        }
        val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
        return if (fmt == "csv") {
            val sb = StringBuilder("timestamp,app,conversa,remetente,direcao,mensagem\n")
            fun esc(s: String) = "\"${s.replace("\"", "\"\"")}\""
            for (m in msgs) sb.append("${esc(sdf.format(java.util.Date(m.id)))},${esc(m.appNome)},${esc(m.conversa)},${esc(m.remetente)},${esc(if (m.meuTurno) "saida" else "entrada")},${esc(m.texto)}\n")
            sb.toString().toByteArray(Charsets.UTF_8)
        } else {
            val sb = StringBuilder()
            var ultConv = ""; var ultApp = ""
            for (m in msgs) {
                if (m.appNome != ultApp || m.conversa != ultConv) {
                    sb.append("\n── ${m.appNome}: ${m.conversa} ──\n\n")
                    ultApp = m.appNome; ultConv = m.conversa
                }
                sb.append("[${sdf.format(java.util.Date(m.id))}] ${if (m.meuTurno) "→ Eu" else "← ${m.remetente}"}: ${m.texto}\n")
            }
            sb.toString().trimStart().toByteArray(Charsets.UTF_8)
        }
    }

    private fun carregar(ctx: Context) {
        if (carregado) return
        synchronized(travoFicheiro) {
            if (carregado) return
            try {
                val arr = JSONArray(File(ctx.filesDir, FICHEIRO).readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val m = Mensagem(
                        id = o.getLong("id"), chave = o.getString("chave"),
                        conversa = o.optString("conversa", "?"), remetente = o.optString("remetente", "?"),
                        texto = o.getString("texto"), pacote = o.optString("pacote", ""),
                        appNome = o.optString("app", ""), meuTurno = o.optBoolean("meuTurno", false),
                        tipo = o.optString("tipo", "texto"), fonte = o.optString("fonte", "notif")
                    )
                    conversas.getOrPut(m.chave) { ArrayDeque() }.addLast(m)
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
                    synchronized(fila) {
                        fila.forEach { m ->
                            arr.put(JSONObject().put("id", m.id).put("chave", m.chave).put("conversa", m.conversa)
                                .put("remetente", m.remetente).put("texto", m.texto).put("pacote", m.pacote)
                                .put("app", m.appNome).put("meuTurno", m.meuTurno).put("tipo", m.tipo).put("fonte", m.fonte))
                        }
                    }
                }
                File(ctx.filesDir, FICHEIRO).writeText(arr.toString())
            } catch (_: Exception) {}
        }
    }

    private fun <K, V> ConcurrentHashMap<K, V>.getOrPut(key: K, default: () -> V): V =
        this[key] ?: default().also { this[key] = it }
}
