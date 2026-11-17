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

        // ── Caminho 1: MessagingStyle via extras Android (sem AndroidX) ──
        // EXTRA_MESSAGES é um array de Bundle: cada bundle = uma mensagem
        @Suppress("UNCHECKED_CAST")
        val msgBundles: Array<android.os.Parcelable>? = try {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        } catch (_: Exception) { null }

        if (!msgBundles.isNullOrEmpty()) {
            val tituloConversa = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: sbn.packageName.substringAfterLast('.')
            val isGrupo = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)

            for (p in msgBundles) {
                val b = p as? android.os.Bundle ?: continue
                val ts = b.getLong("time", 0L)
                if (ts == 0L || !vistos.add(ts)) continue
                val texto = b.getCharSequence("text")?.toString()?.trim() ?: continue
                if (texto.isEmpty()) continue

                // "sender" nulo/vazio = mensagem enviada pelo próprio utilizador
                val senderCharSeq = b.getCharSequence("sender")?.toString()?.trim()
                // API 28+: sender_person é um Person
                val senderPerson: android.app.Person? = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    try { b.getParcelable("sender_person") } catch (_: Exception) { null }
                } else null
                val senderName = senderPerson?.name?.toString()?.trim()
                    ?: senderCharSeq
                val meuTurno = senderName.isNullOrEmpty()
                val remetente = if (meuTurno) "Eu" else (senderName ?: tituloConversa)

                val tipoDetectado = detectarTipo(texto)
                adicionar(ctx, WhatsAppMessage(
                    id = ts,
                    chave = sbn.key,
                    conversa = tituloConversa,
                    remetente = remetente,
                    texto = texto,
                    isGrupo = isGrupo,
                    pacote = sbn.packageName,
                    meuTurno = meuTurno,
                    tipo = tipoDetectado,
                    mediaPath = procurarMedia(sbn.packageName, tipoDetectado, ts)
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

        // No fallback, detetar mensagens saídas via prefixo "Eu:" ou "You:"
        val meuTurnoFallback = texto.startsWith("Eu: ", ignoreCase = true) || texto.startsWith("You: ", ignoreCase = true)
        val textoLimpo = when {
            meuTurnoFallback -> texto.substringAfter(": ")
            else -> texto
        }

        val tipoFallback = detectarTipo(textoLimpo)
        adicionar(ctx, WhatsAppMessage(
            id = ts,
            chave = sbn.key,
            conversa = titulo,
            remetente = if (meuTurnoFallback) "Eu" else titulo,
            texto = textoLimpo,
            isGrupo = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            pacote = sbn.packageName,
            meuTurno = meuTurnoFallback,
            tipo = tipoFallback,
            mediaPath = procurarMedia(sbn.packageName, tipoFallback, ts)
        ))
        return true
    }

    /** Detetar tipo de media a partir do texto da notificação do WhatsApp. */
    private fun detectarTipo(texto: String): String {
        val t = texto.trim()
        return when {
            t.matches(Regex("(?i)(📷|🖼️|📸).*|(foto|photo|image|imagem).*", RegexOption.IGNORE_CASE)) ||
            t.equals("📷 Photo", ignoreCase = true) || t.equals("📷 Foto", ignoreCase = true) ||
            t.startsWith("📷") || t.startsWith("🖼") || t.startsWith("📸") -> "imagem"

            t.matches(Regex("(?i)(🎵|🎶|🔊).*|(áudio|audio|voice message|mensagem de voz).*")) ||
            t.startsWith("🎵") || t.startsWith("🎶") || t.startsWith("🔊") ||
            t.equals("🎤 Voice message", ignoreCase = true) || t.equals("🎤 Mensagem de voz", ignoreCase = true) ||
            t.startsWith("🎤") -> "audio"

            t.matches(Regex("(?i)(🎬|📹).*|(vídeo|video|gif).*")) ||
            t.startsWith("🎬") || t.startsWith("📹") -> "video"

            t.matches(Regex("(?i)(📄|📎|📃|📑).*|(documento|document|file|ficheiro).*")) ||
            t.startsWith("📄") || t.startsWith("📎") || t.startsWith("📃") -> "documento"

            t.matches(Regex("(?i)(📞|☎️|📱).*|(chamada|call|missed call|chamada perdida|chamada recebida|chamada realizada).*")) ||
            t.startsWith("📞") || t.startsWith("☎") ||
            t.contains("chamada", ignoreCase = true) || t.contains("call", ignoreCase = true) -> "chamada"

            t.matches(Regex("(?i)(😊|😀|🙂).*|(sticker|stickers).*")) ||
            t.equals("Sticker", ignoreCase = true) -> "sticker"

            t.matches(Regex("(?i)(📍|🗺️).*|(localização|location).*")) ||
            t.startsWith("📍") || t.startsWith("🗺") -> "localizacao"

            t.startsWith("🎤") || t.contains("voice note", ignoreCase = true) -> "voz"

            else -> "texto"
        }
    }

    /** Tenta encontrar o ficheiro de media mais recente na pasta certa do WhatsApp,
     *  dentro de 10 segundos antes e 30 segundos depois do timestamp da notificação. */
    private fun procurarMedia(pacote: String, tipo: String, ts: Long): String? {
        if (tipo == "texto" || tipo == "chamada" || tipo == "localizacao") return null
        val base = android.os.Environment.getExternalStorageDirectory()
        // WhatsApp guarda media em dois caminhos dependendo da versão Android
        val raizes = listOf(
            java.io.File(base, "Android/media/$pacote/WhatsApp/Media"),
            java.io.File(base, "WhatsApp/Media")
        )
        val subDir = when (tipo) {
            "imagem" -> listOf("WhatsApp Images", "WhatsApp Images/Sent")
            "video"  -> listOf("WhatsApp Video", "WhatsApp Video/Sent")
            "audio"  -> listOf("WhatsApp Audio", "WhatsApp Voice Notes")
            "voz"    -> listOf("WhatsApp Voice Notes", "WhatsApp Audio")
            "sticker"-> listOf("WhatsApp Stickers")
            else     -> listOf("WhatsApp Documents")
        }
        val extensoes = when (tipo) {
            "imagem"  -> setOf("jpg","jpeg","png","webp")
            "video"   -> setOf("mp4","3gp","mkv","webm")
            "audio"   -> setOf("mp3","m4a","ogg","opus","aac","wav")
            "voz"     -> setOf("ogg","opus","m4a","mp3")
            "sticker" -> setOf("webp","png")
            else      -> setOf("pdf","doc","docx","xls","xlsx","ppt","pptx","txt","zip")
        }
        val janela = 30_000L  // 30 segundos de margem
        var melhor: java.io.File? = null
        var menorDelta = Long.MAX_VALUE
        for (raiz in raizes) {
            for (sub in subDir) {
                val dir = java.io.File(raiz, sub)
                if (!dir.isDirectory) continue
                dir.listFiles()?.forEach { f ->
                    if (!f.isFile) return@forEach
                    val ext = f.name.substringAfterLast('.', "").lowercase()
                    if (ext !in extensoes) return@forEach
                    val delta = kotlin.math.abs(f.lastModified() - ts)
                    if (delta <= janela + 5_000L && delta < menorDelta) {
                        menorDelta = delta; melhor = f
                    }
                }
            }
        }
        return melhor?.absolutePath
    }

    fun adicionarDeAcessibilidade(ctx: Context, conversa: String, texto: String, meuTurno: Boolean, pacote: String, ts: Long) {
        if (texto.length < 2) return
        carregar(ctx)
        val chave = "acc_${pacote}_${conversa.trim().lowercase().take(40).replace(Regex("[^a-z0-9]"), "_")}"
        val vistos = tsVistos.getOrPut(chave) { mutableSetOf() }
        val hash = texto.hashCode().toLong() and 0x7FFFFFFFL
        if (!vistos.add(hash)) return
        if (vistos.size > 500) vistos.clear()
        adicionar(ctx, WhatsAppMessage(
            id = ts, chave = chave, conversa = conversa,
            remetente = if (meuTurno) "Eu" else conversa,
            texto = texto, isGrupo = false, pacote = pacote,
            meuTurno = meuTurno, tipo = detectarTipo(texto)
        ))
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
                    .put("ultima_tipo", ultima.tipo)
                    .put("ultima_meu", ultima.meuTurno)
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
        .put("meuTurno", m.meuTurno)
        .put("tipo", m.tipo)
        .apply { if (m.mediaPath != null) put("mediaPath", m.mediaPath) }

    // ──────────────────────────────────────────────────────────────
    //  Exportação
    // ──────────────────────────────────────────────────────────────

    /** Exporta uma conversa ou todas como TXT ou CSV. */
    fun exportar(chave: String?, formato: String): ByteArray {
        val msgs = mutableListOf<WhatsAppMessage>()
        if (chave != null) {
            conversas[chave]?.let { fila -> synchronized(fila) { msgs.addAll(fila) } }
        } else {
            conversas.values.forEach { fila -> synchronized(fila) { msgs.addAll(fila) } }
            msgs.sortBy { it.id }
        }
        return when (formato.lowercase()) {
            "csv" -> exportarCsv(msgs)
            else  -> exportarTxt(msgs)
        }
    }

    private fun exportarTxt(msgs: List<WhatsAppMessage>): ByteArray {
        val sb = StringBuilder()
        var ultimaConversa = ""
        for (m in msgs) {
            if (m.conversa != ultimaConversa) {
                sb.append("\n── ${m.conversa} (${if (m.pacote == "com.whatsapp.w4b") "Business" else "Messenger"}) ──\n\n")
                ultimaConversa = m.conversa
            }
            val hora = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(m.id))
            val dir = if (m.meuTurno) "→" else "←"
            val rem = if (m.meuTurno) "Eu" else m.remetente
            val tipoLabel = if (m.tipo != "texto") "[${m.tipo}] " else ""
            sb.append("[$hora] $dir $rem: $tipoLabel${m.texto}\n")
        }
        return sb.toString().trimStart().toByteArray(Charsets.UTF_8)
    }

    private fun exportarCsv(msgs: List<WhatsAppMessage>): ByteArray {
        val sb = StringBuilder()
        sb.append("timestamp,conversa,app,remetente,direcao,tipo,mensagem\n")
        for (m in msgs) {
            val hora = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(m.id))
            val app = if (m.pacote == "com.whatsapp.w4b") "Business" else "Messenger"
            val dir = if (m.meuTurno) "saida" else "entrada"
            fun esc(s: String) = "\"${s.replace("\"", "\"\"")}\""
            sb.append("${esc(hora)},${esc(m.conversa)},${esc(app)},${esc(m.remetente)},${esc(dir)},${esc(m.tipo)},${esc(m.texto)}\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

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
                        pacote = o.optString("pacote", "com.whatsapp"),
                        meuTurno = o.optBoolean("meuTurno", false),
                        tipo = o.optString("tipo", "texto"),
                        mediaPath = o.optString("mediaPath", "").ifEmpty { null }
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
