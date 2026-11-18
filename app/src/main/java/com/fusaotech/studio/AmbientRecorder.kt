package com.fusaotech.studio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build

/**
 * Gravador de áudio ambiente — capta o microfone pelo número de segundos pedido.
 * Sem notificação. O ficheiro fica em cacheDir e é eliminado após leitura.
 */
object AmbientRecorder {

    @Volatile var gravando = false
    @Volatile var ultimaGravacao: ByteArray? = null
    @Volatile var iniciadoEm: Long = 0L
    @Volatile var duracaoPedida: Int = 0

    @Volatile private var rec: MediaRecorder? = null
    @Volatile private var ficheiro: java.io.File? = null

    fun gravar(c: Context, segundos: Int, cb: (Boolean) -> Unit) {
        if (gravando) { cb(false); return }
        gravando = true
        iniciadoEm = System.currentTimeMillis()
        duracaoPedida = segundos
        ultimaGravacao = null
        Thread {
            var f: java.io.File? = null
            try {
                f = java.io.File(c.cacheDir, "amb_${System.currentTimeMillis()}.aac")
                ficheiro = f
                val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(c)
                        else @Suppress("DEPRECATION") MediaRecorder()
                rec = r
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44100)
                r.setAudioEncodingBitRate(96000)
                r.setMaxDuration((segundos * 1000).coerceAtMost(300_000))
                r.setOutputFile(f.absolutePath)
                r.prepare()
                r.start()
                val max = (segundos * 1000L).coerceAtMost(300_000L)
                val ini = System.currentTimeMillis()
                while (gravando && System.currentTimeMillis() - ini < max) Thread.sleep(300)
                try { r.stop() } catch (_: Exception) {}
                r.release()
            } catch (_: Exception) {
                try { rec?.release() } catch (_: Exception) {}
            } finally {
                rec = null
                gravando = false
                ultimaGravacao = try { f?.takeIf { it.exists() && it.length() > 0 }?.readBytes() } catch (_: Exception) { null }
                f?.delete()
                ficheiro = null
                cb(ultimaGravacao != null)
            }
        }.start()
    }

    fun parar() {
        gravando = false
        try { rec?.stop() } catch (_: Exception) {}
    }

    fun estado(): org.json.JSONObject = org.json.JSONObject()
        .put("gravando", gravando)
        .put("iniciado_em", iniciadoEm)
        .put("duracao_pedida", duracaoPedida)
        .put("tem_audio", ultimaGravacao != null)
        .put("tamanho", ultimaGravacao?.size ?: 0)
}
