package com.fusaotech.studio

/**
 * FusãoTechX Mz • Mensagem de WhatsApp/WhatsApp Business.
 *
 * Extraída do NotificationListenerService via MessagingStyle (preferido)
 * ou fallback para o título/texto simples da notificação.
 */
data class WhatsAppMessage(
    val id: Long,            // timestamp da mensagem (ms) — usado como chave de deduplicação
    val chave: String,       // StatusBarNotification.key — identifica a conversa no sistema
    val conversa: String,    // nome do contato ou do grupo
    val remetente: String,   // quem enviou (= conversa em 1-para-1; nome do membro em grupos)
    val texto: String,
    val isGrupo: Boolean,
    val pacote: String,      // com.whatsapp | com.whatsapp.w4b
    // texto | foto | video | audio | documento | sticker | gif | localizacao | contacto |
    // sondagem | chamada | chamada_perdida
    val tipo: String = "texto"
)
