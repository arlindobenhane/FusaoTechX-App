package com.fusaotech.studio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * FusãoTechX Mz • Servidor dentro do telefone.
 * O PC (ou qualquer navegador na mesma rede/hotspot) abre http://IP:9999,
 * mete o código e controla o telefone. Tudo protegido por código.
 */
class Httpd(ctxIn: Context, private val porta: Int) {
    private val ctx: Context = ctxIn.applicationContext
    private var server: ServerSocket? = null
    @Volatile private var correr = false
    @Volatile private var falhas = 0
    @Volatile private var bloqueioAte = 0L

    fun start() {
        if (correr) return
        correr = true
        Thread {
            try {
                server = ServerSocket(porta)
                while (correr) {
                    val s = try { server!!.accept() } catch (_: Exception) { break }
                    Thread { atender(s) }.start()
                }
            } catch (_: Exception) {}
        }.start()
    }

    fun parar() {
        correr = false
        try { server?.close() } catch (_: Exception) {}
    }

    private fun code() = Prefs.code(ctx)

    private fun atender(s: Socket) {
        try {
            s.soTimeout = 15000
            val inp = s.getInputStream()
            val out = s.getOutputStream()
            val linha = readLine(inp) ?: return
            val partes = linha.split(" ")
            if (partes.size < 2) return
            val metodo = partes[0]
            val caminhoQs = partes[1]
            val qpos = caminhoQs.indexOf('?')
            val caminho = if (qpos >= 0) caminhoQs.substring(0, qpos) else caminhoQs
            val qs = if (qpos >= 0) caminhoQs.substring(qpos + 1) else ""
            val query = parseQuery(qs)

            var contLen = 0
            var chave = query["k"] ?: ""
            while (true) {
                val h = readLine(inp) ?: break
                if (h.isEmpty()) break
                val low = h.lowercase()
                if (low.startsWith("content-length:")) contLen = h.substring(15).trim().toIntOrNull() ?: 0
                if (low.startsWith("x-key:")) chave = h.substring(6).trim()
                if (low.startsWith("cookie:") && chave.isEmpty()) {
                    val m = Regex("ftx=([0-9]+)").find(h); if (m != null) chave = m.groupValues[1]
                }
            }
            var corpo = ByteArray(0)
            if (contLen in 1..52428800) {
                corpo = ByteArray(contLen); var lido = 0
                while (lido < contLen) { val n = inp.read(corpo, lido, contLen - lido); if (n < 0) break; lido += n }
            }

            // Autenticação
            val agora = System.currentTimeMillis()
            if (caminho != "/login" && caminho != "/") {
                if (agora < bloqueioAte) { enviar(out, 429, "application/json", "{\"erro\":\"bloqueado\"}".toByteArray()); return }
                if (chave != code()) { enviar(out, 401, "application/json", "{\"erro\":\"codigo\"}".toByteArray()); return }
            }

            when {
                caminho == "/" -> servirAsset(out, "control.html", "text/html; charset=utf-8")
                caminho == "/login" && metodo == "POST" -> {
                    val b = json(corpo)
                    if (b.optString("code") == code()) {
                        falhas = 0
                        enviarComCookie(out, "{\"ok\":true}", "ftx=" + code() + "; Max-Age=2592000; Path=/")
                    } else {
                        falhas++; if (falhas >= 6) { bloqueioAte = agora + 300000; falhas = 0 }
                        enviar(out, 200, "application/json", "{\"ok\":false}".toByteArray())
                    }
                }
                caminho == "/stream" -> mjpeg(out)
                caminho == "/shot" -> {
                    val f = CaptureService.frame
                    if (f != null) enviar(out, 200, "image/jpeg", f) else enviar(out, 503, "text/plain", "sem ecra".toByteArray())
                }
                caminho == "/state" -> jsonResp(out, estadoCompleto())
                caminho == "/sms" -> jsonResp(out, JSONObject().put("lista", Device.smsInbox(ctx)))
                caminho == "/contactos" -> jsonResp(out, JSONObject().put("lista", Device.contactos(ctx)))
                caminho == "/apps" -> jsonResp(out, JSONObject().put("lista", Device.apps(ctx)))
                caminho == "/notifs" -> jsonResp(out, notifs())
                caminho == "/files" -> jsonResp(out, Device.listar(query["p"]))
                caminho == "/file" -> servirFicheiro(out, query["p"], query["baixar"] != null)
                caminho == "/upload" && metodo == "POST" -> jsonResp(out, upload(query["nome"], corpo))
                metodo == "POST" -> jsonResp(out, acao(caminho, json(corpo)))
                else -> enviar(out, 404, "text/plain", "404".toByteArray())
            }
        } catch (_: Exception) {
        } finally { try { s.close() } catch (_: Exception) {} }
    }

