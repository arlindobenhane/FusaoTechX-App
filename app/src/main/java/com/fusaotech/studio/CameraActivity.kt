package com.fusaotech.studio

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Activity transparente e invisível para tirar fotos em segundo plano.
 * Necessária no Android 12+ onde a câmara não pode ser usada diretamente
 * por um serviço que arrancou no boot — uma Activity contorna essa restrição.
 */
class CameraActivity : Activity() {

    companion object {
        const val EXTRA_FRENTE = "frente"
        const val ACTION_RESULTADO = "com.fusaotech.studio.FOTO_PRONTA"
        const val EXTRA_CAMINHO = "caminho"
        const val EXTRA_ERRO = "erro"

        private val fotoResultado = AtomicReference<Pair<ByteArray?, String?>>(null)
        private val fotoFeito = CountDownLatch(1)
        private var latchAtual: CountDownLatch? = null
        private val lock = Object()

        /** Chamado por Httpd — arranca a Activity e espera pelo resultado. */
        fun tirarFoto(ctx: Context, frente: Boolean, timeoutMs: Long = 12000): ByteArray? {
            val latch = CountDownLatch(1)
            synchronized(lock) { latchAtual = latch; fotoResultado.set(null) }

            val i = Intent(ctx, CameraActivity::class.java)
                .putExtra(EXTRA_FRENTE, frente)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            ctx.startActivity(i)

            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return synchronized(lock) { fotoResultado.get()?.first }
        }

        internal fun notificarResultado(bytes: ByteArray?, erro: String?) {
            synchronized(lock) {
                fotoResultado.set(Pair(bytes, erro))
                latchAtual?.countDown()
                latchAtual = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Janela completamente invisível e transparente
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val frente = intent.getBooleanExtra(EXTRA_FRENTE, true)
        capturar(frente)
    }

    private fun capturar(frente: Boolean) {
        val ht = HandlerThread("ftx-cam-act").apply { start() }
        val h = Handler(ht.looper)

        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cm == null) { notificarResultado(null, "falhou"); finish(); return }

            val querAlvo = if (frente) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            var camId: String? = null; var fallback: String? = null
            for (id in cm.cameraIdList) {
                val f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                if (fallback == null) fallback = id
                if (f == querAlvo) { camId = id; break }
            }
            val usar = camId ?: fallback ?: run { notificarResultado(null, "falhou"); finish(); return }

            val mapa = cm.getCameraCharacteristics(usar).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val tamanhos = mapa?.getOutputSizes(ImageFormat.JPEG)
            val alvo = tamanhos?.filter { it.width <= 1600 && it.height <= 1600 }?.maxByOrNull { it.width.toLong() * it.height }
                ?: tamanhos?.minByOrNull { it.width.toLong() * it.height }
            val w = alvo?.width ?: 1280; val hgt = alvo?.height ?: 960
            val orient = cm.getCameraCharacteristics(usar).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            val reader = ImageReader.newInstance(w, hgt, ImageFormat.JPEG, 1)
            var cam: CameraDevice? = null
            var dado = false

            fun terminar(bytes: ByteArray?) {
                if (dado) return; dado = true
                try { cam?.close() } catch (_: Exception) {}
                try { reader.close() } catch (_: Exception) {}
                try { ht.quitSafely() } catch (_: Exception) {}
                notificarResultado(bytes, if (bytes == null) "falhou" else null)
                runOnUiThread { finish() }
            }

            reader.setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireLatestImage()
                    if (img != null) {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                        img.close()
                        terminar(bytes)
                    } else terminar(null)
                } catch (_: Exception) { terminar(null) }
            }, h)

            @Suppress("MissingPermission")
            cm.openCamera(usar, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cam = device
                    try {
                        device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    req.addTarget(reader.surface)
                                    req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    req.set(CaptureRequest.JPEG_ORIENTATION, orient)
                                    h.postDelayed({ try { session.capture(req.build(), null, h) } catch (_: Exception) { terminar(null) } }, 700)
                                } catch (_: Exception) { terminar(null) }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) { terminar(null) }
                        }, h)
                    } catch (_: Exception) { terminar(null) }
                }
                override fun onDisconnected(device: CameraDevice) { terminar(null) }
                override fun onError(device: CameraDevice, error: Int) { terminar(null) }
            }, h)

            // Timeout de segurança
            h.postDelayed({ terminar(null) }, 10000)

        } catch (_: Exception) {
            notificarResultado(null, "falhou"); finish()
        }
    }

    override fun onStop() { super.onStop(); finish() }
}
