package com.fusaotech.studio

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * FusãoTechX Mz • Câmara silenciosa (Modo Avançado / anti-roubo).
 *
 * Tira uma foto em segundo plano, com o ecrã apagado e sem abrir a app de câmara,
 * para o dono ver onde o telefone está sem quem o tem perceber. Usa a API Camera2
 * directamente: nenhuma pré-visualização, nenhum som de obturador forçado por nós.
 * Precisa da permissão de câmara. Não guarda nada no telefone: a imagem vai só para o PC.
 */
object Camera {

    /** @param frente true = câmara frontal (quem está a usar); false = traseira (o ambiente). */
    @Suppress("MissingPermission")
    fun capturar(ctx: Context, frente: Boolean, timeoutMs: Long = 8000): ByteArray? {
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return null
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null

        val querAlvo = if (frente) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        var camId: String? = null
        var fallback: String? = null
        try {
            for (id in cm.cameraIdList) {
                val f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                if (fallback == null) fallback = id
                if (f == querAlvo) { camId = id; break }
            }
        } catch (_: Exception) {}
        val usar = camId ?: fallback ?: return null

        val ht = HandlerThread("ftx-cam").apply { start() }
        val h = Handler(ht.looper)
        val resultado = AtomicReference<ByteArray?>(null)
        val feito = CountDownLatch(1)
        var reader: ImageReader? = null
        var cam: CameraDevice? = null

        try {
            // Resolução de captura: um tamanho JPEG razoável (não o máximo, para ser rápido).
            val mapa = cm.getCameraCharacteristics(usar).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val tamanhos = mapa?.getOutputSizes(ImageFormat.JPEG)
            val alvo = tamanhos?.filter { it.width <= 1600 && it.height <= 1600 }?.maxByOrNull { it.width.toLong() * it.height }
                ?: tamanhos?.minByOrNull { it.width.toLong() * it.height }
            val w = alvo?.width ?: 1280
            val hgt = alvo?.height ?: 960
            val orient = cm.getCameraCharacteristics(usar).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            reader = ImageReader.newInstance(w, hgt, ImageFormat.JPEG, 1)
            reader.setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireLatestImage()
                    if (img != null) {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                        resultado.set(bytes)
                        img.close()
                    }
                } catch (_: Exception) {} finally { feito.countDown() }
            }, h)
            val surface = reader.surface

            cm.openCamera(usar, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cam = device
                    try {
                        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    req.addTarget(surface)
                                    req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    req.set(CaptureRequest.JPEG_ORIENTATION, orient)
                                    // Deixa o sensor medir a luz antes de disparar (evita foto preta).
                                    h.postDelayed({
                                        try { session.capture(req.build(), null, h) } catch (_: Exception) { feito.countDown() }
                                    }, 700)
                                } catch (_: Exception) { feito.countDown() }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) { feito.countDown() }
                        }, h)
                    } catch (_: Exception) { feito.countDown() }
                }
                override fun onDisconnected(device: CameraDevice) { feito.countDown() }
                override fun onError(device: CameraDevice, error: Int) { feito.countDown() }
            }, h)

            feito.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        } finally {
            try { cam?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            try { ht.quitSafely() } catch (_: Exception) {}
        }
        return resultado.get()
    }
}
