package com.fusaotech.studio

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * FusãoTechX Mz • Ligação remota (telefone e PC em redes diferentes).
 *
 * O telefone liga-se PARA FORA ao relay (studio.fusaotech.com), por isso funciona nos dados móveis,
 * sem IP público e sem abrir portas. Três tarefas:
 *  1. Espera por pedidos do PC (long-poll) e executa-os no servidor local do próprio telefone (Httpd, porta 9999):
 *     o código de acesso continua a ser verificado pelo telefone, como na rede local.
 *  2. Devolve as respostas ao relay.
 *  3. Enquanto o PC estiver a ver o ecrã, envia a imagem mais recente (até ~10 por segundo).
 */
object Relay {
    @Volatile var correr = false; private set
    @Volatile var estado = "desligado"; private set
    @Volatile private var querEcra = false
    @Volatile private var sal = ""
    @Volatile var latenciaMs = 0L; private set
    @Volatile var galeriaAntiga = false; private set  // o admin autoriza puxar fotos antigas (30 dias)
    private var ctx: Context? = null
    private val pool = Executors.newFixedThreadPool(6)
    @Volatile private var pollAtual: HttpURLConnection? = null
    @Volatile private var redeMudou = false
    private var redeCb: android.net.ConnectivityManager.NetworkCallback? = null
    private var wake: android.os.PowerManager.WakeLock? = null

