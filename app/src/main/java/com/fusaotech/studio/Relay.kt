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
 * O telefone liga-se PARA FORA ao relay (fusaotech.com/relay), por isso funciona nos dados móveis,
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
    private var ctx: Context? = null
    private val pool = Executors.newFixedThreadPool(6)

    fun iniciar(c: Context) {
        if (correr) return
        ctx = c.applicationContext
        correr = true
        estado = "a ligar…"
        Thread({ cicloPedidos() }, "ftx-relay-poll").start()
        Thread({ cicloEcra() }, "ftx-relay-ecra").start()
    }

    fun parar() { correr = false; querEcra = false; estado = "desligado" }

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
                val c = abrir(base() + "/api.php?a=poll&id=" + id(), "GET", 40000)
                c.setRequestProperty("X-Pushing", if (querEcra) "1" else "0")
                val code = c.responseCode
                if (code == 403) { estado = "ID em uso por outro aparelho — gere um ID novo"; Thread.sleep(15000); continue }
                if (code != 200) throw Exception("HTTP $code")
                val o = JSONObject(String(ler(c.inputStream), Charsets.UTF_8))
                sal = o.optString("sal", sal)
                querEcra = o.optBoolean("want", false)
                estado = "ligado"
                espera = 2000L
                val l = o.optJSONArray("reqs")
                if (l != null) for (i in 0 until l.length()) {
                    val r = l.getJSONObject(i)
                    pool.execute { atender(r) }
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                if (!correr) return
                estado = "sem Internet — a tentar de novo"
                try { Thread.sleep(espera) } catch (_: Exception) { return }
                espera = (espera * 2).coerceAtMost(20000L)
            }
        }
    }

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