    // ---------------- Ações de controlo ----------------
    private fun acao(caminho: String, b: JSONObject): JSONObject {
        val ctl = ControlService.inst
        val rw = CaptureService.realW; val rh = CaptureService.realH
        fun px(n: String, max: Int) = (b.optDouble(n, 0.0) * max).toInt()
        val r = JSONObject().put("ok", true)
        when (caminho) {
            "/tap" -> ctl?.tap(px("x", rw), px("y", rh))
            "/longpress" -> ctl?.longPress(px("x", rw), px("y", rh))
            "/swipe" -> ctl?.swipe(px("x1", rw), px("y1", rh), px("x2", rw), px("y2", rh), b.optLong("ms", 160))
            "/text" -> r.put("ok", ctl?.type(b.optString("t")) ?: false)
            "/backspace" -> ctl?.backspace()
            "/limpar" -> ctl?.limparCampo()
            "/global" -> r.put("ok", ctl?.global(b.optString("a")) ?: false)
            "/call" -> Device.ligar(ctx, b.optString("num"))
            "/endcall" -> ctl?.global("back")
            "/sendsms" -> r.put("ok", Device.enviarSms(ctx, b.optString("num"), b.optString("texto")))
            "/whatsapp" -> Device.whatsapp(ctx, b.optString("num"), b.optString("texto"))
            "/open" -> Device.abrirUrl(ctx, b.optString("url"))
            "/applaunch" -> Device.abrirApp(ctx, b.optString("pkg"))
            "/notifdismiss" -> if (b.optString("key").isEmpty()) NotifService.inst?.dispensarTodas() else NotifService.inst?.dispensar(b.optString("key"))
            "/quality" -> CaptureService.qualidade = b.optInt("q", 60).coerceIn(20, 90)
            else -> r.put("ok", false)
        }
        return r
    }

    private fun estadoCompleto(): JSONObject {
        val o = Device.estado(ctx)
        o.put("ecra", CaptureService.frame != null)
        o.put("w", CaptureService.realW); o.put("h", CaptureService.realH)
        o.put("ips", JSONArray(Util.localIps()))
        return o
    }

    private fun notifs(): JSONObject {
        val arr = JSONArray()
        val svc = NotifService.inst
        if (svc != null) {
            for (sbn in svc.lista()) {
                try {
                    val ex = sbn.notification.extras
                    val o = JSONObject()
                    o.put("key", sbn.key)
                    o.put("pkg", sbn.packageName)
                    o.put("app", appLabel(sbn.packageName))
                    o.put("titulo", ex.getCharSequence("android.title")?.toString() ?: "")
                    o.put("texto", ex.getCharSequence("android.text")?.toString() ?: "")
                    o.put("data", sbn.postTime)
                    arr.put(o)
                } catch (_: Exception) {}
            }
        }
        return JSONObject().put("lista", arr).put("ativo", svc != null)
    }

