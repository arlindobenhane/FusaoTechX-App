package com.fusaotech.studio

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * FusãoTechX Mz • Captura de mensagens via AccessibilityService.
 * Complementa o NotifService: quando a app está aberta as notificações são suprimidas,
 * mas a árvore de UI continua acessível. As duas fontes alimentam o mesmo store,
 * com deduplicação por hash para não mostrar mensagens a dobrar.
 */
object SocialCapture {

    val TODOS_PACOTES = SocialStore.PACOTES + WhatsAppStore.PACOTES

    @Volatile var larguraEcra = 1080

    private val ultimaCaptura = ConcurrentHashMap<String, Long>()

    fun capturar(svc: ControlService, event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in TODOS_PACOTES) return
        val agora = System.currentTimeMillis()
        val ultimo = ultimaCaptura[pkg] ?: 0L
        if (agora - ultimo < 1500L) return
        ultimaCaptura[pkg] = agora
        Thread { try { capturarAsync(svc, pkg, agora) } catch (_: Exception) {} }.start()
    }

    private fun capturarAsync(svc: ControlService, pkg: String, ts: Long) {
        val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return
        try {
            if (root.packageName?.toString() != pkg) return
            val conversa = extrairTitulo(root, pkg) ?: return
            if (conversa.length < 2 || conversa.length > 120) return
            val msgNodes = mutableListOf<MsgNode>()
            recolherMensagens(root, msgNodes)
            if (msgNodes.isEmpty()) return
            val ctx = svc.applicationContext
            for ((idx, n) in msgNodes.withIndex()) {
                if (n.texto.length < 2) continue
                val msgTs = ts - idx * 100L
                if (pkg in WhatsAppStore.PACOTES) {
                    WhatsAppStore.adicionarDeAcessibilidade(ctx, conversa, n.texto, n.meuTurno, pkg, msgTs)
                } else {
                    val chave = SocialStore.chave(pkg, conversa)
                    val appNome = SocialStore.NOMES[pkg] ?: pkg.substringAfterLast('.')
                    SocialStore.adicionar(ctx, SocialStore.Mensagem(
                        id = msgTs, chave = chave, conversa = conversa,
                        remetente = if (n.meuTurno) "Eu" else conversa,
                        texto = n.texto, pacote = pkg, appNome = appNome,
                        meuTurno = n.meuTurno, fonte = "acc"
                    ))
                }
            }
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun extrairTitulo(root: AccessibilityNodeInfo, pkg: String): String? {
        val idsConhecidos = when (pkg) {
            "com.facebook.orca" -> listOf("$pkg:id/thread_title", "$pkg:id/name", "$pkg:id/action_bar_title")
            "com.instagram.android" -> listOf("$pkg:id/action_bar_title", "$pkg:id/thread_title_username")
            "org.telegram.messenger", "org.telegram.messenger.web" -> listOf("$pkg:id/name_text", "$pkg:id/title")
            "com.discord" -> listOf("$pkg:id/channel_name", "$pkg:id/toolbar_title")
            "com.twitter.android" -> listOf("$pkg:id/toolbar_title")
            else -> listOf("$pkg:id/action_bar_title", "$pkg:id/toolbar_title", "$pkg:id/title", "$pkg:id/thread_name")
        }
        for (id in idsConhecidos) {
            val nodes = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null }
            val t = nodes?.firstNotNullOfOrNull { n -> n.text?.toString()?.trim()?.takeIf { it.length in 2..100 } }
            nodes?.forEach { try { it.recycle() } catch (_: Exception) {} }
            if (t != null) return t
        }
        // Fallback genérico: primeiro texto no topo (200px)
        val candidatos = mutableListOf<Pair<Int, String>>()
        recolherTextosTopo(root, candidatos, 0)
        return candidatos.sortedBy { it.first }.firstOrNull { it.second.length in 2..80 }?.second
    }

    private fun recolherTextosTopo(node: AccessibilityNodeInfo, out: MutableList<Pair<Int, String>>, depth: Int) {
        if (depth > 6) return
        val rect = Rect(); node.getBoundsInScreen(rect)
        val texto = node.text?.toString()?.trim() ?: ""
        if (texto.length in 2..80 && rect.top < 400 && rect.height() in 20..100) {
            out.add(Pair(rect.top, texto))
        }
        for (i in 0 until node.childCount) {
            val c = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            recolherTextosTopo(c, out, depth + 1)
            try { c.recycle() } catch (_: Exception) {}
        }
    }

    data class MsgNode(val texto: String, val meuTurno: Boolean)

    private fun recolherMensagens(root: AccessibilityNodeInfo, out: MutableList<MsgNode>) {
        recolherMsgRec(root, out, 0)
    }

    private fun recolherMsgRec(node: AccessibilityNodeInfo, out: MutableList<MsgNode>, depth: Int) {
        if (depth > 12) return
        val rect = Rect(); node.getBoundsInScreen(rect)
        val texto = node.text?.toString()?.trim() ?: ""
        if (texto.length >= 3 && node.className?.contains("Text") == true && rect.height() >= 20 && rect.top > 300) {
            if (!pareceUILabel(texto) && out.none { it.texto == texto }) {
                out.add(MsgNode(texto, detectarMeuTurno(rect)))
            }
        }
        for (i in 0 until node.childCount) {
            val c = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            recolherMsgRec(c, out, depth + 1)
            try { c.recycle() } catch (_: Exception) {}
        }
    }

    private fun pareceUILabel(t: String): Boolean {
        if (t.length <= 2) return true
        val lower = t.lowercase()
        return lower in setOf("enviar", "send", "reply", "responder", "type a message", "message",
            "escreva uma mensagem", "new message", "search", "procurar", "ok", "cancel", "cancelar",
            "back", "voltar", "more", "mais", "share", "partilhar", "like", "gosto", "comment",
            "follow", "seguir", "emoji", "gif", "sticker", "attach", "photo", "foto", "video", "audio") ||
            t.matches(Regex("^\\d{1,2}:\\d{2}(\\s*(AM|PM))?$")) ||
            t.matches(Regex("^\\d+$"))
    }

    private fun detectarMeuTurno(rect: Rect): Boolean {
        val centro = (rect.left + rect.right) / 2
        return centro > larguraEcra * 0.55
    }
}
