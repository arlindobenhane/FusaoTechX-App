package com.fusaotech.studio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * FusãoTechX Mz • Serviço principal.
 * Mantém o servidor ligado, captura o ecrã (MediaProjection) e produz as
 * imagens que o PC vê. É um serviço em primeiro plano para não ser morto.
 */
class CaptureService : Service() {

    companion object {
        @Volatile var frame: ByteArray? = null
        @Volatile var realW = 0
        @Volatile var realH = 0
        @Volatile var ativo = false
        @Volatile var qualidade = 60
        const val CH = "ftx_run"
        const val NID = 7788
    }

    private var httpd: Httpd? = null
    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var ht: HandlerThread? = null
    private var handler: Handler? = null
    private var ultima = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ativo = true
        if (httpd == null) {
            httpd = Httpd(this, Prefs.PORT)
            try { httpd?.start() } catch (_: Exception) {}
        }
        ht = HandlerThread("ftx-cap").also { it.start() }
        handler = Handler(ht!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        arrancarPrimeiroPlano()
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        if (code != 0 && data != null) {
            handler?.postDelayed({ iniciarCaptura(code, data) }, 400)
        }
        return START_STICKY
    }

    private fun arrancarPrimeiroPlano() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH, "FusãoTechX", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false); nm.createNotificationChannel(ch)
        }
        val ips = Util.localIps().joinToString(", ") { "$it:${Prefs.PORT}" }
        val n: Notification = Notification.Builder(this, CH)
            .setContentTitle("FusãoTechX ligado")
            .setContentText(if (ips.isEmpty()) "Código ${Prefs.code(this)}" else "No PC: $ips")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NID, n)
    }

    private fun iniciarCaptura(code: Int, data: Intent) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { pararCaptura() }
            }, handler)

            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            if (Build.VERSION.SDK_INT >= 30) {
                val b = wm.currentWindowMetrics.bounds
                realW = b.width(); realH = b.height()
                dm.densityDpi = resources.configuration.densityDpi
            } else {
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
                realW = dm.widthPixels; realH = dm.heightPixels
            }
            // Resolução de captura reduzida para fluidez
            val maxLado = 1000.0
            val esc = minOf(1.0, maxLado / maxOf(realW, realH))
            val capW = (realW * esc).toInt().coerceAtLeast(2) and 1.inv()
            val capH = (realH * esc).toInt().coerceAtLeast(2) and 1.inv()

            reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
            reader!!.setOnImageAvailableListener({ r ->
                val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    val agora = System.currentTimeMillis()
                    if (agora - ultima < 45) return@setOnImageAvailableListener
                    ultima = agora
                    val plane = img.planes[0]
                    val buf = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPad = rowStride - pixelStride * capW
                    val bmpW = capW + rowPad / pixelStride
                    val bmp = Bitmap.createBitmap(bmpW, capH, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(buf)
                    val recorte = if (bmpW != capW) Bitmap.createBitmap(bmp, 0, 0, capW, capH) else bmp
                    val bos = ByteArrayOutputStream(60000)
                    recorte.compress(Bitmap.CompressFormat.JPEG, qualidade, bos)
                    frame = bos.toByteArray()
                    if (recorte !== bmp) recorte.recycle()
                    bmp.recycle()
                } catch (_: Exception) {} finally { try { img.close() } catch (_: Exception) {} }
            }, handler)

            vdisplay = projection!!.createVirtualDisplay(
                "ftx", capW, capH, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, handler
            )
        } catch (_: Exception) {}
    }

    private fun pararCaptura() {
        try { vdisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        vdisplay = null; reader = null; projection = null; frame = null
    }

    override fun onDestroy() {
        ativo = false
        pararCaptura()
        try { httpd?.parar() } catch (_: Exception) {}
        try { ht?.quitSafely() } catch (_: Exception) {}
        super.onDestroy()
    }
}
