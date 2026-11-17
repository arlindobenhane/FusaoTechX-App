package com.fusaotech.studio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

object CallRecorder {

    private const val MAX_GRAVACOES = 50
    private const val DIR = "call_recordings"

    private val gravacoes = ConcurrentLinkedDeque<GravacaoInfo>()

    @Volatile private var gravador: MediaRecorder? = null
    @Volatile private var fichAtual: File? = null
    @Volatile private var tsInicio = 0L
    @Volatile private var numeroAtual = ""
    @Volatile private var registado = false

    data class GravacaoInfo(
        val id: String,
        val ts: Long,
        val duracao: Long,
        val numero: String,
        val caminho: String
    )

    fun iniciar(ctx: Context) {
        if (registado) return
        registado = true
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        @Suppress("DEPRECATION")
        tm.listen(object : PhoneStateListener() {
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                tratarEstado(ctx, state, phoneNumber ?: "")
            }
        }, PhoneStateListener.LISTEN_CALL_STATE)
    }

    @Synchronized
    private fun tratarEstado(ctx: Context, state: Int, numero: String) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> iniciarGravacao(ctx, numero)
            TelephonyManager.CALL_STATE_IDLE -> pararGravacao()
        }
    }

    private fun iniciarGravacao(ctx: Context, numero: String) {
        if (gravador != null) return
        val dir = File(ctx.filesDir, DIR).also { it.mkdirs() }
        val id = System.currentTimeMillis().toString()
        val f = File(dir, "rec_$id.m4a")
        fun tentar(source: Int): Boolean {
            return try {
                @Suppress("DEPRECATION")
                val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
                mr.setAudioSource(source)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioEncodingBitRate(64000)
                mr.setAudioSamplingRate(16000)
                mr.setOutputFile(f.absolutePath)
                mr.prepare()
                mr.start()
                gravador = mr
                fichAtual = f
                tsInicio = System.currentTimeMillis()
                numeroAtual = numero
                true
            } catch (_: Exception) { false }
        }
        if (!tentar(MediaRecorder.AudioSource.VOICE_CALL))
            if (!tentar(MediaRecorder.AudioSource.VOICE_COMMUNICATION))
                tentar(MediaRecorder.AudioSource.MIC)
    }

    private fun pararGravacao() {
        val mr = gravador ?: return
        val f = fichAtual ?: return
        val ts = tsInicio
        val num = numeroAtual
        gravador = null; fichAtual = null; tsInicio = 0L; numeroAtual = ""
        try { mr.stop() } catch (_: Exception) {}
        try { mr.release() } catch (_: Exception) {}
        if (f.exists() && f.length() > 1024L) {
            val duracao = System.currentTimeMillis() - ts
            gravacoes.addFirst(GravacaoInfo(id = f.nameWithoutExtension, ts = ts, duracao = duracao, numero = num, caminho = f.absolutePath))
            while (gravacoes.size > MAX_GRAVACOES) gravacoes.pollLast()?.let { File(it.caminho).delete() }
        } else {
            f.delete()
        }
    }

    fun listar(): JSONArray {
        val arr = JSONArray()
        for (g in gravacoes) {
            arr.put(JSONObject()
                .put("id", g.id)
                .put("ts", g.ts)
                .put("duracao", g.duracao)
                .put("numero", g.numero))
        }
        return arr
    }

    fun caminho(id: String): String? = gravacoes.find { it.id == id }?.caminho

    fun apagar(id: String): Boolean {
        val g = gravacoes.find { it.id == id } ?: return false
        gravacoes.remove(g)
        return File(g.caminho).delete()
    }
}
