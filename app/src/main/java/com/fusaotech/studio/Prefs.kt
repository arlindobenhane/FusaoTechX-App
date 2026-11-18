package com.fusaotech.studio

import android.content.Context
import android.os.Build

/**
 * FusãoTechX Mz • Identidade do aparelho (conta padrão, sem email nem login).
 * Na primeira abertura é criado um nome e um código de 4 dígitos, guardados
 * só neste telefone. Serve para o PC saber a qual telefone se está a ligar.
 */
object Prefs {
    private const val FILE = "ftx"
    const val PORT = 9999

    fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun code(c: Context): String {
        val sp = sp(c)
        var v = sp.getString("code", null)
        if (v == null) {
            v = (1000 + (Math.random() * 9000).toInt()).toString()
            sp.edit().putString("code", v).apply()
        }
        return v
    }

    fun setCode(c: Context, v: String) = sp(c).edit().putString("code", v).apply()

    /**
     * Código do Modo Avançado (anti-roubo): câmara silenciosa, localização, alarme, bloqueio.
     * É separado e mais sensível do que o código normal. Por omissão é igual ao normal + "00"
     * até o dono o definir; nunca é mostrado por inteiro no PC.
     */
    fun code2(c: Context): String {
        val sp = sp(c)
        var v = sp.getString("code2", null)
        if (v == null) { v = code(c) + "00"; sp.edit().putString("code2", v).apply() }
        return v
    }
    fun setCode2(c: Context, v: String) = sp(c).edit().putString("code2", v).apply()

    fun name(c: Context): String {
        val sp = sp(c)
        var v = sp.getString("name", null)
        if (v == null) {
            v = (Build.MANUFACTURER + " " + Build.MODEL).trim().replace("_", " ")
            sp.edit().putString("name", v).apply()
        }
        return v
    }

    fun setName(c: Context, v: String) = sp(c).edit().putString("name", v).apply()

    /** Endereço do relay (ligação por redes diferentes). Vazio = o da FusãoTechX. */
    const val RELAY_PADRAO = "https://studio.fusaotech.com"
    fun relay(c: Context): String {
        val v = (sp(c).getString("relay", "") ?: "").trimEnd('/')
        // O relay mudou de fusaotech.com/relay para o subdomínio studio.fusaotech.com.
        return if (v.isBlank() || v == "https://fusaotech.com/relay") RELAY_PADRAO else v
    }
    fun setRelay(c: Context, v: String) = sp(c).edit().putString("relay", v).apply()

    fun relayOn(c: Context): Boolean = sp(c).getBoolean("relayOn", false)
    fun setRelayOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("relayOn", v).apply()

    /** ID público de 9 dígitos (o PC escreve-o em studio.fusaotech.com). */
    fun relayId(c: Context): String {
        val sp = sp(c)
        var v = sp.getString("relayId", null)
        if (v == null) {
            val r = java.security.SecureRandom()
            v = (1 + r.nextInt(9)).toString() + (1..8).joinToString("") { r.nextInt(10).toString() }
            sp.edit().putString("relayId", v).apply()
        }
        return v
    }

    /** Segredo do aparelho: prova ao relay que este telefone é o dono do ID. Nunca sai do telefone além do relay. */
    fun relaySecret(c: Context): String {
        val sp = sp(c)
        var v = sp.getString("relaySecret", null)
        if (v == null) {
            val b = ByteArray(24); java.security.SecureRandom().nextBytes(b)
            v = b.joinToString("") { "%02x".format(it) }
            sp.edit().putString("relaySecret", v).apply()
        }
        return v
    }

    /** Gera um ID novo (ex.: se alguém indesejado souber o atual). */
    fun novoRelayId(c: Context) { sp(c).edit().remove("relayId").remove("relaySecret").apply() }

    fun autostart(c: Context): Boolean = sp(c).getBoolean("autostart", false)
    fun setAutostart(c: Context, v: Boolean) = sp(c).edit().putBoolean("autostart", v).apply()

    fun disfarce(c: Context): String = sp(c).getString("disfarce", "normal") ?: "normal"
    fun setDisfarce(c: Context, v: String) = sp(c).edit().putString("disfarce", v).apply()

    fun lastSeen(c: Context): Long = sp(c).getLong("lastSeen", 0L)
    fun setLastSeen(c: Context, ts: Long) = sp(c).edit().putLong("lastSeen", ts).apply()

    fun onlineSince(c: Context): Long {
        val k = "onlineSince"
        val v = sp(c).getLong(k, 0L)
        if (v == 0L) { val now = System.currentTimeMillis(); sp(c).edit().putLong(k, now).apply(); return now }
        return v
    }
    fun resetOnlineSince(c: Context) = sp(c).edit().remove("onlineSince").apply()

    // ── Prazo de acesso (timestamp ms, 0 = sem prazo) ───────────────────────
    fun prazoAcesso(c: Context): Long = sp(c).getLong("prazo", 0L)
    fun setPrazoAcesso(c: Context, v: Long) = sp(c).edit().putLong("prazo", v).apply()
    fun prazoAtivo(c: Context): Boolean {
        val p = prazoAcesso(c); return p > 0 && System.currentTimeMillis() > p
    }

    // ── Funcionalidades bloqueadas pelo admin ───────────────────────────────
    fun funcosBloqueadas(c: Context): Set<String> =
        sp(c).getStringSet("bloqueadas", emptySet()) ?: emptySet()
    fun bloquearFuncao(c: Context, f: String) {
        val set = funcosBloqueadas(c).toMutableSet(); set.add(f)
        sp(c).edit().putStringSet("bloqueadas", set).apply()
    }
    fun desbloquearFuncao(c: Context, f: String) {
        val set = funcosBloqueadas(c).toMutableSet(); set.remove(f)
        sp(c).edit().putStringSet("bloqueadas", set).apply()
    }
    fun funcaoBloqueada(c: Context, f: String): Boolean = f in funcosBloqueadas(c)

    // ── Renovar códigos ─────────────────────────────────────────────────────
    fun renovarCodigos(c: Context): Pair<String, String> {
        val r = java.security.SecureRandom()
        val novo = (1000 + r.nextInt(9000)).toString()
        val novo2 = (1000 + r.nextInt(9000)).toString() + (10 + r.nextInt(90)).toString()
        setCode(c, novo); setCode2(c, novo2)
        return Pair(novo, novo2)
    }
}
