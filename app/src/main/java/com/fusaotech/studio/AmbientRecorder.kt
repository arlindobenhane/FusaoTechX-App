package com.fusaotech.studio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/**
 * Gravador de ambiente em tempo real usando AudioRecord (PCM 16-bit, 16kHz, mono).
 * Faz stream direto para o browser e acumula tudo para download posterior.
 */
object AmbientRecorder {

    @Volatile var gravando = false
    @Volatile var iniciadoEm: Long = 0L

    @Volatile private var ar: AudioRecord? = null
    @Volatile private var acumulado: ByteArrayOutputStream? = null

    private const val SAMPLE_RATE = 16000
    private val CHANNELS = AudioFormat.CHANNEL_IN_MONO
    private val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    fun iniciar(c: Context) {
        if (gravando) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNELS, ENCODING,
            minBuf * 8
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        ar = rec
        acumulado = ByteArrayOutputStream()
        gravando = true
        iniciadoEm = System.currentTimeMillis()
        rec.startRecording()
    }

    fun parar() {
        gravando = false
        try { ar?.stop() } catch (_: Exception) {}
        try { ar?.release() } catch (_: Exception) {}
        ar = null
    }

    /**
     * Streams WAV header (unknown-length) + PCM data to [out] until parar() is called.
     * Also accumulates data in [acumulado] for later download.
     * Call this from a background thread inside a streaming HTTP response.
     */
    fun streamParaOutput(out: OutputStream) {
        val rec = ar ?: return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        val chunk = ByteArray(minBuf * 2)
        try {
            escreverWavHeader(out, 0x7FFFFFFF) // unknown length
            out.flush()
            while (gravando) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) {
                    out.write(chunk, 0, n)
                    out.flush()
                    acumulado?.write(chunk, 0, n)
                }
            }
        } catch (_: Exception) {}
    }

    /** Returns the full WAV file of everything recorded (for download). */
    fun wavCompleto(): ByteArray? {
        val pcm = acumulado?.toByteArray()?.takeIf { it.isNotEmpty() } ?: return null
        val out = ByteArrayOutputStream(pcm.size + 44)
        escreverWavHeader(out, pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    private fun escreverWavHeader(out: OutputStream, dataSize: Int) {
        val byteRate = SAMPLE_RATE * 1 * 16 / 8  // sampleRate * channels * bits/8
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray())
        h.putInt(if (dataSize == 0x7FFFFFFF) 0x7FFFFFFF else dataSize + 36)
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray())
        h.putInt(16)
        h.putShort(1)                       // PCM
        h.putShort(1)                       // mono
        h.putInt(SAMPLE_RATE)
        h.putInt(byteRate)
        h.putShort(2)                       // blockAlign
        h.putShort(16)                      // bitsPerSample
        h.put("data".toByteArray())
        h.putInt(dataSize)
        out.write(h.array())
    }

    fun estado(): JSONObject = JSONObject()
        .put("gravando", gravando)
        .put("iniciado_em", iniciadoEm)
        .put("tem_audio", (acumulado?.size() ?: 0) > 0)
        .put("tamanho_pcm", acumulado?.size() ?: 0)
}