    private fun appLabel(pkg: String): String {
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg.substringAfterLast('.') }
    }

    private fun upload(nome: String?, corpo: ByteArray): JSONObject {
        return try {
            val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOAD), "FusaoTechX")
            dir.mkdirs()
            val f = java.io.File(dir, nome ?: ("ftx_" + System.currentTimeMillis()))
            f.writeBytes(corpo)
            JSONObject().put("ok", true).put("caminho", f.path)
        } catch (_: Exception) { JSONObject().put("ok", false) }
    }

    // ---------------- Respostas ----------------
    private fun mjpeg(out: OutputStream) {
        val cab = "HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=ftx\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(cab.toByteArray()); out.flush()
        var semEcra = 0
        try {
            while (correr) {
                val f = CaptureService.frame
                if (f == null) {
                    semEcra++; if (semEcra > 200) break
                    Thread.sleep(50); continue
                }
                semEcra = 0
                out.write(("--ftx\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n").toByteArray())
                out.write(f); out.write("\r\n".toByteArray()); out.flush()
                Thread.sleep(55)
            }
        } catch (_: Exception) {}
    }

    private fun servirAsset(out: OutputStream, nome: String, tipo: String) {
        try {
            val bos = ByteArrayOutputStream()
            ctx.assets.open(nome).use { it.copyTo(bos) }
            enviar(out, 200, tipo, bos.toByteArray())
        } catch (_: Exception) { enviar(out, 404, "text/plain", "sem UI".toByteArray()) }
    }

    private fun servirFicheiro(out: OutputStream, p: String?, baixar: Boolean) {
        try {
            val f = java.io.File(p ?: return enviar(out, 404, "text/plain", "x".toByteArray()))
            if (!f.exists() || f.isDirectory) { enviar(out, 404, "text/plain", "x".toByteArray()); return }
            val tipo = mime(f.name)
            val disp = if (baixar) "attachment" else "inline"
            val cab = "HTTP/1.1 200 OK\r\nContent-Type: $tipo\r\nContent-Length: ${f.length()}\r\nContent-Disposition: $disp; filename*=UTF-8''${enc(f.name)}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            out.write(cab.toByteArray())
            f.inputStream().use { it.copyTo(out) }
            out.flush()
        } catch (_: Exception) {}
    }

    private fun jsonResp(out: OutputStream, o: JSONObject) = enviar(out, 200, "application/json; charset=utf-8", o.toString().toByteArray())

    private fun enviar(out: OutputStream, code: Int, tipo: String, corpo: ByteArray) {
        try {
            val txt = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; 429 -> "Too Many Requests"; 503 -> "Unavailable"; else -> "Error" }
            out.write(("HTTP/1.1 $code $txt\r\nContent-Type: $tipo\r\nContent-Length: ${corpo.size}\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(corpo); out.flush()
        } catch (_: Exception) {}
    }

    private fun enviarComCookie(out: OutputStream, corpo: String, cookie: String) {
        try {
            val b = corpo.toByteArray()
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nSet-Cookie: $cookie\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(b); out.flush()
        } catch (_: Exception) {}
    }

    // ---------------- Utilidades ----------------
    private fun readLine(inp: InputStream): String? {
        val b = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val c = inp.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) break
            if (prev == '\r'.code) { } // ignora
            if (c != '\r'.code) b.write(c)
            prev = c
        }
        return b.toString("UTF-8")
    }

    private fun parseQuery(qs: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (p in qs.split("&")) {
            if (p.isEmpty()) continue
            val i = p.indexOf('=')
            try {
                if (i < 0) m[dec(p)] = "" else m[dec(p.substring(0, i))] = dec(p.substring(i + 1))
            } catch (_: Exception) {}
        }
        return m
    }

    private fun dec(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
    private fun enc(s: String) = try { java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20") } catch (_: Exception) { s }

    private fun json(b: ByteArray): JSONObject = try { if (b.isEmpty()) JSONObject() else JSONObject(String(b, Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }

    private fun mime(n: String): String {
        val e = n.substringAfterLast('.', "").lowercase()
        return when (e) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
            "mp4" -> "video/mp4"; "3gp" -> "video/3gpp"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"
            "pdf" -> "application/pdf"; "txt" -> "text/plain; charset=utf-8"; "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }
}