    fun iniciar(c: Context) {
        if (correr) return
        ctx = c.applicationContext
        correr = true
        estado = "a ligar…"
        vigiarRede()
        // Mantém o processador acordado para a ligação não cair com o ecrã apagado.
        try {
            val pm = c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "FusaoTechX:relay").also { it.setReferenceCounted(false); it.acquire() }
        } catch (_: Exception) {}
        Thread({ cicloPedidos() }, "ftx-relay-poll").start()
        Thread({ cicloEcra() }, "ftx-relay-ecra").start()
    }

    fun parar() {
        correr = false; querEcra = false; estado = "desligado"
        try { pollAtual?.disconnect() } catch (_: Exception) {}
        try { redeCb?.let { (ctx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)?.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        redeCb = null
        try { wake?.release() } catch (_: Exception) {}
        wake = null
    }

    /**
     * Wi-Fi ↔ dados móveis: quando a rede muda, a ligação antiga fica "pendurada".
     * Cortamo-la logo e voltamos a ligar pela rede nova, sem esperar pelo tempo limite.
     */
    private fun vigiarRede() {
        try {
            val cm = ctx!!.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { religar("rede disponível") }
                override fun onLost(network: android.net.Network) { estado = "sem rede — à espera de Wi-Fi ou dados móveis" }
            }
            cm.registerDefaultNetworkCallback(cb)
            redeCb = cb
        } catch (_: Exception) {}
    }

    private fun religar(motivo: String) {
        if (!correr) return
        redeMudou = true
        estado = "a religar ($motivo)…"
        try { pollAtual?.disconnect() } catch (_: Exception) {}
    }

    private fun base(): String = Prefs.relay(ctx!!)
    private fun id(): String = Prefs.relayId(ctx!!)

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun abrir(url: String, metodo: String, timeoutLer: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        val ct = ctx!!
        c.requestMethod = metodo
        c.connectTimeout = 15000
        c.readTimeout = timeoutLer
        c.useCaches = false
        c.setRequestProperty("X-Dev-Secret", Prefs.relaySecret(ct))
        c.setRequestProperty("X-Dev-Nome", URLEncoder.encode(Prefs.name(ct), "UTF-8"))
        if (sal.isNotEmpty()) c.setRequestProperty("X-Dev-Code", sha256(sal + Prefs.code(ct)))
        return c
    }

    private fun ler(inp: InputStream?): ByteArray {
        if (inp == null) return ByteArray(0)
        val bos = ByteArrayOutputStream()
        inp.use { it.copyTo(bos) }
        return bos.toByteArray()
    }

    // ---------- 1. Pedidos do PC ----------
    private fun cicloPedidos() {
        var espera = 2000L
        while (correr) {
            try {
                redeMudou = false
                val t0 = System.currentTimeMillis()
                val c = abrir(base() + "/api.php?a=poll&id=" + id(), "GET", 40000)
                c.setRequestProperty("X-Pushing", if (querEcra) "1" else "0")
                pollAtual = c
                val code = c.responseCode
                pollAtual = null
                if (code == 403) { estado = "ID em uso por outro aparelho — gere um ID novo"; Thread.sleep(15000); continue }
                if (code != 200) throw Exception("HTTP $code")
                val o = JSONObject(String(ler(c.inputStream), Charsets.UTF_8))
                sal = o.optString("sal", sal)
                // Licença controlada pelo administrador (bloqueio / validade).
                val lic = o.optJSONObject("licenca")
                galeriaAntiga = lic?.optBoolean("galeria_antiga", false) ?: false
                if (lic != null && !lic.optBoolean("ok", true)) {
                    querEcra = false
                    estado = when (lic.optString("motivo")) {
                        "bloqueado" -> "bloqueado pelo administrador"
                        "expirada" -> "licença expirada — contacte o administrador"
                        "por_autorizar" -> "a aguardar autorização do administrador"
                        else -> "sem licença"
                    }
                    try { Thread.sleep(10000) } catch (_: Exception) { return }
                    continue
                }
                querEcra = o.optBoolean("want", false)
                estado = "ligado"
                espera = 1000L
                if (l0(o)) latenciaMs = System.currentTimeMillis() - t0
                val l = o.optJSONArray("reqs")
                if (l != null) for (i in 0 until l.length()) {
                    val r = l.getJSONObject(i)
                    pool.execute { atender(r) }
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                pollAtual = null
                if (!correr) return
                if (redeMudou) { espera = 300L } else estado = "sem Internet — a tentar de novo"
                // Volta a tentar depressa: no máximo de 8 em 8 segundos, ou logo que a rede mude.
                var dormido = 0L
                while (dormido < espera && correr && !redeMudou) { try { Thread.sleep(200) } catch (_: Exception) { return }; dormido += 200 }
                espera = if (redeMudou) 300L else (espera * 2).coerceAtMost(8000L)
            }
        }
    }

    /** Resposta que chegou logo (pedidos à espera): serve para medir a latência real. */
    private fun l0(o: JSONObject): Boolean = (o.optJSONArray("reqs")?.length() ?: 0) > 0

    private fun atender(r: JSONObject) {
        val rid = r.optString("rid")
        if (!Regex("^[a-f0-9]{16}$").matches(rid)) return
        var status = 502; var tipo = "application/json"; var disp = ""; var corpo: ByteArray = "{\"erro\":\"telefone\"}".toByteArray()
        try {
            val caminho = r.optString("p", "/")
            if (!caminho.startsWith("/") || caminho.startsWith("/stream")) throw Exception("caminho")
            var entrada = ByteArray(0)
            if (r.optBoolean("b")) {
                val cb = abrir(base() + "/api.php?a=body&id=" + id() + "&rid=" + rid, "GET", 60000)
                entrada = ler(cb.inputStream)
            }
            // Executa no servidor do próprio telefone (o mesmo que a rede local usa).
            val l = URL("http://127.0.0.1:" + Prefs.PORT + caminho).openConnection() as HttpURLConnection
            l.requestMethod = if (r.optString("m") == "POST") "POST" else "GET"
            l.connectTimeout = 5000; l.readTimeout = 25000
            val t = r.optString("t"); if (t.isNotEmpty()) l.setRequestProperty("Content-Type", t)
            if (l.requestMethod == "POST") { l.doOutput = true; l.setFixedLengthStreamingMode(entrada.size); l.outputStream.use { it.write(entrada) } }
            status = l.responseCode
            tipo = l.contentType ?: "application/octet-stream"
            disp = l.getHeaderField("Content-Disposition") ?: ""
            corpo = ler(if (status >= 400) l.errorStream else l.inputStream)
        } catch (_: Exception) {}
        try {
            val c = abrir(base() + "/api.php?a=resp&id=" + id() + "&rid=" + rid, "POST", 60000)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setRequestProperty("X-Status", status.toString())
            c.setRequestProperty("X-Type", tipo)
            if (disp.isNotEmpty()) c.setRequestProperty("X-Disp", disp)
            c.setFixedLengthStreamingMode(corpo.size)
            c.outputStream.use { it.write(corpo) }
            c.responseCode
            c.disconnect()
        } catch (_: Exception) {}
    }

    // ---------- 3. Ecrã ao vivo ----------
    private fun cicloEcra() {
        var ultima: ByteArray? = null
        while (correr) {
            try {
                val f = CaptureService.frame
                if (!querEcra || f == null || f === ultima) { Thread.sleep(if (querEcra) 40 else 500); continue }
                val t0 = System.currentTimeMillis()
                val c = abrir(base() + "/api.php?a=frame&id=" + id(), "POST", 20000)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "image/jpeg")
                c.setFixedLengthStreamingMode(f.size)
                c.outputStream.use { it.write(f) }
                if (c.responseCode == 200) {
                    val o = JSONObject(String(ler(c.inputStream), Charsets.UTF_8))
                    querEcra = o.optBoolean("want", querEcra)
                    sal = o.optString("sal", sal)
                }
                c.disconnect()
                ultima = f
                val dt = System.currentTimeMillis() - t0
                if (dt < 100) Thread.sleep(100 - dt)
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                try { Thread.sleep(1500) } catch (_: Exception) { return }
            }
        }
    }
}
